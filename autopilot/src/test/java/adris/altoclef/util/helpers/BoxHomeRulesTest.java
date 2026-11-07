package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// box_home's rules (BoxHomeRules): when the box holds the buddy, when a rod trip begins and ends, which fire is put out, when
// the fight's chase lets go, when a heal run goes home, when a heal is sealed and over, and when a fire resistance potion waits
public class BoxHomeRulesTest {

    @Test
    public void theBoxHoldsTillTheRodsAreIn() {
        assertTrue(BoxHomeRules.holds(true, 0, 2));
        assertTrue(BoxHomeRules.holds(true, 9, 10));
        assertFalse(BoxHomeRules.holds(true, 10, 10));
        // (no box standing, nothing holds: the camp as without the switch)
        assertFalse(BoxHomeRules.holds(false, 0, 10));
    }

    // no hit yet
    private static final long NO_HIT = Long.MAX_VALUE / 2;

    @Test
    public void b3sRodsTwoUpByTheBoxAreFetched() {
        // (realfort-pair-home B:3: 9 rods lost, all 2.0 to 2.8 from the cell and two up on the spawner's platform; the old
        // gate, 4 flat and 1.5 up, never sent the buddy out)
        assertNull(BoxHomeRules.rodLeft(2.0, 2.0, false, false, true, 20f, 8.0, false, NO_HIT));
        assertNull(BoxHomeRules.rodLeft(2.8, 2.0, false, false, true, 20f, 8.0, false, NO_HIT));
        // (most fell into the fire there and burned in a tick or two: let go)
        assertEquals("on fire", BoxHomeRules.rodLeft(2.0, 2.0, true, false, true, 20f, 8.0, false, NO_HIT));
        // (a blaze the fight let go at 4.4 is still too near for a trip)
        assertEquals("blaze", BoxHomeRules.rodLeft(2.0, 2.0, false, false, true, 20f, 4.4, false, NO_HIT));
        // (three up is out of the pickup's reach from the ledge's foot)
        assertEquals("high", BoxHomeRules.rodLeft(2.0, 3.0, false, false, true, 20f, 8.0, false, NO_HIT));
    }

    @Test
    public void b2sRodsPastTheWallAreFetched() {
        // (B:2: two rods 3.4 and 3.7 from the cell, one up, past the bent box's wall; despawned after 300 s each)
        assertNull(BoxHomeRules.rodLeft(3.4, 1.0, false, false, true, 20f, 24.0, false, NO_HIT));
        assertNull(BoxHomeRules.rodLeft(3.7, 1.0, false, false, true, 13.5f, BoxHomeRules.TRIP_CLEAR + 0.1, false, NO_HIT));
        // (once out, the trip goes on whatever the blazes do: the gate is not asked again, tripEnd has no say on them. the
        // pickup and the lure took turns every tick there, 105 pickups begun in 61 s)
        assertNull(BoxHomeRules.tripEnd(false, false, false, true, false, 40));
        // (after the failed seal shut the box, every trip found no way: over, and the rod let go)
        String end = BoxHomeRules.tripEnd(false, false, false, false, false, 40);
        assertEquals("no way to it", end);
        assertTrue(BoxHomeRules.letGoAfter(end));
        assertEquals("let go", BoxHomeRules.rodLeft(3.4, 1.0, false, true, true, 20f, 24.0, false, NO_HIT));
    }

    @Test
    public void aTripBeginsFromTheBoxWithHealthAndNoBlazeClose() {
        assertEquals("out of the box", BoxHomeRules.rodLeft(3.0, 0, false, false, false, 20f, 24.0, false, NO_HIT));
        assertEquals("hp", BoxHomeRules.rodLeft(3.0, 0, false, false, true, BoxHomeRules.TRIP_HP - 0.5f, 24.0, false, NO_HIT));
        assertNull(BoxHomeRules.rodLeft(3.0, 0, false, false, true, BoxHomeRules.TRIP_HP, 24.0, false, NO_HIT));
        // (under fire resistance only a blaze within the bite's reach keeps the buddy in)
        assertNull(BoxHomeRules.rodLeft(3.0, 0, false, false, true, 20f, 3.0, true, NO_HIT));
        assertEquals("blaze", BoxHomeRules.rodLeft(3.0, 0, false, false, true, 20f, 3.0, false, NO_HIT));
        assertEquals("blaze", BoxHomeRules.rodLeft(3.0, 0, false, false, true, 20f, 2.0, true, NO_HIT));
        // (a trip that ended by a hit: the next waits)
        assertEquals("hit", BoxHomeRules.rodLeft(3.0, 0, false, false, true, 20f, 24.0, false, 40));
        assertNull(BoxHomeRules.rodLeft(3.0, 0, false, false, true, 20f, 24.0, false, BoxHomeRules.HIT_PAUSE_TICKS));
        // (the reach: 8 flat, two down)
        assertEquals("far", BoxHomeRules.rodLeft(BoxHomeRules.ROD_RANGE + 0.1, 0, false, false, true, 20f, 24.0, false, NO_HIT));
        assertEquals("low", BoxHomeRules.rodLeft(3.0, -2.5, false, false, true, 20f, 24.0, false, NO_HIT));
        assertNull(BoxHomeRules.rodLeft(BoxHomeRules.ROD_RANGE, -2.0, false, false, true, 20f, 24.0, false, NO_HIT));
    }

    @Test
    public void aTripEndsTakenGoneBurningHitOrLate() {
        assertEquals("picked up", BoxHomeRules.tripEnd(true, true, false, true, false, 60));
        assertEquals("gone", BoxHomeRules.tripEnd(true, false, false, true, false, 60));
        assertEquals("on fire", BoxHomeRules.tripEnd(false, false, true, true, false, 60));
        assertEquals("hit", BoxHomeRules.tripEnd(false, false, false, true, true, 60));
        assertEquals("not reached in 10 s", BoxHomeRules.tripEnd(false, false, false, true, false, BoxHomeRules.TRIP_TICKS + 1));
        assertNull(BoxHomeRules.tripEnd(false, false, false, true, false, BoxHomeRules.TRIP_TICKS));
        // (a hit or a rod gone is no reason to give the rod up for good; no way and too long are)
        assertFalse(BoxHomeRules.letGoAfter("hit"));
        assertFalse(BoxHomeRules.letGoAfter("picked up"));
        assertFalse(BoxHomeRules.letGoAfter(null));
        assertTrue(BoxHomeRules.letGoAfter("not reached in 10 s"));
    }

    @Test
    public void aFireIsPutOutOnlyInTheBoxOrItsDoor() {
        assertTrue(BoxHomeRules.fireOut(true, false));
        assertTrue(BoxHomeRules.fireOut(false, true));
        assertFalse(BoxHomeRules.fireOut(false, false));
    }

    @Test
    public void theChaseLetsGoOutsideTheLeashWhateverTheBlazeAtHome() {
        // (a blaze in the bite's reach: fought anywhere without the switch, let go past the leash with it)
        assertFalse(BoxHomeRules.letGo(false, 4.0, 2.5, false));
        assertTrue(BoxHomeRules.letGo(true, 4.0, 2.5, false));
        assertTrue(BoxHomeRules.letGo(false, 4.0, 2.5, true));
        // (inside the leash the fight goes on)
        assertFalse(BoxHomeRules.letGo(true, 2.0, 2.5, true));
    }

    @Test
    public void aHealRunGoesHomeFromAnywhere() {
        assertTrue(BoxHomeRules.runHome(true, true, false));
        assertTrue(BoxHomeRules.runHome(false, false, true));
        assertFalse(BoxHomeRules.runHome(false, true, false));
        // (a corner that is not the box: the run's range as before)
        assertFalse(BoxHomeRules.runHome(true, false, false));
    }

    @Test
    public void aHealIsSealedOnlyInTheBoxWithTheBlocks() {
        assertTrue(BoxHomeRules.sealHeal(true, true, true, false, 4, 2));
        assertFalse(BoxHomeRules.sealHeal(true, true, false, false, 4, 2));
        assertFalse(BoxHomeRules.sealHeal(true, false, true, false, 4, 2));
        assertFalse(BoxHomeRules.sealHeal(false, true, true, false, 4, 2));
        assertFalse(BoxHomeRules.sealHeal(true, true, true, true, 4, 2));
        assertFalse(BoxHomeRules.sealHeal(true, true, true, false, 1, 2));
    }

    @Test
    public void theSealedHealEndsFullStuckOrLate() {
        assertNull(BoxHomeRules.healDone(12f, 20f, 100, 50));
        assertEquals("full", BoxHomeRules.healDone(19.2f, 20f, 100, 50));
        assertEquals("no rise in 30 s", BoxHomeRules.healDone(12f, 20f, 700, BoxHomeRules.NO_RISE_TICKS + 1));
        assertEquals("sealed 120 s", BoxHomeRules.healDone(12f, 20f, BoxHomeRules.HEAL_MAX_TICKS + 1, 0));
    }

    @Test
    public void thePotionWaitsForTheFirstBlazeInTheBox() {
        // (on the way, on fire with health to spare: held)
        assertTrue(BoxHomeRules.holdPotion(false, false, true, 15, 8, false, false));
        // (a blaze near but the buddy not in the box yet: held)
        assertTrue(BoxHomeRules.holdPotion(false, false, false, 20, 8, false, true));
        // (in the box with a blaze near: down)
        assertFalse(BoxHomeRules.holdPotion(false, false, false, 20, 8, true, true));
        // (in the box on fire with no blaze near: held, the fire is put out or burns out)
        assertTrue(BoxHomeRules.holdPotion(false, false, true, 15, 8, true, false));
        // (low on fire, in lava, or asked by the brew box: down)
        assertFalse(BoxHomeRules.holdPotion(false, false, true, 8, 8, false, false));
        assertFalse(BoxHomeRules.holdPotion(false, true, true, 20, 8, false, false));
        assertFalse(BoxHomeRules.holdPotion(true, false, false, 20, 8, false, false));
    }
}
