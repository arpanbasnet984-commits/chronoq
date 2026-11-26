package chronoq.scheduler;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskSpecTest {

    private static final Runnable NOOP = () -> { };

    @Test
    void builderDefaultsToAOneOffTaskDueNowWithPriorityZero() {
        TaskSpec spec = TaskSpec.named("job").runs(NOOP).build();
        assertEquals(Duration.ZERO, spec.initialDelay());
        assertEquals(0, spec.priority());
        assertNull(spec.period());
        assertEquals(1, spec.maxRuns());
        assertFalse(spec.isRecurring());
    }

    @Test
    void repeatingTaskWithoutTimesRunsUntilCancelled() {
        TaskSpec spec = TaskSpec.named("tick").runs(NOOP).every(Duration.ofSeconds(1)).build();
        assertTrue(spec.isRecurring());
        assertEquals(0, spec.maxRuns());
        assertEquals(3, TaskSpec.named("tick").runs(NOOP).every(Duration.ofSeconds(1)).times(3).build().maxRuns());
    }

    @Test
    void rejectsInvalidSpecs() {
        assertThrows(IllegalArgumentException.class, () -> TaskSpec.named(" ").runs(NOOP).build());
        assertThrows(NullPointerException.class, () -> TaskSpec.named("x").build());
        assertThrows(IllegalArgumentException.class,
                () -> TaskSpec.named("x").runs(NOOP).after(Duration.ofMillis(-1)).build());
        assertThrows(IllegalArgumentException.class,
                () -> TaskSpec.named("x").runs(NOOP).every(Duration.ZERO).build());
        assertThrows(IllegalArgumentException.class,
                () -> TaskSpec.named("x").runs(NOOP).every(Duration.ofMillis(-5)).build());
        assertThrows(IllegalArgumentException.class, () -> TaskSpec.named("x").runs(NOOP).times(0));
        assertThrows(IllegalArgumentException.class, () -> TaskSpec.named("x").runs(NOOP).times(3).build(),
                "a one-off task cannot run more than once");
    }

    @Test
    void rejectsDurationsTooLongForTheClock() {
        // Durations near Long.MAX_VALUE nanoseconds would break nanoTime arithmetic.
        assertThrows(IllegalArgumentException.class,
                () -> TaskSpec.named("x").runs(NOOP).after(Duration.ofDays(365L * 200)).build());
        assertThrows(IllegalArgumentException.class,
                () -> TaskSpec.named("x").runs(NOOP).every(Duration.ofSeconds(Long.MAX_VALUE)).build());
        TaskSpec.named("x").runs(NOOP).after(Duration.ofDays(365L * 100)).build(); // 100 years is fine
    }
}
