package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.EndermanNook.Spot;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EndermanNookTest {

    // a clean tunnel with the lid already on
    private static Spot good() {
        return new Spot(true, true, true, true, true, 0, false, false, 0);
    }

    @Test
    public void aCleanTunnelIsOk() {
        assertTrue(EndermanNook.spotOk(good()));
    }

    @Test
    public void everyMissingWallKillsTheSpot() {
        assertFalse(EndermanNook.spotOk(new Spot(false, true, true, true, true, 0, false, false, 0)));
        assertFalse(EndermanNook.spotOk(new Spot(true, false, true, true, true, 0, false, false, 0)));
        assertFalse(EndermanNook.spotOk(new Spot(true, true, false, true, true, 0, false, false, 0)));
        assertFalse(EndermanNook.spotOk(new Spot(true, true, true, false, true, 0, false, false, 0)));
        // an enderman cannot stand in the mouth, so it cannot come to the door
        assertFalse(EndermanNook.spotOk(new Spot(true, true, true, true, false, 0, false, false, 0)));
    }

    @Test
    public void lavaNearAnyCellRejectsIt() {
        assertFalse(EndermanNook.spotOk(new Spot(true, true, true, true, true, 0, false, true, 9)));
    }

    @Test
    public void aMissingLidNeedsBlocksToPlace() {
        assertFalse(EndermanNook.spotOk(new Spot(true, true, true, true, true, 2, false, false, 1)));
        assertTrue(EndermanNook.spotOk(new Spot(true, true, true, true, true, 2, false, false, 2)));
        assertTrue(EndermanNook.spotOk(new Spot(true, true, true, true, true, 1, false, false, 5)));
    }

    @Test
    public void aLidThatCannotBeFixedRejectsIt() {
        // sand over the cell, or a lid with nothing to click: it never comes right
        assertFalse(EndermanNook.spotOk(new Spot(true, true, true, true, true, 0, true, false, 9)));
    }

    @Test
    public void cheaperSpotsWin() {
        assertTrue(EndermanNook.spotCost(3, 0, 0) < EndermanNook.spotCost(3, 4, 0));
        assertTrue(EndermanNook.spotCost(3, 0, 0) < EndermanNook.spotCost(3, 0, 2));
        assertTrue(EndermanNook.spotCost(2, 4, 2) > EndermanNook.spotCost(2, 0, 0));
    }

    @Test
    public void standRuleIsTwoPointOneThree() {
        assertFalse(EndermanNook.standDeepEnough(2.0));
        assertFalse(EndermanNook.standDeepEnough(2.129));
        assertTrue(EndermanNook.standDeepEnough(2.13));
        assertTrue(EndermanNook.standDeepEnough(2.2));
    }

    @Test
    public void creepStopsAtTheLineAndTheWall() {
        // the middle of the back cell is 2.0: a bit further in, but never past what the hitbox has before the wall
        assertEquals(0.18, EndermanNook.creepBy(2.0), 1e-9);
        assertEquals(0, EndermanNook.creepBy(2.13), 1e-9);
        assertEquals(0, EndermanNook.creepBy(2.2), 1e-9);
        assertTrue(2.0 + EndermanNook.creepBy(2.0) <= EndermanNook.BACK_WALL_IN);
    }

    @Test
    public void noAggroBurningHurtOrNextToLava() {
        assertTrue(EndermanNook.mayAggro(false, 20, 99, 0, 20));
        assertFalse(EndermanNook.mayAggro(true, 20, 99, 0, 20));
        assertFalse(EndermanNook.mayAggro(false, 17.5f, 99, 0, 20));
        assertTrue(EndermanNook.mayAggro(false, 18, 99, 0, 20));
        assertFalse(EndermanNook.mayAggro(false, 20, 2, 0, 20));
        assertFalse(EndermanNook.mayAggro(false, 20, 1, 0, 20));
        assertTrue(EndermanNook.mayAggro(false, 20, 3, 0, 20));
    }

    @Test
    public void neverAggroASecondOneOrStareFromPointBlank() {
        assertFalse(EndermanNook.mayAggro(false, 20, 99, 1, 20));
        assertFalse(EndermanNook.mayAggro(false, 20, 99, 0, 3));
        assertTrue(EndermanNook.mayAggro(false, 20, 99, 0, 6));
    }

    @Test
    public void nearestAngryWinsInsideTheCap() {
        assertEquals(1, EndermanNook.nearestAngry(new double[]{20, 9, 14}));
        assertEquals(0, EndermanNook.nearestAngry(new double[]{3, 9, 14}));
    }

    @Test
    public void nearestAngryIgnoresTheFarAndTheDead() {
        assertEquals(-1, EndermanNook.nearestAngry(new double[]{33, 130}));
        assertEquals(-1, EndermanNook.nearestAngry(new double[]{Double.NaN}));
        assertEquals(2, EndermanNook.nearestAngry(new double[]{Double.NaN, 40, 32}));
        assertEquals(-1, EndermanNook.nearestAngry(new double[]{}));
    }

    @Test
    public void latchHasADistance() {
        assertTrue(EndermanNook.latchSees(40));
        assertFalse(EndermanNook.latchSees(40.1));
        assertFalse(EndermanNook.latchSees(130));
        assertFalse(EndermanNook.latchSees(Double.POSITIVE_INFINITY));
    }

    @Test
    public void latchOnlyCountsEndermenInSight() {
        // (NaN = dead or behind a hill)
        assertEquals(Double.POSITIVE_INFINITY, EndermanNook.nearestSeen(new double[]{Double.NaN, Double.NaN}), 0);
        assertEquals(Double.POSITIVE_INFINITY, EndermanNook.nearestSeen(new double[]{}), 0);
        assertEquals(30, EndermanNook.nearestSeen(new double[]{Double.NaN, 30, 44}), 0);
        assertTrue(EndermanNook.latchSees(EndermanNook.nearestSeen(new double[]{25})));
        assertFalse(EndermanNook.latchSees(EndermanNook.nearestSeen(new double[]{Double.NaN})));
    }

    @Test
    public void hotspotNeedsTheYBandToo() {
        assertTrue(EndermanNook.atHotspot(10, 3, 10, 40, 8));
        assertTrue(EndermanNook.atHotspot(0, -8, 0, 40, 8));
        // a hill 17 above the forest floor is not the forest
        assertFalse(EndermanNook.atHotspot(10, 17, 10, 40, 8));
        assertFalse(EndermanNook.atHotspot(41, 0, 0, 40, 8));
    }

    @Test
    public void walkingInNeedsTheAggroHealth() {
        assertTrue(EndermanNook.mayWalkIn(18));
        assertFalse(EndermanNook.mayWalkIn(17.5f));
        assertFalse(EndermanNook.mayWalkIn(3));
    }

    @Test
    public void brainStandsDownOnlyForEndermenAloneDuringTheHunt() {
        assertTrue(EndermanNook.brainStandsDown(true, 1, 1));
        assertTrue(EndermanNook.brainStandsDown(true, 2, 2));
        // a zombie in the mix keeps the usual rules, the hunt not running does too, and so does nobody being there
        assertFalse(EndermanNook.brainStandsDown(true, 2, 1));
        assertFalse(EndermanNook.brainStandsDown(false, 1, 1));
        assertFalse(EndermanNook.brainStandsDown(true, 0, 0));
    }

    @Test
    public void theNookHuntGetsALongerStallWindow() {
        assertEquals(120, EndermanNook.stallSeconds(false, 120), 0);
        assertTrue(EndermanNook.stallSeconds(true, 120) > EndermanNook.stallSeconds(false, 120));
        // a phase that already waits longer than the nook does is not shortened by being in one
        assertEquals(500, EndermanNook.stallSeconds(true, 500), 0);
    }

    @Test
    public void lavaOrFireStepsAway() {
        assertEquals(EndermanNook.Stance.AWAY, EndermanNook.stance(true, false, 20, false));
        assertEquals(EndermanNook.Stance.AWAY, EndermanNook.stance(false, true, 20, false));
        // even hurt: standing in it is worse than walking
        assertEquals(EndermanNook.Stance.AWAY, EndermanNook.stance(true, true, 4, true));
    }

    @Test
    public void lowHpWaitsOnlyWhileItIsRising() {
        assertEquals(EndermanNook.Stance.WAIT, EndermanNook.stance(false, false, 10, true));
        assertEquals(EndermanNook.Stance.HUNT, EndermanNook.stance(false, false, 10, false));
    }

    @Test
    public void veryLowHpNeverHuntsEvenWhenFlat() {
        assertEquals(EndermanNook.Stance.WAIT, EndermanNook.stance(false, false, 0.7f, false));
        assertEquals(EndermanNook.Stance.WAIT, EndermanNook.stance(false, false, EndermanNook.NEVER_HUNT_HP, false));
        assertEquals(EndermanNook.Stance.HUNT, EndermanNook.stance(false, false, EndermanNook.NEVER_HUNT_HP + 1, false));
    }

    @Test
    public void fineHuntsWhateverTheClockSays() {
        assertEquals(EndermanNook.Stance.HUNT, EndermanNook.stance(false, false, 20, true));
        assertEquals(EndermanNook.Stance.HUNT, EndermanNook.stance(false, false, EndermanNook.AGGRO_MIN_HP, false));
    }

    @Test
    public void hpClockRunsOutAfterThirtySeconds() {
        assertTrue(EndermanNook.hpRising(1000 + EndermanNook.HP_FLAT_TICKS, 1000));
        assertFalse(EndermanNook.hpRising(1001 + EndermanNook.HP_FLAT_TICKS, 1000));
    }

    @Test
    public void lavaUnderAFloorDoesNotCount() {
        assertTrue(EndermanNook.lavaExposed(true, false));
        assertFalse(EndermanNook.lavaExposed(true, true));
        assertFalse(EndermanNook.lavaExposed(false, false));
    }

    // alive, calm, seen, in range, healthy, our lava clear, nobody angry yet, no lava between
    private static boolean stare(double dist, boolean between) {
        return EndermanNook.stareWorthwhile(false, 20, true, 0, dist, true, false, true, between);
    }

    @Test
    public void staresOnlyAtACalmSeenEndermanInRange() {
        assertTrue(stare(20, false));
        assertFalse(EndermanNook.stareWorthwhile(false, 20, true, 0, 20, false, false, true, false));
        assertFalse(EndermanNook.stareWorthwhile(false, 20, true, 0, 20, true, true, true, false));
        assertFalse(EndermanNook.stareWorthwhile(false, 20, true, 0, 20, true, false, false, false));
        assertFalse(stare(48.5, false));
        assertTrue(stare(48, false));
        // closer than 6 only makes it blink away
        assertFalse(stare(5.9, false));
    }

    @Test
    public void stareFollowsTheAggroRules() {
        assertFalse(EndermanNook.stareWorthwhile(true, 20, true, 0, 20, true, false, true, false));
        assertFalse(EndermanNook.stareWorthwhile(false, 17, true, 0, 20, true, false, true, false));
        assertFalse(EndermanNook.stareWorthwhile(false, 20, false, 0, 20, true, false, true, false));
        assertFalse(EndermanNook.stareWorthwhile(false, 20, true, 1, 20, true, false, true, false));
    }

    @Test
    public void lavaBetweenOnlyMattersInsideTheTeleportRange() {
        // inside 16 it walks and a walk does not cross lava
        assertFalse(stare(10, true));
        assertFalse(stare(16, true));
        assertTrue(stare(10, false));
        // past 16 an angry one teleports to us, lava or not
        assertTrue(stare(16.5, true));
    }
}
