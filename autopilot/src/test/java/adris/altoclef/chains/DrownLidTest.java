package adris.altoclef.chains;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// the escape under a lid when drowning (Tuning drown_lid): the jump let go for the lid break on the floor, and the walk to
// air's stuck clock waiting for the first path
public class DrownLidTest {

    @Test
    public void theLidBreakStandsOnTheFloor() {
        assertTrue(WorldSurvivalChain.lidStands(true, true, true));
    }

    @Test
    public void deeperWaterKeepsTheJump() {
        assertFalse(WorldSurvivalChain.lidStands(true, true, false));
    }

    @Test
    public void noLidBreakKeepsTheJump() {
        assertFalse(WorldSurvivalChain.lidStands(true, false, true));
    }

    @Test
    public void offKeepsTheJump() {
        assertFalse(WorldSurvivalChain.lidStands(false, true, true));
    }

    @Test
    public void theClockWaitsForTheFirstPath() {
        assertTrue(WorldSurvivalChain.toAirClockWaits(true, false, 0));
        assertTrue(WorldSurvivalChain.toAirClockWaits(true, false, WorldSurvivalChain.TO_AIR_CALC));
    }

    @Test
    public void theClockRunsOnceAPathRuns() {
        assertFalse(WorldSurvivalChain.toAirClockWaits(true, true, 5));
    }

    @Test
    public void theClockWaitsTwoSecondsAtMost() {
        assertFalse(WorldSurvivalChain.toAirClockWaits(true, false, WorldSurvivalChain.TO_AIR_CALC + 1));
    }

    @Test
    public void offTheClockRunsFromThePick() {
        assertFalse(WorldSurvivalChain.toAirClockWaits(false, false, 0));
    }

    // (drown_ore_ban) the block under the pick is left once, on the escape's first tick
    @Test
    public void theFirstLowAirTickWhileBreakingBans() {
        assertTrue(WorldSurvivalChain.drownBanNow(true, true, false, true));
    }

    @Test
    public void laterTicksOfTheSameEscapeDoNot() {
        assertFalse(WorldSurvivalChain.drownBanNow(true, true, true, true));
    }

    @Test
    public void noBreakNoBan() {
        assertFalse(WorldSurvivalChain.drownBanNow(true, false, false, true));
    }

    @Test
    public void breathingNoBan() {
        assertFalse(WorldSurvivalChain.drownBanNow(true, true, false, false));
    }

    @Test
    public void offNoBan() {
        assertFalse(WorldSurvivalChain.drownBanNow(false, true, false, true));
    }
}
