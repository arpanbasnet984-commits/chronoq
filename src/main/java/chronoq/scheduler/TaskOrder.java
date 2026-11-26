package chronoq.scheduler;

import java.util.Comparator;

/**
 * The scheduling rules, as pure functions with no threads or clocks, so they can be unit-tested exactly.
 *
 * <p>Two orders are needed because a task lives in two places over its life:
 * <ul>
 *   <li>{@link #BY_DUE_TIME} orders the <i>timer heap</i> of tasks that are not due yet, so the
 *       scheduler always knows how long it can sleep.</li>
 *   <li>{@link #BY_PRIORITY} orders the <i>ready heap</i> of tasks that are due, so a free worker always
 *       takes the most important one. Older due time, then submission order, break ties, which keeps
 *       equal-priority tasks first-in, first-out.</li>
 * </ul>
 */
final class TaskOrder {

    private TaskOrder() {
    }

    /**
     * Earliest due time first, then submission order. Due times come from {@link System#nanoTime()},
     * which may wrap around, so they are compared by the sign of their difference rather than with
     * {@code <}. The JDK documentation for nanoTime prescribes the same.
     */
    static final Comparator<ScheduledTask> BY_DUE_TIME = (a, b) -> {
        int byTime = compareNanos(a.dueNanos, b.dueNanos);
        return byTime != 0 ? byTime : Long.compare(a.sequence, b.sequence);
    };

    /** Highest priority first, then earliest due time, then submission order. */
    static final Comparator<ScheduledTask> BY_PRIORITY = (a, b) -> {
        if (a.priority() != b.priority()) {
            return Integer.compare(b.priority(), a.priority());
        }
        int byTime = compareNanos(a.dueNanos, b.dueNanos);
        return byTime != 0 ? byTime : Long.compare(a.sequence, b.sequence);
    };

    static int compareNanos(long a, long b) {
        long diff = a - b;
        return diff < 0 ? -1 : (diff > 0 ? 1 : 0);
    }

    /**
     * Next due time for a fixed-rate task whose run was due at {@code previousDue}.
     *
     * <p>Runs stay on the original grid ({@code firstDue + n * period}), so they never drift. If a run
     * overran one or more slots, the missed slots are skipped instead of being run back-to-back to catch
     * up. The result is the first slot at or after {@code now}.
     */
    static long nextFixedRateDue(long previousDue, long periodNanos, long now) {
        long next = previousDue + periodNanos;
        long late = now - next;
        if (late > 0) {
            long slotsToSkip = (late + periodNanos - 1) / periodNanos; // ceiling division
            next += slotsToSkip * periodNanos;
        }
        return next;
    }
}
