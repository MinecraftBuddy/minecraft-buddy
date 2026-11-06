package adris.altoclef.util.helpers;

import adris.altoclef.tasks.speedrun.gamer.GamerPhase;
import net.minecraft.core.BlockPos;
import org.junit.Test;

import static org.junit.Assert.*;

public class WaterUpkeepTest {

    private static boolean wanted(boolean empty, boolean water, boolean evaporates, boolean threat, boolean calm, boolean room, GamerPhase phase) {
        return WaterUpkeep.wanted(empty, water, evaporates, threat, calm, room, phase);
    }

    @Test
    public void anEmptyBucketAndNoWaterInACalmRunIsWanted() {
        assertTrue(wanted(true, false, false, false, true, true, GamerPhase.IRON));
    }

    @Test
    public void everyConditionIsNeeded() {
        assertFalse(wanted(false, false, false, false, true, true, GamerPhase.IRON));
        assertFalse(wanted(true, true, false, false, true, true, GamerPhase.IRON));
        assertFalse(wanted(true, false, true, false, true, true, GamerPhase.IRON));
        assertFalse(wanted(true, false, false, true, true, true, GamerPhase.IRON));
        assertFalse(wanted(true, false, false, false, false, true, GamerPhase.IRON));
        assertFalse(wanted(true, false, false, false, true, false, GamerPhase.IRON));
    }

    @Test
    public void thePhasesThatJuggleBucketsAreLeftAlone() {
        for (GamerPhase p : new GamerPhase[]{GamerPhase.PORTAL, GamerPhase.END_PREP, GamerPhase.DRAGON, GamerPhase.DONE, GamerPhase.STUCK}) {
            assertFalse(p.name(), WaterUpkeep.phaseAllows(p));
        }
        for (GamerPhase p : new GamerPhase[]{GamerPhase.GATHER, GamerPhase.IRON, GamerPhase.NETHER, GamerPhase.EYES, GamerPhase.RETURN,
                GamerPhase.LOCATE, GamerPhase.ROOM, GamerPhase.OPEN}) {
            assertTrue(p.name(), WaterUpkeep.phaseAllows(p));
        }
    }

    @Test
    public void noGamerRunMeansNoUpkeep() {
        assertFalse(WaterUpkeep.phaseAllows(null));
    }

    @Test
    public void aFailureRestsForAMinute() {
        assertTrue(WaterUpkeep.coolingDown(1000 + WaterUpkeep.COOLDOWN_TICKS - 1, 1000));
        assertFalse(WaterUpkeep.coolingDown(1000 + WaterUpkeep.COOLDOWN_TICKS, 1000));
        // never failed
        assertFalse(WaterUpkeep.coolingDown(0, Integer.MIN_VALUE / 2));
    }

    @Test
    public void oneGoIsFifteenSeconds() {
        assertFalse(WaterUpkeep.timedOut(500 + WaterUpkeep.TIMEOUT_TICKS - 1, 500));
        assertTrue(WaterUpkeep.timedOut(500 + WaterUpkeep.TIMEOUT_TICKS, 500));
        assertEquals(300, WaterUpkeep.TIMEOUT_TICKS);
    }

    @Test
    public void aPocketUnderALidIsNotAPond() {
        assertTrue(WaterUpkeep.pondOpen(false, true));
        // one block of air then dirt: the flooded cave
        assertFalse(WaterUpkeep.pondOpen(true, true));
        // open sky but nowhere to stand
        assertFalse(WaterUpkeep.pondOpen(false, false));
        assertFalse(WaterUpkeep.pondOpen(true, false));
    }

    @Test
    public void aDryStandNeedsAFloorAndRoomAndNoWater() {
        assertTrue(WaterUpkeep.standable(true, false, true, true, false));
        assertFalse(WaterUpkeep.standable(false, false, true, true, false));
        assertFalse(WaterUpkeep.standable(true, true, true, true, false));
        assertFalse(WaterUpkeep.standable(true, false, false, true, false));
        assertFalse(WaterUpkeep.standable(true, false, true, false, false));
        assertFalse(WaterUpkeep.standable(true, false, true, true, true));
    }

    @Test
    public void aReconnectDoesNotFreezeTheRules() {
        // the tick counter starts over, so "now" can be smaller than any stamp from before
        assertFalse(WaterUpkeep.coolingDown(50, 400000));
        // a tick back is lag: still cooling, not timed out
        assertTrue(WaterUpkeep.coolingDown(998, 1000));
        assertFalse(WaterUpkeep.timedOut(998, 1000));
        assertTrue(WaterUpkeep.timedOut(50, 400000));
        assertFalse(WaterUpkeep.lookedRecently(50, 400000));
        assertTrue(WaterUpkeep.lookedRecently(110, 100));
    }

    @Test
    public void waterOnOrNextToAnEndPortalIsNoPond() {
        BlockPos portal = new BlockPos(5, 40, 5);
        // sitting on the portal, next to it at the same height, and on the frame one block over
        assertTrue(WaterUpkeep.byEndPortal(portal.above(), portal::equals));
        assertTrue(WaterUpkeep.byEndPortal(portal.east(), portal::equals));
        assertTrue(WaterUpkeep.byEndPortal(portal.east().north().above(), portal::equals));
    }

    @Test
    public void waterAwayFromThePortalIsStillAPond() {
        BlockPos portal = new BlockPos(5, 40, 5);
        assertFalse(WaterUpkeep.byEndPortal(portal.east(2).above(), portal::equals));
        assertFalse(WaterUpkeep.byEndPortal(portal.above(2), portal::equals));
        assertFalse(WaterUpkeep.byEndPortal(portal.below(), portal::equals));
    }
}
