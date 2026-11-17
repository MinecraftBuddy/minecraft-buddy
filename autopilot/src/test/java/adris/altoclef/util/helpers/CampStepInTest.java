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
