package adris.altoclef.util.helpers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

// the buddy's fork: numbers the drills set per block, a block per value (steering entry 13, 4 Nov 2026: the strategies work
// better, their numbers still want tuning). the rig's drillrun.py writes the queue line's TUNE_* values into
// tuning.properties in the game directory before each run ("buddy_leash=5"); read again when the file changes, looked at
// once a second. no file, or no such key: DEFAULTS, then the caller's fallback, as on every real buddy
public final class Tuning {
    // the code defaults: what every control arm of the A/B blocks carried on 7 Nov 2026 (the brief's repair 2), so a queue
    // line names only what an arm changes and a real buddy plays what the drills measured. a key changes here only when its
    // A/B block confirms it (or at the maintainer's word)
    public static final java.util.Map<String, Double> DEFAULTS = java.util.Map.ofEntries(
            java.util.Map.entry("brew_box", 2.0),
            java.util.Map.entry("calm_reach", 4.5),
            java.util.Map.entry("cream_down", 6.0),
            java.util.Map.entry("cream_up", 12.0),
            java.util.Map.entry("cream_wild", 1.0),
            java.util.Map.entry("fireproof_camp", 1.0),
            java.util.Map.entry("fireproof_side", 1.0),
            java.util.Map.entry("lure_cap", 120.0),
            java.util.Map.entry("piglin_grudge", 1.0),
            java.util.Map.entry("spawner_wait_cap", 90.0));

    private static Path file;
    private static Properties props = new Properties();
    private static long fileTime = Long.MIN_VALUE;
    private static long lookedAt;

    private Tuning() {
    }

    public static synchronized double get(String key, double fallback) {
        long now = System.currentTimeMillis();
        if (now - lookedAt > 1000) {
            lookedAt = now;
            Path f = file();
            long t;
            try {
                t = f != null && Files.exists(f) ? Files.getLastModifiedTime(f).toMillis() : 0;
            } catch (Exception e) {
                t = 0;
            }
            if (t != fileTime) {
                fileTime = t;
                Properties p = new Properties();
                if (t != 0) {
                    try (var in = Files.newInputStream(f)) {
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
        double base = DEFAULTS.getOrDefault(key, fallback);
        if (v == null) return base;
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return base;
        }
    }

    // tuning.properties in the game directory (the system property buddy.tuning first, for tests); null before the game is up
    private static Path file() {
        if (file != null) return file;
        String prop = System.getProperty("buddy.tuning");
        if (prop != null) return file = Path.of(prop);
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null && mc.gameDirectory != null) file = mc.gameDirectory.toPath().resolve("tuning.properties");
        } catch (Throwable ignored) {
            // no game (a unit test): the defaults
        }
        return file;
    }

    // every key in force now, the defaults and what the file sets, sorted (the snapshots' header, Snapshots)
    public static synchronized java.util.Map<String, String> all() {
        get("", 0);
        java.util.Map<String, String> out = new java.util.TreeMap<>();
        for (var e : DEFAULTS.entrySet()) out.put(e.getKey(), String.valueOf(e.getValue()));
        for (String k : props.stringPropertyNames()) out.put(k, props.getProperty(k));
        return out;
    }
}
