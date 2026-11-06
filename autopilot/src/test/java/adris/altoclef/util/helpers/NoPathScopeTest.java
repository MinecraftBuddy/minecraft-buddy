package adris.altoclef.util.helpers;

import adris.altoclef.util.helpers.NoPathScope.Verdict;
import net.minecraft.core.BlockPos;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NoPathScopeTest {
    private static final BlockPos T = new BlockPos(10, 20, 30);

    // ---- cells

    @Test
    public void aTimedOutSearchBansOnlyTheBlock() {
        List<BlockPos> known = List.of(T, T.east(), T.below());
        assertEquals(List.of(T), NoPathScope.cells(T, known, Verdict.TIMED_OUT, true));
    }

    @Test
    public void anExhaustedSearchTakesTheConnectedVein() {
        // T - east - east2 in a row, and a corner touch down and across
        BlockPos e = T.east();
        BlockPos e2 = T.east(2);
        BlockPos corner = e2.below().south();
        List<BlockPos> out = NoPathScope.cells(T, List.of(T, e, e2, corner), Verdict.EXHAUSTED, true);
        assertEquals(T, out.get(0));
        assertEquals(4, out.size());
        assertTrue(out.containsAll(List.of(e, e2, corner)));
    }

    @Test
    public void aGapBreaksTheVein() {
        BlockPos near = T.east();
        // two blocks away from everything, same type, a different vein
        BlockPos otherVein = T.east(5);
        List<BlockPos> out = NoPathScope.cells(T, List.of(T, near, otherVein), Verdict.EXHAUSTED, true);
        assertEquals(List.of(T, near), out);
    }

    @Test
    public void veinsOffMeansJustTheBlock() {
        List<BlockPos> out = NoPathScope.cells(T, List.of(T, T.east()), Verdict.EXHAUSTED, false);
        assertEquals(List.of(T), out);
    }

    @Test
    public void theTargetIsInEvenIfTheTrackerDoesNotKnowIt() {
        List<BlockPos> out = NoPathScope.cells(T, List.of(T.above()), Verdict.EXHAUSTED, true);
        assertEquals(List.of(T, T.above()), out);
    }

    @Test
    public void aHugeVeinStopsAtTheCap() {
        List<BlockPos> wall = new ArrayList<>();
        for (int x = 0; x < 20; x++) {
            for (int y = 0; y < 20; y++) {
                wall.add(T.offset(x, y, 0));
            }
        }
        List<BlockPos> out = NoPathScope.cells(T, wall, Verdict.EXHAUSTED, true);
        assertEquals(NoPathScope.VEIN_CAP, out.size());
        assertEquals(T, out.get(0));
        assertEquals(out.size(), out.stream().distinct().count());
    }

    @Test
    public void closerBlocksComeBeforeFarOnes() {
        List<BlockPos> line = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            line.add(T.east(i));
        }
        List<BlockPos> out = NoPathScope.cells(T, line, Verdict.EXHAUSTED, true);
        // a walk out from the block, so the cap eats the far end of the vein
        assertEquals(T.east(NoPathScope.VEIN_CAP - 1), out.get(out.size() - 1));
    }

    // ---- the watch

    @Test
    public void quietUntilAFailureLandsAfterArming() {
        NoPathScope.Watch w = new NoPathScope.Watch();
        assertFalse(w.armed());
        assertEquals(Verdict.NONE, w.poll(5, 2, false));
        w.arm(5, 2);
        assertTrue(w.armed());
        assertEquals(Verdict.NONE, w.poll(5, 2, false));
    }

    @Test
    public void aTimeoutIsNotAnExhaustedSearch() {
        NoPathScope.Watch w = new NoPathScope.Watch();
        w.arm(5, 2);
        // a failure with the exhausted count unmoved = ran out of time
        assertEquals(Verdict.TIMED_OUT, w.poll(6, 2, false));
        // once per failure
        assertEquals(Verdict.NONE, w.poll(6, 2, false));
    }

    @Test
    public void anExhaustedFailureSaysSo() {
        NoPathScope.Watch w = new NoPathScope.Watch();
        w.arm(5, 2);
        assertEquals(Verdict.EXHAUSTED, w.poll(6, 3, false));
        assertEquals(Verdict.NONE, w.poll(6, 3, false));
        // and the next one is judged on its own
        assertEquals(Verdict.TIMED_OUT, w.poll(7, 3, false));
    }

    @Test
    public void aFailureWhileWalkingAPathIsAnEarlierAsk() {
        NoPathScope.Watch w = new NoPathScope.Watch();
        w.arm(5, 2);
        assertEquals(Verdict.NONE, w.poll(6, 3, true));
        // and it is spent, it doesn't fire when the path ends
        assertEquals(Verdict.NONE, w.poll(6, 3, false));
    }

    @Test
    public void disarmedIsQuietUntilArmedAgain() {
        NoPathScope.Watch w = new NoPathScope.Watch();
        w.arm(1, 0);
        w.disarm();
        assertFalse(w.armed());
        assertEquals(Verdict.NONE, w.poll(9, 9, false));
        w.arm(9, 9);
        assertEquals(Verdict.TIMED_OUT, w.poll(10, 9, false));
    }

    @Test
    public void aFarTimedOutSearchGetsTheLongBanAtOnce() {
        assertEquals(0, NoPathScope.strikesAllowed(NoPathScope.Verdict.TIMED_OUT, 200 * 200));
        // near, or a search that proved it, keeps the two strikes
        assertEquals(2, NoPathScope.strikesAllowed(NoPathScope.Verdict.TIMED_OUT, 30 * 30));
        assertEquals(2, NoPathScope.strikesAllowed(NoPathScope.Verdict.EXHAUSTED, 200 * 200));
    }
}
