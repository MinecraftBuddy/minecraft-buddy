package adris.altoclef.tasks.resources;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// the seen-hold of obsidian (obsidian_seen_hold) and its end for the buddy's own mined cast block (cast_hold_end):
// CollectObsidianTask.held
public class CastHoldTest {
    private static final long NONE = Long.MIN_VALUE;
    private static final long HOLD = CollectObsidianTask.SEEN_HOLD;

    @Test
    public void theOwnCastBlockMinedEndsTheHoldAtOnce() {
        // the cast block was seen (and its drop) a tick ago, nothing else was ever found, the block is gone
        assertFalse(CollectObsidianTask.held(true, true, 1000, 999, NONE, true));
        // nothing else for longer than the hold either
        assertFalse(CollectObsidianTask.held(true, true, 1000, 999, 1000 - HOLD - 1, true));
    }

    @Test
    public void naturalObsidianKeepsItsHold() {
        // a natural block seen 5 s ago, then gone from the tracker: held, whether or not a cast block was mined since
        assertTrue(CollectObsidianTask.held(true, true, 1000, 900, 900, false));
        assertTrue(CollectObsidianTask.held(true, true, 1000, 999, 900, true));
        // never a cast of our own: the hold as it was
        assertTrue(CollectObsidianTask.held(true, true, 1000, 900, 900, false));
    }

    @Test
    public void theCastBlockStillThereHoldsAsBefore() {
        // the block of the cast is not gone (it went out of the tracker's view only): the old hold
        assertTrue(CollectObsidianTask.held(true, true, 1000, 999, NONE, false));
    }

    @Test
    public void theSwitchOffHoldsAsBefore() {
        assertTrue(CollectObsidianTask.held(true, false, 1000, 999, NONE, true));
    }

    @Test
    public void noHoldWithoutTheHoldOrPastIt() {
        assertFalse(CollectObsidianTask.held(false, true, 1000, 999, 999, false));
        assertFalse(CollectObsidianTask.held(true, true, 1000, 1000 - HOLD - 1, 1000 - HOLD - 1, false));
        assertTrue(CollectObsidianTask.held(true, true, 1000, 1000 - HOLD, 1000 - HOLD, false));
        assertFalse(CollectObsidianTask.held(true, true, 1000, NONE, NONE, false));
    }
}
