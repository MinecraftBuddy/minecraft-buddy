package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.SafeStand.Push;
import adris.altoclef.util.helpers.StandModel.Buddy;
import adris.altoclef.util.helpers.StandModel.Choice;
import adris.altoclef.util.helpers.StandModel.Cost;
import adris.altoclef.util.helpers.StandModel.Mob;
import adris.altoclef.util.helpers.StandModel.Query;
import adris.altoclef.util.helpers.StandModel.Target;
import adris.altoclef.util.helpers.StandModel.View;
import baritone.pathing.movement.CliffAvoidance.Cell;
import baritone.pathing.movement.CliffAvoidance.Cells;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

// one model of where to stand, on built cell worlds: the cases the old rules were made for, each from its death
public class StandModelTest {

    // a floor of stone at y 63 out to 30 each way (stone past it), open above; blocks set where set says
    static final class Room implements Cells {
        final Map<Long, Cell> set = new HashMap<>();

        @Override
        public Cell at(int x, int y, int z) {
            Cell c = set.get(BlazeCover.key(x, y, z));
            if (c != null) return c;
            if (Math.abs(x) > 30 || Math.abs(z) > 30) return Cell.SOLID;
            return y <= 63 ? Cell.SOLID : Cell.OPEN;
        }

        // a box of one cell kind, corners included
        Room fill(int x0, int y0, int z0, int x1, int y1, int z1, Cell cell) {
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) set.put(BlazeCover.key(x, y, z), cell);
                }
            }
            return this;
        }

        // a wall three high on the floor
        Room wall(int x0, int z0, int x1, int z1) {
            return fill(x0, 64, z0, x1, 66, z1, Cell.SOLID);
        }
    }

    private static Buddy at(double x, double y, double z) {
        return new Buddy(x, y, z, false);
    }

    private static Mob mob(String kind, double x, double y, double z) {
        return new Mob(kind, 1, x, y, z, true);
    }

    private static Choice pick(Cells cells, Buddy b, List<Mob> mobs) {
        return StandModel.choose(View.of(cells), b, mobs, null, Query.of(), null);
    }

    private static boolean behind(Cost c, int maxX) {
        return c.x() <= maxX;
    }

    @Test
    public void aBlazeInTheOpenIsHiddenFromBehindAWall() {
        // the blaze 10 east, a wall three high and five wide 3 west: the cells behind it are out of its sight
        Room room = new Room().wall(-3, -2, -3, 2);
        Choice c = pick(room, at(0.5, 64, 0.5), List.of(mob("blaze", 10.5, 66, 0.5)));
        assertNotNull(c.cell());
        assertTrue("behind the wall: " + c.cell(), behind(c.cell(), -4));
        assertEquals(0, c.cell().rate(), 1e-9);
        assertTrue(c.changed());
    }

    @Test
    public void aWitherSkeletonByTheCornerKeepsTheBuddyOutOfIt() {
        // the same wall, and a wither skeleton behind it: the cells that hide from the blaze are in its reach at once
        // (attempt 214's kind: the corner that hides from blazes is no cover from what walks)
        Room room = new Room().wall(-3, -2, -3, 2);
        Choice c = pick(room, at(0.5, 64, 0.5), List.of(mob("blaze", 10.5, 66, 0.5), mob("wither_skeleton", -4.5, 64, 0.5)));
        assertNotNull(c.cell());
        assertFalse("not behind the wall: " + c.cell(), behind(c.cell(), -4));
        assertTrue("away from the wither skeleton: " + c.cell(), Math.hypot(c.cell().x() + 5, c.cell().z()) > 4);
    }

    @Test
    public void aRoofTwoHighKeepsTheWitherSkeletonOut() {
        // a pocket one wide, two high and three deep west of us, its mouth south, roofed: the wither skeleton (2.4 high) cannot
        // walk in, its reach from the mouth does not get past the first cell, and the blaze east cannot see in
        Room room = new Room()
                .fill(-4, 64, -4, -4, 66, -1, Cell.SOLID)
                .fill(-2, 64, -4, -2, 66, -1, Cell.SOLID)
                .fill(-3, 66, -4, -3, 66, -1, Cell.SOLID)
                .fill(-3, 64, -4, -3, 65, -4, Cell.SOLID);
        Choice c = pick(room, at(0.5, 64, 0.5), List.of(mob("blaze", 10.5, 66, 0.5), mob("wither_skeleton", 3.5, 64, 4.5)));
        assertNotNull(c.cell());
        assertEquals(-3, c.cell().x());
        assertTrue("past the first cell: " + c.cell(), c.cell().z() <= -2);
        assertEquals(0, c.cell().rate(), 1e-9);
    }

    @Test
    public void lavaBehindASkeletonInFrontIsNoPlaceToStand() {
        // lava from x -3 west at the feet's level, a skeleton 8 east: its arrows knock us west, so no cell within a knock of
        // the lava is picked, where we stand now included
        Room room = new Room().fill(-30, 64, -30, -3, 64, 30, Cell.LAVA);
        Choice c = pick(room, at(0.5, 64, 0.5), List.of(mob("skeleton", 8.5, 64, 0.5)));
        assertNotNull(c.cell());
        assertTrue("moved: " + c.cell(), c.changed());
        Cost cell = c.cell();
        assertFalse(SafeStand.unsafe(room, cell.x(), cell.y(), cell.z(), List.of(Push.away(8.5, 0.5, SafeStand.HIT))));
        assertFalse(SafeStand.unsafe(room, cell.x(), cell.y(), cell.z()));
        assertEquals(0, cell.risk(), 1e-9);
    }

    @Test
    public void aGhastIsHiddenFromBehindCobblestone() {
        // a ghast 24 off and 6 up; a wall three high between it and the cells south of the wall
        Room room = new Room().wall(-2, 3, 2, 3);
        Choice c = pick(room, at(0.5, 64, 5.5), List.of(mob("ghast", 0.5, 70, 30.5)));
        assertNotNull(c.cell());
        assertTrue("south of the wall: " + c.cell(), c.cell().z() < 3);
        assertEquals(0, c.cell().rate(), 1e-9);
    }

    // the floor ends at z -6 north, over a drop of 23 onto lava (the lava sea's rim)
    private static Cells rim() {
        Room room = new Room();
        return (x, y, z) -> {
            if (z > -7 || Math.abs(x) > 30 || z < -30) return room.at(x, y, z);
            if (y > 40) return Cell.OPEN;
            return y == 40 ? Cell.LAVA : Cell.SOLID;
        };
    }

    @Test
    public void aGhastsPushKeepsThePickFourAndAHalfFromTheRim() {
        // no cover anywhere; a ghast 29 south sees every cell, and its ball carries us 4.5 north: the cells nearer the rim than
        // that are a death, the first one past it is picked
        Cells world = rim();
        Choice c = pick(world, at(0.5, 64, -3.5), List.of(mob("ghast", 0.5, 66, 25.5)));
        assertNotNull(c.cell());
        assertTrue("4.5 from the rim: " + c.cell(), c.cell().z() >= -2);
        assertEquals(0, c.cell().risk(), 1e-9);
        // (where we stand, 2.5 in, the push is the cost)
        Choice stay = StandModel.choose(View.of(world), at(0.5, 64, -3.5), List.of(mob("ghast", 0.5, 66, 25.5)), null,
                Query.of().radius(0), null);
        assertNotNull(stay.cell());
        assertTrue("the rim's risk: " + stay.cell().breakdown(), stay.cell().risk() >= StandModel.DISASTER * 0.3);
    }

    @Test
    public void aSlightlyBetterCellDoesNotFlipTheChoice() {
        // a blaze 12 south; two walls north, one either side: the west cover is a step nearer than the east one
        Room both = new Room().wall(-4, -3, -2, -3).wall(3, -3, 5, -3);
        Room east = new Room().wall(3, -3, 5, -3);
        List<Mob> blaze = List.of(mob("blaze", 0.5, 66, 12.5));
        Buddy b = at(0.5, 64, 0.5);
        Choice west = pick(both, b, blaze);
        assertTrue("west first: " + west.cell(), west.cell().x() < 0);
        Cost held = pick(east, b, blaze).cell();
        assertTrue("east alone: " + held, held.x() > 0);
        Choice kept = StandModel.choose(View.of(both), b, blaze, null, Query.of(), held.cell());
        assertTrue(held.at(kept.cell().cell()));
        assertFalse(kept.changed());
        assertTrue("the west one is cheaper, a little", west.cell().total() < kept.cell().total());
        // a held cell the blaze sees gives way at once
        Choice moved = StandModel.choose(View.of(both), b, blaze, null, Query.of(), new int[]{0, 64, 0});
        assertTrue(moved.changed());
        assertTrue(moved.cell().x() < 0);
    }

    @Test
    public void theTargetInReachIsWorthAStep() {
        // a blaze that has not noticed us 7 east: in a fight the pick is a cell from which the sword reaches it
        Room room = new Room();
        Mob blaze = new Mob("blaze", 1, 3.5, 65, 0.5, false);
        Target target = new Target(3.5, 65, 0.5, 0.6, 1.8);
        Choice c = StandModel.choose(View.of(room), at(-3.5, 64, 0.5), List.of(blaze), target, Query.of(), null);
        assertNotNull(c.cell());
        assertTrue("in reach: " + c.cell().breakdown(), c.cell().hit() > 0);
        Choice run = StandModel.choose(View.of(room), at(-3.5, 64, 0.5), List.of(blaze), target, Query.of().hitBack(0), null);
        assertEquals(0, run.cell().hit(), 1e-9);
    }

    @Test
    public void aRunTakesNoCellThatStillHurts() {
        // in the open between two blazes: every cell takes more than a run's limit, so none (the caller's own run then)
        Room room = new Room();
        Choice c = StandModel.choose(View.of(room), at(0.5, 64, 0.5), List.of(mob("blaze", 6.5, 66, 0.5), mob("blaze", -5.5, 66, 0.5)), null,
                Query.of().maxRate(StandModel.RUN_RATE), null);
        assertNull(c.cell());
        assertNotNull(c.why());
    }

    @Test
    public void fireIsNoCellAndNeutralsCountOnlyAngry() {
        Room room = new Room();
        StandModel.Fire fire = (x, y, z) -> x == 0 && y == 64 && z == 0;
        Choice c = StandModel.choose(new View(room, fire), at(0.5, 64, 0.5), List.of(), null, Query.of(), null);
        assertNotNull(c.cell());
        assertFalse(c.cell().x() == 0 && c.cell().z() == 0);
        // a calm zombified piglin beside us is no danger, an angry one is
        Choice calm = pick(room, at(0.5, 64, 0.5), List.of(new Mob("zombified_piglin", 1, 1.5, 64, 0.5, false)));
        assertEquals(0, calm.cell().rate(), 1e-9);
        Choice angry = StandModel.choose(View.of(room), at(0.5, 64, 0.5), List.of(new Mob("zombified_piglin", 1, 1.5, 64, 0.5, true)),
                null, Query.of().radius(0), null);
        assertTrue(angry.cell().rate() > 1);
    }

    @Test
    public void theSamePictureGivesTheSamePick() {
        Cells world = fortress();
        List<Mob> mobs = fortressMobs();
        Choice a = StandModel.choose(View.of(world), at(0.5, 64, 0.5), mobs, null, Query.of(), null);
        Choice b = StandModel.choose(View.of(world), at(0.5, 64, 0.5), mobs, null, Query.of(), null);
        assertNotNull(a.cell());
        assertEquals(a.cell(), b.cell());
    }

    // a blaze room as the drills have it: a platform 13 across at y 64 over a lava sea at y 40, fenced but for a bridge's mouth,
    // a corridor one wide north under a roof, the spawner raised in the middle
    static Cells fortress() {
        Room room = new Room();
        room.fill(-6, 64, -6, 6, 64, -6, Cell.SOLID).fill(-6, 64, 6, 6, 64, 6, Cell.SOLID)
                .fill(-6, 64, -6, -6, 64, 6, Cell.SOLID).fill(6, 64, -6, 6, 64, 6, Cell.SOLID)
                .fill(-1, 64, 6, 1, 64, 6, Cell.OPEN)
                .fill(-1, 64, -1, 1, 64, 1, Cell.SOLID)
                .fill(-1, 64, -12, -1, 67, -7, Cell.SOLID).fill(1, 64, -12, 1, 67, -7, Cell.SOLID)
                .fill(-1, 66, -12, 1, 66, -7, Cell.SOLID).fill(0, 64, -6, 0, 65, -6, Cell.OPEN);
        return (x, y, z) -> {
            boolean platform = Math.abs(x) <= 6 && Math.abs(z) <= 6;
            boolean corridor = Math.abs(x) <= 1 && z <= -7 && z >= -12;
            boolean bridge = Math.abs(x) <= 1 && z > 6 && z <= 20;
            if (platform || corridor || bridge) return room.at(x, y, z);
            if (y > 40) return Cell.OPEN;
            return y == 40 ? Cell.LAVA : Cell.SOLID;
        };
    }

    static List<Mob> fortressMobs() {
        return List.of(mob("blaze", 4.5, 67, 3.5), mob("blaze", -3.5, 68, 4.5), mob("blaze", 0.5, 66, 0.5), mob("blaze", 9.5, 70, -2.5),
                mob("wither_skeleton", 4.5, 64, -4.5), mob("wither_skeleton", 0.5, 64, 14.5), new Mob("magma_cube", 4, -4.5, 64, -3.5, true),
                mob("ghast", 20.5, 70, 30.5));
    }

    @Test
    public void anEvaluationIsCheap() {
        // the blaze room with four blazes, two wither skeletons, a big magma cube and a ghast, the buddy on the platform
        Cells world = fortress();
        List<Mob> mobs = fortressMobs();
        Buddy b = at(3.5, 64, 2.5);
        for (Query q : List.of(Query.of(), Query.of().radius(8), Query.of().radius(10))) {
            Choice c = null;
            for (int i = 0; i < 100; i++) c = StandModel.choose(View.of(world), b, mobs, null, q, null);
            int runs = 300;
            long[] took = new long[runs];
            long t0 = System.nanoTime();
            for (int i = 0; i < runs; i++) {
                long s = System.nanoTime();
                c = StandModel.choose(View.of(world), b, mobs, null, q, c.cell() == null ? null : c.cell().cell());
                took[i] = System.nanoTime() - s;
            }
            double mean = (System.nanoTime() - t0) / 1e3 / runs;
            java.util.Arrays.sort(took);
            System.out.printf("StandModel radius %d: %d cells, %d world reads, %d rays; %.0f us an evaluation on average, median %.0f, "
                            + "95th %.0f; picked %s%n", q.radius(), c.looked(), c.reads(), c.rays(), mean, took[runs / 2] / 1e3,
                    took[runs * 95 / 100] / 1e3, c.cell() == null ? "none (" + c.why() + ")" : c.cell().x() + " " + c.cell().y() + " "
                            + c.cell().z() + ": " + c.cell().breakdown());
            assertTrue("an evaluation took " + mean + " us", mean < 5000);
        }
    }
}
