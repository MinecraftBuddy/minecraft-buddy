package adris.altoclef.util.helpers;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.Test;

// the snapshot regression tests: every snapshot file (Snapshots, from the rig's runs) under a folder, its frames rebuilt as cells and
// held to the invariants (SnapshotChecks), the planners of this build run again on each. skipped unless a folder is given:
//   SNAPSHOTS=<folder> (or -Dsnapshots=<folder> from an IDE), searched all the way down for *.json.gz. the rig's lanes bring the
//   files back to ~/Development/buddy-practice/drills/cascade/lanes/<lane>/game-logs/snapshots, so the lanes folder takes them all:
//   SNAPSHOTS=~/Development/buddy-practice/drills/cascade/lanes ./gradlew test --tests '*SnapshotReplayTest' --rerun
// each violation is one line: the file, how long before its end, the check, what and why, in build/snapshots-report.txt (or
// SNAPSHOT_REPORT=<file>) and the test's output. the test fails on any violation, or with
// SNAPSHOT_BASELINE=<file> on any not listed there (one key a line, Violation.key); SNAPSHOT_BASELINE_WRITE=1 writes the
// violations of this run to that file and passes, so a known set can be accepted and only a new one fails a build
public class SnapshotReplayTest {
    // the lines printed per check and file at most, the rest counted
    static final int LINES = 4;

    static String setting(String env, String property) {
        String v = System.getenv(env);
        if (v == null || v.isBlank()) v = System.getProperty(property);
        if (v == null || v.isBlank()) return null;
        return v.startsWith("~/") ? System.getProperty("user.home") + v.substring(1) : v;
    }

    @Test
    public void everySnapshotHoldsTheInvariants() throws IOException {
        String dir = setting("SNAPSHOTS", "snapshots");
        assumeTrue("no SNAPSHOTS folder given", dir != null);
        List<Path> files;
        try (Stream<Path> s = Files.walk(Path.of(dir))) {
            files = s.filter(p -> p.getFileName().toString().endsWith(".json.gz")).sorted().toList();
        }
        assumeTrue("no snapshot files under " + dir, !files.isEmpty());

        long start = System.nanoTime();
        List<SnapshotChecks.Violation> all = new ArrayList<>();
        int frames = 0, unreadable = 0;
        int[] skips = new int[1];
        StringBuilder report = new StringBuilder();
        for (Path p : files) {
            SnapshotFile file;
            try {
                file = SnapshotFile.read(p);
            } catch (Exception e) {
                unreadable++;
                report.append(p.getFileName()).append("  unreadable: ").append(e).append('\n');
                continue;
            }
            frames += file.frames.size();
            List<SnapshotChecks.Violation> found = SnapshotChecks.run(file, SnapshotChecks.ALL, skips);
            all.addAll(found);
            Map<String, Integer> shown = new LinkedHashMap<>();
            for (SnapshotChecks.Violation v : found) {
                int n = shown.merge(v.check(), 1, Integer::sum);
                if (n <= LINES) report.append(v.line()).append('\n');
            }
            for (Map.Entry<String, Integer> e : shown.entrySet()) {
                if (e.getValue() > LINES) report.append(file.name).append("  ").append(e.getKey()).append(": ").append(e.getValue() - LINES).append(" more\n");
            }
        }
        double seconds = (System.nanoTime() - start) / 1e9;

        String baseline = setting("SNAPSHOT_BASELINE", "snapshots.baseline");
        Set<String> known = new HashSet<>();
        if (baseline != null && "1".equals(setting("SNAPSHOT_BASELINE_WRITE", "snapshots.baseline.write"))) {
            List<String> keys = new ArrayList<>();
            for (SnapshotChecks.Violation v : all) keys.add(v.key());
            Files.write(Path.of(baseline), keys, StandardCharsets.UTF_8);
            known.addAll(keys);
        } else if (baseline != null && Files.exists(Path.of(baseline))) {
            known.addAll(Files.readAllLines(Path.of(baseline), StandardCharsets.UTF_8));
        }
        List<SnapshotChecks.Violation> fresh = new ArrayList<>();
        for (SnapshotChecks.Violation v : all) if (!known.contains(v.key())) fresh.add(v);
        Map<String, Integer> byCheck = new LinkedHashMap<>();
        for (SnapshotChecks.Check c : SnapshotChecks.ALL) byCheck.put(c.name(), 0);
        for (SnapshotChecks.Violation v : all) byCheck.merge(v.check(), 1, Integer::sum);
        String summary = String.format(java.util.Locale.ROOT, "snapshots: %d files (%d unreadable), %d frames, %.1f s; %d violations (%d not in the baseline), %d judgements skipped (cells not seen); by check %s",
                files.size(), unreadable, frames, seconds, all.size(), fresh.size(), skips[0], byCheck);
        System.out.println(report);
        System.out.println(summary);
        // (Gradle shows a failed test's message cut short: the whole report goes to a file as well, build/snapshots-report.txt
        // under the autopilot's folder, or SNAPSHOT_REPORT)
        String to = setting("SNAPSHOT_REPORT", "snapshots.report");
        Path file = to != null ? Path.of(to) : Files.isDirectory(Path.of("build")) ? Path.of("build", "snapshots-report.txt") : null;
        if (file != null) Files.writeString(file, summary + "\n\n" + report, StandardCharsets.UTF_8);
        assertTrue(summary + (file == null ? "" : " (the report: " + file.toAbsolutePath() + ")") + "\n" + report, fresh.isEmpty());
    }
}
