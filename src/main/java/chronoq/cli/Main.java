package chronoq.cli;

import chronoq.bench.HeapBenchmark;
import chronoq.scheduler.ScheduledTask;
import chronoq.scheduler.TaskScheduler;
import chronoq.scheduler.TaskSpec;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Command-line entry point.
 *
 * <pre>
 *   java -jar target/chronoq.jar run examples/jobs.csv [--workers N]
 *   java -jar target/chronoq.jar bench
 * </pre>
 */
public final class Main {

    private static final String USAGE = String.join(System.lineSeparator(),
            "Chronoq: a priority task scheduler",
            "",
            "Usage:",
            "  java -jar chronoq.jar run <jobs.csv> [--workers N]   run the jobs in a file (default 2 workers)",
            "  java -jar chronoq.jar bench                          compare the heap with java.util.PriorityQueue",
            "",
            "Jobs file columns: name,start_after_ms,priority,duration_ms,repeat_every_ms,repeat_count");

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("-h")) {
            System.out.println(USAGE);
            return args.length == 0 ? 2 : 0;
        }
        try {
            switch (args[0]) {
                case "run":
                    return runJobs(args);
                case "bench":
                    HeapBenchmark.main(new String[0]);
                    return 0;
                default:
                    System.err.println("Unknown command: " + args[0]);
                    System.err.println(USAGE);
                    return 2;
            }
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            return 2;
        }
    }

    private static int runJobs(String[] args) {
        if (args.length != 2 && args.length != 4) {
            throw new IllegalArgumentException("expected: run <jobs.csv> [--workers N]");
        }
        int workers = 2;
        if (args.length == 4) {
            if (!args[2].equals("--workers")) {
                throw new IllegalArgumentException("unknown option " + args[2]);
            }
            try {
                workers = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("--workers needs a whole number");
            }
            if (workers < 1 || workers > 64) {
                throw new IllegalArgumentException("--workers must be between 1 and 64");
            }
        }

        List<JobFile.Job> jobs;
        try {
            jobs = JobFile.read(Path.of(args[1]));
        } catch (NoSuchFileException e) {
            throw new IllegalArgumentException("file not found: " + args[1]);
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read " + args[1] + ": " + e.getMessage());
        }

        System.out.printf("Running %d jobs on %d workers%n%n", jobs.size(), workers);
        long start = System.nanoTime();
        List<ScheduledTask> tasks = new ArrayList<>();
        try (TaskScheduler scheduler = new TaskScheduler(workers)) {
            for (JobFile.Job job : jobs) {
                tasks.add(scheduler.submit(toSpec(job, start)));
            }
            for (ScheduledTask task : tasks) {
                awaitQuietly(task);
            }
        } // close(): shut down and wait for the workers

        System.out.printf("%nSummary (%.2f s)%n", (System.nanoTime() - start) / 1e9);
        System.out.printf("  %-18s %8s %5s  %s%n", "job", "priority", "runs", "state");
        int failed = 0;
        for (ScheduledTask t : tasks) {
            System.out.printf("  %-18s %8d %5d  %s%n", t.name(), t.priority(), t.runCount(), t.state());
            if (t.state() == ScheduledTask.State.FAILED) {
                failed++;
            }
        }
        return failed == 0 ? 0 : 1;
    }

    private static TaskSpec toSpec(JobFile.Job job, long start) {
        int totalRuns = job.repeats() ? job.repeatCount() : 1;
        int[] run = {0}; // runs of one task never overlap, so a plain counter is safe
        Runnable action = () -> {
            run[0]++;
            String label = totalRuns > 1 ? job.name() + " (run " + run[0] + " of " + totalRuns + ")" : job.name();
            log(start, "start", label + ", priority " + job.priority());
            try {
                Thread.sleep(job.durationMs()); // stands in for real work
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            log(start, "done ", label);
        };
        TaskSpec.Builder b = TaskSpec.named(job.name())
                .runs(action)
                .after(Duration.ofMillis(job.startAfterMs()))
                .priority(job.priority());
        if (job.repeats()) {
            b.every(Duration.ofMillis(job.repeatEveryMs())).times(job.repeatCount());
        }
        return b.build();
    }

    private static final Object LOG_LOCK = new Object();

    /** Timestamp and print under one lock, so lines from different workers appear in time order. */
    private static void log(long start, String event, String what) {
        synchronized (LOG_LOCK) {
            System.out.printf("[%7.3f s] %-9s %s  %s%n",
                    (System.nanoTime() - start) / 1e9, Thread.currentThread().getName(), event, what);
        }
    }

    private static void awaitQuietly(ScheduledTask task) {
        try {
            task.await(Duration.ofDays(1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
