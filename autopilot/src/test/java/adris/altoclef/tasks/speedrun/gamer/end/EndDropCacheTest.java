package adris.altoclef.tasks.speedrun.gamer.end;

import adris.altoclef.tasks.speedrun.gamer.RunState;
import com.google.gson.Gson;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EndDropCacheTest {
    private static Map<String, Integer> drops(Object... pairs) {
        Map<String, Integer> out = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], (Integer) pairs[i + 1]);
        }
        return out;
    }

    @Test
    public void emptyListRightAfterArrivingIsNotBelieved() {
        EndDropCache cache = new EndDropCache(2);
        cache.load(drops("water_bucket", 1));
        cache.restartWait(100);
        assertFalse(cache.update(drops(), 100.5, true));
        assertFalse(cache.update(drops(), 101.9, true));
        assertEquals(1, cache.count("water_bucket"));
        assertTrue(cache.update(drops(), 102.0, true));
        assertEquals(0, cache.count("water_bucket"));
        assertTrue(cache.isEmpty());
    }

    @Test
    public void aNonEmptyListRestartsTheWait() {
        EndDropCache cache = new EndDropCache(2);
        cache.restartWait(0);
        assertTrue(cache.update(drops("white_bed", 3), 10, true));
        assertFalse(cache.update(drops(), 11, true));
        assertEquals(3, cache.count("white_bed"));
        assertTrue(cache.update(drops(), 12, true));
        assertEquals(0, cache.count("white_bed"));
    }

    @Test
    public void nonEmptyListReplacesWhatWeSaw() {
        EndDropCache cache = new EndDropCache(2);
        cache.update(drops("white_bed", 3, "iron_pickaxe", 1), 10, true);
        // we picked the pickaxe up
        assertTrue(cache.update(drops("white_bed", 3), 11, true));
        assertEquals(0, cache.count("iron_pickaxe"));
        assertFalse(cache.update(drops("white_bed", 3), 12, true));
    }

    @Test
    public void farFromTheDropSiteAnEmptyListSaysNothingAndAPartialOneOnlyAdds() {
        EndDropCache cache = new EndDropCache(2);
        cache.load(drops("white_bed", 3, "iron_pickaxe", 1));
        cache.restartWait(0);
        assertFalse(cache.update(drops(), 500, false));
        assertEquals(3, cache.count("white_bed"));
        // a stray item in view does not wipe the pile we cannot see
        cache.update(drops("white_bed", 1, "gold_ingot", 4), 501, false);
        assertEquals(3, cache.count("white_bed"));
        assertEquals(1, cache.count("iron_pickaxe"));
        assertEquals(4, cache.count("gold_ingot"));
    }

    @Test
    public void survivesARunStateRoundTripThroughGson() {
        EndDropCache cache = new EndDropCache(2);
        cache.update(drops("white_bed", 5, "water_bucket", 1), 10, true);
        RunState state = new RunState();
        cache.saveTo(state.endDrops);

        RunState back = new Gson().fromJson(new Gson().toJson(state), RunState.class);
        EndDropCache restored = new EndDropCache(2);
        restored.load(back.endDrops);
        assertEquals(5, restored.count("white_bed"));
        assertEquals(1, restored.count("water_bucket"));
        assertFalse(restored.isEmpty());
    }

    @Test
    public void saveToReplacesTheTargetNotMerges() {
        EndDropCache cache = new EndDropCache(2);
        cache.update(drops("white_bed", 5), 10, true);
        Map<String, Integer> target = drops("stale", 9);
        cache.saveTo(target);
        assertEquals(drops("white_bed", 5), target);
    }
}
