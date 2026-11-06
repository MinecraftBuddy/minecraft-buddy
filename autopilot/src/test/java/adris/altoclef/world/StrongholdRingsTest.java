package adris.altoclef.world;

import adris.altoclef.world.StrongholdRings.Stronghold;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// guards the test-only ring generator against the vectors in gamer-research-route.md D.3, so the monte carlo runs on
// the real vanilla layout and not on something that merely looks like it
public class StrongholdRingsTest {
    private static final int[] RING_COUNTS = {3, 6, 10, 15, 21, 28, 36, 9};

    // first nine chunk positions per seed, from the route doc (computed there with java.util.Random)
    private static void assertFirstNine(long seed, int[][] expected) {
        List<Stronghold> all = StrongholdRings.generate(seed);
        for (int i = 0; i < expected.length; i++) {
            assertEquals("seed " + seed + " #" + i + " x", expected[i][0], all.get(i).chunkX());
            assertEquals("seed " + seed + " #" + i + " z", expected[i][1], all.get(i).chunkZ());
        }
    }

    @Test
    public void seedZeroMatchesTheRouteDoc() {
        assertFirstNine(0L, new int[][]{{-13, -106}, {121, 52}, {-92, 69}, {-75, -342}, {223, -203}, {278, 89},
                {69, 316}, {-213, 194}, {-298, -95}});
    }

    @Test
    public void seedOneMatchesTheRouteDoc() {
        assertFirstNine(1L, new int[][]{{-14, -120}, {105, 45}, {-71, 53}, {-170, -313}, {163, -266}, {303, 8},
                {138, 254}, {-174, 284}, {-310, -8}});
    }

    @Test
    public void seed12345MatchesTheRouteDoc() {
        assertFirstNine(12345L, new int[][]{{-105, 124}, {-39, -107}, {114, 21}, {293, 0}, {178, 308}, {-172, 299},
                {-307, 0}, {-177, -306}, {144, -249}});
    }

    @Test
    public void bigNegativeSeedMatchesTheRouteDoc() {
        assertFirstNine(-4172144997902289642L, new int[][]{{-42, 87}, {-81, -118}, {132, -11}, {295, -118},
                {229, 181}, {-44, 305}, {-297, 118}, {-240, -189}, {42, -285}});
    }

    @Test
    public void ringCountsAreTheVanillaOnes() {
        for (long seed : new long[]{0, 1, 12345, -4172144997902289642L, 987654321L}) {
            int[] counts = new int[8];
            List<Stronghold> all = StrongholdRings.generate(seed);
            for (Stronghold s : all) {
                counts[s.ring()]++;
            }
            for (int i = 0; i < 8; i++) {
                assertEquals("seed " + seed + " ring " + i, RING_COUNTS[i], counts[i]);
            }
            assertEquals(128, all.size());
        }
    }

    @Test
    public void radiiStayInsideNominalPlusMinusFortyChunks() {
        for (long seed = -20; seed < 60; seed++) {
            for (Stronghold s : StrongholdRings.generate(seed)) {
                double r = Math.hypot(s.chunkX(), s.chunkZ());
                double nominal = 128 + 192 * s.ring();
                // +-1 for Math.round on each axis
                assertTrue("seed " + seed + " ring " + s.ring() + " r=" + r, Math.abs(r - nominal) <= 40 + 1.5);
            }
        }
    }

    @Test
    public void ringZeroIsSpacedAHundredAndTwentyDegrees() {
        for (long seed = 0; seed < 60; seed++) {
            List<Stronghold> all = StrongholdRings.generate(seed);
            for (int i = 0; i < 3; i++) {
                Stronghold a = all.get(i);
                Stronghold b = all.get((i + 1) % 3);
                double diff = Math.atan2(b.chunkZ(), b.chunkX()) - Math.atan2(a.chunkZ(), a.chunkX());
                diff = ((diff % (2 * Math.PI)) + 2 * Math.PI) % (2 * Math.PI);
                // Math.round moves a point by up to 0.7 chunk, at 88+ chunks that is under 0.02 rad
                assertEquals("seed " + seed, 2 * Math.PI / 3, diff, 0.03);
            }
        }
    }

    @Test
    public void ringOneIsSixthsOfACircle() {
        List<Stronghold> all = StrongholdRings.generate(7);
        for (int i = 3; i < 9; i++) {
            Stronghold a = all.get(i);
            Stronghold b = all.get(i + 1 < 9 ? i + 1 : 3);
            double diff = Math.atan2(b.chunkZ(), b.chunkX()) - Math.atan2(a.chunkZ(), a.chunkX());
            diff = ((diff % (2 * Math.PI)) + 2 * Math.PI) % (2 * Math.PI);
            assertEquals(Math.PI / 3, diff, 0.02);
        }
    }

    @Test
    public void nearestPicksTheClosestCorner() {
        List<Stronghold> all = StrongholdRings.generate(0);
        Stronghold s = all.get(1);
        assertEquals(s, StrongholdRings.nearest(all, s.cornerX() + 5, s.cornerZ() - 3));
    }
}
