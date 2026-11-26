package chronoq.scheduler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour of the real, multi-threaded scheduler. To stay reliable on slow CI machines these tests
 * assert on order and counts, never on exact timings, and use latches instead of sleeps wherever an
 * ordering has to be forced.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class TaskSchedulerTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    private TaskScheduler scheduler;

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.close();
        }
    }

    private static TaskSpec.Builder task(String name, Runnable action) {
        return TaskSpec.named(name).runs(action);
    }

    /** Submits a task that occupies the scheduler's only worker until the returned latch is released. */
    private CountDownLatch blockOnlyWorker() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        scheduler.submit(task("gate", () -> {
            started.countDown();
            awaitUninterruptibly(release);
        }).build());
        assertTrue(started.await(10, TimeUnit.SECONDS));
        return release;
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------------------------- basics

    @Test
    void rejectsZeroWorkers() {
        assertThrows(IllegalArgumentException.class, () -> new TaskScheduler(0));
    }

    @Test
    void runsAOneOffTaskNoEarlierThanItsDelay() throws InterruptedException {
        scheduler = new TaskScheduler(2);
        long submitted = System.nanoTime();
        long[] ranAt = new long[1];
        ScheduledTask t = scheduler.schedule("once", Duration.ofMillis(80), () -> ranAt[0] = System.nanoTime());

        assertTrue(t.await(WAIT));
        assertEquals(ScheduledTask.State.COMPLETED, t.state());
        assertEquals(1, t.runCount());
        assertTrue(ranAt[0] - submitted >= TimeUnit.MILLISECONDS.toNanos(80), "ran before its delay");
    }

    @Test
    void runsTasksInDueTimeOrder() throws InterruptedException {
        scheduler = new TaskScheduler(1);
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        ScheduledTask c = scheduler.schedule("c", Duration.ofMillis(450), () -> order.add("c"));
        scheduler.schedule("a", Duration.ofMillis(150), () -> order.add("a"));
        scheduler.schedule("b", Duration.ofMillis(300), () -> order.add("b"));
        assertTrue(c.await(WAIT));
        assertEquals(List.of("a", "b", "c"), order);
    }

    // ---------------------------------------------------------------------------------- priority

    @Test
    void aFreeWorkerTakesTheHighestPriorityDueTask() throws InterruptedException {
        scheduler = new TaskScheduler(1);
        CountDownLatch release = blockOnlyWorker();
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        // All three fall due while the only worker is busy, so priority alone decides the order.
        List<ScheduledTask> tasks = List.of(
                scheduler.submit(task("low", () -> order.add("low")).priority(1).build()),
                scheduler.submit(task("high", () -> order.add("high")).priority(9).build()),
                scheduler.submit(task("mid", () -> order.add("mid")).priority(5).build()));

        release.countDown();
        for (ScheduledTask t : tasks) {
            assertTrue(t.await(WAIT));
        }
        assertEquals(List.of("high", "mid", "low"), order);
    }

    @Test
    void equalPriorityTasksRunFirstInFirstOut() throws InterruptedException {
        scheduler = new TaskScheduler(1);
        CountDownLatch release = blockOnlyWorker();
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        List<ScheduledTask> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            final int n = i;
            tasks.add(scheduler.submit(task("t" + i, () -> order.add(n)).priority(3).build()));
        }
        release.countDown();
        for (ScheduledTask t : tasks) {
            assertTrue(t.await(WAIT));
        }
        List<Integer> expected = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            expected.add(i);
        }
        assertEquals(expected, order);
    }

    // ---------------------------------------------------------------------------- cancellation

    @Test
    void cancellingAWaitingTaskStopsItFromRunning() throws InterruptedException {
        scheduler = new TaskScheduler(2);
        AtomicInteger runs = new AtomicInteger();
        ScheduledTask t = scheduler.schedule("doomed", Duration.ofMillis(200), runs::incrementAndGet);

        assertTrue(t.cancel());
        assertEquals(ScheduledTask.State.CANCELLED, t.state());
        assertFalse(t.cancel(), "a second cancel has nothing left to prevent");
        assertEquals(0, scheduler.pendingCount());

        ScheduledTask later = scheduler.schedule("later", Duration.ofMillis(300), () -> { });
        assertTrue(later.await(WAIT));
        assertEquals(0, runs.get());
    }

    @Test
    void cancellingAFinishedTaskReturnsFalse() throws InterruptedException {
        scheduler = new TaskScheduler(1);
        ScheduledTask t = scheduler.schedule("quick", Duration.ZERO, () -> { });
        assertTrue(t.await(WAIT));
        assertFalse(t.cancel());
        assertEquals(ScheduledTask.State.COMPLETED, t.state());
    }

    @Test
    void cancellingTheEarliestTaskDoesNotDelayTheNextOne() throws InterruptedException {
        scheduler = new TaskScheduler(1);
        ScheduledTask first = scheduler.schedule("first", Duration.ofMillis(300), () -> { });
        Thread.sleep(50); // let the worker start timing "first"
        ScheduledTask second = scheduler.schedule("second", Duration.ofMillis(400), () -> { });
        assertTrue(first.cancel());
        assertTrue(second.await(WAIT));
        assertEquals(ScheduledTask.State.COMPLETED, second.state());
    }

    @Test
    void aSoonerTaskSubmittedLaterIsNotStuckBehindALongDelay() throws InterruptedException {
        scheduler = new TaskScheduler(1);
        ScheduledTask slow = scheduler.schedule("in-an-hour", Duration.ofHours(1), () -> { });
        Thread.sleep(50); // the worker is now sleeping until the one-hour task is due
        long t0 = System.nanoTime();
        ScheduledTask soon = scheduler.schedule("soon", Duration.ofMillis(50), () -> { });
        assertTrue(soon.await(WAIT));
        assertTrue(System.nanoTime() - t0 < TimeUnit.SECONDS.toNanos(5), "the new head did not wake the worker");
        assertEquals(ScheduledTask.State.SCHEDULED, slow.state());
    }

    // ----------------------------------------------------------------------------- repetition

    @Test
    void aRepeatingTaskRunsExactlyMaxRunsTimes() throws InterruptedException {
        scheduler = new TaskScheduler(2);
        AtomicInteger runs = new AtomicInteger();
        ScheduledTask t = scheduler.submit(task("tick", runs::incrementAndGet)
                .every(Duration.ofMillis(20)).times(5).build());
        assertTrue(t.await(WAIT));
        assertEquals(ScheduledTask.State.COMPLETED, t.state());
        assertEquals(5, runs.get());
        assertEquals(5, t.runCount());
    }

    @Test
    void runsOfOneRepeatingTaskNeverOverlap() throws InterruptedException {
        scheduler = new TaskScheduler(4);
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger overlaps = new AtomicInteger();
        // Each run takes longer than the period, and 4 workers are free, yet runs must not overlap.
        ScheduledTask t = scheduler.submit(task("slow-tick", () -> {
            if (inside.incrementAndGet() > 1) {
                overlaps.incrementAndGet();
            }
            sleep(30);
            inside.decrementAndGet();
        }).every(Duration.ofMillis(5)).times(6).build());
        assertTrue(t.await(WAIT));
        assertEquals(0, overlaps.get());
        assertEquals(6, t.runCount());
    }

    @Test
    void aRepeatingTaskCanCancelItselfFromInsideARun() throws InterruptedException {
        scheduler = new TaskScheduler(1);
        AtomicInteger runs = new AtomicInteger();
        ScheduledTask[] self = new ScheduledTask[1];
        CountDownLatch submitted = new CountDownLatch(1);
        self[0] = scheduler.submit(task("self-stopping", () -> {
            awaitUninterruptibly(submitted);
            if (runs.incrementAndGet() == 3) {
                assertTrue(self[0].cancel());
            }
        }).every(Duration.ofMillis(10)).build());
        submitted.countDown();
        assertTrue(self[0].await(WAIT));
        assertEquals(ScheduledTask.State.CANCELLED, self[0].state());
        assertEquals(3, runs.get());
    }

    // --------------------------------------------------------------------------------- failures

    @Test
    void aFailingTaskIsRecordedAndOtherTasksStillRun() throws InterruptedException {
        scheduler = new TaskScheduler(1);
        ScheduledTask bad = scheduler.schedule("bad", Duration.ZERO, () -> {
            throw new IllegalStateException("boom");
        });
        ScheduledTask good = scheduler.schedule("good", Duration.ofMillis(20), () -> { });
        assertTrue(bad.await(WAIT));
        assertTrue(good.await(WAIT));
        assertEquals(ScheduledTask.State.FAILED, bad.state());
        assertInstanceOf(IllegalStateException.class, bad.failure().orElseThrow());
        assertEquals(ScheduledTask.State.COMPLETED, good.state(), "the worker survived the exception");
    }

    @Test
    void aFailingRepeatingTaskStopsRepeating() throws InterruptedException {
        scheduler = new TaskScheduler(1);
        AtomicInteger runs = new AtomicInteger();
        ScheduledTask t = scheduler.submit(task("flaky", () -> {
            if (runs.incrementAndGet() == 2) {
                throw new RuntimeException("second run fails");
            }
        }).every(Duration.ofMillis(10)).times(10).build());
        assertTrue(t.await(WAIT));
        assertEquals(ScheduledTask.State.FAILED, t.state());
        assertEquals(2, runs.get());
    }

    @Test
    void aTaskThatLeavesItsInterruptFlagSetDoesNotKillTheWorker() throws InterruptedException {
        scheduler = new TaskScheduler(1); // one worker: if it died, nothing below would ever run
        ScheduledTask polite = scheduler.schedule("restores-interrupt", Duration.ZERO,
                () -> Thread.currentThread().interrupt());
        assertTrue(polite.await(WAIT));
        ScheduledTask next = scheduler.schedule("next", Duration.ofMillis(30), () -> { });
        assertTrue(next.await(WAIT));
        assertEquals(ScheduledTask.State.COMPLETED, next.state());
    }

    // ----------------------------------------------------------------------------- concurrency

    @Test
    void manyTasksOnManyWorkersEachRunExactlyOnce() throws InterruptedException {
        scheduler = new TaskScheduler(4);
        int n = 2_000;
        AtomicInteger[] runsPerTask = new AtomicInteger[n];
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxRunning = new AtomicInteger();
        List<ScheduledTask> tasks = new ArrayList<>();
        Random rnd = new Random(1);
        for (int i = 0; i < n; i++) {
            AtomicInteger counter = new AtomicInteger();
            runsPerTask[i] = counter;
            tasks.add(scheduler.submit(task("t" + i, () -> {
                int now = running.incrementAndGet();
                maxRunning.accumulateAndGet(now, Math::max);
                counter.incrementAndGet();
                if (now % 7 == 0) {
                    Thread.yield();
                }
                running.decrementAndGet();
            }).after(Duration.ofMillis(rnd.nextInt(100))).priority(rnd.nextInt(5)).build()));
        }
        for (ScheduledTask t : tasks) {
            assertTrue(t.await(WAIT));
        }
        for (int i = 0; i < n; i++) {
            assertEquals(1, runsPerTask[i].get(), "task " + i);
        }
        assertTrue(maxRunning.get() <= 4, "more tasks ran at once than there are workers");
        assertEquals(0, scheduler.pendingCount());
    }

    @Test
    void concurrentSubmitAndCancelFromManyThreadsStaysConsistent() throws InterruptedException {
        scheduler = new TaskScheduler(3);
        int threads = 8, perThread = 300;
        AtomicInteger ran = new AtomicInteger();
        AtomicInteger cancelled = new AtomicInteger();
        List<ScheduledTask> all = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> submitters = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int seed = t;
            Thread th = new Thread(() -> {
                awaitUninterruptibly(go);
                Random rnd = new Random(seed);
                for (int i = 0; i < perThread; i++) {
                    ScheduledTask task = scheduler.submit(task("s" + seed + "-" + i, ran::incrementAndGet)
                            .after(Duration.ofMillis(rnd.nextInt(50))).build());
                    all.add(task);
                    if (rnd.nextBoolean() && task.cancel()) {
                        cancelled.incrementAndGet();
                    }
                }
            });
            submitters.add(th);
            th.start();
        }
        go.countDown();
        for (Thread th : submitters) {
            th.join();
        }
        for (ScheduledTask t : all) {
            assertTrue(t.await(WAIT));
        }
        long completed = all.stream().filter(t -> t.state() == ScheduledTask.State.COMPLETED).count();
        long cancelledState = all.stream().filter(t -> t.state() == ScheduledTask.State.CANCELLED).count();
        assertEquals(threads * perThread, completed + cancelledState);
        assertEquals(cancelled.get(), cancelledState);
        assertEquals(completed, ran.get(), "every completed task ran exactly once, no cancelled task ran");
    }

    // -------------------------------------------------------------------------------- shutdown

    @Test
    void shutdownCancelsWaitingTasksAndLetsRunningOnesFinish() throws InterruptedException {
        scheduler = new TaskScheduler(1);
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger finished = new AtomicInteger();
        ScheduledTask running = scheduler.schedule("running", Duration.ZERO, () -> {
            started.countDown();
            sleep(100);
            finished.incrementAndGet();
        });
        ScheduledTask waiting = scheduler.schedule("waiting", Duration.ofMillis(50), () -> { });
        ScheduledTask repeating = scheduler.scheduleAtFixedRate("repeating", Duration.ofHours(1),
                Duration.ofSeconds(1), () -> { });
        assertTrue(started.await(10, TimeUnit.SECONDS));

        scheduler.shutdown();
        assertTrue(scheduler.isShutdown());
        assertTrue(scheduler.awaitTermination(WAIT));

        assertEquals(ScheduledTask.State.COMPLETED, running.state());
        assertEquals(1, finished.get());
        assertEquals(ScheduledTask.State.CANCELLED, waiting.state());
        assertEquals(ScheduledTask.State.CANCELLED, repeating.state());
        assertThrows(RejectedExecutionException.class,
                () -> scheduler.schedule("too-late", Duration.ZERO, () -> { }));
    }

    @Test
    void awaitTerminationFromAWorkerFailsInsteadOfDeadlocking() throws InterruptedException {
        scheduler = new TaskScheduler(1);
        TaskScheduler s = scheduler;
        ScheduledTask t = s.schedule("self-await", Duration.ZERO, () -> {
            try {
                s.awaitTermination(Duration.ofSeconds(1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(t.await(WAIT));
        assertEquals(ScheduledTask.State.FAILED, t.state());
        assertInstanceOf(IllegalStateException.class, t.failure().orElseThrow());
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
