package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.FightOpenRules.Rush;
import baritone.pathing.movement.CliffAvoidance.Cell;
import baritone.pathing.movement.CliffAvoidance.Cells;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

// the blaze fight's opening (open_attack), the rush under fire resistance (fireproof_rush) and the potion held for the blazes
// (fr_hold): FightOpenRules
public class FightOpenRulesTest {

    // ---- open_attack

    @Test
    public void fewerThanThreeBlazesWithinSixteenIsTheOpenAttack() {
        assertTrue(FightOpenRules.open(0));
        assertTrue(FightOpenRules.open(1));
        assertTrue(FightOpenRules.open(2));
        assertFalse(FightOpenRules.open(3));
        assertFalse(FightOpenRules.open(7));
        assertEquals(16, FightOpenRules.OPEN_RADIUS, 0);
    }

    @Test
    public void theFirstTickAtTheSpawnerTakesTheCountsSideAtOnce() {
        FightOpenRules.OpenLatch l = new FightOpenRules.OpenLatch();
        assertFalse(l.update(100, false, true));
        assertFalse(l.changed());
        assertTrue(l.update(101, true, true));
        assertTrue(l.changed());
        assertTrue(l.update(102, true, true));
        assertFalse(l.changed());
    }

    @Test
    public void aChangeHoldsASecondBeforeItCounts() {
        FightOpenRules.OpenLatch l = new FightOpenRules.OpenLatch();
        l.update(0, true, true);
        // a third blaze in and out again within the second: no change, no line
        assertTrue(l.update(10, true, false));
        assertTrue(l.update(25, true, false));
        assertTrue(l.update(26, true, true));
        assertFalse(l.changed());
        // three or more for a whole second: the lure again, one line
        assertTrue(l.update(40, true, false));
        assertTrue(l.update(59, true, false));
        assertFalse(l.changed());
        assertFalse(l.update(60, true, false));
        assertTrue(l.changed());
        assertFalse(l.update(61, true, false));
        assertFalse(l.changed());
    }

    @Test
    public void awayFromTheSpawnerTheStateIsForgotten() {
        FightOpenRules.OpenLatch l = new FightOpenRules.OpenLatch();
        l.update(0, true, false);
        assertFalse(l.update(1, false, true));
        assertFalse(l.changed());
        assertFalse(l.open());
        // back at it: the count's side at once, with its line
        assertTrue(l.update(2, true, true));
        assertTrue(l.changed());
        assertTrue(l.open());
    }

    @Test
    public void theOpenAttacksLines() {
        assertEquals("open attack: 2 within 16, going after the nearest in reach (no lure, no hide, no leash)", FightOpenRules.openLine(true, 2));
        assertEquals("open attack: 4 within 16, 3 or more, the lure", FightOpenRules.openLine(false, 4));
    }

    // ---- fireproof_rush

    @Test
    public void theRushNeedsTheSwitchAndMoreThanTenSecondsOrAPotionLeft() {
        assertEquals(Rush.OFF, FightOpenRules.rush(false, 3600, 3));
        assertEquals(Rush.OFF, FightOpenRules.rush(true, 0, 3));
        assertEquals(Rush.ON, FightOpenRules.rush(true, 201, 0));
        assertEquals(Rush.ON, FightOpenRules.rush(true, Integer.MAX_VALUE, 0));
        // ten seconds or less: on while the food chain drinks again, off with no potion left (the camp as without the rush
        // before the effect ends)
        assertEquals(Rush.REDRINK, FightOpenRules.rush(true, 200, 1));
        assertEquals(Rush.REDRINK, FightOpenRules.rush(true, 1, 2));
        assertEquals(Rush.OFF, FightOpenRules.rush(true, 200, 0));
        assertEquals(200, FightOpenRules.RUSH_MIN_TICKS);
    }

    @Test
    public void theRushOffSaysWhy() {
        assertEquals("the switch off", FightOpenRules.offWhy(false, 3600, 1));
        assertEquals("the effect gone, 2 potions left", FightOpenRules.offWhy(true, 0, 2));
        assertEquals("the effect gone, no potion left", FightOpenRules.offWhy(true, 0, 0));
        assertEquals("no potion left, 10 s of the effect, the camp as without the rush", FightOpenRules.offWhy(true, 200, 0));
    }

    @Test
    public void fireIsWalkableOnlyWithMoreThanTenSecondsLeft() {
        assertTrue(FightOpenRules.fireWalkable(Rush.ON));
        assertFalse(FightOpenRules.fireWalkable(Rush.REDRINK));
        assertFalse(FightOpenRules.fireWalkable(Rush.OFF));
    }

    @Test
    public void aRodIsPickedUpAtOnceUnlessABlazeBitesOrARunHolds() {
        assertFalse(FightOpenRules.holdsPickup(false, 2.1));
        assertFalse(FightOpenRules.holdsPickup(false, Double.POSITIVE_INFINITY));
        assertTrue(FightOpenRules.holdsPickup(false, 2.0));
        assertTrue(FightOpenRules.holdsPickup(false, 0.8));
        assertTrue(FightOpenRules.holdsPickup(true, 30));
    }

    @Test
    public void aBitingBlazeIsFoughtFirst() {
        assertTrue(FightOpenRules.biterFirst(4, 2, 2.5));
        assertTrue(FightOpenRules.biterFirst(9, 2.5, 2.5));
        // the target bites itself, or the other one does not
        assertFalse(FightOpenRules.biterFirst(2, 1, 2.5));
        assertFalse(FightOpenRules.biterFirst(4, 3, 2.5));
    }

    @Test
    public void underTheRushABlazesSightIsNoThreat() {
        assertTrue(FightOpenRules.sightIsThreat(false, true));
        assertFalse(FightOpenRules.sightIsThreat(false, false));
        assertFalse(FightOpenRules.sightIsThreat(true, true));
    }

    // a stone floor at y 63, the spawner at 0 64 0, open above; set holds other cells
    private static Cells room(Map<Long, Cell> set) {
        return (x, y, z) -> {
            Cell c = set.get(BlazeCover.key(x, y, z));
            if (c != null) return c;
            if (x == 0 && y == 64 && z == 0) return Cell.SOLID;
            return y <= 63 ? Cell.SOLID : Cell.OPEN;
        };
    }

    @Test
    public void theSpawnersTopFirstThenBesideItNearestFirst() {
        List<int[]> stands = FightOpenRules.stands(room(new HashMap<>()), 0, 64, 0);
        assertEquals(FightOpenRules.STAND_MAX, stands.size());
        assertEquals(List.of(0, 65, 0), List.of(stands.get(0)[0], stands.get(0)[1], stands.get(0)[2]));
        // the four beside it on the floor next, then the corners
        for (int i = 1; i <= 4; i++) {
            int[] c = stands.get(i);
            assertEquals(64, c[1]);
            assertEquals(1, Math.abs(c[0]) + Math.abs(c[2]));
        }
        for (int i = 5; i < stands.size(); i++) {
            int[] c = stands.get(i);
            assertEquals(2, Math.abs(c[0]) + Math.abs(c[2]));
        }
    }

    @Test
    public void aStandByLavaIsNone() {
        // lava east of the spawner, two out: the cells east of it are too near the lava
        Map<Long, Cell> set = new HashMap<>();
        for (int z = -3; z <= 3; z++) set.put(BlazeCover.key(2, 63, z), Cell.LAVA);
        List<int[]> stands = FightOpenRules.stands(room(set), 0, 64, 0);
        for (int[] c : stands) assertTrue("not by the lava: " + c[0] + " " + c[1] + " " + c[2], c[0] < 1);
    }

    @Test
    public void noStandFitsTheTopAllTheSame() {
        Cells sea = (x, y, z) -> x == 0 && y == 64 && z == 0 ? Cell.SOLID : y <= 63 ? Cell.LAVA : Cell.OPEN;
        List<int[]> stands = FightOpenRules.stands(sea, 0, 64, 0);
        assertEquals(1, stands.size());
        assertEquals(List.of(0, 65, 0), List.of(stands.get(0)[0], stands.get(0)[1], stands.get(0)[2]));
    }

    // ---- fr_hold

    @Test
    public void theSwitchOffHoldsNothing() {
        assertFalse(FightOpenRules.holdPotion(false, false, false, false, false, true, 18, 8));
    }

    @Test
    public void anyPotionWaitsForTheBlazesOnTheWay() {
        // merely on fire on the way: held
        assertTrue(FightOpenRules.holdPotion(true, false, false, false, false, true, 18, 8));
        // a blaze in drinking range, by the spawner, asked for by a task, in lava, or on fire at the hold's health: down it goes
        assertFalse(FightOpenRules.holdPotion(true, true, false, false, false, true, 18, 8));
        assertFalse(FightOpenRules.holdPotion(true, false, true, false, false, true, 18, 8));
        assertFalse(FightOpenRules.holdPotion(true, false, false, true, false, true, 18, 8));
        assertFalse(FightOpenRules.holdPotion(true, false, false, false, true, true, 18, 8));
        assertFalse(FightOpenRules.holdPotion(true, false, false, false, false, true, 8, 8));
        assertTrue(FightOpenRules.holdPotion(true, false, false, false, false, true, 8.5, 8));
    }
}
