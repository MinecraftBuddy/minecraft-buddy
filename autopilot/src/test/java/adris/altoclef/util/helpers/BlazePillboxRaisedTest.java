package adris.altoclef.util.helpers;

import static org.junit.Assert.assertTrue;

import baritone.pathing.movement.CliffAvoidance.Cell;
import baritone.pathing.movement.CliffAvoidance.Cells;
import java.util.List;
import org.junit.Test;

// the raised box's measure (BlazePillbox.raisedSight, seenAt): a spawner on a platform three high, a body at the platform's foot
// sees little of the spawn area, the same body three higher sees it all
public class BlazePillboxRaisedTest {

    // the floor at y 61, the platform nine across at y 62 to 64, the spawner on it at (0, 65, 0)
    static final Cells PLATFORM = (x, y, z) -> {
        if (y <= 61 || y >= 90) return Cell.SOLID;
        if (Math.abs(x) <= 4 && Math.abs(z) <= 4 && y <= 64) return Cell.SOLID;
        if (x == 0 && y == 65 && z == 0) return Cell.SOLID;
        return Cell.OPEN;
    };

    @Test
    public void aBodyRaisedOverThePlatformsEdgeSeesMore() {
        List<net.minecraft.world.phys.Vec3> eyes = BlazePillbox.fineEyes(0, 65, 0);
        double foot = BlazePillbox.seenAt(PLATFORM, eyes, 7, 62, 0);
        double up3 = BlazePillbox.seenAt(PLATFORM, eyes, 7, 65, 0);
        System.out.println("at the foot " + foot + ", three up " + up3);
        assertTrue("foot " + foot, foot < 0.5);
        assertTrue("three up " + up3, up3 > 0.9);
        assertTrue(BlazePillbox.raisedOpen(PLATFORM, 7, 62, 0, 3));
        assertTrue(!BlazePillbox.raisedOpen(PLATFORM, 4, 62, 0, 1));
    }

    // the raised box by the platform: it fits, its look out sees more than the ground box's, and every block has, when its turn
    // comes, a solid face beside or under it that the eye at its stand is in front of (the roof row is clicked at a jump's top)
    @Test
    public void theRaisedBoxFitsAndEachBlockHasAFaceInSight() {
        List<net.minecraft.world.phys.Vec3> eyes = BlazeCover.spawnEyes(0, 65, 0);
        int[] start = {6, 62, 0};
        BlazePillbox.Plan ground = BlazePillbox.planBent(PLATFORM, start, 0, 65, 0, eyes);
        BlazePillbox.Plan up = BlazePillbox.planBent(PLATFORM, start, 0, 65, 0, eyes, 2);
        org.junit.Assert.assertNotNull("no raised plan", up);
        StringBuilder sb = new StringBuilder("raised: cell " + java.util.Arrays.toString(up.cell()) + " front " + java.util.Arrays.toString(up.front())
                + " look out " + java.util.Arrays.toString(up.lookout()) + ", " + up.blocks().size() + " blocks:");
        for (int i = 0; i < up.blocks().size(); i++) sb.append(' ').append(java.util.Arrays.toString(up.blocks().get(i))).append('@').append(up.stands().get(i)[1]);
        System.out.println(sb);
        assertTrue(up.raise() == 2 && up.cell()[1] == 64 && up.lookout()[1] == 64);
        assertTrue(up.blocks().size() == up.stands().size());
        List<net.minecraft.world.phys.Vec3> fine = BlazePillbox.fineEyes(0, 65, 0);
        // (the same look out on the ground sees less: the platform's edge in the way)
        double seenUp = BlazePillbox.seenShare(PLATFORM, up, fine);
        double sameOnGround = BlazePillbox.seenAt(BlazePillbox.after(PLATFORM, up.blocks()), fine, up.lookout()[0], up.lookout()[1] - 2, up.lookout()[2]);
        double seenGround = ground == null ? 0 : BlazePillbox.seenShare(PLATFORM, ground, fine);
        System.out.println("look out seen: raised " + seenUp + ", the same cell on the ground " + sameOnGround + ", the ground box's " + seenGround);
        assertTrue(seenUp > sameOnGround);
        java.util.Set<Long> placed = new java.util.HashSet<>();
        Cells now = (x, y, z) -> placed.contains(BlazeCover.key(x, y, z)) ? Cell.SOLID : PLATFORM.at(x, y, z);
        for (int i = 0; i < up.blocks().size(); i++) {
            int[] b = up.blocks().get(i), s = up.stands().get(i);
            assertTrue("block " + i + " already solid", now.at(b[0], b[1], b[2]) == Cell.OPEN);
            boolean roofRow = b[1] >= s[1] + 2;
            double ex = s[0] + 0.5, ey = s[1] + (roofRow ? 1.25 : 0) + 1.62, ez = s[2] + 0.5;
            boolean face = now.at(b[0], b[1] - 1, b[2]) == Cell.SOLID && ey > b[1];
            int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] d : sides) {
                if (face || now.at(b[0] + d[0], b[1], b[2] + d[1]) != Cell.SOLID) continue;
                if (d[0] != 0) face = d[0] > 0 ? ex < b[0] + 1 : ex > b[0];
                else face = d[1] > 0 ? ez < b[2] + 1 : ez > b[2];
            }
            assertTrue("block " + i + " " + java.util.Arrays.toString(b) + " from " + java.util.Arrays.toString(s) + ": no face in sight", face);
            // (the stand up there has its floor by then, but F's own floor, which Baritone's pillar puts in on the walk up)
            boolean ownFloor = b[0] == s[0] && b[1] == s[1] - 1 && b[2] == s[2];
            assertTrue("block " + i + ": stand without a floor", ownFloor || now.at(s[0], s[1] - 1, s[2]) == Cell.SOLID);
            placed.add(BlazeCover.key(b[0], b[1], b[2]));
        }
    }
}
