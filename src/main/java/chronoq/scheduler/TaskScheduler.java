package chronoq.scheduler;

import chronoq.heap.DaryHeap;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Runs one-off and repeating tasks on a fixed pool of worker threads, in time and priority order.
 *
 * <h2>How it works</h2>
 * Tasks wait in one of two heaps, both guarded by one lock:
 * <ul>
 *   <li>the <b>timer heap</b>, ordered by due time, holds tasks that are not due yet;</li>
 *   <li>the <b>ready heap</b>, ordered by priority, holds tasks that are due.</li>
 * </ul>
 * Each worker loops: move every task whose time has come from the timer heap to the ready heap, then take
 * the top of the ready heap and run it. If nothing is due, one worker (the <i>leader</i>) sleeps until
 * the earliest due time, and the others sleep until they are signalled. This is the leader/follower
 * pattern used by {@link java.util.concurrent.DelayQueue}. It means one thread watches the clock, rather
 * than every idle worker waking at the same moment.
 *
 * <p>Workers take tasks from the ready heap themselves, instead of handing them to an executor's FIFO
 * queue. So when every worker is busy and several tasks fall due, the next free worker still picks the
 * highest priority one.
 *
 * <h2>Semantics</h2>
 * <ul>
 *   <li>Repeating tasks run at a fixed rate on their original time grid. A run is never overlapped by the
 *       next run of the same task, and slots missed during a long run are skipped
 *       ({@link TaskOrder#nextFixedRateDue}).</li>
 *   <li>An exception in a run marks the task {@link ScheduledTask.State#FAILED} and stops it repeating.
 *       It never kills the worker thread.</li>
 *   <li>{@link #shutdown()} rejects new tasks, cancels every waiting task and lets running tasks finish.</li>
 * </ul>
 */
public final class TaskScheduler implements AutoCloseable {

    /** 2^2 = 4 children per node: shallower than a binary heap, and each level is still cheap to scan. */
    public static final int DEFAULT_ARITY_EXPONENT = 2;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition available = lock.newCondition();
    private final DaryHeap<ScheduledTask> timers;
    private final DaryHeap<ScheduledTask> ready;
    private final List<Thread> workers;

    // Guarded by lock.
    private Thread leader;
    private long nextSequence;
    private boolean shutdown;

    public TaskScheduler(int workerCount) {
        this(workerCount, DEFAULT_ARITY_EXPONENT);
    }

    /**
     * @param workerCount   number of worker threads, at least 1
     * @param arityExponent each heap node has 2^arityExponent children
     */
    public TaskScheduler(int workerCount, int arityExponent) {
        if (workerCount < 1) {
            throw new IllegalArgumentException("workerCount must be at least 1: " + workerCount);
        }
        this.timers = new DaryHeap<>(arityExponent, TaskOrder.BY_DUE_TIME);
        this.ready = new DaryHeap<>(arityExponent, TaskOrder.BY_PRIORITY);
        List<Thread> threads = new ArrayList<>(workerCount);
        for (int i = 1; i <= workerCount; i++) {
            Thread t = new Thread(this::workerLoop, "worker-" + i);
            threads.add(t);
        }
        this.workers = List.copyOf(threads);
        // Started last: every field is assigned, and Thread.start() publishes them to the new threads.
        workers.forEach(Thread::start);
    }

    // ------------------------------------------------------------------------------------------ public API

    /**
     * Queues a task.
     *
     * @throws RejectedExecutionException if the scheduler has been shut down
     */
    public ScheduledTask submit(TaskSpec spec) {
        ScheduledTask task = new ScheduledTask(this, spec);
        lock.lock();
        try {
            if (shutdown) {
                throw new RejectedExecutionException("scheduler is shut down; cannot accept " + spec.name());
            }
            long now = System.nanoTime();
            task.dueNanos = now + spec.initialDelayNanos();
            enqueue(task, now);
        } finally {
            lock.unlock();
        }
        return task;
    }

    /** Convenience: a one-off task with priority 0. */
    public ScheduledTask schedule(String name, Duration delay, Runnable action) {
        return submit(TaskSpec.named(name).runs(action).after(delay).build());
    }

    /** Convenience: a task that repeats every {@code period} until cancelled, with priority 0. */
    public ScheduledTask scheduleAtFixedRate(String name, Duration initialDelay, Duration period, Runnable action) {
        return submit(TaskSpec.named(name).runs(action).after(initialDelay).every(period).build());
    }

    /** Tasks waiting to run, whether or not they are due yet. */
    public int pendingCount() {
        lock.lock();
        try {
            return timers.size() + ready.size();
        } finally {
            lock.unlock();
        }
    }

    public int workerCount() {
        return workers.size();
    }

    /**
     * Stops accepting tasks, cancels every task still waiting, and lets running tasks finish.
     * Repeating tasks are not rescheduled after their current run. Calling it again does nothing.
     */
    public void shutdown() {
        lock.lock();
        try {
            if (shutdown) {
                return;
            }
            shutdown = true;
            cancelAll(timers);
            cancelAll(ready);
            available.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public boolean isShutdown() {
        lock.lock();
        try {
            return shutdown;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Waits for all worker threads to finish after {@link #shutdown()}.
     *
     * @return true if every worker finished within the timeout
     * @throws IllegalStateException if called from one of this scheduler's own workers, where it would
     *                               wait for itself forever
     */
    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        if (workers.contains(Thread.currentThread())) {
            throw new IllegalStateException("awaitTermination called from a worker thread would wait forever");
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        for (Thread worker : workers) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return !anyAlive();
            }
            worker.join(left / 1_000_000, (int) (left % 1_000_000));
        }
        return !anyAlive();
    }

    /** Shuts down and waits for running tasks to finish. */
    @Override
    public void close() {
        shutdown();
        boolean interrupted = false;
        while (true) {
            try {
                if (awaitTermination(Duration.ofDays(1))) {
                    break;
                }
            } catch (InterruptedException e) {
                interrupted = true; // keep waiting, then restore the flag for the caller
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------------------------------ internals

    /** Called by {@link ScheduledTask#cancel()}. */
    boolean cancel(ScheduledTask task) {
        lock.lock();
        try {
            switch (task.state()) {
                case SCHEDULED:
                    if (!timers.remove(task.handle)) {
                        ready.remove(task.handle);
                    }
                    task.handle = null;
                    task.finish(ScheduledTask.State.CANCELLED, null);
                    // The removed task may have been the one the leader is timing, so let a worker re-check.
                    leader = null;
                    available.signal();
                    return true;
                case RUNNING:
                    if (task.cancelRequested || !task.hasMoreRuns()) {
                        return false; // nothing left to prevent: the current run is its last
                    }
                    task.cancelRequested = true;
                    return true;
                default:
                    return false; // already finished
            }
        } finally {
            lock.unlock();
        }
    }

    /** Lock held. Puts a task in the right heap and wakes a worker if it changes what they should do. */
    private void enqueue(ScheduledTask task, long now) {
        task.sequence = nextSequence++;
        if (task.dueNanos - now <= 0) {
            task.handle = ready.add(task);
            available.signal(); // a worker waiting for work can take it now
        } else {
            task.handle = timers.add(task);
            if (timers.peekHandle() == task.handle) {
                // New earliest due time: whoever is timing the old head is sleeping too long.
                leader = null;
                available.signal();
            }
        }
    }

    private void workerLoop() {
        while (true) {
            ScheduledTask task;
            try {
                task = take();
            } catch (InterruptedException e) {
                // The scheduler never interrupts its workers, so a stray interrupt is ignored (the
                // exception has already cleared the flag) and the worker keeps serving.
                continue;
            }
            if (task == null) {
                return; // shut down
            }
            run(task);
            // A task that catches InterruptedException correctly restores the interrupt flag. If it
            // stayed set, the worker's next wait would throw immediately, so clear it between tasks
            // (ThreadPoolExecutor does the same).
            Thread.interrupted();
        }
    }

    /** Blocks until a task is ready, then returns it marked RUNNING. Returns null after shutdown. */
    private ScheduledTask take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (true) {
                if (shutdown) {
                    return null;
                }
                long now = System.nanoTime();
                promoteDueTasks(now);
                ScheduledTask next = ready.poll();
                if (next != null) {
                    next.handle = null;
                    next.markRunning();
                    return next;
                }
                ScheduledTask firstTimer = timers.peek();
                if (firstTimer == null) {
                    available.await(); // nothing queued at all
                } else if (leader != null) {
                    available.await(); // another worker is already timing the earliest task
                } else {
                    Thread me = Thread.currentThread();
                    leader = me;
                    try {
                        available.awaitNanos(firstTimer.dueNanos - now);
                    } finally {
                        if (leader == me) {
                            leader = null;
                        }
                    }
                }
            }
        } finally {
            // If no one is timing the queue and work remains, hand that job to another worker.
            if (leader == null && !(timers.isEmpty() && ready.isEmpty())) {
                available.signal();
            }
            lock.unlock();
        }
    }

    /** Lock held. Moves every task whose due time has arrived from the timer heap to the ready heap. */
    private void promoteDueTasks(long now) {
        ScheduledTask head;
        while ((head = timers.peek()) != null && head.dueNanos - now <= 0) {
            timers.poll();
            head.handle = ready.add(head);
        }
    }

    private void run(ScheduledTask task) {
        Throwable failure = null;
        try {
            task.spec().action().run();
        } catch (Throwable t) {
            failure = t;
        }
        afterRun(task, failure);
        if (failure instanceof VirtualMachineError) {
            throw (VirtualMachineError) failure; // e.g. OutOfMemoryError: recorded, but never swallowed
        }
    }

    private void afterRun(ScheduledTask task, Throwable failure) {
        lock.lock();
        try {
            if (failure != null) {
                task.finish(ScheduledTask.State.FAILED, failure);
            } else if (task.cancelRequested || (shutdown && task.hasMoreRuns())) {
                task.finish(ScheduledTask.State.CANCELLED, null);
            } else if (task.hasMoreRuns()) {
                long now = System.nanoTime();
                task.dueNanos = TaskOrder.nextFixedRateDue(task.dueNanos, task.spec().periodNanos(), now);
                task.markScheduled();
                enqueue(task, now);
            } else {
                task.finish(ScheduledTask.State.COMPLETED, null);
            }
        } finally {
            lock.unlock();
        }
    }

    private static void cancelAll(DaryHeap<ScheduledTask> heap) {
        ScheduledTask t;
        while ((t = heap.poll()) != null) {
            t.handle = null;
            t.finish(ScheduledTask.State.CANCELLED, null);
        }
    }

    private boolean anyAlive() {
        for (Thread w : workers) {
            if (w.isAlive()) {
                return true;
            }
        }
        return false;
    }
}
