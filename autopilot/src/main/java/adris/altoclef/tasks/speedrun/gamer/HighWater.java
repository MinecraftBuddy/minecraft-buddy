package adris.altoclef.tasks.speedrun.gamer;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.item.Item;

// "did the bag get anywhere": an item count passing the most we have held this phase. the old test was any change at all, and a
// bucket swapping between water and empty and lava changes the bag every few seconds for ever, so the 120 s no-progress timer
// never got to run. going down and coming back to a count we already had is not new. pure, the caller hands in the counts
public final class HighWater {
    private final Map<Item, Integer> max = new HashMap<>();

    // true when some item is above its high mark (a new item type counts, it went from nothing). this also raises the marks
    public boolean rose(Map<Item, Integer> counts) {
        boolean up = false;
        for (Map.Entry<Item, Integer> e : counts.entrySet()) {
            int n = e.getValue();
            Integer old = max.get(e.getKey());
            if (n > 0 && (old == null || n > old)) {
                max.put(e.getKey(), n);
                up = true;
            }
        }
        return up;
    }

    // a fresh phase starts the marks from what we carry now, so what we walked in with is not progress
    public void reset(Map<Item, Integer> counts) {
        max.clear();
        rose(counts);
    }
}
