package adris.altoclef.tasks.speedrun.gamer;

import java.util.Map;
import java.util.TreeMap;

// the one line a chest visit leaves in the log, because "it just wandered off" is not a bug report
public final class LootVisit {
    private LootVisit() {
    }

    // why the visit ended. settled means the open chest stayed empty of anything we want; otherwise it was the timer
    public static String why(boolean settled, boolean timedOut, boolean tookAny) {
        if (settled) {
            return tookAny ? "emptied" : "nothing wanted";
        }
        return timedOut ? "timer" : "stopped";
    }

    // "3x iron_ingot, 1x obsidian" or "nothing", sorted so the line is the same every time
    public static String summary(Map<String, Integer> taken) {
        if (taken.isEmpty()) {
            return "nothing";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : new TreeMap<>(taken).entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(e.getValue()).append("x ").append(e.getKey());
        }
        return sb.toString();
    }

    public static String line(String what, int x, int y, int z, String why, Map<String, Integer> taken) {
        return what + " chest " + x + " " + y + " " + z + " done (" + why + "), took " + summary(taken);
    }
}
