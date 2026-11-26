package chronoq.scheduler;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The ordering and timing rules, tested as pure functions with no threads or clocks. */
class TaskOrderTest {

    private static ScheduledTask task(String name, int priority, long due, long seq) {
        ScheduledTask t = new ScheduledTask(null, TaskSpec.named(name).runs(() -> { }).priority(priority).build());
        t.dueNanos = due;
        t.sequence = seq;
        return t;
    }

    private static List<String> sortedNames(List<ScheduledTask> tasks, java.util.Comparator<ScheduledTask> order) {
        List<ScheduledTask> copy = new ArrayList<>(tasks);
        copy.sort(order);
        List<String> names = new ArrayList<>();
        copy.forEach(t -> names.add(t.name()));
        return names;
    }

    @Test
    void timerHeapOrdersByDueTimeThenSubmissionOrder() {
        List<ScheduledTask> tasks = List.of(
                task("late", 9, 300, 0),
                task("early", 0, 100, 1),
                task("middle-second", 5, 200, 3),
                task("middle-first", 1, 200, 2));
        assertEquals(List.of("early", "middle-first", "middle-second", "late"),
                sortedNames(tasks, TaskOrder.BY_DUE_TIME));
    }

    @Test
    void readyHeapOrdersByPriorityThenDueTimeThenSubmissionOrder() {
        List<ScheduledTask> tasks = List.of(
                task("low", 1, 0, 0),
                task("high", 9, 500, 1),
                task("mid-newer", 5, 400, 2),
                task("mid-older", 5, 100, 3),
                task("mid-older-tie", 5, 100, 4));
        assertEquals(List.of("high", "mid-older", "mid-older-tie", "mid-newer", "low"),
                sortedNames(tasks, TaskOrder.BY_PRIORITY));
    }

    @Test
    void dueTimesCompareCorrectlyAcrossNanoTimeWraparound() {
        // nanoTime may wrap: a due time just past Long.MAX_VALUE becomes a large negative number.
        long beforeWrap = Long.MAX_VALUE - 10;
        long afterWrap = beforeWrap + 20; // overflows to a negative value, but is 20 ns later
        assertTrue(afterWrap < beforeWrap, "sanity: the raw values really did wrap");
        assertEquals(-1, TaskOrder.compareNanos(beforeWrap, afterWrap));
        assertEquals(1, TaskOrder.compareNanos(afterWrap, beforeWrap));
        assertEquals(List.of("first", "second"), sortedNames(
                List.of(task("second", 0, afterWrap, 0), task("first", 0, beforeWrap, 1)),
                TaskOrder.BY_DUE_TIME));
    }

    @Test
    void fixedRateStaysOnTheOriginalGrid() {
        // Ran on time: next slot is exactly one period later.
        assertEquals(110, TaskOrder.nextFixedRateDue(100, 10, 103));
        // Finished exactly at the next slot: that slot is still used.
        assertEquals(110, TaskOrder.nextFixedRateDue(100, 10, 110));
    }

    @Test
    void fixedRateSkipsSlotsMissedByALongRun() {
        // Due at 100 with period 10, but the run ended at 135: slots 110, 120 and 130 are skipped.
        assertEquals(140, TaskOrder.nextFixedRateDue(100, 10, 135));
        // Ended exactly on a later slot: that slot is used rather than skipped.
        assertEquals(130, TaskOrder.nextFixedRateDue(100, 10, 130));
        // Works when the clock values wrap around too.
        long due = Long.MAX_VALUE - 5;
        assertEquals(due + 30, TaskOrder.nextFixedRateDue(due, 10, due + 25));
    }
}
