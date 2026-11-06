package adris.altoclef.util.helpers;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import baritone.pathing.movement.CliffAvoidance.Cell;
import baritone.pathing.movement.CliffAvoidance.Cells;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

// the cover the blaze fight's run goes to: the nearest safe cell no blaze sees (the sight itself is the world's, a lambda here)
public class BlazeCoverTest {

    // a floor of solid at y 63 out to 30 each way, open above it; lava where lava says, solid columns where walls says
    private static Cells room(Set<Long> lava, Set<Long> walls) {
        return (x, y, z) -> {
            if (Math.abs(x) > 30 || Math.abs(z) > 30) return Cell.SOLID;
            if (walls.contains(BlazeCover.key(x, 0, z)) && y >= 64 && y <= 66) return Cell.SOLID;
            if (y == 64 && lava.contains(BlazeCover.key(x, 0, z))) return Cell.LAVA;
            return y <= 63 ? Cell.SOLID : Cell.OPEN;
        };
    }

    @Test
    public void theNearestHiddenCellWins() {
        // only x <= -4 is out of sight (a wall the lambda stands for)
        int[] c = BlazeCover.pick(room(Set.of(), Set.of()), 0, 64, 0, List.of(), new HashSet<>(), (x, y, z) -> x <= -4);
        assertArrayEquals(new int[]{-4, 64, 0}, c);
    }

    @Test
    public void noHiddenCellIsNoCover() {
        assertNull(BlazeCover.pick(room(Set.of(), Set.of()), 0, 64, 0, List.of(), new HashSet<>(), (x, y, z) -> false));
    }

    @Test
    public void aBannedCellIsPassedOver() {
        Set<Long> banned = new HashSet<>(Set.of(BlazeCover.key(-4, 64, 0)));
        int[] c = BlazeCover.pick(room(Set.of(), Set.of()), 0, 64, 0, List.of(), banned, (x, y, z) -> x <= -4);
        assertOneOf(c, new int[]{-4, 64, -1}, new int[]{-4, 64, 1}, new int[]{-5, 64, 0});
    }

    @Test
    public void noCoverBesideLava() {
        // lava at (-5, 64, 0): the cells within a block of it are out, (-4, 64, 1) and (-4, 64, -1) too, for the walk as well
        Set<Long> lava = Set.of(BlazeCover.key(-5, 0, 0));
        int[] c = BlazeCover.pick(room(lava, Set.of()), 0, 64, 0, List.of(), new HashSet<>(), (x, y, z) -> x <= -4);
        assertOneOf(c, new int[]{-4, 64, -2}, new int[]{-4, 64, 2});
    }

    @Test
    public void noCoverByAWitherSkeleton() {
        // one standing at (-4.5, 0.5): everything within six of it is theirs
        int[] c = BlazeCover.pick(room(Set.of(), Set.of()), 0, 64, 0, List.of(new double[]{-4.5, 0.5}), new HashSet<>(), (x, y, z) -> x <= -4);
        assertOneOf(c, new int[]{-4, 64, -6}, new int[]{-4, 64, 6});
    }

    @Test
    public void noCoverOnAnEdge() {
        // the floor ends at x = -6: the cells along it are an edge, the hidden ones further in stay
        Cells edge = (x, y, z) -> x < -6 ? Cell.OPEN : room(Set.of(), Set.of()).at(x, y, z);
        assertNull(BlazeCover.pick(edge, 0, 64, 0, List.of(), new HashSet<>(), (x, y, z) -> x <= -6));
        assertArrayEquals(new int[]{-5, 64, 0}, BlazeCover.pick(edge, 0, 64, 0, List.of(), new HashSet<>(), (x, y, z) -> x <= -5));
    }

    @Test
    public void aCellBehindAWallIsReachedRoundItsEnd() {
        // a wall two thick at x -4 and -5 from z -30 to 3: the hidden side is walked to round its end at z 4
        Set<Long> walls = new HashSet<>();
        for (int z = -30; z <= 3; z++) {
            walls.add(BlazeCover.key(-4, 0, z));
            walls.add(BlazeCover.key(-5, 0, z));
        }
        int[] c = BlazeCover.pick(room(Set.of(), walls), 0, 64, 0, List.of(), new HashSet<>(), (x, y, z) -> x <= -6);
        assertArrayEquals(new int[]{-6, 64, 4}, c);
    }

    @Test
    public void aCellPastAWallWithNoWayRoundIsNoCover() {
        // the same wall all along: the hidden cells behind it cannot be walked to (as past the drill's barrier)
        Set<Long> walls = new HashSet<>();
        for (int z = -30; z <= 30; z++) {
            walls.add(BlazeCover.key(-4, 0, z));
            walls.add(BlazeCover.key(-5, 0, z));
        }
        assertNull(BlazeCover.pick(room(Set.of(), walls), 0, 64, 0, List.of(), new HashSet<>(), (x, y, z) -> x <= -6));
    }

    @Test
    public void aWalkTooLongIsNoCover() {
        // hidden only past the walk's reach
        assertNull(BlazeCover.pick(room(Set.of(), Set.of()), 0, 64, 0, List.of(), new HashSet<>(), (x, y, z) -> x + z <= -(BlazeCover.MAX_WALK + 1)));
    }

    @Test
    public void theCornerIsTheNearestHiddenCellWithALookOut() {
        // the spawn area sees x >= -3 only: the corner is the first cell past it, the cell before it the look out
        int[][] c = BlazeCover.corner(room(Set.of(), Set.of()), new int[]{0, 64, 0}, 0, 0, List.of(), (x, y, z) -> x < -3);
        assertArrayEquals(new int[]{-4, 64, 0}, c[0]);
        assertArrayEquals(new int[]{-3, 64, 0}, c[1]);
    }

    @Test
    public void noCornerWithoutALookOut() {
        // hidden everywhere: no cell to look round the corner from
        assertNull(BlazeCover.corner(room(Set.of(), Set.of()), new int[]{0, 64, 0}, 0, 0, List.of(), (x, y, z) -> true));
    }

    @Test
    public void noCornerInTheSpawnArea() {
        // hidden from x <= -1 on: the corner keeps CORNER_MIN from the spawner
        int[][] c = BlazeCover.corner(room(Set.of(), Set.of()), new int[]{0, 64, 0}, 0, 0, List.of(), (x, y, z) -> x <= -1 && z <= -3);
        assertTrue(Math.hypot(c[0][0], c[0][2]) >= BlazeCover.CORNER_MIN);
    }

    @Test
    public void theMostClosedInCoverWinsOverTheNearest() {
        // hidden from x <= -4 on; the cells at z = 1 are closed in, the rest open: (-4, 64, 1) beats the nearer (-4, 64, 0)
        int[] c = BlazeCover.pick(room(Set.of(), Set.of()), 0, 64, 0, List.of(), new HashSet<>(), (x, y, z) -> x <= -4,
                (x, y, z) -> z == 1 ? 20 : 4);
        assertArrayEquals(new int[]{-4, 64, 1}, c);
    }

    @Test
    public void theMostClosedInCornerWins() {
        // the spawn area sees x >= -3; the corner cells at z = 2 are pockets
        int[][] c = BlazeCover.corner(room(Set.of(), Set.of()), new int[]{0, 64, 0}, 0, 0, List.of(), (x, y, z) -> x < -3,
                (x, y, z) -> z == 2 ? 22 : 6);
        assertArrayEquals(new int[]{-4, 64, 2}, c[0]);
        assertArrayEquals(new int[]{-3, 64, 2}, c[1]);
    }

    @Test
    public void aPocketHasOneWayIn() {
        boolean T = true, F = false;
        // closed all round, or one way in up to three of eight wide: a pocket
        org.junit.Assert.assertTrue(BlazeCover.onePocket(new boolean[]{F, F, F, F, F, F, F, F}, 0));
        org.junit.Assert.assertTrue(BlazeCover.onePocket(new boolean[]{T, T, F, F, F, F, F, T}, 1));
        // open in front and behind: an open corner, no pocket
        org.junit.Assert.assertFalse(BlazeCover.onePocket(new boolean[]{T, F, F, F, T, F, F, F}, 0));
        // four of eight open, or no roof: no pocket
        org.junit.Assert.assertFalse(BlazeCover.onePocket(new boolean[]{T, T, T, T, F, F, F, F}, 0));
        org.junit.Assert.assertFalse(BlazeCover.onePocket(new boolean[]{F, F, F, F, F, F, F, F}, 2));
    }

    private static void assertOneOf(int[] got, int[]... any) {
        for (int[] a : any) {
            if (java.util.Arrays.equals(a, got)) return;
        }
        assertTrue("got " + java.util.Arrays.toString(got), false);
    }

    // the looks' cells (BlazeLureTask.of): along a corridor one wide, one cell a step, each with its step count, the start left out
    @Test
    public void aroundCountsTheStepsAlongACorridor() {
        Set<Long> walls = new HashSet<>();
        for (int x = -3; x <= 9; x++) {
            for (int z = -3; z <= 3; z++) {
                if (!(z == 0 && x >= 0 && x <= 6)) walls.add(BlazeCover.key(x, 0, z));
            }
        }
        List<int[]> got = BlazeCover.around(room(Set.of(), walls), 0, 64, 0, 4);
        assertTrue("got " + got.size(), got.size() == 4);
        for (int d = 1; d <= 4; d++) assertArrayEquals(new int[]{d, 64, 0, d}, got.get(d - 1));
    }

    // the same cases with SafeStand's switch on (tuning safe_stand): its knock reach instead of the old edge and lava tests
    @Test
    public void noCoverBesideLavaWithSafeStand() {
        SafeStand.forced = true;
        try {
            // lava at feet height at (-5, 64, 0): a knock carries the box into it from about 2.8 off (the reach and half the box),
            // so along x = -4 the first cell clear of it is four along
            Set<Long> lava = Set.of(BlazeCover.key(-5, 0, 0));
            int[] c = BlazeCover.pick(room(lava, Set.of()), 0, 64, 0, List.of(), new HashSet<>(), (x, y, z) -> x <= -4);
            assertOneOf(c, new int[]{-4, 64, -4}, new int[]{-4, 64, 4});
        } finally {
            SafeStand.forced = null;
        }
    }

    @Test
    public void noCoverOnAnEdgeWithSafeStand() {
        SafeStand.forced = true;
        try {
            // the floor ends at x = -6 (the void past it): a knock carries the box over it from two cells in, so the first cover is
            // three in
            Cells edge = (x, y, z) -> x < -6 ? Cell.OPEN : room(Set.of(), Set.of()).at(x, y, z);
            assertNull(BlazeCover.pick(edge, 0, 64, 0, List.of(), new HashSet<>(), (x, y, z) -> x <= -6));
            assertNull(BlazeCover.pick(edge, 0, 64, 0, List.of(), new HashSet<>(), (x, y, z) -> x <= -5));
            assertArrayEquals(new int[]{-4, 64, 0}, BlazeCover.pick(edge, 0, 64, 0, List.of(), new HashSet<>(), (x, y, z) -> x <= -4));
        } finally {
            SafeStand.forced = null;
        }
    }
}
