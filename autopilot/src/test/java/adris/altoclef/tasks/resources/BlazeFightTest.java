package adris.altoclef.tasks.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.BlazeFightRules;
import java.util.List;
import org.junit.Test;

// the run crowd is blazes plus whoever else is after us, so a bot with no blaze on it still has something to run from
public class BlazeFightTest {

    @Test
    public void chasersAloneMakeACrowd() {
        List<String> crowd = BlazeFight.merge(List.of(), List.of("wither skeleton"));
        assertEquals(List.of("wither skeleton"), crowd);
    }

    @Test
    public void blazesComeFirstAndNobodyIsDropped() {
        assertEquals(List.of("blaze", "wither skeleton", "hoglin"),
                BlazeFight.merge(List.of("blaze"), List.of("wither skeleton", "hoglin")));
    }

    @Test
    public void nobodyMeansSittingTight() {
        assertTrue(BlazeFight.merge(List.of(), List.of()).isEmpty());
    }

    // shorter than fire range and a blaze with a line on us counts as far enough, so the run stands there and takes it
    @Test
    public void aRunGoesPastFireRange() {
        assertTrue(BlazeFight.RUN_DISTANCE > BlazeFightRules.FIRE_RANGE);
    }

    @Test
    public void feetLevelAndUpStayBannedWhileFighting() {
        assertTrue(BlazeFight.placingBanned(true, 68, 68));
        assertTrue(BlazeFight.placingBanned(true, 69, 68));
    }

    @Test
    public void belowTheFeetIsStillBridgeable() {
        assertFalse(BlazeFight.placingBanned(true, 67, 68));
    }

    // a hair under the block top still stands on 65, so 64 is the bridge below us and stays open
    @Test
    public void floorRoundsLikeBaritoneFeet() {
        int floor = BlazeFight.floorY(64.92);
        assertEquals(65, floor);
        assertFalse(BlazeFight.placingBanned(true, 64, floor));
        assertTrue(BlazeFight.placingBanned(true, 65, floor));
        assertEquals(64, BlazeFight.floorY(64.5));
    }

    @Test
    public void nothingIsBannedWhenNotFighting() {
        assertFalse(BlazeFight.placingBanned(false, 70, 68));
        assertFalse(BlazeFight.placingBanned(false, 67, 68));
    }

    // the run keeps its shooter in the crowd while a shot is fresh, so an empty crowd list does not stand us still under fire
    @Test
    public void aFreshShotKeepsTheRunGoingAndAnOldOneDoesNot() {
        assertTrue(BlazeFightRules.shotLately(1000, 1000));
        assertTrue(BlazeFightRules.shotLately(1000 + BlazeFightRules.RUN_SHOT_TICKS, 1000));
        assertFalse(BlazeFightRules.shotLately(1001 + BlazeFightRules.RUN_SHOT_TICKS, 1000));
        // never shot, and a clock that went backwards
        assertFalse(BlazeFightRules.shotLately(1000, Long.MIN_VALUE / 2));
        assertFalse(BlazeFightRules.shotLately(500, 1000));
    }

    // the rods lost on the ground (RodWatch): a merge leaves no gap, a pickup is no loss, a rod that went and did not come is
    @Test
    public void rodsLostCountsOnlyWhatLeftTheGroundAndNotIntoTheBag() {
        org.junit.Assert.assertEquals(0, RodWatch.lostRods(2, 0, 2, 0));
        org.junit.Assert.assertEquals(0, RodWatch.lostRods(2, 0, 0, 2));
        org.junit.Assert.assertEquals(1, RodWatch.lostRods(1, 0, 0, 0));
        org.junit.Assert.assertEquals(1, RodWatch.lostRods(2, 1, 1, 1));
        org.junit.Assert.assertEquals(0, RodWatch.lostRods(0, 0, 0, -3));
    }
}
