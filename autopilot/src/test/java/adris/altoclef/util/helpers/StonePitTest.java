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
}
