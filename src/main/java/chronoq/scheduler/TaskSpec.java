package chronoq.scheduler;

import java.time.Duration;
import java.util.Objects;

/**
 * An immutable description of a task: what to run, when, how important it is, and whether it repeats.
 *
 * <pre>{@code
 * TaskSpec spec = TaskSpec.named("sales-report")
 *         .runs(() -> generateReport())
 *         .after(Duration.ofSeconds(30))
 *         .priority(5)
 *         .every(Duration.ofMinutes(10))
 *         .times(6)
 *         .build();
 * }</pre>
 *
 * @param name         label used in logs and errors
 * @param action       the code to run
 * @param initialDelay how long after submission the first run becomes due
 * @param priority     higher runs first when several tasks are due at once
 * @param period       time between run start slots, or null for a one-off task
 * @param maxRuns      runs before the task completes; 0 means a repeating task runs until cancelled
 */
public record TaskSpec(String name, Runnable action, Duration initialDelay, int priority,
                       Duration period, int maxRuns) {

    /**
     * Upper limit for delays and periods, about 146 years. It keeps every due time within the range where
     * comparisons like {@code a - b < 0} on {@link System#nanoTime()} values stay correct.
     */
    static final long MAX_NANOS = Long.MAX_VALUE / 2;

    public TaskSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(initialDelay, "initialDelay");
        if (initialDelay.isNegative() || exceedsLimit(initialDelay)) {
            throw new IllegalArgumentException("initialDelay must be between zero and ~146 years: " + initialDelay);
        }
        if (period != null && (period.isZero() || period.isNegative() || exceedsLimit(period))) {
            throw new IllegalArgumentException("period must be positive and at most ~146 years: " + period);
        }
        if (maxRuns < 0) {
            throw new IllegalArgumentException("maxRuns must not be negative: " + maxRuns);
        }
        if (period == null && maxRuns != 1) {
            throw new IllegalArgumentException("a task without a period runs exactly once (maxRuns 1)");
        }
    }

    public boolean isRecurring() {
        return period != null;
    }

    long initialDelayNanos() {
        return initialDelay.toNanos();
    }

    long periodNanos() {
        return period.toNanos();
    }

    private static boolean exceedsLimit(Duration d) {
        return d.compareTo(Duration.ofNanos(MAX_NANOS)) > 0;
    }

    public static Builder named(String name) {
        return new Builder(name);
    }

    /** Fluent builder. Only {@link #runs} is required. */
    public static final class Builder {
        private final String name;
        private Runnable action;
        private Duration delay = Duration.ZERO;
        private int priority;
        private Duration period;
        private int times = -1; // not set

        private Builder(String name) {
            this.name = name;
        }

        public Builder runs(Runnable action) {
            this.action = action;
            return this;
        }

        public Builder after(Duration delay) {
            this.delay = delay;
            return this;
        }

        public Builder priority(int priority) {
            this.priority = priority;
            return this;
        }

        /** Repeat at a fixed rate: run slots are {@code period} apart, measured from the first due time. */
        public Builder every(Duration period) {
            this.period = period;
            return this;
        }

        /** Number of runs for a repeating task (at least 1). Leave unset to repeat until cancelled. */
        public Builder times(int times) {
            if (times < 1) {
                throw new IllegalArgumentException("times must be at least 1: " + times);
            }
            this.times = times;
            return this;
        }

        public TaskSpec build() {
            int maxRuns = period == null ? (times == -1 ? 1 : times) : (times == -1 ? 0 : times);
            return new TaskSpec(name, action, delay, priority, period, maxRuns);
        }
    }
}
