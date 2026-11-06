package adris.altoclef.util.helpers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

// the buddy's fork: numbers the drills set per block, a block per value (steering entry 13, 4 Nov 2026: the strategies work
// better, their numbers still want tuning). the rig's drillrun.py writes the queue line's TUNE_* values into
// the buddy's folder before each run (tuning.properties, "buddy_leash=5"); read again when the file changes, looked at once a
// second. no file, or no such key, is the code's own default, as on every real buddy
public final class Tuning {
    private static final Path FILE = Path.of(System.getProperty("user.home"), "Development", "minecraft-afk", "tuning.properties");
    private static Properties props = new Properties();
    private static long fileTime = Long.MIN_VALUE;
    private static long lookedAt;

    private Tuning() {
    }

    public static synchronized double get(String key, double fallback) {
        long now = System.currentTimeMillis();
        if (now - lookedAt > 1000) {
            lookedAt = now;
            long t;
            try {
                t = Files.exists(FILE) ? Files.getLastModifiedTime(FILE).toMillis() : 0;
            } catch (Exception e) {
                t = 0;
            }
            if (t != fileTime) {
                fileTime = t;
                Properties p = new Properties();
                if (t != 0) {
                    try (var in = Files.newInputStream(FILE)) {
                        p.load(in);
                    } catch (Exception ignored) {
                        // a file being written: the next look reads it
                        fileTime = Long.MIN_VALUE;
                    }
                }
                props = p;
                if (!p.isEmpty()) adris.altoclef.Debug.logInternal("tuning: " + p);
            }
        }
        String v = props.getProperty(key);
        if (v == null) return fallback;
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
