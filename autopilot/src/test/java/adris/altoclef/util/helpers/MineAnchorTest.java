package adris.altoclef.util.helpers;

import net.minecraft.core.BlockPos;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MineAnchorTest {
    private static final BlockPos ANCHOR = new BlockPos(-48, 68, 89);

    @Test
    public void aTrunkGoesUpAndABoxIsEvenAroundTheAnchor() {
        assertTrue(MineAnchor.near(ANCHOR, ANCHOR.above(), true));
        assertTrue(MineAnchor.near(ANCHOR, ANCHOR.above(MineAnchor.LOG_UP), true));
        assertFalse(MineAnchor.near(ANCHOR, ANCHOR.above(MineAnchor.LOG_UP + 1), true));
        assertTrue(MineAnchor.near(ANCHOR, ANCHOR.below(MineAnchor.LOG_DOWN), true));
        assertFalse(MineAnchor.near(ANCHOR, ANCHOR.below(MineAnchor.LOG_DOWN + 1), true));
        // not a tree: a vein bends the same way in every direction
        assertTrue(MineAnchor.near(ANCHOR, ANCHOR.below(MineAnchor.AROUND), false));
        assertFalse(MineAnchor.near(ANCHOR, ANCHOR.above(MineAnchor.AROUND + 1), false));
        assertFalse(MineAnchor.near(ANCHOR, ANCHOR.below(MineAnchor.AROUND + 1), false));
    }

    @Test
    public void sidewaysIsTwoEitherWay() {
        assertTrue(MineAnchor.near(ANCHOR, ANCHOR.east(2).north(2), true));
        assertFalse(MineAnchor.near(ANCHOR, ANCHOR.east(3), true));
        assertFalse(MineAnchor.near(ANCHOR, ANCHOR.south(3), false));
    }

    @Test
    public void anotherTreeFiveBlocksAwayIsNotInTheRunning() {
        // we stand next to the trunk. the other trees are 5 over, outside the anchor's neighbourhood
        List<BlockPos> known = List.of(ANCHOR.above(), ANCHOR.east(5), ANCHOR.west(5).above());
        Optional<BlockPos> pick = MineAnchor.nearest(ANCHOR, known, true, -47.5, 68.0, 89.5, p -> true);
        assertEquals(ANCHOR.above(), pick.orElseThrow());
    }

    @Test
    public void behindAndAboveAreBothTheSameTree() {
        // the log 1 above and the log 1 behind us: plain distance picks whichever is nearer, not whichever is "cheaper"
        BlockPos above = ANCHOR.above();
        BlockPos behind = ANCHOR.west();
        Optional<BlockPos> pick = MineAnchor.nearest(ANCHOR, List.of(behind, above), true, -47.5, 69.0, 89.5, p -> true);
        assertEquals(above, pick.orElseThrow());
    }

    @Test
    public void unusableBlocksAreSkippedAndNothingLeftMeansEmpty() {
        BlockPos up = ANCHOR.above();
        BlockPos up2 = ANCHOR.above(2);
        List<BlockPos> known = List.of(up, up2);
        assertEquals(up2, MineAnchor.nearest(ANCHOR, known, true, -47.5, 68.0, 89.5, p -> !p.equals(up)).orElseThrow());
        assertTrue(MineAnchor.nearest(ANCHOR, known, true, -47.5, 68.0, 89.5, p -> false).isEmpty());
        assertTrue(MineAnchor.nearest(ANCHOR, List.of(), true, 0, 0, 0, p -> true).isEmpty());
    }

    @Test
    public void theValidityCheckIsOnlyAskedAboutTheNeighbourhood() {
        List<BlockPos> asked = new ArrayList<>();
        List<BlockPos> known = List.of(ANCHOR.above(), ANCHOR.east(40));
        MineAnchor.nearest(ANCHOR, known, true, -47.5, 68.0, 89.5, p -> {
            asked.add(p);
            return true;
        });
        assertEquals(List.of(ANCHOR.above()), asked);
    }

    @Test
    public void aTieGoesToTheBlockNearerTheAnchorNotTheCacheOrder() {
        // x = -47 is exactly 1.5 from the center of both (-45.5 and -48.5), but one of them is next to the anchor
        BlockPos far = ANCHOR.east(2);
        BlockPos close = ANCHOR.west();
        assertEquals(close, MineAnchor.nearest(ANCHOR, List.of(far, close), false, -47.0, 68.5, 89.5, p -> true).orElseThrow());
        assertEquals(close, MineAnchor.nearest(ANCHOR, List.of(close, far), false, -47.0, 68.5, 89.5, p -> true).orElseThrow());
    }

    @Test
    public void plainDistanceDoesNotThinkBelowIsFree() {
        // baritone's heuristic made the log under us nearly free. squared distance is symmetric
        assertEquals(MineAnchor.distSq(0, 0, 0, 0, 3, 0), MineAnchor.distSq(0, 0, 0, 0, -3, 0), 0.0);
    }
}
