# Chronoq

A priority task scheduler for Java, built from the data structure up.

Chronoq runs one-off and repeating tasks on a pool of worker threads. When several tasks are due at once, the most important one runs first. Underneath is a d-ary heap I wrote for this project, where every parent has a power-of-two number of children and every element has a handle. That makes cancelling a task O(log n) instead of the O(n) that `java.util.PriorityQueue` needs.

```java
try (TaskScheduler scheduler = new TaskScheduler(4)) {
    ScheduledTask report = scheduler.submit(TaskSpec.named("sales-report")
            .runs(() -> generateReport())
            .after(Duration.ofSeconds(30))
            .priority(5)
            .every(Duration.ofMinutes(10))
            .times(6)
            .build());

    report.cancel();   // O(log n): the task's handle knows exactly where it sits in the heap
}                      // close(): stop accepting tasks, let running ones finish
```

## Features

- **Priority among due tasks.** Tasks that are not due yet wait in a timer heap ordered by time. Once due, they move to a ready heap ordered by priority. Workers take from the ready heap themselves, so priority still counts when every worker is busy.
- **Fast cancellation.** Every queued task holds a handle to its heap slot, so `cancel()` removes it in O(log n).
- **Repeating tasks** at a fixed rate on the original time grid, with no drift. A run is never overlapped by the next run of the same task, and slots missed during a long run are skipped instead of run in a burst.
- **Failure isolation.** An exception marks that task `FAILED` and stops it repeating. It never kills a worker thread.
- **Clean shutdown.** `shutdown()` rejects new work, cancels waiting tasks and lets running tasks finish. `close()` also waits for them.
- **No dependencies.** Plain Java 17. JUnit 5 is used for tests only.

## Quick start

Requires Java 17 or newer and Maven.

```bash
mvn verify                                               # build and run all 56 tests
java -jar target/chronoq.jar run examples/jobs.csv --workers 2
java -jar target/chronoq.jar bench                       # compare with java.util.PriorityQueue
```

Demo output (two workers, six jobs). `heartbeat` falls due at 0.1 s, but it has the lowest priority, so it waits until no higher-priority job is due:

```
[  0.019 s] worker-1  start  cache-warmup, priority 8
[  0.022 s] worker-2  start  nightly-backup, priority 5
[  0.272 s] worker-1  start  send-emails, priority 2
[  0.424 s] worker-2  start  sales-report, priority 6
[  0.425 s] worker-1  start  cleanup-temp, priority 3
[  0.533 s] worker-1  start  heartbeat (run 1 of 5), priority 1
[  0.714 s] worker-1  start  heartbeat (run 2 of 5), priority 1
...
```

The jobs file has one job per line: `name,start_after_ms,priority,duration_ms,repeat_every_ms,repeat_count`.

## Design

```
TaskScheduler (lock + condition, N worker threads)
├── timer heap   DaryHeap<ScheduledTask>, ordered by due time   → how long the leader thread may sleep
└── ready heap   DaryHeap<ScheduledTask>, ordered by priority   → which due task a free worker takes
```

1. `submit()` puts a task in the timer heap, or straight into the ready heap if it is already due.
2. Each worker loops: move every due task from the timer heap to the ready heap, then take the top of the ready heap.
3. If nothing is due, one worker (the *leader*) sleeps until the earliest due time, and the others wait for a signal. This leader/follower pattern is the one `java.util.concurrent.DelayQueue` uses. It means one thread watches the clock, not every idle worker at once.
4. After a run, a repeating task is put back with its next due time. Failures, cancellations and the last run end it.

The ordering and timing rules are pure functions in `TaskOrder`, so they are unit-tested exactly, without threads.

| Operation | Cost (n queued tasks, d children per node) |
|---|---|
| `submit` | O(log_d n) |
| worker takes the next task | O(d log_d n), plus O(log n) for each task that falls due |
| `cancel` a waiting task | O(d log_d n) |
| `pendingCount` | O(1) |

## Benchmark

`java -jar target/chronoq.jar bench`, best of 5 runs after warm-up, OpenJDK 21. Figures are relative: this is not a JMH harness.

| Workload | `PriorityQueue` | `DaryHeap` |
|---|---|---|
| Push + pop 1,000,000 longs | 650 ms | 810 ms (4-ary) · 785 ms (8-ary) · 1,106 ms (binary) |
| Cancel 10,000 of 100,000 queued tasks | 771 ms | **4.6 ms (168× faster)** |

The handles cost 20–25% on plain push and pop, because each insert allocates one and every move updates it. In return, cancellation is two orders of magnitude faster.

## Tests

56 JUnit 5 tests (`mvn verify`). The included GitHub Actions workflow runs them on Java 17 and 21:

- **Heap:** randomized step-by-step comparison against `PriorityQueue` for arities 1 to 2^30, checking heap order and every handle's slot after each operation.
- **Rules:** due-time and priority ordering, `nanoTime` wraparound, and fixed-rate slot skipping, all as pure functions.
- **Scheduler:** priority when every worker is busy, FIFO among equal priorities, cancellation (including cancelling the task the leader is timing), no overlapping runs, failures, a task that leaves its interrupt flag set, 2,000 tasks on 4 workers, concurrent submit and cancel from 8 threads, and shutdown.

The threaded tests assert on order and counts, never exact timings, so they pass reliably on slow CI machines.

## Project layout

```
src/main/java/chronoq/
  heap/DaryHeap.java              power-of-two d-ary heap with handles
  scheduler/TaskScheduler.java    worker threads, two heaps, leader/follower waiting
  scheduler/ScheduledTask.java    the handle returned to callers: state, await, cancel
  scheduler/TaskSpec.java         immutable task description + builder
  scheduler/TaskOrder.java        ordering and fixed-rate rules as pure functions
  cli/Main.java, cli/JobFile.java command-line demo
  bench/HeapBenchmark.java        DaryHeap vs PriorityQueue
```
