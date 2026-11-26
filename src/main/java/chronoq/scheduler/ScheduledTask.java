package chronoq.scheduler;

import chronoq.heap.DaryHeap;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The handle returned when a task is submitted: check its state, wait for it, or cancel it.
 *
 * <p>Thread-safety: the public getters read volatile fields. All other fields are touched only while
 * the owning scheduler's lock is held.
 */
public final class ScheduledTask {

    public enum State {
        /** Waiting for its due time, or due and waiting for a free worker. */
        SCHEDULED,
        /** A worker is running it right now. */
        RUNNING,
        /** Finished all of its runs. */
        COMPLETED,
        /** A run threw an exception; see {@link #failure()}. A repeating task stops repeating. */
        FAILED,
        /** Cancelled, or still pending when the scheduler shut down. */
        CANCELLED;

        public boolean isTerminal() {
            return this == COMPLETED || this == FAILED || this == CANCELLED;
        }
    }

    private final TaskScheduler scheduler;
    private final TaskSpec spec;
    private final CountDownLatch finished = new CountDownLatch(1);

    private volatile State state = State.SCHEDULED;
    private volatile int runCount;
    private volatile Throwable failure;

    // Guarded by the scheduler's lock.
    long dueNanos;
    long sequence;
    DaryHeap.Handle<ScheduledTask> handle;
    boolean cancelRequested;

    ScheduledTask(TaskScheduler scheduler, TaskSpec spec) {
        this.scheduler = scheduler;
        this.spec = spec;
    }

    public String name() {
        return spec.name();
    }

    public int priority() {
        return spec.priority();
    }

    public TaskSpec spec() {
        return spec;
    }

    public State state() {
        return state;
    }

    /** Number of runs that have started. */
    public int runCount() {
        return runCount;
    }

    /** The exception that ended the task, if it failed. */
    public Optional<Throwable> failure() {
        return Optional.ofNullable(failure);
    }

    public boolean isDone() {
        return state.isTerminal();
    }

    /**
     * Stops all future runs. A waiting task is removed from the queue in O(log n). A run already in
     * progress is not interrupted; a repeating task simply won't be rescheduled after it.
     *
     * @return true if this call prevented at least one run that would otherwise have happened
     */
    public boolean cancel() {
        return scheduler.cancel(this);
    }

    /** Waits until the task reaches a terminal state. @return false if the timeout elapsed first */
    public boolean await(Duration timeout) throws InterruptedException {
        return finished.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    // ------------------------------------------------------------- transitions (scheduler lock held)

    void markRunning() {
        runCount++; // single writer: only ever changed under the scheduler lock
        state = State.RUNNING;
    }

    void markScheduled() {
        state = State.SCHEDULED;
    }

    boolean hasMoreRuns() {
        return spec.isRecurring() && (spec.maxRuns() == 0 || runCount < spec.maxRuns());
    }

    void finish(State terminal, Throwable cause) {
        failure = cause;
        state = terminal;
        finished.countDown();
    }

    @Override
    public String toString() {
        return "ScheduledTask{" + spec.name() + ", priority=" + spec.priority() + ", " + state
                + ", runs=" + runCount + '}';
    }
}
