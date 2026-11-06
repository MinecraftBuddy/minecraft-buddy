package adris.altoclef.tasks.construction;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalNear;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// closing in on a block whose open sides are all used up (or never existed): dig into a side instead of standing there
public class DestroyBlockCloseInTest {

    private static final BlockPos SAND = new BlockPos(0, 64, 0);
    // where GoalMineFromSide let us stop with no ray to it
    private static final BlockPos DIAGONAL = SAND.north().east();
    private static final Predicate<BlockPos> ANY = p -> true;

    private static final class MapGetter implements BlockGetter {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();

        MapGetter set(BlockPos pos, BlockState state) {
            blocks.put(pos.immutable(), state);
            return this;
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return Fluids.EMPTY.defaultFluidState();
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinY() {
            return -64;
        }
    }

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    // sand with sand on every face, the dune case
    private static MapGetter dune() {
        MapGetter w = new MapGetter().set(SAND, Blocks.SAND.defaultBlockState());
        for (Direction side : Direction.values()) {
            w.set(SAND.relative(side), Blocks.SAND.defaultBlockState());
        }
        return w;
    }

    private static Goal closeIn(MapGetter w, Set<BlockPos> tried, boolean sidesOnly, Predicate<BlockPos> mayDig) {
        return DestroyBlockTask.pickGoal(w, SAND, true, tried, sidesOnly, mayDig);
    }

    @Test
    public void everyFaceSolidMeansDigIntoASide() {
        Goal goal = closeIn(dune(), Set.of(DIAGONAL), true, ANY);
        assertTrue(goal instanceof GoalComposite);
        for (Direction side : Direction.Plane.HORIZONTAL) {
            assertTrue(side.toString(), goal.isInGoal(SAND.relative(side)));
        }
    }

    @Test
    public void neverTheTargetItselfOrItsColumn() {
        // standing where the sand was is standing where the rest of the stack lands
        Goal goal = closeIn(dune(), Set.of(DIAGONAL), true, ANY);
        assertFalse(goal.isInGoal(SAND));
        assertFalse(goal.isInGoal(SAND.above()));
        assertFalse(goal.isInGoal(SAND.below()));
        assertFalse(goal.isInGoal(SAND.below(2)));
        assertFalse(goal.isInGoal(DIAGONAL));
    }

    @Test
    public void notTheTopEvenForAPlainBlock() {
        Goal goal = closeIn(dune(), Set.of(DIAGONAL), false, ANY);
        assertFalse(goal.isInGoal(SAND.above()));
        assertTrue(goal.isInGoal(SAND.west()));
    }

    @Test
    public void triedCellsAreSkippedForDiggingToo() {
        Goal goal = closeIn(dune(), Set.of(DIAGONAL, SAND.west(), SAND.south()), true, ANY);
        assertFalse(goal.isInGoal(SAND.west()));
        assertFalse(goal.isInGoal(SAND.south()));
        assertTrue(goal.isInGoal(SAND.east()));
        assertTrue(goal.isInGoal(SAND.north()));
    }

    @Test
    public void allFourTriedIsTheOldGoal() {
        Set<BlockPos> tried = Set.of(DIAGONAL, SAND.west(), SAND.east(), SAND.north(), SAND.south());
        assertTrue(closeIn(dune(), tried, true, ANY) instanceof GoalNear);
    }

    @Test
    public void noDiggingAllowedIsTheOldGoal() {
        // allowBreak off (or a protected frame): nothing to hand over, the progress checker bans it like before
        assertTrue(closeIn(dune(), Set.of(DIAGONAL), true, p -> false) instanceof GoalNear);
        assertTrue(closeIn(dune(), Set.of(DIAGONAL), true, null) instanceof GoalNear);
    }

    @Test
    public void theRulesGoCellByCell() {
        Goal goal = closeIn(dune(), Set.of(DIAGONAL), true, p -> !p.equals(SAND.north()));
        assertFalse(goal.isInGoal(SAND.north()));
        assertTrue(goal.isInGoal(SAND.south()));
    }

    @Test
    public void inThePlainGoalWithNoRayIsTheCueToCloseIn() {
        // iron in a wall: GoalNear(pos, 1) is happy beside it, the ray isn't
        Goal plain = new GoalNear(SAND, 1);
        assertTrue(DestroyBlockTask.arrivedBlind(plain, SAND.west(), false));
        // a ray means we're about to swing, not stuck
        assertFalse(DestroyBlockTask.arrivedBlind(plain, SAND.west(), true));
        // not there yet is the pather's job
        assertFalse(DestroyBlockTask.arrivedBlind(plain, SAND.west(3), false));
    }

    @Test
    public void closingInFromThePlainGoalLeavesTheCellWeAreIn() {
        BlockPos feet = SAND.west();
        MapGetter w = dune().set(feet, Blocks.AIR.defaultBlockState()).set(feet.above(), Blocks.AIR.defaultBlockState());
        assertTrue(DestroyBlockTask.arrivedBlind(new GoalNear(SAND, 1), feet, false));
        // the one open face is the one we're in, so the next goal digs into another side
        Goal next = closeIn(w, Set.of(feet), false, ANY);
        assertFalse(next.isInGoal(feet));
        assertTrue(next.isInGoal(SAND.east()));
    }

    @Test
    public void anOpenSideStillBeatsDigging() {
        MapGetter w = dune().set(SAND.east(), Blocks.AIR.defaultBlockState());
        Goal goal = closeIn(w, Set.of(DIAGONAL), true, ANY);
        assertTrue(goal.isInGoal(SAND.east()));
        assertFalse(goal.isInGoal(SAND.west()));
    }
}
