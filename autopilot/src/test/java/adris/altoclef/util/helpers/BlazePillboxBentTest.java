package adris.altoclef.util.helpers;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import baritone.pathing.movement.CliffAvoidance.Cell;
import baritone.pathing.movement.CliffAvoidance.Cells;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

// the bent pillbox (BlazePillbox.planBent): two cells, the back one round a corner from the opening, hidden from a ring of eyes
// all round, in the drill room's straight shape (bin/drill.sh blazeroom, BEND=0) with the spawner at the origin
public class BlazePillboxBentTest {

    // the platform thirteen across at y 60 to 63 over a lava sea, its raised middle, the rim of fences with its three gaps, the
    // bridge from the west and the corridor north, one wide and two high through a block of bricks
    static final Cells ROOM = (x, y, z) -> {
        if (Math.abs(x) >= 31 || Math.abs(z) >= 21 || y <= 24 || y >= 92) return Cell.SOLID;
        if (x == 0 && y == 65 && z == 0) return Cell.SOLID;
        if (Math.abs(x) <= 6 && Math.abs(z) <= 6) {
            if (y >= 60 && y <= 63) return Cell.SOLID;
            if (y == 64 && Math.abs(x) <= 2 && Math.abs(z) <= 2) return Cell.SOLID;
            if (y == 64 && (Math.abs(x) == 6 || Math.abs(z) == 6)) {
                boolean gap = (x == -6 && Math.abs(z) <= 1) || (x == 6 && (z == 3 || z == 4)) || (z == -6 && x == 0);
                return gap ? Cell.OPEN : Cell.SOLID;
            }
        }
        if (x >= -25 && x <= -7 && Math.abs(z) <= 1 && y >= 60 && y <= 63) return Cell.SOLID;
        if (x >= -25 && x <= -7 && Math.abs(z) == 2 && y == 64) return Cell.SOLID;
        if (Math.abs(x) <= 1 && z >= -17 && z <= -7 && y >= 60 && y <= 66) {
            boolean passage = x == 0 && z >= -16 && (y == 64 || y == 65);
            return passage ? Cell.OPEN : Cell.SOLID;
        }
        return y <= 40 ? Cell.LAVA : Cell.OPEN;
    };

    static final List<net.minecraft.world.phys.Vec3> EYES = BlazeCover.spawnEyes(0, 65, 0);

    @Test
    public void theStraightRoomGetsABentBox() {
        BlazePillbox.Plan p = BlazePillbox.planBent(ROOM, new int[]{1, 65, 1}, 0, 65, 0, EYES);
        assertNotNull("no bent plan", p);
        StringBuilder sb = new StringBuilder("cell " + Arrays.toString(p.cell()) + " front " + Arrays.toString(p.front()) + " look out "
                + Arrays.toString(p.lookout()) + " blocks " + p.blocks().size() + ":");
        for (int[] b : p.blocks()) sb.append(' ').append(Arrays.toString(b));
        System.out.println(sb);
        assertTrue("blocks " + p.blocks().size(), p.blocks().size() <= BlazePillbox.MOST_BENT);
        Cells after = BlazePillbox.after(ROOM, p.blocks());
        assertTrue(BlazePillbox.ringHidden(after, p.cell()[0], p.cell()[1], p.cell()[2]));
        assertTrue(BlazePillbox.hiddenWide(after, EYES.stream().filter(e -> after.at((int) Math.floor(e.x), (int) Math.floor(e.y),
                (int) Math.floor(e.z)) == Cell.OPEN).toList(), p.cell()[0], p.cell()[1], p.cell()[2]));
        assertTrue(!BlazePillbox.hidden(after, EYES, p.lookout()[0], p.lookout()[1], p.lookout()[2]));
        // (the front cell beside the cell, the look out beside the front cell and not beside the cell: round a corner)
        assertTrue(Math.abs(p.front()[0] - p.cell()[0]) + Math.abs(p.front()[2] - p.cell()[2]) == 1);
        assertTrue(Math.abs(p.lookout()[0] - p.cell()[0]) == 1 && Math.abs(p.lookout()[2] - p.cell()[2]) == 1);
        // (the roof row last, and the cell's roof in it)
        int[] last = p.blocks().get(p.blocks().size() - 1);
        assertTrue(last[1] == p.cell()[1] + 2);
        assertTrue(p.blocks().stream().anyMatch(b -> b[0] == p.cell()[0] && b[1] == p.cell()[1] + 2 && b[2] == p.cell()[2]));
    }

    // the one-cell box of the same room is seen by the ring: what the bent box is for
    @Test
    public void theOneCellBoxIsSeenByTheRing() {
        BlazePillbox.Plan p = BlazePillbox.plan(ROOM, new int[]{1, 65, 1}, 0, 65, 0, EYES);
        if (p == null) return;
        Cells after = BlazePillbox.after(ROOM, p.blocks());
        assertTrue(!BlazePillbox.ringHidden(after, p.cell()[0], p.cell()[1], p.cell()[2]));
    }

    // an open floor: a bent box too, with its cell round the corner from the look out
    @Test
    public void anOpenFloorGetsABentBox() {
        Cells floor = (x, y, z) -> {
            if (Math.abs(x) > 30 || Math.abs(z) > 30) return Cell.SOLID;
            if (x == 0 && z == 0 && y == 64) return Cell.SOLID;
            return y <= 63 ? Cell.SOLID : Cell.OPEN;
        };
        BlazePillbox.Plan p = BlazePillbox.planBent(floor, new int[]{2, 64, 0}, 0, 64, 0, BlazeCover.spawnEyes(0, 64, 0));
        assertNotNull(p);
        assertTrue(BlazePillbox.ringHidden(BlazePillbox.after(floor, p.blocks()), p.cell()[0], p.cell()[1], p.cell()[2]));
    }

    // a real fortress's spawner (steering 30): on a platform three over the floor, seven across, a stair down its east side.
    // the box goes farther out than the platform's foot, where its edge hid the look out (realfort-a-bent-a, every box five
    // out), and its look out is seen by at least half the spawn eyes
    @Test
    public void aRaisedSpawnerGetsABoxFartherOut() {
        Cells raised = (x, y, z) -> {
            if (Math.abs(x) > 30 || Math.abs(z) > 30) return Cell.SOLID;
            if (x == 0 && z == 0 && y == 70) return Cell.SOLID;
            if (Math.abs(x) <= 3 && Math.abs(z) <= 3) return y <= 69 ? Cell.SOLID : Cell.OPEN;
            if (x == 4 && Math.abs(z) <= 1) return y <= 68 ? Cell.SOLID : Cell.OPEN;
            if (x == 5 && Math.abs(z) <= 1) return y <= 67 ? Cell.SOLID : Cell.OPEN;
            return y <= 66 ? Cell.SOLID : Cell.OPEN;
        };
        List<net.minecraft.world.phys.Vec3> eyes = BlazeCover.spawnEyes(0, 70, 0).stream()
                .filter(e -> raised.at((int) Math.floor(e.x), (int) Math.floor(e.y), (int) Math.floor(e.z)) == Cell.OPEN).toList();
        BlazePillbox.Plan p = BlazePillbox.planBent(raised, new int[]{1, 70, 1}, 0, 70, 0, eyes);
        assertNotNull("no bent plan", p);
        double flat = Math.hypot(p.cell()[0], p.cell()[2]);
        System.out.println("raised: cell " + Arrays.toString(p.cell()) + " flat " + flat + " look out " + Arrays.toString(p.lookout())
                + " seen " + BlazePillbox.seenShare(raised, p, eyes) + " blocks " + p.blocks().size());
        assertTrue("cell " + Arrays.toString(p.cell()), p.cell()[1] == 67 && flat > 6);
        assertTrue(BlazePillbox.seenShare(raised, p, eyes) >= BlazePillbox.SEEN_MIN);
    }

    // bent-b run 4, the first bent box built whole: B (3196, 64, 402), F east of it, the look out north of F; blazes on the
    // raised middle hit the buddy in B (it stood up to 0.3 off the cell's middle) through the look out's far side. six of
    // those hits, the blaze's eye to the buddy's body: every line open without the wing, every one shut with it
    @Test
    public void theWingClosesTheLineThroughTheLookOut() {
        int[] a = {1, 0}, p = {0, -1};
        double[][] hits = {{3200.32, 65.00, 399.52, 3196.32, 402.56}, {3201.65, 65.00, 400.31, 3196.56, 402.45}, {3201.28, 65.39, 399.04, 3196.56, 402.62},
                {3201.08, 65.00, 399.07, 3196.80, 402.70}, {3200.69, 64.92, 397.13, 3197.30, 402.45}, {3201.01, 65.76, 399.44, 3197.09, 402.56}};
        BlazePillbox.Plan bare = BlazePillbox.planBentAt(ROOM, -4, 64, 2, a, p, false, EYES, 0, 0);
        BlazePillbox.Plan winged = BlazePillbox.planBentAt(ROOM, -4, 64, 2, a, p, true, EYES, 0, 0);
        assertNotNull(bare);
        assertNotNull(winged);
        Cells open = BlazePillbox.after(ROOM, bare.blocks()), shut = BlazePillbox.after(ROOM, winged.blocks());
        for (double[] h : hits) {
            net.minecraft.world.phys.Vec3 eye = new net.minecraft.world.phys.Vec3(h[0] - 3200, h[1] + 1.53, h[2] - 400);
            net.minecraft.world.phys.Vec3 body = new net.minecraft.world.phys.Vec3(h[3] - 3200, 64.9, h[4] - 400);
            assertTrue("open " + Arrays.toString(h), BlazePillbox.clear(open, eye, body));
            assertTrue("shut " + Arrays.toString(h), !BlazePillbox.clear(shut, eye, body));
        }
    }
}
