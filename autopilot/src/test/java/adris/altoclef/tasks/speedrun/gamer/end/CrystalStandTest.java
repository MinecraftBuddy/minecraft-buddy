package adris.altoclef.tasks.speedrun.gamer.end;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import adris.altoclef.util.helpers.ReachRules;
import org.junit.Test;

// the covered spot to hit a crystal from, on a fake pillar
public class CrystalStandTest {
    // a pillar centred on 0,0 like vanilla builds it: obsidian up to y 98, one bedrock block on top at 0 99 0, the crystal
    // standing on that at y 100
    private static final int TOP = 98;

    private static CrystalStand.PillarLookup pillar(int radius) {
        return (x, y, z) -> (x == 0 && z == 0 && y == TOP + 1) || (y <= TOP && y >= 60 && x * x + z * z <= radius * radius);
    }

    @Test
    public void standsJustPastTheEdgeTwoUnder() {
        for (int r = 2; r <= 5; r++) {
            assertArrayEquals("radius " + r, new int[]{r + 1, 98, 0}, CrystalStand.stand(0, 100, 0, 1, 0, pillar(r)));
            assertArrayEquals("radius " + r, new int[]{0, 98, -r - 1}, CrystalStand.stand(0, 100, 0, 0, -1, pillar(r)));
        }
    }

    @Test
    public void reachDecidesTheFatPillar() {
        // eye is 0.38 under the box, the box's face is r out from the stand's middle
        for (int r = 2; r <= 4; r++) {
            int[] stand = CrystalStand.stand(0, 100, 0, 1, 0, pillar(r));
            double gap = CrystalStand.standGap(stand, 0.5, 100, 0.5);
            assertEquals(Math.sqrt(r * r + 0.38 * 0.38), gap, 1e-9);
            assertNull(CrystalStand.skipReason(stand, gap, 0));
        }
        // radius 5 puts it just past the crystal reach, that one gets left alone
        int[] stand = CrystalStand.stand(0, 100, 0, 1, 0, pillar(5));
        double gap = CrystalStand.standGap(stand, 0.5, 100, 0.5);
        assertEquals(true, gap > ReachRules.CRYSTAL_REACH);
        assertNotNull(CrystalStand.skipReason(stand, gap, 0));
    }

    @Test
    public void noPillarNoStand() {
        // floating in the air, nothing under it to hide behind
        assertNull(CrystalStand.stand(0, 100, 0, 1, 0, (x, y, z) -> false));
        assertNotNull(CrystalStand.skipReason(null, Double.MAX_VALUE, 0));
        // a pillar wider than any vanilla one isn't one we know
        assertNull(CrystalStand.stand(0, 100, 0, 1, 0, pillar(20)));
        // a cap wider than the pillar under it would put obsidian in our head cell: not a shape we know, pass
        CrystalStand.PillarLookup mushroom = (x, y, z) -> y <= TOP + 1 && y >= 60 && x * x + z * z <= (y == TOP + 1 ? 16 : 4);
        assertNull(CrystalStand.stand(0, 100, 0, 1, 0, mushroom));
        // only the bedrock is at crystal y - 1, a scan along that layer would call every vanilla pillar weird
        assertNotNull(CrystalStand.stand(0, 100, 0, 1, 0, pillar(3)));
    }

    @Test
    public void givesUpAfterAMinute() {
        int[] stand = {3, 98, 0};
        assertNull(CrystalStand.skipReason(stand, 3, CrystalStand.GIVE_UP_TICKS));
        assertNotNull(CrystalStand.skipReason(stand, 3, CrystalStand.GIVE_UP_TICKS + 1));
    }

    @Test
    public void sideFacesUs() {
        assertArrayEquals(new int[]{1, 0}, CrystalStand.side(0.5, 0.5, 20, 3));
        assertArrayEquals(new int[]{-1, 0}, CrystalStand.side(0.5, 0.5, -20, 3));
        assertArrayEquals(new int[]{0, 1}, CrystalStand.side(0.5, 0.5, 2, 30));
        assertArrayEquals(new int[]{0, -1}, CrystalStand.side(0.5, 0.5, 2, -30));
    }
}
