package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// box_home's rules (BoxHomeRules): when the box holds the buddy, which rod and fire are taken from it, when the fight's chase
// lets go, when a heal run goes home, when a heal is sealed and over, and when a fire resistance potion waits
public class BoxHomeRulesTest {

    @Test
    public void theBoxHoldsTillTheRodsAreIn() {
        assertTrue(BoxHomeRules.holds(true, 0, 2));
        assertTrue(BoxHomeRules.holds(true, 9, 10));
        assertFalse(BoxHomeRules.holds(true, 10, 10));
        // (no box standing, nothing holds: the camp as without the switch)
        assertFalse(BoxHomeRules.holds(false, 0, 10));
    }

    @Test
    public void aRodIsFetchedOnlyCloseAndWithNoBlazeInSight() {
        assertTrue(BoxHomeRules.rodFetch(false, 3.5, false));
        assertFalse(BoxHomeRules.rodFetch(false, 3.5, true));
        assertFalse(BoxHomeRules.rodFetch(false, BoxHomeRules.ROD_RANGE + 0.1, false));
        // (one in the box is taken whatever is about)
        assertTrue(BoxHomeRules.rodFetch(true, 0.5, true));
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
