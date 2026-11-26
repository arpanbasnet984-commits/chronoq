package chronoq.cli;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobFileTest {

    private static List<JobFile.Job> parse(String... lines) {
        return JobFile.parse(List.of(lines), "jobs.csv");
    }

    private static String errorFor(String... lines) {
        return assertThrows(IllegalArgumentException.class, () -> parse(lines)).getMessage();
    }

    @Test
    void parsesJobsSkippingCommentsBlankLinesAndHeader() {
        List<JobFile.Job> jobs = parse(
                "# a comment",
                "name,start_after_ms,priority,duration_ms,repeat_every_ms,repeat_count",
                "",
                "backup, 0, 5, 400, 0, 0",
                "heartbeat,100,1,20,200,5");
        assertEquals(2, jobs.size());
        assertEquals(new JobFile.Job("backup", 0, 5, 400, 0, 0), jobs.get(0));
        JobFile.Job hb = jobs.get(1);
        assertTrue(hb.repeats());
        assertEquals(5, hb.repeatCount());
        assertFalse(jobs.get(0).repeats());
    }

    @Test
    void reportsTheLineNumberOfBadInput() {
        assertEquals("jobs.csv line 2: priority is not a whole number: 'high'",
                errorFor("a,0,1,10,0,0", "b,0,high,10,0,0"));
        assertTrue(errorFor("a,0,1,10,0").contains("line 1: expected 6"));
        assertTrue(errorFor("a,-5,1,10,0,0").contains("must not be negative"));
        assertTrue(errorFor(",0,1,10,0,0").contains("name is empty"));
        assertTrue(errorFor("a,0,1,10,0,0", "a,0,1,10,0,0").contains("line 2: duplicate job name 'a'"));
        assertTrue(errorFor("a,0,99999999999,10,0,0").contains("priority must be between"));
    }

    @Test
    void rejectsRepeatSettingsThatWouldNeverFinishOrMakeNoSense() {
        assertTrue(errorFor("a,0,1,10,100,0").contains("needs repeat_count of at least 1"));
        assertTrue(errorFor("a,0,1,10,0,3").contains("needs repeat_every_ms > 0"));
    }

    @Test
    void rejectsAFileWithNoJobs() {
        assertTrue(errorFor("# only a comment", "").contains("no jobs found"));
    }
}
