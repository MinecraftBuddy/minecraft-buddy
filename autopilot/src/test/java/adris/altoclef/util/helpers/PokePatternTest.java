package adris.altoclef.util.helpers;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.PokePattern.Kind;
import adris.altoclef.util.helpers.PokePattern.Move;
import adris.altoclef.util.helpers.PokePattern.Run;
import adris.altoclef.util.helpers.PokePattern.Turn;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

// (Tuning gold_poke_shafts) the gold mine's poke hole pattern: which cells, in which order, and how the serpentine turns
public class PokePatternTest {

    // the next moves of a run, every hole dug and every step reached
    private static List<Move> play(Run run, int moves) {
        List<Move> out = new ArrayList<>();
        for (int i = 0; i < moves; i++) {
            Move m = run.next();
            out.add(m);
            if (m.kind() == Kind.HOLE) run.holeDone();
            else run.stepped();
        }
        return out;
    }

    private static void assertMove(Move m, Kind kind, int x, int y, int z) {
        assertEquals(kind, m.kind());
        assertEquals(x + " " + y + " " + z, m.x() + " " + m.y() + " " + m.z());
    }

    @Test
    public void theDepthIsWhatTheReachAllows() {
        // survival's 4.5: the far cell's middle 4 from the eye
        assertEquals(4, PokePattern.depthFor(4.5));
        assertEquals(2, PokePattern.depthFor(3.0));
        assertEquals(4, PokePattern.depthFor(6.0));
        assertEquals(1, PokePattern.depthFor(1.0));
    }

    @Test
    public void leftOfAHeading() {
        // facing east the left is north, facing north it is west
        assertArrayEquals(new int[]{0, -1}, PokePattern.left(1, 0));
        assertArrayEquals(new int[]{-1, 0}, PokePattern.left(0, -1));
        assertArrayEquals(new int[]{1, 0}, PokePattern.left(0, 1));
    }

    @Test
    public void aStationDigsBothHolesAtHeadHeightThenFourSteps() {
        Run run = new Run(0, -17, 0, 1, 0, 4);
        List<Move> m = play(run, 12);
        // left (north) 1 to 4 deep at head height, from where the buddy stands
        for (int k = 1; k <= 4; k++) assertMove(m.get(k - 1), Kind.HOLE, 0, -16, -k);
        // then right (south)
        for (int k = 1; k <= 4; k++) assertMove(m.get(3 + k), Kind.HOLE, 0, -16, k);
        // then the tunnel's four steps east at the feet's height
        for (int s = 1; s <= 4; s++) assertMove(m.get(7 + s), Kind.STEP, s, -17, 0);
        // and the next station four on
        assertTrue(run.station());
        assertMove(run.next(), Kind.HOLE, 4, -16, -1);
    }

    @Test
    public void stepsBetweenStationsDigNoHoles() {
        Run run = new Run(10, -20, 5, 0, 1, 4);
        play(run, 8);
        assertFalse(run.station());
        assertFalse(run.stepped());
        assertEquals(10, run.x());
        assertEquals(6, run.z());
    }

    @Test
    public void aSkippedCellLeavesTheRestOfItsSide() {
        Run run = new Run(0, -17, 0, 1, 0, 4);
        run.holeDone();
        // the second cell of the left hole fails a check: the right hole's first cell is next
        run.skipSide(false);
        assertMove(run.next(), Kind.HOLE, 0, -16, 1);
        run.skipSide(false);
        // no cave: the leg goes on
        assertMove(run.next(), Kind.STEP, 1, -17, 0);
        assertFalse(run.connecting());
    }

    @Test
    public void aLegTurnsAfterSixtyFourStepsAndTheNextRunsTwelveOffTheOtherWay() {
        Run run = new Run(0, -17, 0, 1, 0, 4);
        // 17 stations of 8 holes and 64 steps
        play(run, 17 * 8 + 64);
        assertEquals(64, run.x());
        // the last station's holes done, the connector goes left of the first heading (north)
        assertTrue(run.connecting());
        assertMove(run.next(), Kind.STEP, 64, -17, -1);
        play(run, PokePattern.GAP);
        // the next leg, west, twelve north of the first; its first station four steps on
        assertFalse(run.connecting());
        assertEquals(-12, run.z());
        assertEquals(2, run.legs());
        assertMove(run.next(), Kind.STEP, 63, -17, -12);
        play(run, 4);
        assertTrue(run.station());
        assertMove(run.next(), Kind.HOLE, 60, -16, -11);
    }

    @Test
    public void theSerpentineKeepsItsSide() {
        Run run = new Run(0, -17, 0, 1, 0, 4);
        play(run, 8);
        assertEquals(Turn.TURNED, run.blocked());
        play(run, PokePattern.GAP);
        assertEquals(-12, run.z());
        // the second leg, west, closed too: the connector goes north again, not back south over the first leg
        assertEquals(Turn.TURNED, run.blocked());
        assertMove(run.next(), Kind.STEP, 0, -17, -13);
    }

    @Test
    public void aClosedWayTurnsIntoAConnectorThenItsOtherSideThenGivesUp() {
        Run run = new Run(0, -17, 0, 1, 0, 4);
        play(run, 8 + 2);
        assertEquals(2, run.x());
        assertEquals(Turn.TURNED, run.blocked());
        assertTrue(run.connecting());
        assertMove(run.next(), Kind.STEP, 2, -17, -1);
        // the connector's first cell closed: the other side
        assertEquals(Turn.TURNED, run.blocked());
        assertMove(run.next(), Kind.STEP, 2, -17, 1);
        // closed both ways: nothing left to turn to here
        assertEquals(Turn.RESET, run.blocked());
    }

    @Test
    public void aConnectorClosedPastHalfWayStartsTheNextLegThere() {
        Run run = new Run(0, -17, 0, 1, 0, 4);
        play(run, 8);
        run.blocked();
        play(run, PokePattern.MIN_GAP);
        assertEquals(Turn.TURNED, run.blocked());
        assertFalse(run.connecting());
        assertEquals(-1, run.dx());
        assertEquals(-PokePattern.MIN_GAP, run.z());
        // one short of it: nothing to turn to
        Run near = new Run(0, -17, 0, 1, 0, 4);
        play(near, 8);
        near.blocked();
        play(near, PokePattern.MIN_GAP - 1);
        assertEquals(Turn.RESET, near.blocked());
    }

    @Test
    public void aHoleThatMeetsACaveTurnsTheRunAwayFromIt() {
        Run run = new Run(0, -17, 0, 1, 0, 4);
        // the left (north) hole's second cell would open into a cave
        run.holeDone();
        run.skipSide(true);
        play(run, 4);
        // after the station: a connector south, away from the cave, not on along the cave
        assertTrue(run.connecting());
        assertMove(run.next(), Kind.STEP, 0, -17, 1);
        // a cave on the right sends it north
        Run right = new Run(0, -17, 0, 1, 0, 4);
        play(right, 4);
        right.skipSide(true);
        assertTrue(right.connecting());
        assertMove(right.next(), Kind.STEP, 0, -17, -1);
    }

    @Test
    public void thePatternShowsMoreRockPerBlockDugThanAStraightTunnel() {
        // cells whose face shows at some time (the dug ones among them), per block dug, over 64 steps
        int[][] start = {{0, 0, 0}, {0, 1, 0}};
        List<int[]> tunnel = new ArrayList<>();
        for (int a = 1; a <= 64; a++) {
            tunnel.add(new int[]{a, 0, 0});
            tunnel.add(new int[]{a, 1, 0});
        }
        Run run = new Run(0, 0, 0, 1, 0, 4);
        List<int[]> pattern = new ArrayList<>();
        while (run.x() < 64 || run.station()) {
            Move m = run.next();
            if (m.kind() == Kind.HOLE) {
                pattern.add(new int[]{m.x(), m.y(), m.z()});
                run.holeDone();
            } else {
                pattern.add(new int[]{m.x(), m.y(), m.z()});
                pattern.add(new int[]{m.x(), m.y() + 1, m.z()});
                run.stepped();
            }
        }
        double t = shown(tunnel, start) / (double) tunnel.size();
        double p = shown(pattern, start) / (double) pattern.size();
        assertEquals(4.0, t, 0.01);
        assertTrue("pattern " + p, p > 4.1 && p < 4.2);
        // and the holes reach rock five to each side, where the tunnel shows one
        int reach = 0;
        for (int[] c : pattern) reach = Math.max(reach, Math.abs(c[2]) + 1);
        assertEquals(5, reach);
    }

    private static int shown(List<int[]> dug, int[][] start) {
        Set<String> base = new HashSet<>();
        for (int[] c : start) {
            base.add(key(c[0], c[1], c[2]));
            for (int[] n : neighbours(c)) base.add(key(n[0], n[1], n[2]));
        }
        Set<String> seen = new HashSet<>();
        for (int[] c : dug) {
            seen.add(key(c[0], c[1], c[2]));
            for (int[] n : neighbours(c)) seen.add(key(n[0], n[1], n[2]));
        }
        seen.removeAll(base);
        return seen.size();
    }

    private static int[][] neighbours(int[] c) {
        return new int[][]{{c[0] + 1, c[1], c[2]}, {c[0] - 1, c[1], c[2]}, {c[0], c[1] + 1, c[2]}, {c[0], c[1] - 1, c[2]},
                {c[0], c[1], c[2] + 1}, {c[0], c[1], c[2] - 1}};
    }

    private static String key(int x, int y, int z) {
        return x + "," + y + "," + z;
    }
}
