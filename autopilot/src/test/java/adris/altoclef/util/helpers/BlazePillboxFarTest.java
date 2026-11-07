package adris.altoclef.util.helpers;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import baritone.pathing.movement.CliffAvoidance.Cell;
import baritone.pathing.movement.CliffAvoidance.Cells;
import java.util.Arrays;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

// the far box (BlazePillbox.planFar, Tuning far_box): a made-up fortress with the spawner at (0, 64, 0) in a room fifteen
// across, walled five high, a door three wide in its east wall and a corridor seven wide from it to the east, forty long.
// the floor is the fortress's nether bricks west of x 26, netherrack from there on
public class BlazePillboxFarTest {

    static final Cells FORT = (x, y, z) -> {
        if (Math.abs(x) > 50 || Math.abs(z) > 30 || y <= 40 || y >= 90) return Cell.SOLID;
        if (y <= 63) return Cell.SOLID;
        if (x == 0 && y == 64 && z == 0) return Cell.SOLID;
        if (y >= 69) return Cell.OPEN;
        boolean room = Math.abs(x) <= 7 && Math.abs(z) <= 7;
        boolean door = x == 8 && Math.abs(z) <= 1;
        boolean corridor = x >= 9 && x <= 44 && Math.abs(z) <= 3;
        return room || door || corridor ? Cell.OPEN : Cell.SOLID;
    };

    // the same with a screen of bricks in the room before the door, seven high: the corridor has no line to the spawn area,
    // the walk goes round it
    static final Cells SCREENED = (x, y, z) -> {
        if ((x == 5 || x == 6) && Math.abs(z) <= 2 && y >= 64 && y <= 70) return Cell.SOLID;
        return FORT.at(x, y, z);
    };

    static final BlazePillbox.Floor BRICKS = (x, y, z) -> x < 26;
    static final int[] START = {1, 64, 1};

    static List<Vec3> eyes(Cells cells) {
        return BlazeCover.spawnEyes(0, 64, 0).stream()
                .filter(e -> cells.at((int) Math.floor(e.x), (int) Math.floor(e.y), (int) Math.floor(e.z)) == Cell.OPEN).toList();
    }

    static String show(BlazePillbox.Plan p) {
        StringBuilder sb = new StringBuilder("cell " + Arrays.toString(p.cell()) + " front " + Arrays.toString(p.front()) + " look out "
                + Arrays.toString(p.lookout()) + " blocks " + p.blocks().size() + ":");
        for (int[] b : p.blocks()) sb.append(' ').append(Arrays.toString(b));
        return sb.toString();
    }

    // the switch off: the camp's planner is planBent, untouched. its plan on this fortress as it was before the far box came
    // in: the room's south-east corner, nine out, inside the spawner's range
    @Test
    public void theSwitchOffKeepsTheBentPlan() {
        BlazePillbox.Plan bent = BlazePillbox.planBent(FORT, START, 0, 64, 0, eyes(FORT));
        assertNotNull(bent);
        assertArrayEquals(new int[]{7, 64, 5}, bent.cell());
        assertArrayEquals(new int[]{7, 64, 6}, bent.front());
        assertArrayEquals(new int[]{6, 64, 6}, bent.lookout());
        int[][] blocks = {{6, 64, 5}, {7, 64, 4}, {6, 64, 7}, {7, 64, 7}, {6, 65, 5}, {7, 65, 4}, {6, 65, 7}, {7, 65, 7}, {7, 66, 4}, {7, 66, 5}, {7, 66, 6}};
        assertEquals(blocks.length, bent.blocks().size());
        for (int i = 0; i < blocks.length; i++) assertArrayEquals("block " + i, blocks[i], bent.blocks().get(i));
        assertNull(bent.stands());
        assertEquals(0, bent.raise());
        assertTrue(BlazePillbox.spawnerDistance(7, 64, 5, 0, 64, 0) < BlazePillbox.SPAWN_RANGE);
    }

    // the switch on: the box in the corridor, its three cells outside the spawner's range on the fortress's floor, the look
    // out seen by enough of the spawn eyes, the back cell hidden from them and from the ring, and the trip's cell in range
    // and in the spawn area's sight a few steps from the look out
    @Test
    public void theFarBoxStandsOutsideTheSpawnersRange() {
        List<Vec3> eyes = eyes(FORT);
        BlazePillbox.FarRule rule = BlazePillbox.FarRule.DEFAULT;
        long t0 = System.nanoTime();
        BlazePillbox.Far far = BlazePillbox.planFar(FORT, START, 0, 64, 0, eyes, rule, BRICKS);
        System.out.println("far: " + (far == null ? "none" : show(far.plan()) + ", " + far.distance() + " out, trip " + Arrays.toString(far.trip())
                + " in " + far.tripSteps() + " steps") + " (" + BlazePillbox.whyNotFar() + ", " + (System.nanoTime() - t0) / 1_000_000 + " ms)");
        assertNotNull(far);
        BlazePillbox.Plan p = far.plan();
        for (int[] c : new int[][]{p.cell(), p.front(), p.lookout()}) {
            double d = BlazePillbox.spawnerDistance(c[0], c[1], c[2], 0, 64, 0);
            assertTrue(Arrays.toString(c) + " " + d, d >= rule.min() && d > BlazePillbox.SPAWN_RANGE);
            assertTrue(Arrays.toString(c), BRICKS.fortress(c[0], c[1], c[2]));
            assertTrue(Arrays.toString(c), BlazeCover.walkable(FORT, c[0], c[1], c[2]));
        }
        assertTrue(far.distance() <= rule.max());
        assertEquals(BlazePillbox.spawnerDistance(p.cell()[0], p.cell()[1], p.cell()[2], 0, 64, 0), far.distance(), 1e-9);
        assertTrue(p.blocks().size() <= BlazePillbox.MOST_BENT);
        Cells after = BlazePillbox.after(FORT, p.blocks());
        assertTrue(BlazePillbox.seenShare(FORT, p, eyes) >= rule.seenMin());
        assertTrue(BlazePillbox.hiddenWide(after, eyes, p.cell()[0], p.cell()[1], p.cell()[2]));
        assertTrue(BlazePillbox.ringHidden(after, p.cell()[0], p.cell()[1], p.cell()[2]));
        int[] t = far.trip();
        assertTrue(BlazePillbox.spawnerDistance(t[0], t[1], t[2], 0, 64, 0) <= rule.depth());
        assertTrue(BlazePillbox.seenAt(after, eyes, t[0], t[1], t[2]) > 0);
        assertTrue(far.tripSteps() > 0 && far.tripSteps() <= rule.tripWalk());
        // (the near plan of the same fortress is another box, inside the range)
        BlazePillbox.Plan bent = BlazePillbox.planBent(FORT, START, 0, 64, 0, eyes);
        assertTrue(!Arrays.equals(bent.cell(), p.cell()));
    }

    // the floor: no far cell on bricks, no far box; any floor, the same box as on the bricks here
    @Test
    public void onlyOnTheFortressFloor() {
        List<Vec3> eyes = eyes(FORT);
        assertNull(BlazePillbox.planFar(FORT, START, 0, 64, 0, eyes, BlazePillbox.FarRule.DEFAULT, (x, y, z) -> x < 17));
        assertTrue(BlazePillbox.whyNotFar().contains("floor"));
        BlazePillbox.Far any = BlazePillbox.planFar(FORT, START, 0, 64, 0, eyes, BlazePillbox.FarRule.DEFAULT, null);
        BlazePillbox.Far bricks = BlazePillbox.planFar(FORT, START, 0, 64, 0, eyes, BlazePillbox.FarRule.DEFAULT, BRICKS);
        assertNotNull(any);
        assertArrayEquals(bricks.plan().cell(), any.plan().cell());
    }

    // a screen before the door: no line from the corridor to where the blazes fly, no far box
    @Test
    public void noLineToTheSpawnAreaNoFarBox() {
        assertNull(BlazePillbox.planFar(SCREENED, START, 0, 64, 0, eyes(SCREENED), BlazePillbox.FarRule.DEFAULT, BRICKS));
        assertTrue(BlazePillbox.whyNotFar(), BlazePillbox.whyNotFar().contains("look out unseen"));
    }

    // the numbers are the rule's: nothing as far as forty-five, and the corridor ends at forty-four
    @Test
    public void beyondTheFloorNoFarBox() {
        BlazePillbox.FarRule far = new BlazePillbox.FarRule(45, 50, 0.3, 14, 32);
        assertNull(BlazePillbox.planFar(FORT, START, 0, 64, 0, eyes(FORT), far, null));
    }

    // the trip's cell: the first by the walk from the look out that is in range and seen; none within the steps, none
    @Test
    public void theTripCellIsTheFirstInRangeByTheWalk() {
        BlazePillbox.FarRule rule = BlazePillbox.FarRule.DEFAULT;
        int[] t = BlazePillbox.tripCell(FORT, new int[]{18, 64, 3}, 0, 64, 0, eyes(FORT), rule);
        assertNotNull(t);
        assertArrayEquals(new int[]{13, 64, 3, 5}, t);
        assertNull(BlazePillbox.tripCell(FORT, new int[]{18, 64, 3}, 0, 64, 0, eyes(FORT), new BlazePillbox.FarRule(17, 30, 0.3, 14, 4)));
        // (in a screened room the first in range is taken all the same, seen or not)
        int[] s = BlazePillbox.tripCell(SCREENED, new int[]{18, 64, 3}, 0, 64, 0, eyes(SCREENED), rule);
        assertNotNull(s);
        assertTrue(BlazePillbox.spawnerDistance(s[0], s[1], s[2], 0, 64, 0) <= rule.depth());
    }

    // the world's blocks where Cells says only SOLID (BlazePillbox.Sound): Cells everywhere, except the cells given
    static BlazePillbox.Sound soundBut(int[] noWall, int[] noFace) {
        return new BlazePillbox.Sound() {
            @Override
            public boolean wall(int x, int y, int z) {
                return !(noWall != null && x == noWall[0] && y == noWall[1] && z == noWall[2]) && FORT.at(x, y, z) == Cell.SOLID;
            }

            @Override
            public boolean face(int x, int y, int z) {
                return !(noFace != null && x == noFace[0] && y == noFace[1] && z == noFace[2]) && FORT.at(x, y, z) == Cell.SOLID;
            }
        };
    }

    // the first live far box (7 Nov 2026): a cell of its walls read SOLID (Baritone's walk rule: a fire on the floor) but was
    // no block, the high block over it had nothing to go against, and the box was given up two short. the world's own
    // blocks agreeing with Cells, the box is the one Cells alone gives; a wall cell of it that is no block, another box
    @Test
    public void aWallCellThatIsNoBlockIsNoWall() {
        List<Vec3> eyes = eyes(FORT);
        BlazePillbox.Far plain = BlazePillbox.planFar(FORT, START, 0, 64, 0, eyes, BlazePillbox.FarRule.DEFAULT, BRICKS);
        BlazePillbox.Far agreed = BlazePillbox.planFar(FORT, START, 0, 64, 0, eyes, BlazePillbox.FarRule.DEFAULT, BRICKS, soundBut(null, null));
        assertArrayEquals(new int[]{19, 64, 2}, plain.plan().cell());
        assertArrayEquals(plain.plan().cell(), agreed.plan().cell());
        assertTrue(BlazePillbox.sound(plain.plan(), soundBut(null, null)));
        // (F's far wall, the corridor's own wall at z 4, which the box places nothing in)
        int[] fire = {19, 64, 4};
        BlazePillbox.Sound s = soundBut(fire, null);
        assertTrue(!BlazePillbox.sound(plain.plan(), s));
        BlazePillbox.Far far = BlazePillbox.planFar(FORT, START, 0, 64, 0, eyes, BlazePillbox.FarRule.DEFAULT, BRICKS, s);
        assertNotNull(far);
        assertTrue(BlazePillbox.whyNotFar(), BlazePillbox.whyNotFar().contains("not sound"));
        assertTrue(!Arrays.equals(far.plan().cell(), plain.plan().cell()));
        assertTrue(BlazePillbox.sound(far.plan(), s));
    }

    // a low block the box places on what is no whole block (a slab, a stair: its top is no face at the block's top), and the
    // block on B's back wall that B's roof goes against: the box is planned elsewhere
    @Test
    public void aLowBlockNeedsAWholeBlockUnderIt() {
        List<Vec3> eyes = eyes(FORT);
        BlazePillbox.Plan plain = BlazePillbox.planFar(FORT, START, 0, 64, 0, eyes, BlazePillbox.FarRule.DEFAULT, BRICKS).plan();
        // (the box places the low block at 18 64 2, west of B, on the floor at 18 63 2)
        assertTrue(plain.blocks().stream().anyMatch(b -> Arrays.equals(b, new int[]{18, 64, 2})));
        BlazePillbox.Sound slab = soundBut(null, new int[]{18, 63, 2});
        assertTrue(!BlazePillbox.sound(plain, slab));
        BlazePillbox.Far far = BlazePillbox.planFar(FORT, START, 0, 64, 0, eyes, BlazePillbox.FarRule.DEFAULT, BRICKS, slab);
        assertNotNull(far);
        assertTrue(BlazePillbox.sound(far.plan(), slab));
        // (no world, no check: as before)
        assertTrue(BlazePillbox.sound(plain, null));
    }
    // the bag: no plan with more blocks than the buddy carries; the full bag gives the plan as before, too few none
    @Test
    public void noMoreBlocksThanTheBag() {
        List<Vec3> eyes = eyes(FORT);
        BlazePillbox.Far full = BlazePillbox.planFar(FORT, START, 0, 64, 0, eyes, BlazePillbox.FarRule.DEFAULT, BRICKS);
        assertNotNull(full);
        int n = full.plan().blocks().size();
        BlazePillbox.Far enough = BlazePillbox.planFar(FORT, START, 0, 64, 0, eyes, BlazePillbox.FarRule.DEFAULT, BRICKS, null, n);
        assertNotNull(enough);
        assertArrayEquals(full.plan().cell(), enough.plan().cell());
        BlazePillbox.Far fewer = BlazePillbox.planFar(FORT, START, 0, 64, 0, eyes, BlazePillbox.FarRule.DEFAULT, BRICKS, null, n - 1);
        if (fewer != null) assertTrue(fewer.plan().blocks().size() <= n - 1);
        assertNull(BlazePillbox.planFar(FORT, START, 0, 64, 0, eyes, BlazePillbox.FarRule.DEFAULT, BRICKS, null, 0));
        assertTrue(BlazePillbox.whyNotFar(), BlazePillbox.whyNotFar().contains("more blocks than the bag"));
    }
}
