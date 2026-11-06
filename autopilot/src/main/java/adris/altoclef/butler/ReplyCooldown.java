package adris.altoclef.butler;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

// one answer per name per window, for strangers. somebody who whispers us in a loop gets one "not authorized" and then
// silence, not a reply for every line
public class ReplyCooldown {

    private final long windowMs;
    private final int maxNames;
    private final Map<String, Long> last = new HashMap<>();

    public ReplyCooldown(long windowMs, int maxNames) {
        this.windowMs = windowMs;
        this.maxNames = maxNames;
    }

    public boolean tryReply(String name, long nowMs) {
        String key = name.toLowerCase(Locale.ROOT);
        Long before = last.get(key);
        if (before != null && nowMs - before < windowMs) {
            return false;
        }
        if (last.size() >= maxNames) {
            // a name spammer can't grow this forever: forget everybody whose window is over, and if that is nobody, everybody
            last.values().removeIf(t -> nowMs - t >= windowMs);
            if (last.size() >= maxNames) {
                last.clear();
            }
        }
        last.put(key, nowMs);
        return true;
    }
}
