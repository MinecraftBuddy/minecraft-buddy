package adris.altoclef.tasks.movement;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.BeforeClass;
import org.junit.Test;

// (Tuning tunnel_flip_guard) the test heel's tunnel flip: a tunnel dug north at y 16 to A, water two cells past A and beside
// both cells, and the step went from A back to B and from B back to A, 1042 times in 322 s
public class TunnelFlipTest {
    private static BlockState stone, air, water;
    // the tunnel dug north (z down): D, C, B, A
    private static final BlockPos A = new BlockPos(0, 16, 0), B = new BlockPos(0, 16, 1), C = new BlockPos(0, 16, 2), D = new BlockPos(0, 16, 3);
    private static Function<BlockPos, BlockState> world;

    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        stone = Blocks.STONE.defaultBlockState();
        air = Blocks.AIR.defaultBlockState();
        water = Blocks.WATER.defaultBlockState();
        Map<BlockPos, BlockState> m = new HashMap<>();
        for (BlockPos p : new BlockPos[]{A, B, C, D}) {
            m.put(p, air);
            m.put(p.above(), air);
        }
        m.put(new BlockPos(0, 16, -2), water);
        for (int x : new int[]{-2, 2}) {
            m.put(new BlockPos(x, 16, 0), water);
            m.put(new BlockPos(x, 16, 1), water);
        }
        world = p -> m.getOrDefault(p, stone);
    }

    @Test
    public void onByDefault() {
        assertTrue(BranchMineTask.flipGuardOn());
    }

    @Test
    public void theLoopAsItWas() {
        // at A going north: water ahead and on both sides, the tunnel behind is open: half round
        int[] atA = BranchMineTask.wayRanks(world, A, 0, -1, 0);
        assertArrayEquals(new int[]{BranchMineTask.WAY_WET, BranchMineTask.WAY_WET, BranchMineTask.WAY_OPEN, BranchMineTask.WAY_WET}, atA);
        assertEquals(2, BranchMineTask.pickWay(atA, new boolean[4]));
        // at B going south: back to A is the only closed way, the water a cell past what the step looks at: half round again
        int[] atB = BranchMineTask.wayRanks(world, B, 0, 1, 0);
        assertArrayEquals(new int[]{BranchMineTask.WAY_OPEN, BranchMineTask.WAY_WET, BranchMineTask.WAY_CLOSED, BranchMineTask.WAY_WET}, atB);
        assertEquals(2, BranchMineTask.pickWay(atB, new boolean[4]));
    }

    @Test
    public void theStepRefusedAgainstWaterIsNotTakenFromB() {
        TunnelFlip flip = new TunnelFlip();
        int[] atA = BranchMineTask.wayRanks(world, A, 0, -1, 0);
        int pick = BranchMineTask.pickWay(atA, new boolean[4]);
        assertTrue(TunnelFlip.refusesWater(atA, pick));
        flip.refuse(A.offset(0, 0, -1), 1000);
        assertArrayEquals(new int[]{0, 16, -1}, flip.refused(1000));
        int[] atB = BranchMineTask.wayRanks(world, B, 0, 1, 0);
        boolean[] never = TunnelFlip.either(BranchMineTask.wayNever(B, 0, 1, 0, null), BranchMineTask.wayNever(B, 0, 1, 0, flip.refused(1010)));
        assertArrayEquals(new boolean[]{false, false, true, false}, never);
        // on along the tunnel, not back to A
        assertEquals(0, BranchMineTask.pickWay(atB, never));
        assertFalse(TunnelFlip.refusesWater(atB, 0));
    }

    @Test
    public void onlyAHalfTurnFromWaterRefuses() {
        assertTrue(TunnelFlip.refusesWater(new int[]{2, 2, 1, 2}, 2));
        assertFalse(TunnelFlip.refusesWater(new int[]{1, 2, 0, 2}, 2));
        assertFalse(TunnelFlip.refusesWater(new int[]{2, 0, 1, 2}, 1));
        assertFalse(TunnelFlip.refusesWater(new int[]{2, 2, 2, 2}, 0));
    }

    @Test
    public void theRefusedStepIsKeptAGameMinute() {
        TunnelFlip flip = new TunnelFlip();
        // never refused: nothing kept (a clock that starts at Long.MIN_VALUE)
        assertNull(flip.refused(0));
        assertNull(flip.refused(5_000_000));
        flip.refuse(new BlockPos(3, 16, -4), 1000);
        assertArrayEquals(new int[]{3, 16, -4}, flip.refused(1000));
        assertArrayEquals(new int[]{3, 16, -4}, flip.refused(1000 + TunnelFlip.KEEP - 1));
        assertNull(flip.refused(1000 + TunnelFlip.KEEP));
        // the clock went back: not kept
        assertNull(flip.refused(999));
    }

    @Test
    public void aToBToAIsAFire() {
        TunnelFlip flip = new TunnelFlip();
        // dug north: D to C to B to A, each reached
        assertFalse(flip.step(D, C, false));
        assertFalse(flip.step(C, B, true));
        assertFalse(flip.step(B, A, true));
        // A back to B: a dead end, one step back, no fire
        assertFalse(flip.step(A, B, true));
        // B back to A: the second in a row
        assertTrue(flip.step(B, A, true));
    }

    @Test
    public void oneStepBackThenOnIsNoFire() {
        TunnelFlip flip = new TunnelFlip();
        assertFalse(flip.step(C, B, false));
        assertFalse(flip.step(B, A, true));
        assertFalse(flip.step(A, B, true));
        // on south from B, past where it came from
        assertFalse(flip.step(B, C, true));
        assertFalse(flip.step(C, D, true));
    }

    @Test
    public void aStepNotReachedBreaksTheRow() {
        TunnelFlip flip = new TunnelFlip();
        assertFalse(flip.step(C, B, false));
        assertFalse(flip.step(B, A, true));
        assertFalse(flip.step(A, B, true));
        // the step to B was made anew (a turn after ten seconds, a fire): no row
        assertFalse(flip.step(B, A, false));
        assertFalse(flip.step(A, B, true));
        assertTrue(flip.step(B, A, true));
    }
}
