package chronoq.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads a jobs file for the command-line demo. One job per line, comma-separated:
 *
 * <pre>
 * # name,start_after_ms,priority,duration_ms,repeat_every_ms,repeat_count
 * nightly-backup,0,5,400,0,0
 * heartbeat,100,1,20,200,5
 * </pre>
 *
 * Lines starting with {@code #} and blank lines are ignored, and so is an optional header row starting
 * with {@code name,}. {@code repeat_every_ms = 0} means the job runs once. A repeating job needs
 * {@code repeat_count >= 1}, so a demo run always finishes.
 */
final class JobFile {

    record Job(String name, long startAfterMs, int priority, long durationMs, long repeatEveryMs, int repeatCount) {
        boolean repeats() {
            return repeatEveryMs > 0;
        }
    }

    private JobFile() {
    }

    static List<Job> read(Path path) throws IOException {
        return parse(Files.readAllLines(path, StandardCharsets.UTF_8), path.getFileName().toString());
    }

    static List<Job> parse(List<String> lines, String source) {
        List<Job> jobs = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            int lineNo = i + 1;
            if (line.isEmpty() || line.startsWith("#") || line.toLowerCase().startsWith("name,")) {
                continue;
            }
            String[] f = line.split(",", -1);
            if (f.length != 6) {
                throw error(source, lineNo, "expected 6 comma-separated fields, found " + f.length);
            }
            String name = f[0].strip();
            if (name.isEmpty()) {
                throw error(source, lineNo, "job name is empty");
            }
            if (!names.add(name)) {
                throw error(source, lineNo, "duplicate job name '" + name + "'");
            }
            long start = number(f[1], "start_after_ms", source, lineNo);
            int priority = (int) bounded(number(f[2], "priority", source, lineNo), Integer.MIN_VALUE,
                    Integer.MAX_VALUE, "priority", source, lineNo);
            long duration = number(f[3], "duration_ms", source, lineNo);
            long every = number(f[4], "repeat_every_ms", source, lineNo);
            long count = number(f[5], "repeat_count", source, lineNo);
            if (start < 0 || duration < 0 || every < 0 || count < 0) {
                throw error(source, lineNo, "times and counts must not be negative");
            }
            if (every > 0 && count < 1) {
                throw error(source, lineNo, "a repeating job needs repeat_count of at least 1");
            }
            if (every == 0 && count > 1) {
                throw error(source, lineNo, "repeat_count > 1 needs repeat_every_ms > 0");
            }
            jobs.add(new Job(name, start, priority, duration, every,
                    (int) bounded(count, 0, 1_000_000, "repeat_count", source, lineNo)));
        }
        if (jobs.isEmpty()) {
            throw new IllegalArgumentException(source + ": no jobs found");
        }
        return jobs;
    }

    private static long number(String text, String field, String source, int lineNo) {
        try {
            return Long.parseLong(text.strip());
        } catch (NumberFormatException e) {
            throw error(source, lineNo, field + " is not a whole number: '" + text.strip() + "'");
        }
    }

    private static long bounded(long value, long min, long max, String field, String source, int lineNo) {
        if (value < min || value > max) {
            throw error(source, lineNo, field + " must be between " + min + " and " + max);
        }
        return value;
    }

    private static IllegalArgumentException error(String source, int lineNo, String message) {
        return new IllegalArgumentException(source + " line " + lineNo + ": " + message);
    }
}
