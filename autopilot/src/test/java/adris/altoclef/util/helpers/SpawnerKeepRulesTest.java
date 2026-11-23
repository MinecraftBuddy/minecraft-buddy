package adris.altoclef.util.helpers;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// (spawner_keep) the blaze spawner the rods came from, kept for the run (look 234)
public class SpawnerKeepRulesTest {

    @Test
    public void aRodByTheSpawnerMarksIt() {
        assertTrue(SpawnerKeepRules.rodFrom(true, 2, 3, true, 10));
        assertFalse("no rod came in", SpawnerKeepRules.rodFrom(true, 3, 3, true, 10));
        assertFalse("the first look at the bag", SpawnerKeepRules.rodFrom(true, -1, 3, true, 10));
        assertFalse("no spawner known", SpawnerKeepRules.rodFrom(true, 2, 3, false, 10));
        assertFalse("far from the spawner", SpawnerKeepRules.rodFrom(true, 2, 3, true, SpawnerKeepRules.RODS_FROM + 1));
        assertFalse("the switch off", SpawnerKeepRules.rodFrom(false, 2, 3, true, 10));
    }

    @Test
    public void theWalkBackOnlyInRangeInTheNetherAndNotBanned() {
        assertTrue(SpawnerKeepRules.walkBack(true, true, false, true, 300));
        assertFalse("nothing kept", SpawnerKeepRules.walkBack(true, false, false, true, 300));
        assertFalse("banned as unreachable", SpawnerKeepRules.walkBack(true, true, true, true, 300));
        assertFalse("in the overworld", SpawnerKeepRules.walkBack(true, true, false, false, 300));
        assertFalse("too far", SpawnerKeepRules.walkBack(true, true, false, true, SpawnerKeepRules.KEEP_RANGE + 1));
        assertFalse("the switch off", SpawnerKeepRules.walkBack(false, true, false, true, 300));
    }

    @Test
    public void theWalkBackIsStuckAfterTwoMinutesWithNoGain() {
        assertFalse(SpawnerKeepRules.stuck(1000, 1000 + SpawnerKeepRules.STUCK_TICKS));
        assertTrue(SpawnerKeepRules.stuck(1000, 1001 + SpawnerKeepRules.STUCK_TICKS));
        assertFalse("a clock that went back", SpawnerKeepRules.stuck(1000, 500));
    }

    @Test
    public void theFirstSpawnerFoundIsKeptWhenNoneIs() {
        assertTrue(SpawnerKeepRules.keepFound(true, false));
        assertFalse("one already kept (a rod came from it, or found first)", SpawnerKeepRules.keepFound(true, true));
        assertFalse("the switch off", SpawnerKeepRules.keepFound(false, false));
    }
}
