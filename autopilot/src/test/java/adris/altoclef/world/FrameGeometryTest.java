package adris.altoclef.world;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FrameGeometryTest {
    private static final int[] C = {10, 40, 20};

    // frames with the facing a generated room gives them: toward the hole
    private static List<int[]> ringWithFacing() {
        List<int[]> out = new ArrayList<>();
        for (int[] p : FrameGeometry.positions(C)) {
            int ox = p[0] - C[0];
            int oz = p[2] - C[2];
            int fx = Math.abs(ox) == 2 ? -Integer.signum(ox) : 0;
            int fz = Math.abs(oz) == 2 ? -Integer.signum(oz) : 0;
            out.add(new int[]{p[0], p[1], p[2], fx, fz});
        }
        return out;
    }

    private static List<int[]> plain(List<int[]> frames) {
        List<int[]> out = new ArrayList<>();
        for (int[] f : frames) {
            out.add(new int[]{f[0], f[1], f[2]});
        }
        return out;
    }

    @Test
    public void twelveFramesOnTheRingNoCornersNoHole() {
        List<int[]> ps = FrameGeometry.positions(C);
        assertEquals(12, ps.size());
        Set<String> seen = new HashSet<>();
        for (int[] p : ps) {
            assertEquals(C[1], p[1]);
            int ox = p[0] - C[0];
            int oz = p[2] - C[2];
            // 5x5 square minus its corners minus the 3x3 hole
            assertTrue(Math.max(Math.abs(ox), Math.abs(oz)) == 2);
            assertFalse(Math.abs(ox) == 2 && Math.abs(oz) == 2);
            assertTrue(seen.add(ox + "," + oz));
        }
    }

    @Test
    public void wholeRingGivesTheCentre() {
        assertArrayEquals(C, FrameGeometry.centreFromFrames(plain(ringWithFacing())).get());
        assertArrayEquals(C, FrameGeometry.centreFromFrames(ringWithFacing()).get());
    }

    @Test
    public void everySubsetNeverGivesAWrongCentre() {
        List<int[]> ring = ringWithFacing();
        for (int mask = 0; mask < (1 << 12); mask++) {
            if (Integer.bitCount(mask) < 3) {
                continue;
            }
            List<int[]> sub = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                if ((mask & (1 << i)) != 0) {
                    sub.add(ring.get(i));
                }
            }
            Optional<int[]> withFacing = FrameGeometry.centreFromFrames(sub);
            Optional<int[]> positionsOnly = FrameGeometry.centreFromFrames(plain(sub));
            // empty (ambiguous) is allowed, wrong is not
            if (withFacing.isPresent()) {
                assertArrayEquals("mask " + mask, C, withFacing.get());
            }
            if (positionsOnly.isPresent()) {
                assertArrayEquals("mask " + mask, C, positionsOnly.get());
            }
        }
    }

    @Test
    public void facingSettlesAnySetThatTouchesBothAxes() {
        List<int[]> ring = ringWithFacing();
        for (int mask = 0; mask < (1 << 12); mask++) {
            if (Integer.bitCount(mask) < 3) {
                continue;
            }
            List<int[]> sub = new ArrayList<>();
            boolean eastWest = false;
            boolean northSouth = false;
            for (int i = 0; i < 12; i++) {
                if ((mask & (1 << i)) != 0) {
                    int[] f = ring.get(i);
                    sub.add(f);
                    eastWest |= f[3] != 0;
                    northSouth |= f[4] != 0;
                }
            }
            // frames on two parallel sides only (east + west) cannot pin the shift along the sides, any other mix can.
            // all three of one side can too, thanks to the facing
            if (eastWest && northSouth) {
                Optional<int[]> c = FrameGeometry.centreFromFrames(sub);
                assertTrue("mask " + mask, c.isPresent());
                assertArrayEquals("mask " + mask, C, c.get());
            }
        }
    }

    @Test
    public void threeOnOneSideNeedTheFacing() {
        // east side, three in a row: positions fit a ring on either side of them
        int[] a = {12, 40, 19};
        int[] b = {12, 40, 20};
        int[] c = {12, 40, 21};
        assertTrue(FrameGeometry.centreFromFrames(List.of(a, b, c)).isEmpty());
        // facing west (-1, 0) means the hole is to the west of them
        List<int[]> withFacing = List.of(
                new int[]{12, 40, 19, -1, 0}, new int[]{12, 40, 20, -1, 0}, new int[]{12, 40, 21, -1, 0});
        assertArrayEquals(C, FrameGeometry.centreFromFrames(withFacing).get());
    }

    @Test
    public void notEnoughFramesGivesNothing() {
        assertTrue(FrameGeometry.centreFromFrames(List.of()).isEmpty());
        assertTrue(FrameGeometry.centreFromFrames(List.of(new int[]{12, 40, 20}, new int[]{12, 40, 21})).isEmpty());
        // the same frame three times is one frame
        int[] f = {12, 40, 20};
        assertTrue(FrameGeometry.centreFromFrames(List.of(f, f, f)).isEmpty());
    }

    @Test
    public void inconsistentSetsAreRejected() {
        // two frames of this ring and one 30 blocks away
        List<int[]> far = List.of(new int[]{12, 40, 20}, new int[]{12, 40, 21}, new int[]{42, 40, 20});
        assertTrue(FrameGeometry.centreFromFrames(far).isEmpty());
        // different heights are not one flat ring
        List<int[]> tall = List.of(new int[]{12, 40, 20}, new int[]{12, 41, 21}, new int[]{11, 40, 22});
        assertTrue(FrameGeometry.centreFromFrames(tall).isEmpty());
        // a corner is not a frame position
        List<int[]> corner = List.of(new int[]{12, 40, 22}, new int[]{12, 40, 21}, new int[]{12, 40, 20});
        assertTrue(FrameGeometry.centreFromFrames(corner).isEmpty());
        // garbage entries
        assertTrue(FrameGeometry.centreFromFrames(List.of(new int[]{1, 2}, new int[]{1, 2, 3}, new int[]{4, 5, 6})).isEmpty());
    }

    @Test
    public void outwardFacingFramesDoNotFitTheModel() {
        List<int[]> outward = new ArrayList<>();
        for (int[] f : ringWithFacing()) {
            outward.add(new int[]{f[0], f[1], f[2], -f[3], -f[4]});
        }
        assertTrue(FrameGeometry.centreFromFrames(outward).isEmpty());
    }

    @Test
    public void filledAndSeenCounts() {
        Set<String> eyes = new HashSet<>();
        List<int[]> ps = FrameGeometry.positions(C);
        for (int i = 0; i < 5; i++) {
            eyes.add(ps.get(i)[0] + "," + ps.get(i)[2]);
        }
        assertEquals(5, FrameGeometry.filledCount(C, p -> eyes.contains(p[0] + "," + p[2])));
        assertEquals(0, FrameGeometry.filledCount(C, p -> false));
        assertEquals(12, FrameGeometry.seenCount(C, p -> true));
        assertTrue(FrameGeometry.allSeen(C, p -> true));
        assertFalse(FrameGeometry.allSeen(C, p -> p[0] != C[0] + 2 || p[2] != C[2]));
    }

    @Test
    public void ringIsTheSameAlongEitherAxis() {
        // the positions set is symmetric under a quarter turn
        Set<String> a = new HashSet<>();
        Set<String> b = new HashSet<>();
        for (int[] p : FrameGeometry.positions(new int[]{0, 0, 0})) {
            a.add(p[0] + "," + p[2]);
            b.add((-p[2]) + "," + p[0]);
        }
        assertEquals(a, b);
    }
}
