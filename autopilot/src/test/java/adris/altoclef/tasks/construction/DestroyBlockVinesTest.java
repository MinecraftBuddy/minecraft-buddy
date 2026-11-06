package adris.altoclef.tasks.construction;

import adris.altoclef.util.baritone.GoalReachBlock;
import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DestroyBlockVinesTest {

    private static final BlockPos LOG = new BlockPos(0, 64, 0);

    // just enough world to throw a ray through
    private static final class MapGetter implements BlockGetter {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();

        void set(BlockPos pos, BlockState state) {
            blocks.put(pos.immutable(), state);
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

    private static BlockState vineOn(Direction attached) {
        return Blocks.VINE.defaultBlockState().setValue(VineBlock.PROPERTY_BY_DIRECTION.get(attached), true);
    }

    private static MapGetter trunk() {
        MapGetter w = new MapGetter();
        for (int y = 60; y <= 72; y++) {
            w.set(new BlockPos(0, y, 0), Blocks.JUNGLE_LOG.defaultBlockState());
        }
        return w;
    }

    // eyes of someone standing on the ground three blocks east of the trunk
    private static final Vec3 EYE = new Vec3(3.5, 63 + 1.62, 0.5);

    @Test
    public void plainLogGetsTheOldGoal() {
        Goal goal = DestroyBlockTask.pickGoal(trunk(), LOG, false);
        assertTrue(goal instanceof GoalNear);
    }

    @Test
    public void logWithVinesOnItGetsTheReachGoal() {
        MapGetter w = trunk();
        w.set(LOG.east(), vineOn(Direction.WEST));
        assertTrue(DestroyBlockTask.pickGoal(w, LOG, false) instanceof GoalReachBlock);
        // vines a block off to the side count too, they're what the planner climbs into
        MapGetter above = trunk();
        above.set(LOG.above().south(), vineOn(Direction.NORTH));
        assertTrue(DestroyBlockTask.pickGoal(above, LOG.above(), false) instanceof GoalReachBlock);
    }

    @Test
    public void closingInFallsBackToTouching() {
        MapGetter w = trunk();
        w.set(LOG.east(), vineOn(Direction.WEST));
        // nothing to step off of (no feet given) is the plain touching goal
        assertTrue(DestroyBlockTask.pickGoal(w, LOG, true) instanceof GoalNear);
        // far from the log: any cell touching it will do
        Goal far = DestroyBlockTask.pickGoal(w, LOG, true, Set.of(new BlockPos(9, 64, 9)));
        assertTrue(far.isInGoal(LOG.west()));
        assertTrue(far.isInGoal(LOG.east()));
    }

    @Test
    public void closingInFromBesideItMeansAnotherCell() {
        MapGetter w = trunk();
        w.set(LOG.east(), vineOn(Direction.WEST));
        BlockPos feet = LOG.west();
        Goal goal = DestroyBlockTask.pickGoal(w, LOG, true, Set.of(feet));
        // the cell we're already in must not count as arrived, that's the whole stall
        assertFalse(goal.isInGoal(feet));
        assertTrue(goal.isInGoal(LOG.north()));
        assertTrue(goal.isInGoal(LOG.south()));
        assertTrue(goal.isInGoal(LOG.east()));
    }

    @Test
    public void closingInSkipsCellsYouCantStandIn() {
        MapGetter w = trunk();
        w.set(LOG.north(), Blocks.STONE.defaultBlockState());
        Goal goal = DestroyBlockTask.pickGoal(w, LOG, true, Set.of(LOG.west()));
        assertFalse(goal.isInGoal(LOG.north()));
        assertTrue(goal.isInGoal(LOG.south()));
    }

    @Test
    public void closingInNeverWantsTheCellUnderIt() {
        // our head would be in the block
        MapGetter w = new MapGetter();
        w.set(LOG, Blocks.JUNGLE_LOG.defaultBlockState());
        Goal goal = DestroyBlockTask.pickGoal(w, LOG, true, Set.of(LOG.west()));
        assertFalse(goal.isInGoal(LOG.below()));
        assertTrue(goal.isInGoal(LOG.above()));
    }

    @Test
    public void aBlockMinedFromTheSideIsNeverClosedInOnFromOnTop() {
        MapGetter w = new MapGetter();
        w.set(LOG, Blocks.JUNGLE_LOG.defaultBlockState());
        Goal goal = DestroyBlockTask.pickGoal(w, LOG, true, Set.of(LOG.west()), true);
        assertFalse(goal.isInGoal(LOG.above()));
        assertFalse(goal.isInGoal(LOG.below()));
        assertTrue(goal.isInGoal(LOG.east()));
    }

    @Test
    public void boxedInFallsBackToTheOldGoal() {
        MapGetter w = trunk();
        for (Direction side : Direction.values()) {
            if (side != Direction.WEST) {
                w.set(LOG.relative(side), Blocks.STONE.defaultBlockState());
            }
        }
        assertTrue(DestroyBlockTask.pickGoal(w, LOG, true, Set.of(LOG.west())) instanceof GoalNear);
    }

    @Test
    public void everyCellTriedMeansTheOldGoalNotAShuffle() {
        MapGetter w = trunk();
        w.set(LOG.north(), Blocks.STONE.defaultBlockState());
        w.set(LOG.south(), Blocks.STONE.defaultBlockState());
        // west and east are all that's left, and we've stood in both
        assertTrue(DestroyBlockTask.pickGoal(w, LOG, true, Set.of(LOG.west())).isInGoal(LOG.east()));
        assertTrue(DestroyBlockTask.pickGoal(w, LOG, true, Set.of(LOG.west(), LOG.east())) instanceof GoalNear);
    }

    @Test
    public void feetOnlyMattersWhenClosingIn() {
        MapGetter w = trunk();
        assertTrue(DestroyBlockTask.pickGoal(w, LOG, false, Set.of(LOG.west())) instanceof GoalNear);
    }

    @Test
    public void snowOnTopStillMeansStandOnIt() {
        MapGetter w = trunk();
        w.set(LOG.east(), vineOn(Direction.WEST));
        w.set(LOG.above(), Blocks.SNOW.defaultBlockState());
        assertTrue(DestroyBlockTask.pickGoal(w, LOG, false) instanceof GoalBlock);
    }

    @Test
    public void aVineInFrontOfTheLogIsTheOneToBreak() {
        MapGetter w = trunk();
        w.set(LOG.east(), vineOn(Direction.WEST));
        assertEquals(LOG.east(), DestroyBlockTask.clearableInTheWay(w, EYE, LOG, 4.5));
    }

    @Test
    public void nothingInTheWayMeansNothingToBreak() {
        assertNull(DestroyBlockTask.clearableInTheWay(trunk(), EYE, LOG, 4.5));
    }

    @Test
    public void aVineOutOfReachIsLeftAlone() {
        MapGetter w = trunk();
        w.set(LOG.east(), vineOn(Direction.WEST));
        assertNull(DestroyBlockTask.clearableInTheWay(w, EYE, LOG, 2.0));
    }

    @Test
    public void onlyVinesAreOursToBreak() {
        // leaves in the way (or any other block) aren't something to clear on the way to a log
        MapGetter w = trunk();
        w.set(LOG.east(), Blocks.JUNGLE_LEAVES.defaultBlockState());
        assertNull(DestroyBlockTask.clearableInTheWay(w, EYE, LOG, 4.5));
    }

    @Test
    public void ofTwoVinesTheNearerFirst() {
        MapGetter w = trunk();
        w.set(LOG.east(), vineOn(Direction.WEST));
        w.set(LOG.east(2), vineOn(Direction.WEST));
        assertEquals(LOG.east(2), DestroyBlockTask.clearableInTheWay(w, EYE, LOG, 4.5));
    }

    private static BlockPos firstHit(BlockGetter w, Vec3 eye, Vec3 to) {
        return w.clip(new ClipContext(eye, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty())).getBlockPos();
    }

    @Test
    public void looseStuffInFrontIsOursToBreak() {
        for (Block loose : new Block[]{Blocks.GRAVEL, Blocks.SAND, Blocks.RED_SAND, Blocks.CONCRETE_POWDER.white(), Blocks.SUSPICIOUS_GRAVEL, Blocks.SUSPICIOUS_SAND}) {
            MapGetter w = trunk();
            w.set(LOG.east(), loose.defaultBlockState());
            assertEquals(loose.toString(), LOG.east(), DestroyBlockTask.clearableInTheWay(w, EYE, LOG, 4.5));
        }
    }

    @Test
    public void anvilsEggsAndWallsAreNot() {
        for (Block block : new Block[]{Blocks.ANVIL, Blocks.DRAGON_EGG, Blocks.STONE, Blocks.NETHERRACK}) {
            MapGetter w = trunk();
            w.set(LOG.east(), block.defaultBlockState());
            // the ray really does stop on it, or this would pass for the wrong reason
            assertEquals(block.toString(), LOG.east(), firstHit(w, EYE, Vec3.atCenterOf(LOG)));
            assertNull(block.toString(), DestroyBlockTask.clearableInTheWay(w, EYE, LOG, 4.5));
        }
    }

    @Test
    public void gravelOutOfReachIsLeftAlone() {
        MapGetter w = trunk();
        w.set(LOG.east(), Blocks.GRAVEL.defaultBlockState());
        // its face is 1.5 out
        assertNull(DestroyBlockTask.clearableInTheWay(w, EYE, LOG, 1.4));
    }

    @Test
    public void gravelInFrontOfAFarBlockIsLeftAlone() {
        MapGetter w = trunk();
        w.set(LOG.east(), Blocks.GRAVEL.defaultBlockState());
        // the gravel is in reach, the log (3 out) is not. walking up is the answer, not digging
        assertNull(DestroyBlockTask.clearableInTheWay(w, EYE, LOG, 2.0));
        assertEquals(LOG.east(), DestroyBlockTask.clearableInTheWay(w, EYE, LOG, 3.1));
    }

    @Test
    public void aVineInFrontOfAFarLogStaysUpUntilTheLogIsInReach() {
        // same question as the gravel: the vine is in reach, the log (3 out) is not. we can walk through it and climb it
        MapGetter w = trunk();
        w.set(LOG.east(), vineOn(Direction.WEST));
        assertNull(DestroyBlockTask.clearableInTheWay(w, EYE, LOG, 2.5));
        assertEquals(LOG.east(), DestroyBlockTask.clearableInTheWay(w, EYE, LOG, 3.1));
    }

    @Test
    public void neverTheFloor() {
        // standing on the block at 0 64 0, feet at y 65
        AABB box = new AABB(0.2, 65, 0.2, 0.8, 66.8, 0.8);
        assertTrue(DestroyBlockTask.underBox(LOG, box));
        assertTrue(DestroyBlockTask.underBox(LOG.below(), box));
        // half over the edge counts too
        AABB edge = new AABB(0.7, 65, 0.2, 1.3, 66.8, 0.8);
        assertTrue(DestroyBlockTask.underBox(LOG.east(), edge));
        assertTrue(DestroyBlockTask.underBox(LOG, edge));
        // beside us or at our feet is not under us
        assertFalse(DestroyBlockTask.underBox(LOG.east(), box));
        assertFalse(DestroyBlockTask.underBox(LOG.above(), box));
    }

    @Test
    public void gravelOnlyInTheWayOfAFaceStillCounts() {
        MapGetter w = new MapGetter();
        w.set(LOG, Blocks.JUNGLE_LOG.defaultBlockState());
        w.set(LOG.east(), Blocks.STONE.defaultBlockState());
        w.set(LOG.above(), Blocks.GRAVEL.defaultBlockState());
        // a block higher up: the middle ray dips into the stone, the one to the top face runs over it into the gravel
        Vec3 eye = new Vec3(3.5, 64 + 1.62, 0.5);
        assertEquals(LOG.east(), firstHit(w, eye, Vec3.atCenterOf(LOG)));
        assertEquals(LOG.above(), DestroyBlockTask.clearableInTheWay(w, eye, LOG, 4.5));
    }
}
