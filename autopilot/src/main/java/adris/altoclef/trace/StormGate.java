package adris.altoclef.trace;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

// one storm, one flag. a parent and the child it keeps swapping (the mining task and its pickup, the pickup and its walk)
// storm together, and saying so three times in a row buries the one line that matters. the outermost class that storms
// gets the flag, anything under it that storms inside the same minute stays quiet. pure, the clock is an argument
public final class StormGate {

    private final Map<Class<?>, Long> flagged = new HashMap<>();

    // a class that is about to be flagged. true when something above it already was (within the window), or is restarting
    // so much that it is about to be (more than half the limit): the child's count is just a symptom of that. ancestors
    // are the parent classes from the nearest up, a class does not shadow itself
    public boolean shadowed(Class<?> cls, List<Class<?>> ancestors, long nowMs, ToIntFunction<Class<?>> recentStarts) {
        for (Class<?> up : ancestors) {
            if (up == cls) {
                continue;
            }
            Long at = flagged.get(up);
            if (at != null && nowMs - at <= StartStormWatch.WINDOW_MS) {
                return true;
            }
            if (recentStarts.applyAsInt(up) > StartStormWatch.LIMIT / 2) {
                return true;
            }
        }
        return false;
    }

    // a storm was seen for this class, said out loud or not: whatever is under it is covered by it for the next minute
    public void seen(Class<?> cls, long nowMs) {
        flagged.put(cls, nowMs);
    }
}
