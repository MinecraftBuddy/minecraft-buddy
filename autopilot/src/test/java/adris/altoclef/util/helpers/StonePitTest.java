package adris.altoclef.util.helpers;

import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// (Tuning stone_pit_first) the pit for the first stone where the server hides what is buried: when one is wanted, where it may
// go, when it ends
public class StonePitTest {
    @BeforeClass
    public static void boot() {
        // StoneDigRank's block sets need the registries
        ItemFixture.boot();
    }

    @Test
    public void aStoneFarBelowWantsAPit() {
        // test heel: feet at 66, the cave wall 21 to 25 below
        assertTrue(StoneDigRank.pitWanted(false, 66, 44, false));
        assertTrue(StoneDigRank.pitWanted(false, 66, 57, false));
        // eight below or less is the pick's own (a walk, the tunnel)
        assertFalse(StoneDigRank.pitWanted(false, 66, 58, false));
        assertFalse(StoneDigRank.pitWanted(false, 66, 70, false));
    }

    @Test
    public void noStoneKnownWantsAPitOnlyWhileWandering() {
        assertTrue(StoneDigRank.pitWanted(false, 66, null, true));
        assertFalse(StoneDigRank.pitWanted(false, 66, null, false));
    }

    @Test
    public void aLatchedTunnelWantsNoPit() {
        assertFalse(StoneDigRank.pitWanted(true, 66, 40, false));
        assertFalse(StoneDigRank.pitWanted(true, 66, null, true));
    }

    @Test
    public void pitsAreSpacedAndCapped() {
        assertTrue(StoneDigRank.pitAllowed(0, Long.MAX_VALUE / 2, false));
        assertTrue(StoneDigRank.pitAllowed(1, StoneDigRank.PIT_GAP_TICKS, false));
        assertFalse(StoneDigRank.pitAllowed(StoneDigRank.PIT_MAX, Long.MAX_VALUE / 2, false));
        assertFalse(StoneDigRank.pitAllowed(0, StoneDigRank.PIT_GAP_TICKS - 1, false));
        assertFalse(StoneDigRank.pitAllowed(0, Long.MAX_VALUE / 2, true));
    }

    @Test
    public void aPitNearOneDugLatelyIsRefused() {
        List<long[]> recent = new ArrayList<>();
        recent.add(new long[]{10, 20, 1000});
        assertTrue(StoneDigRank.nearPit(recent, 12, 18, 1000 + 100));
        assertFalse(StoneDigRank.nearPit(recent, 13, 20, 1000 + 100));
        // older than PIT_SAME_TICKS: forgotten
        assertFalse(StoneDigRank.nearPit(recent, 10, 20, 1000 + StoneDigRank.PIT_SAME_TICKS));
        assertFalse(StoneDigRank.nearPit(new ArrayList<>(), 10, 20, 1000));
    }

    @Test
    public void aColumnOfGroundOverAFloorTakesAPit() {
        // feet at 64: cells 63 to 60 to break, the floor at 59
        assertTrue(StoneDigRank.pitColumn(64, 4, y -> true, y -> false, y -> y == 59));
    }

    @Test
    public void aColumnOverACaveWaterOrBedrockTakesNone() {
        // air under the last cell: a drop into a cave
        assertFalse(StoneDigRank.pitColumn(64, 4, y -> true, y -> false, y -> false));
        // water beside the third cell
        assertFalse(StoneDigRank.pitColumn(64, 4, y -> true, y -> y == 61, y -> true));
        // a cell that will not break (air in the column, bedrock, a chest)
        assertFalse(StoneDigRank.pitColumn(64, 4, y -> y != 62, y -> false, y -> true));
        // a pit of one: the next cell and the floor under it
        assertTrue(StoneDigRank.pitColumn(64, 1, y -> y == 63, y -> false, y -> y == 62));
    }

    @Test
    public void thePitEndsOnStoneInReachAtItsFloorOrInTime() {
        assertEquals("stone in reach", StoneDigRank.pitEnd(63, 60, true, true, 20));
        assertEquals("at its floor", StoneDigRank.pitEnd(60, 60, true, false, 100));
        assertEquals("no stone in 30 s", StoneDigRank.pitEnd(62, 60, true, false, StoneDigRank.PIT_TICKS + 1));
        assertNull(StoneDigRank.pitEnd(62, 60, true, false, 100));
        // falling to the floor is not there yet
        assertNull(StoneDigRank.pitEnd(60, 60, false, false, 100));
    }

    // (Tuning stone_pit_direct) the column is refused where water lies beside the ground the buddy stands on: it comes into the
    // pit with its first block, and Baritone refuses that block
    @Test
    public void waterBesideTheGroundTakesNoDirectPit() {
        // feet at 64, a pond beside the feet's cell
        assertFalse(StoneDigRank.pitColumn(64, 4, y -> true, y -> y == 64, y -> y == 59, true));
        // the old rule looked at the cells under the feet only
        assertTrue(StoneDigRank.pitColumn(64, 4, y -> true, y -> y == 64, y -> y == 59, false));
        assertTrue(StoneDigRank.pitColumn(64, 4, y -> true, y -> false, y -> y == 59, true));
        // a cell Baritone's avoid refuses is no cell to break (the breakable test carries it)
        assertFalse(StoneDigRank.pitColumn(64, 4, y -> y != 63, y -> false, y -> y == 59, true));
    }

    // (Tuning stone_pit_direct) test heel: two pits ended "no stone in 30 s" with no block dug and used both of the task's
    // pits; such a pit counts no more, but no more than PIT_TRIES begin
    @Test
    public void aPitThatDugNothingDoesNotCount() {
        // began at 64, the feet still there and the block under them whole
        assertFalse(StoneDigRank.pitCounts(64, 64, false));
        // the feet dropped, or the first cell went
        assertTrue(StoneDigRank.pitCounts(64, 63, false));
        assertTrue(StoneDigRank.pitCounts(64, 64, true));
        // two empty pits: none counted, a third may start
        assertTrue(StoneDigRank.pitAllowed(0, 2, StoneDigRank.PIT_GAP_TICKS, false));
        // the tries are capped all the same, and PIT_MAX holds for pits that dug
        assertFalse(StoneDigRank.pitAllowed(0, StoneDigRank.PIT_TRIES, Long.MAX_VALUE / 2, false));
        assertFalse(StoneDigRank.pitAllowed(StoneDigRank.PIT_MAX, 2, Long.MAX_VALUE / 2, false));
        // the old form counts no tries
        assertTrue(StoneDigRank.pitAllowed(1, Long.MAX_VALUE / 2, false));
    }
}
