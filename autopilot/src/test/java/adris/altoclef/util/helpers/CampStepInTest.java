package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import baritone.pathing.movement.CliffAvoidance.Cell;
import baritone.pathing.movement.CliffAvoidance.Cells;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

// the fire-proof camp's step in by the spawner (camp_step_in): CampStepIn
public class CampStepInTest {

    // a long potion just drunk: eight minutes
    private static final int LONG = 9600;

    @Test
    public void dueAfterThreeDryMinutesAtTheOpenSide() {
        assertEquals(3600, CampStepIn.DRY_TICKS);
        assertFalse(CampStepIn.due(true, true, true, false, LONG, CampStepIn.DRY_TICKS - 1));
        assertTrue(CampStepIn.due(true, true, true, false, LONG, CampStepIn.DRY_TICKS));
        // the stall read: fifteen minutes there
        assertTrue(CampStepIn.due(true, true, true, false, LONG, 18000));
    }

    @Test
    public void notDueOtherwise() {
        // the switch off
        assertFalse(CampStepIn.due(false, true, true, false, LONG, 18000));
        // not the fire-proof camp at the open side (by the spawner already, or another camp)
        assertFalse(CampStepIn.due(true, false, true, false, LONG, 18000));
        // a blaze in reach this tick
        assertFalse(CampStepIn.due(true, true, false, false, LONG, 18000));
        // this camp stepped in already
        assertFalse(CampStepIn.due(true, true, true, true, LONG, 18000));
        // the effect at its last seconds, or gone
        assertFalse(CampStepIn.due(true, true, true, false, FightOpenRules.RUSH_MIN_TICKS, 18000));
        assertFalse(CampStepIn.due(true, true, true, false, 0, 18000));
        assertTrue(CampStepIn.due(true, true, true, false, Integer.MAX_VALUE, 18000));
    }

    // ---- potion_step_in

    // a traded potion's full length, and a brewed one's
    private static final int TRADED = 3600;
    private static final int BREWED = 9600;
    private static final int ENDLESS = Integer.MAX_VALUE;

    @Test
    public void aShortPotionStepsInAfterThirtySecondsWithAMinuteLeft() {
        assertEquals(600, CampStepIn.POTION_DRY_TICKS);
        assertEquals(1200, CampStepIn.POTION_IN_TICKS);
        // 60 s left and 30 s dry: in; the old clock (dryTicks) does not count
        assertTrue(CampStepIn.due(true, true, true, true, true, false, false, 1200, 0, 600));
        // 59.95 s left, or 29.95 s dry: not
        assertFalse(CampStepIn.due(true, true, true, true, true, false, false, 1199, 18000, 600));
        assertFalse(CampStepIn.due(true, true, true, true, true, false, false, 3000, 18000, 599));
        // the potion just drunk, after a minute with no rod
        assertTrue(CampStepIn.due(true, true, true, true, true, false, false, 2400, 0, 1200));
    }

    @Test
    public void aShortPotionStepInNotOtherwise() {
        // camp_step_in off, not the open side, a blaze in reach, stepped in already at this camp
        assertFalse(CampStepIn.due(false, true, true, true, true, false, false, 2400, 18000, 18000));
        assertFalse(CampStepIn.due(true, true, true, false, true, false, false, 2400, 18000, 18000));
        assertFalse(CampStepIn.due(true, true, true, true, false, false, false, 2400, 18000, 18000));
        assertFalse(CampStepIn.due(true, true, true, true, true, true, false, 2400, 18000, 18000));
        // the stand left under this potion: no step-in till a new one
        assertFalse(CampStepIn.due(true, true, true, true, true, false, true, 2400, 18000, 18000));
    }

    @Test
    public void switchOffOrNoShortPotionIsTheOldRule() {
        int[] effects = {0, 200, 201, 500, 1199, 1200, 2400, 3600, 9600, ENDLESS};
        long[] dries = {0, 599, 600, 3599, 3600, 18000};
        boolean[] tf = {false, true};
        for (int e : effects) {
            for (long d : dries) {
                for (long pd : dries) {
                    for (boolean stepped : tf) {
                        for (boolean barred : tf) {
                            boolean old = CampStepIn.due(true, true, true, stepped, e, d);
                            // the switch off, whatever the potion
                            assertEquals(old, CampStepIn.due(true, false, true, true, true, stepped, barred, e, d, pd));
                            assertEquals(old, CampStepIn.due(true, false, false, true, true, stepped, barred, e, d, pd));
                            // on, but an endless effect or a brewed potion: the old rule, its three minutes
                            assertEquals(old, CampStepIn.due(true, true, false, true, true, stepped, barred, e, d, pd));
                        }
                    }
                }
            }
        }
        // the last three minutes of a brewed 8:00 potion (phase 6) are no short potion's: three dry minutes still
        assertFalse(CampStepIn.shortPotion(2400, BREWED));
        assertFalse(CampStepIn.due(true, true, CampStepIn.shortPotion(2400, BREWED), true, true, false, false, 2400, 600, 18000));
        assertTrue(CampStepIn.due(true, true, CampStepIn.shortPotion(2400, BREWED), true, true, false, false, 2400, 3600, 0));
    }

    @Test
    public void aShortPotionIsFiniteAndThreeMinutesOrLessInAll() {
        assertTrue(CampStepIn.shortPotion(2400, TRADED));
        assertTrue(CampStepIn.shortPotion(1, TRADED));
        // a splash potion thrown at the feet, a little less
        assertTrue(CampStepIn.shortPotion(2000, 3100));
        assertFalse(CampStepIn.shortPotion(0, TRADED));
        assertFalse(CampStepIn.shortPotion(ENDLESS, ENDLESS));
        assertFalse(CampStepIn.shortPotion(ENDLESS, TRADED));
        assertFalse(CampStepIn.shortPotion(2400, BREWED));
        // on before the first look: of no known length
        assertFalse(CampStepIn.shortPotion(2400, CampStepIn.fullTicks(-1, 2400, 0)));
    }

    @Test
    public void theFullLengthIsTheNewPotionsTicks() {
        // no look yet: none, or one of no known length
        assertEquals(0, CampStepIn.fullTicks(-1, 0, 0));
        assertEquals(Integer.MAX_VALUE, CampStepIn.fullTicks(-1, 2400, 0));
        assertFalse(CampStepIn.newPotion(-1, 2400));
        // drunk from none
        assertTrue(CampStepIn.newPotion(0, TRADED));
        assertEquals(TRADED, CampStepIn.fullTicks(0, TRADED, 0));
        // ticking down: the same potion
        assertFalse(CampStepIn.newPotion(TRADED, TRADED - 1));
        assertEquals(TRADED, CampStepIn.fullTicks(TRADED, TRADED - 1, TRADED));
        // a sync a few ticks up: the same potion
        assertFalse(CampStepIn.newPotion(2400, 2400 + CampStepIn.NEW_POTION_RISE));
        assertEquals(BREWED, CampStepIn.fullTicks(2400, 2450, BREWED));
        // a traded potion drunk with a brewed one's last minute left, and the other way round
        assertTrue(CampStepIn.newPotion(1200, TRADED));
        assertEquals(TRADED, CampStepIn.fullTicks(1200, TRADED, BREWED));
        assertEquals(BREWED, CampStepIn.fullTicks(1200, BREWED, TRADED));
        // gone
        assertEquals(0, CampStepIn.fullTicks(5, 0, TRADED));
        assertFalse(CampStepIn.newPotion(5, 0));
        // endless: never a new potion by its ticks, never short
        assertFalse(CampStepIn.newPotion(ENDLESS, ENDLESS));
        assertEquals(ENDLESS, CampStepIn.fullTicks(0, ENDLESS, 0));
    }

    @Test
    public void outAtTwentyFiveSecondsWithNoPotionLeft() {
        assertEquals(500, CampStepIn.POTION_OUT_TICKS);
        assertTrue(CampStepIn.leaves(true, true, true, 500, 0));
        assertTrue(CampStepIn.leaves(true, true, true, 1, 0));
        // 25.05 s left: not yet
        assertFalse(CampStepIn.leaves(true, true, true, 501, 0));
        // a potion left to go down next
        assertFalse(CampStepIn.leaves(true, true, true, 500, 1));
        // not at a step-in stand
        assertFalse(CampStepIn.leaves(true, false, true, 500, 0));
        // an endless effect or a brewed potion
        assertFalse(CampStepIn.leaves(true, true, CampStepIn.shortPotion(ENDLESS, ENDLESS), ENDLESS, 0));
        assertFalse(CampStepIn.leaves(true, true, CampStepIn.shortPotion(400, BREWED), 400, 0));
        // the switch off: never
        assertFalse(CampStepIn.leaves(false, true, true, 500, 0));
        assertFalse(CampStepIn.leaves(false, true, true, 1, 0));
    }

    // a stone floor at y 60, the spawner at 0 63 0 on a pillar from the floor, open above; set holds other cells
    private static Cells room(Map<Long, Cell> set) {
        return (x, y, z) -> {
            Cell c = set.get(BlazeCover.key(x, y, z));
            if (c != null) return c;
            if (x == 0 && z == 0 && y >= 61 && y <= 63) return Cell.SOLID;
            return y <= 60 ? Cell.SOLID : Cell.OPEN;
        };
    }

    private static double dist(int[] c) {
        return Math.sqrt(c[0] * c[0] + (c[1] - 63) * (c[1] - 63) + c[2] * c[2]);
    }

    @Test
    public void standsNearTheSpawnerFirstNeverItsTop() {
        // the camp at the open side, six out and two down
        List<int[]> stands = CampStepIn.stands(room(new HashMap<>()), 0, 63, 0, 6, 61, 0);
        assertEquals(CampStepIn.STAND_MAX, stands.size());
        double last = 0;
        for (int[] c : stands) {
            // on the floor beside the pillar, never on the spawner
            assertEquals(61, c[1]);
            assertFalse(c[0] == 0 && c[2] == 0);
            assertTrue(Math.hypot(c[0], c[2]) <= CampStepIn.FLAT_MAX);
            assertTrue(dist(c) >= last);
            last = dist(c);
        }
        // the four beside the pillar first
        for (int i = 0; i < 4; i++) assertEquals(1, Math.abs(stands.get(i)[0]) + Math.abs(stands.get(i)[2]));
    }

    @Test
    public void aStandIsTwoNearerThanTheCamp() {
        // the camp 4.5 from the spawner (four out, two down): stands within 2.5 of it, the eight round the pillar's foot
        List<int[]> stands = CampStepIn.stands(room(new HashMap<>()), 0, 63, 0, 4, 61, 0);
        assertEquals(8, stands.size());
        for (int[] c : stands) assertTrue(dist(c) <= Math.sqrt(20) - CampStepIn.STEP_GAIN + 1e-9);
        // a camp 2.8 from it already: none is two nearer
        assertTrue(CampStepIn.stands(room(new HashMap<>()), 0, 63, 0, 2, 61, 0).isEmpty());
    }

    @Test
    public void aStandByLavaOrAnEdgeIsNone() {
        // lava along the east, two out from the pillar
        Map<Long, Cell> set = new HashMap<>();
        for (int z = -5; z <= 5; z++) set.put(BlazeCover.key(2, 60, z), Cell.LAVA);
        List<int[]> stands = CampStepIn.stands(room(set), 0, 63, 0, -6, 61, 0);
        assertFalse(stands.isEmpty());
        for (int[] c : stands) assertTrue("not by the lava: " + c[0] + " " + c[1] + " " + c[2], c[0] < 1 || c[0] > 3);
    }

    @Test
    public void noFloorNoStand() {
        // the spawner over the lava sea: nothing to stand on beside it
        Cells sea = (x, y, z) -> x == 0 && z == 0 && y >= 61 && y <= 63 ? Cell.SOLID : y <= 60 ? Cell.LAVA : Cell.OPEN;
        assertTrue(CampStepIn.stands(sea, 0, 63, 0, 6, 61, 0).isEmpty());
    }
}
