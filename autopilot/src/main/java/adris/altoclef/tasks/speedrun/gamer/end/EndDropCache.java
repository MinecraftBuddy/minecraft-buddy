package adris.altoclef.tasks.speedrun.gamer.end;

import java.util.HashMap;
import java.util.Map;

// what we left lying in the End (item registry name -> count). fed the dropped item stacks every tick we are there,
// persisted into RunState.endDrops by the phase. the clock is passed in so a test does not need a game
public class EndDropCache {
    private final Map<String, Integer> counts = new HashMap<>();
    private final double waitSeconds;
    // last time we saw a non empty list, or arrived. right after a death the game thinks there are NO items for a moment
    private double quietSince;

    public EndDropCache(double waitSeconds) {
        this.waitSeconds = waitSeconds;
    }

    public void load(Map<String, Integer> saved) {
        counts.clear();
        counts.putAll(saved);
    }

    public void saveTo(Map<String, Integer> target) {
        target.clear();
        target.putAll(counts);
    }

    // arriving in the End (or leaving it): the empty list wait starts over
    public void restartWait(double now) {
        quietSince = now;
    }

    // returns true when the cache changed. canSeeDropSite = we are close enough to where the stuff fell that an empty list
    // means it is really gone, otherwise (items 100 blocks away are not tracked) an empty list says nothing and a partial
    // one can only add
    public boolean update(Map<String, Integer> dropped, double now, boolean canSeeDropSite) {
        if (dropped.isEmpty()) {
            if (!canSeeDropSite || now - quietSince < waitSeconds || counts.isEmpty()) {
                return false;
            }
            counts.clear();
            return true;
        }
        quietSince = now;
        Map<String, Integer> next = canSeeDropSite ? new HashMap<>(dropped) : merged(dropped);
        if (next.equals(counts)) {
            return false;
        }
        counts.clear();
        counts.putAll(next);
        return true;
    }

    private Map<String, Integer> merged(Map<String, Integer> seen) {
        Map<String, Integer> out = new HashMap<>(counts);
        seen.forEach((item, count) -> out.merge(item, count, Math::max));
        return out;
    }

    public int count(String item) {
        return counts.getOrDefault(item, 0);
    }

    public boolean isEmpty() {
        return counts.isEmpty();
    }
}
