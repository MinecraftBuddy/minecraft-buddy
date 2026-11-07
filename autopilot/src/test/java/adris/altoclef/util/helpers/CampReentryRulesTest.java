package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import baritone.pathing.movement.CliffAvoidance.Cell;
import baritone.pathing.movement.CliffAvoidance.Cells;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

// camp_reentry's rules (CampReentryRules): when the walk back into the blaze camp counts as shut out, and what takes the
// camp's place
public class CampReentryRulesTest {
    private static final BlockPos CORNER = new BlockPos(100, 64, -40);

    @Test
    public void threeFailedSearchesOfOneWalkAreShutOut() {
        CampReentryRules.Watch w = new CampReentryRules.Watch();
        w.camp(CORNER, false);
        // (the searches that failed before the walk began are not its own)
        w.walk(1000, 7, true);
        assertNull(w.shutOut(1000));
        w.walk(1001, 7, true);
        w.walk(1002, 8, true);
        w.walk(1003, 9, true);
        assertNull(w.shutOut(1003));
        w.walk(1004, 9, true);
        w.walk(1005, 10, true);
        assertEquals("no path x3", w.shutOut(1005));
        // (said once per walk)
        w.walk(1006, 11, true);
        assertNull(w.shutOut(1006));
    }

    @Test
    public void aFightInBetweenKeepsTheWalkButNotItsFailures() {
        CampReentryRules.Watch w = new CampReentryRules.Watch();
        w.camp(CORNER, false);
        w.walk(1000, 0, true);
        w.walk(1001, 1, true);
        w.walk(1002, 2, true);
        // (a chase of four seconds, its own searches failing: not the walk's, but the walk goes on)
        w.walk(1080, 6, true);
        assertNull(w.shutOut(1080));
        w.walk(1081, 7, true);
        assertEquals("no path x3", w.shutOut(1081));
        // (its clock went on through the chase: 20 s from the first ask)
        CampReentryRules.Watch v = new CampReentryRules.Watch();
        v.camp(CORNER, false);
        for (long t = 1000; t < 1000 + CampReentryRules.WALK_TICKS; t += 80) v.walk(t, 0, true);
        v.walk(1000 + CampReentryRules.WALK_TICKS, 0, true);
        assertEquals("not reached in 20 s", v.shutOut(1000 + CampReentryRules.WALK_TICKS));
    }

    @Test
    public void aPauseIsANewWalk() {
        CampReentryRules.Watch v = new CampReentryRules.Watch();
        v.camp(CORNER, false);
        v.walk(1000, 0, true);
        v.walk(1001, 2, true);
        // (a pause past GAP_TICKS: a new walk, the count starts afresh)
        v.walk(1002 + CampReentryRules.GAP_TICKS, 5, true);
        v.walk(1003 + CampReentryRules.GAP_TICKS, 6, true);
        assertNull(v.shutOut(1003 + CampReentryRules.GAP_TICKS));
        // (and so does its clock)
        v.walk(1001 + CampReentryRules.GAP_TICKS + CampReentryRules.WALK_TICKS / 2, 6, true);
        assertNull(v.shutOut(1001 + CampReentryRules.GAP_TICKS + CampReentryRules.WALK_TICKS / 2));
    }

    @Test
    public void twentySecondsOfWalkingBackAreShutOut() {
        CampReentryRules.Watch w = new CampReentryRules.Watch();
        w.camp(CORNER, false);
        long t = 5000;
        for (; t < 5000 + CampReentryRules.WALK_TICKS; t += 20) {
            w.walk(t, 0, true);
            assertNull(w.shutOut(t));
        }
        w.walk(t, 0, true);
        assertEquals("not reached in 20 s", w.shutOut(t));
    }

    @Test
    public void inTheCampANewCampOrFarOffStartsAfresh() {
        CampReentryRules.Watch w = new CampReentryRules.Watch();
        w.camp(CORNER, false);
        w.walk(1000, 0, true);
        w.walk(1001, 2, true);
        // (in the camp: the walk is over)
        w.camp(CORNER, true);
        w.walk(1002, 3, true);
        w.camp(CORNER, false);
        w.walk(1003, 4, true);
        w.walk(1004, 5, true);
        assertNull(w.shutOut(1004));
        // (a new camp)
        w.camp(CORNER.east(5), false);
        w.walk(1005, 7, true);
        w.walk(1006, 8, true);
        assertNull(w.shutOut(1006));
        // (farther than NEAR: the way there, not back)
        w.walk(1007, 9, false);
        w.walk(1008, 10, true);
        w.walk(1009, 11, true);
        assertNull(w.shutOut(1009));
        // (no camp at all: nothing counts)
        w.camp(null, false);
        w.walk(1010, 20, true);
        assertNull(w.shutOut(1010));
    }

    @Test
    public void noSayOnceTheWalkStopped() {
        CampReentryRules.Watch w = new CampReentryRules.Watch();
        w.camp(CORNER, false);
        w.walk(1000, 0, true);
        w.walk(1001, 3, true);
        // (the walk is no longer asked for: the next ask decides)
        assertNull(w.shutOut(1002 + CampReentryRules.GAP_TICKS));
    }

    @Test
    public void aPillboxFirstThenAnotherCornerThenNone() {
        assertEquals(CampReentryRules.Choice.PILLBOX, CampReentryRules.choose(true, true));
        assertEquals(CampReentryRules.Choice.PILLBOX, CampReentryRules.choose(true, false));
        assertEquals(CampReentryRules.Choice.CORNER, CampReentryRules.choose(false, true));
        assertEquals(CampReentryRules.Choice.NONE, CampReentryRules.choose(false, false));
    }

    // a floor of solid at y 63 out to 30 each way, open above it, the spawner a solid block at (0, 64, 0); walls where walls says
    private static Cells floor(Set<Long> walls) {
        return (x, y, z) -> {
            if (Math.abs(x) > 30 || Math.abs(z) > 30) return Cell.SOLID;
            if (x == 0 && z == 0 && y == 64) return Cell.SOLID;
            if (walls.contains(BlazeCover.key(x, y, z))) return Cell.SOLID;
            return y <= 63 ? Cell.SOLID : Cell.OPEN;
        };
    }

    private static final List<Vec3> EYES = BlazeCover.spawnEyes(0, 64, 0);

    @Test
    public void aPillboxRoundTheCellItStandsOn() {
        Cells cells = floor(Set.of());
        BlazePillbox.Plan p = CampReentryRules.pillboxHere(cells, 7, 64, 0, 0, 64, 0, EYES, true, 64);
        assertNotNull(p);
        assertEquals(7, p.cell()[0]);
        assertEquals(64, p.cell()[1]);
        assertEquals(0, p.cell()[2]);
        Cells after = BlazePillbox.after(cells, p.blocks());
        assertTrue(BlazePillbox.hidden(after, EYES, 7, 64, 0));
        assertFalse(BlazePillbox.hidden(after, EYES, p.lookout()[0], p.lookout()[1], p.lookout()[2]));
        // (the bent box where the bag holds it, its back cell where the feet are)
        assertNotNull(p.front());
        // (the one-cell box when the bag holds no bent one)
        BlazePillbox.Plan small = CampReentryRules.pillboxHere(cells, 7, 64, 0, 0, 64, 0, EYES, true, BlazePillbox.MOST);
        assertNotNull(small);
        assertTrue("blocks " + small.blocks().size(), small.blocks().size() <= BlazePillbox.MOST);
        assertEquals(7, small.cell()[0]);
        BlazePillbox.Plan oneCell = CampReentryRules.pillboxHere(cells, 7, 64, 0, 0, 64, 0, EYES, false, 64);
        assertNotNull(oneCell);
        assertNull(oneCell.front());
    }

    @Test
    public void noPillboxWithoutTheBlocksTooCloseOrByAnEdge() {
        Cells cells = floor(Set.of());
        assertNull(CampReentryRules.pillboxHere(cells, 7, 64, 0, 0, 64, 0, EYES, true, 2));
        // (closer to the spawner than CELL_MIN)
        assertNull(CampReentryRules.pillboxHere(cells, 2, 64, 0, 0, 64, 0, EYES, true, 64));
        // (on a wall's top, a drop beside it: the builder would stand on the rim)
        Set<Long> wall = new HashSet<>();
        for (int z = -6; z <= 6; z++) {
            for (int y = 64; y <= 66; y++) wall.add(BlazeCover.key(7, y, z));
        }
        assertNull(CampReentryRules.pillboxHere(floor(wall), 7, 67, 0, 0, 64, 0, EYES, true, 64));
    }

    @Test
    public void anotherCornerOnlyOneAWalkReachesAndNotOneShutOutOf() {
        // a pocket at (8, 64, 8) walled in on every side: no walk from (6, 64, 0) gets in
        Set<Long> walls = new HashSet<>();
        for (int y = 64; y <= 65; y++) {
            walls.add(BlazeCover.key(9, y, 8));
            walls.add(BlazeCover.key(7, y, 8));
            walls.add(BlazeCover.key(8, y, 9));
            walls.add(BlazeCover.key(8, y, 7));
        }
        walls.add(BlazeCover.key(8, 66, 8));
        Cells cells = floor(walls);
        Set<Long> reach = CampReentryRules.reachable(cells, 6, 64, 0, CampReentryRules.REACH_STEPS);
        assertTrue(reach.contains(BlazeCover.key(6, 64, 0)));
        assertTrue(reach.contains(BlazeCover.key(6, 64, 5)));
        assertFalse(reach.contains(BlazeCover.key(8, 64, 8)));
        Set<Long> shut = Set.of(BlazeCover.key(6, 64, 5));
        assertFalse(CampReentryRules.cornerAllowed(8, 64, 8, Set.of(), reach));
        assertFalse(CampReentryRules.cornerAllowed(6, 64, 5, shut, reach));
        assertTrue(CampReentryRules.cornerAllowed(6, 64, 4, shut, reach));
        assertTrue(CampReentryRules.cornerAllowed(8, 64, 8, Set.of(), null));
    }
}
