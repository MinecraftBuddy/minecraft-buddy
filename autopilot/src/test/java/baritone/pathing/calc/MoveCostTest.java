/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.pathing.calc;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalYLevel;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.movements.MovementAscend;
import baritone.pathing.movement.movements.MovementDescend;
import baritone.pathing.movement.movements.MovementDiagonal;
import baritone.pathing.movement.movements.MovementDownward;
import baritone.pathing.movement.movements.MovementTraverse;
import baritone.utils.BlockStateInterface;
import baritone.utils.ExperimentalMovement;
import baritone.utils.ToolSet;
import baritone.utils.pathing.MutableMoveResult;
import baritone.utils.pathing.BetterWorldBorder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.level.border.WorldBorder;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static baritone.api.pathing.movement.ActionCosts.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// what the planner charges for a move against how long the move really takes. each test says where the real number
// comes from. same fake world as FallingCostTest, and the same skip when there's no shadow BaritoneAPI around
public class MoveCostTest {

    private static final class MapWorld extends BlockStateInterface {
        final Map<Long, BlockState> blocks = new HashMap<>();

        MapWorld() {
            super(new BetterWorldBorder(new WorldBorder()), -64, 384);
        }

        void set(int x, int y, int z, BlockState s) {
            blocks.put(BlockPos.asLong(x, y, z), s);
        }

        @Override
        protected BlockState getUncached(int x, int y, int z) {
            // y comes in shifted by -minY
            return blocks.getOrDefault(BlockPos.asLong(x, y - 64, z), Blocks.AIR.defaultBlockState());
        }

        @Override
        public boolean isLoaded(int x, int z) {
            return true;
        }

        @Override
        public boolean worldContainsLoadedChunk(int x, int z) {
            return true;
        }
    }

    private static class Ctx extends CalculationContext {
        Ctx(BlockStateInterface bsi) {
            this(bsi, 0.0f);
        }

        // waterEfficiency is what Depth Strider adds up to, 0 for none and 1 for level three
        Ctx(BlockStateInterface bsi, float waterEfficiency) {
            this(bsi, waterEfficiency, 0);
        }

        Ctx(BlockStateInterface bsi, float waterEfficiency, int soulSpeed) {
            this(bsi, waterEfficiency, soulSpeed, false);
        }

        Ctx(BlockStateInterface bsi, boolean waterBucket) {
            this(bsi, 0.0f, 0, waterBucket);
        }

        Ctx(BlockStateInterface bsi, float waterEfficiency, int soulSpeed, boolean waterBucket) {
            super(null, true, null, null, bsi, new ToolSet(null) {
                @Override
                public double getStrVsBlock(BlockState state) {
                    float hardness = state.getBlock().defaultDestroyTime();
                    return hardness < 0 ? -1 : hardness == 0 ? 1 : 8.0 / (hardness * 30);
                }
            }, false, waterBucket, true, 0, soulSpeed, waterEfficiency, 20.0, false);
        }
    }

    private Settings settings;
    private boolean potions;
    private boolean diagonalAscend;

    @Before
    public void setUp() {
        ItemFixture.boot();
        try {
            settings = BaritoneAPI.getSettings();
        } catch (Throwable t) {
            Assume.assumeNoException("needs the shadow BaritoneAPI from the bench harness", t);
        }
        potions = settings.considerPotionEffects.value;
        // the fake tool set would go ask a player for haste, and there is no player
        settings.considerPotionEffects.value = false;
        diagonalAscend = settings.allowDiagonalAscend.value;
        settings.allowDiagonalAscend.value = true;
    }

    @After
    public void tearDown() {
        if (settings != null) {
            settings.considerPotionEffects.value = potions;
            settings.allowDiagonalAscend.value = diagonalAscend;
        }
    }

    // stone floor at 63 everywhere in a 7x7
    private static MapWorld floor() {
        MapWorld w = new MapWorld();
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                w.set(x, 63, z, Blocks.STONE.defaultBlockState());
            }
        }
        return w;
    }

    // ---- fall time ----

    // the floor next door sits `drop` blocks lower, starting at the usual 63
    private static MapWorld ledge(int drop) {
        MapWorld w = floor();
        w.set(1, 63, 0, Blocks.AIR.defaultBlockState());
        w.set(1, 63 - drop, 0, Blocks.STONE.defaultBlockState());
        return w;
    }

    @Test
    public void fallingOntoGroundPaysForTheRealDrop() {
        // feet go from 64 to 62: two blocks of falling, not the three that the old indexing charged
        MutableMoveResult res = new MutableMoveResult();
        MovementDescend.cost(new Ctx(ledge(2)), 0, 64, 0, 1, 0, res);
        assertEquals(62, res.y);
        assertEquals(WALK_OFF_BLOCK_COST + FALL_N_BLOCKS_COST[2], res.cost, 1e-9);
    }

    @Test
    public void fallingIntoWaterPaysForTheWholeDepth() {
        // water is landed in, feet end up at the water cell (61), a drop of three. the old number was already right for this one
        MapWorld w = floor();
        w.set(1, 63, 0, Blocks.AIR.defaultBlockState());
        w.set(1, 60, 0, Blocks.STONE.defaultBlockState());
        w.set(1, 61, 0, Blocks.WATER.defaultBlockState());
        MutableMoveResult res = new MutableMoveResult();
        MovementDescend.cost(new Ctx(w), 0, 64, 0, 1, 0, res);
        assertEquals(61, res.y);
        assertEquals(WALK_OFF_BLOCK_COST + FALL_N_BLOCKS_COST[3], res.cost, 1e-9);
    }

    // ---- diagonals ----

    private static final double ROOT_2 = Math.sqrt(2);

    private static double diagonal(MapWorld w) {
        MutableMoveResult res = new MutableMoveResult();
        MovementDiagonal.cost(new Ctx(w), 0, 64, 0, 1, 1, res);
        return res.cost;
    }

    @Test
    public void edgingAroundACornerIsWalkSpeedOverTheDiagonal() {
        // a block on one corner means the executor walks (Diagonal.sprint refuses when a corner is blocked), so one
        // walked root 2 and not root 2 squared. two sprinted straights are 7.13, the edge has to beat that or we L-walk
        MapWorld w = floor();
        w.set(0, 64, 1, Blocks.STONE.defaultBlockState());
        w.set(0, 65, 1, Blocks.STONE.defaultBlockState());
        double cost = diagonal(w);
        assertEquals(WALK_ONE_BLOCK_COST * ROOT_2, cost, 1e-9);
        assertTrue(cost < 2 * SPRINT_ONE_BLOCK_COST);
    }

    @Test
    public void anOpenDiagonalStillSprints() {
        assertEquals(SPRINT_ONE_BLOCK_COST * ROOT_2, diagonal(floor()), 1e-9);
    }

    @Test
    public void diagonalAscendPaysTheJumpPenalty() {
        MapWorld w = floor();
        w.set(1, 64, 1, Blocks.STONE.defaultBlockState());
        MutableMoveResult res = new MutableMoveResult();
        MovementDiagonal.cost(new Ctx(w), 0, 64, 0, 1, 1, res);
        assertEquals(65, res.y);
        assertEquals(WALK_ONE_BLOCK_COST * ROOT_2 + JUMP_ONE_BLOCK_COST + settings.jumpPenalty.value, res.cost, 1e-9);
    }

    @Test
    public void diagonalAscendOntoABottomSlabIsAWalk() {
        // vanilla steps up half a block by itself, same as MovementAscend. no jump, no penalty
        MapWorld w = floor();
        w.set(1, 64, 1, Blocks.STONE_SLAB.defaultBlockState());
        MutableMoveResult res = new MutableMoveResult();
        MovementDiagonal.cost(new Ctx(w), 0, 64, 0, 1, 1, res);
        assertEquals(65, res.y);
        assertEquals(WALK_ONE_BLOCK_COST * ROOT_2, res.cost, 1e-9);
    }

    @Test
    public void youCantDiagonalAscendOffASlabOntoAFullBlock() {
        MapWorld w = floor();
        w.set(0, 63, 0, Blocks.STONE_SLAB.defaultBlockState());
        w.set(1, 64, 1, Blocks.STONE.defaultBlockState());
        MutableMoveResult res = new MutableMoveResult();
        MovementDiagonal.cost(new Ctx(w), 0, 64, 0, 1, 1, res);
        assertEquals(COST_INF, res.cost, 0);
    }

    // ---- the drop heuristic ----

    // what the search thread would have the heuristic use, then back to the default for the next test
    private static double descentWith(boolean bucket, int blocks) {
        Ctx ctx = new Ctx(floor(), bucket);
        GoalYLevel.setDescentTicksPerBlock(ctx.descentTicksPerBlock());
        try {
            return GoalYLevel.calculate(0, blocks);
        } finally {
            GoalYLevel.resetDescentTicksPerBlock();
        }
    }

    @Test
    public void theDescentEstimateNeverBeatsWhatTheCheapestPlainFallCosts() {
        // for any plain fall this is a lower bound, the only free ones are 3 blocks and under
        for (int n = 1; n <= 3; n++) {
            assertTrue("a " + n + " block drop", descentWith(false, n) <= FALL_N_BLOCKS_COST[n] + 1e-9);
        }
        assertEquals(FALL_N_BLOCKS_COST[3] / 3, GoalYLevel.calculate(0, 1), 1e-9);
    }

    @Test
    public void aBucketDoesNotMakeDiggingDownLookWorthless() {
        // a bucket fall is cheaper than this, but pricing it in made the floor 1.49 in fastMode and a bucket carrier could
        // never make a downward partial worth taking again. so the bucket gets the same floor as everyone
        for (int n = 1; n <= 20; n++) {
            assertEquals("a " + n + " block drop", descentWith(false, n), descentWith(true, n), 1e-9);
        }
        assertTrue(descentWith(true, 10) > 30);
        assertEquals(GoalYLevel.calculate(0, 10), GoalBlock.calculate(0, 10, 0), 1e-9);
        // and the bucket floor goes through GoalBlock the same way, not just the default one
        // (descentWith resets the thread on the way out, so it goes first)
        double bucketFloor = descentWith(true, 10);
        GoalYLevel.setDescentTicksPerBlock(new Ctx(floor(), true).descentTicksPerBlock());
        try {
            assertEquals(GoalYLevel.calculate(0, 10), GoalBlock.calculate(0, 10, 0), 1e-9);
            assertEquals(bucketFloor, GoalBlock.calculate(0, 10, 0), 1e-9);
        } finally {
            GoalYLevel.resetDescentTicksPerBlock();
        }
    }

    @Test
    public void aPlainFallIsFreeOnlyWhileItDealsNoDamage() {
        // 3 blocks is the last free one. past that every block of fall costs a half heart, and a half heart is fallDamageCost
        Ctx ctx = new Ctx(floor(), false);
        assertEquals(FALL_N_BLOCKS_COST[3] / 3, ctx.descentTicksPerBlock(), 1e-9);
        ctx.maxFallHeightNoWater = 9;
        double best = FALL_N_BLOCKS_COST[3] / 3;
        for (int n = 4; n <= 9; n++) {
            best = Math.min(best, (FALL_N_BLOCKS_COST[n] + (n - 3) * ctx.fallDamageCost) / n);
        }
        assertEquals(best, ctx.descentTicksPerBlock(), 1e-9);
        // at the default price of a heart none of the 4 to 9 block falls gets near the 3 block one, so it reduces to that
        assertEquals(FALL_N_BLOCKS_COST[3] / 3, best, 1e-9);
    }

    @Test
    public void fastModeDoesNotBringTheFloorDownToTheFreeFallTimeOfTwentyThreeBlocks() {
        boolean fast = settings.fastMode.value;
        double cost = settings.fallDamageCost.value;
        settings.fastMode.value = true;
        try {
            // the old floor was 23 blocks of free fall, about 1.16 a block, and nothing downhill could beat a dig step's share
            // with that. a 23 block fall takes 20 hp, it is nowhere near that cheap
            assertTrue(FALL_N_BLOCKS_COST[23] / 23 < 1.2);
            Ctx ctx = new Ctx(floor(), false);
            assertEquals(FALL_N_BLOCKS_COST[3] / 3, ctx.descentTicksPerBlock(), 1e-9);
            ctx.maxFallHeightNoWater = 9;
            assertEquals(FALL_N_BLOCKS_COST[3] / 3, ctx.descentTicksPerBlock(), 1e-9);
            // hearts that cost nothing are the long fall's free fall time, and a cheap one is the per n minimum
            settings.fallDamageCost.value = 0.0;
            assertEquals(FALL_N_BLOCKS_COST[23] / 23, new Ctx(floor(), false).descentTicksPerBlock(), 1e-9);
            settings.fallDamageCost.value = 1.0;
            double best = FALL_N_BLOCKS_COST[3] / 3;
            for (int n = 4; n <= 23; n++) {
                best = Math.min(best, (FALL_N_BLOCKS_COST[n] + (n - 3)) / n);
            }
            assertTrue(best < FALL_N_BLOCKS_COST[3] / 3);
            assertEquals(best, new Ctx(floor(), false).descentTicksPerBlock(), 1e-9);
        } finally {
            settings.fastMode.value = fast;
            settings.fallDamageCost.value = cost;
        }
    }

    @Test
    public void carryingABucketOrAClutchItemLeavesTheFloorAlone() {
        // same as aBucketDoesNotMakeDiggingDownLookWorthless, a clutch would have been under 0.6 a block
        boolean fast = settings.fastMode.value;
        try {
            for (boolean fastMode : new boolean[]{false, true}) {
                settings.fastMode.value = fastMode;
                double plain = new Ctx(floor(), false).descentTicksPerBlock();
                assertEquals(FALL_N_BLOCKS_COST[3] / 3, plain, 1e-9);
                assertEquals(plain, new Ctx(floor(), true).descentTicksPerBlock(), 1e-9);
                for (boolean pickup : new boolean[]{false, true}) {
                    Ctx clutch = new Ctx(floor(), true);
                    clutch.hasClutchItem = true;
                    clutch.clutchPicksUp = pickup;
                    assertEquals(plain, clutch.descentTicksPerBlock(), 1e-9);
                }
                // and a placement that pays you changes nothing either
                Ctx paid = new Ctx(floor(), true) {
                    @Override
                    public double placeBucketCost() {
                        return -5;
                    }
                };
                paid.hasClutchItem = true;
                assertEquals(plain, paid.descentTicksPerBlock(), 1e-9);
            }
        } finally {
            settings.fastMode.value = fast;
        }
    }

    @Test
    public void aFallMoveNeverCostsLessThanTheEstimateSaysItShould() {
        // the estimate is added to the flat distance, so it only stays admissible if a fall (1 block out, n down) is
        // worth at least that much on the heuristic. WALK_OFF 3.7 is a hair over the 3.56 a block of flat. past 3 blocks the
        // fall hurts and the hearts are in its price. bucket falls are not covered, on purpose
        double hurt = new Ctx(floor(), false).fallDamageCost;
        for (int n = 1; n <= 23; n++) {
            double saved = GoalBlock.calculate(1, 0, 0) + descentWith(false, n);
            assertTrue("fall of " + n, saved <= WALK_OFF_BLOCK_COST + FALL_N_BLOCKS_COST[n] + ExperimentalMovement.fallDamage(n) * hurt + 1e-9);
        }
    }

    @Test
    public void climbingStillLooksAtLeastAsHardAsItIs() {
        // going up isn't changed, one block of it is the jump itself
        assertEquals(JUMP_ONE_BLOCK_COST * 3, GoalYLevel.calculate(8, 5), 1e-9);
    }

    // ---- water ----

    // one block of still water over the floor next door. the planner only walks through the top layer of water, deeper
    // water isn't walkable through, so this is the shape every lake crossing has
    private static MapWorld lake() {
        MapWorld w = floor();
        w.set(1, 64, 0, Blocks.WATER.defaultBlockState());
        return w;
    }

    @Test
    public void wadingIsPricedAtWaterSpeedAndNotLandSpeed() {
        // vanilla: 0.02 push and 0.8 drag walking is 1.96 blocks/s, and the head is out of the water on a surface route so
        // sprint gets cancelled. the old default priced a lake at 4.63 a block, a speed nothing in water can reach
        double cost = MovementTraverse.cost(new Ctx(lake()), 0, 64, 0, 1, 0);
        assertEquals(WALK_ONE_IN_WATER_COST, cost, 1e-9);
        assertTrue(cost > 1.5 * WALK_ONE_BLOCK_COST);
    }

    @Test
    public void depthStriderBlendsBackTowardsLandSpeed() {
        assertEquals(WALK_ONE_BLOCK_COST, MovementTraverse.cost(new Ctx(lake(), 1.0f), 0, 64, 0, 1, 0), 1e-9);
        assertEquals((WALK_ONE_IN_WATER_COST + WALK_ONE_BLOCK_COST) / 2, MovementTraverse.cost(new Ctx(lake(), 0.5f), 0, 64, 0, 1, 0), 1e-9);
    }

    // ---- soul sand ----

    // the vanilla tick, run by hand: push, move, soul sand scales the velocity, then ground drag
    private static double terminalSpeed(double speedFactor) {
        double v = 0;
        double moved = 0;
        for (int i = 0; i < 200; i++) {
            moved = v + 0.98 * 0.1;
            v = moved * speedFactor * 0.6 * 0.91;
        }
        return moved;
    }

    @Test
    public void soulSandIsFiftyEightPercentSpeedAndNotHalf() {
        double ratio = terminalSpeed(0.4) / terminalSpeed(1.0);
        assertEquals(0.5809, ratio, 1e-4);
        assertEquals(WALK_ONE_BLOCK_COST / ratio, WALK_ONE_OVER_SOUL_SAND_COST, 1e-3);
    }

    // soul sand under the destination square only
    private static MapWorld soulSandAhead() {
        MapWorld w = floor();
        w.set(1, 63, 0, Blocks.SOUL_SAND.defaultBlockState());
        return w;
    }

    @Test
    public void aStepOntoSoulSandPaysHalfTheExtraAndSprintScalesIt() {
        double expected = (WALK_ONE_BLOCK_COST + (WALK_ONE_OVER_SOUL_SAND_COST - WALK_ONE_BLOCK_COST) / 2) * SPRINT_MULTIPLIER;
        assertEquals(expected, MovementTraverse.cost(new Ctx(soulSandAhead()), 0, 64, 0, 1, 0), 1e-9);
    }

    @Test
    public void soulSpeedBootsTakeTheSlowdownOffButNeverUndercutTheHeuristic() {
        // the data file turns the slowdown off and adds 0.0405 / 0.051 / 0.0615 of walking speed on top of 0.1, which on paper
        // is 1.4 to 1.6x walking. a sprinted step cheaper than costHeuristic would make A* stop being A*, so it bottoms out at walking
        double plain = MovementTraverse.cost(new Ctx(soulSandAhead()), 0, 64, 0, 1, 0);
        double one = MovementTraverse.cost(new Ctx(soulSandAhead(), 0f, 1), 0, 64, 0, 1, 0);
        double three = MovementTraverse.cost(new Ctx(soulSandAhead(), 0f, 3), 0, 64, 0, 1, 0);
        double flat = MovementTraverse.cost(new Ctx(floor()), 0, 64, 0, 1, 0);
        assertTrue(one < plain);
        assertEquals(flat, one, 1e-3);
        assertEquals(flat, three, 1e-3);
        assertEquals(WALK_ONE_BLOCK_COST / 1.615, CalculationContext.soulSandWalkCost(3), 1e-9);
        assertEquals(WALK_ONE_BLOCK_COST / 1.405, CalculationContext.soulSandWalkCost(1), 1e-9);
    }

    @Test
    public void aContextThatRaisesTheFallLimitAfterwardsAndMakesFallsFreeGetsTheLowerFloor() {
        // the elytra walk off context sets maxFallHeightNoWater to 10000 after the constructor has run
        Ctx ctx = new Ctx(floor(), false);
        assertEquals(FALL_N_BLOCKS_COST[3] / 3, ctx.descentTicksPerBlock(), 1e-9);
        // raising the limit alone prices the hearts, so it is no cheaper to go down
        ctx.maxFallHeightNoWater = 10000;
        assertEquals(FALL_N_BLOCKS_COST[3] / 3, ctx.descentTicksPerBlock(), 1e-9);
        // plainFallsAreFree is the other half, there the fall is the point and nothing inside the limit is charged
        ctx.plainFallsAreFree = true;
        assertEquals(FALL_N_BLOCKS_COST[4096] / 4096, ctx.descentTicksPerBlock(), 1e-9);
    }

    // ---- stairs ----

    private static BlockState stair(Direction facing, Half half, StairsShape shape) {
        return Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, facing).setValue(StairBlock.HALF, half).setValue(StairBlock.SHAPE, shape);
    }

    // walking +x onto a stair at (1,64,0), off the usual stone floor
    private static double ascendOnto(BlockState onto) {
        MapWorld w = floor();
        w.set(1, 64, 0, onto);
        return MovementAscend.cost(new Ctx(w), 0, 64, 0, 1, 0);
    }

    private static final double JUMP_UP = Math.max(JUMP_ONE_BLOCK_COST, WALK_ONE_BLOCK_COST) + 2;

    @Test
    public void aStairWalkedUpTheWayItFacesIsAWalk() {
        // facing is the tall side, so we meet the 0.5 low step first and vanilla steps over anything up to 0.6
        assertEquals(WALK_ONE_BLOCK_COST, ascendOnto(stair(Direction.EAST, Half.BOTTOM, StairsShape.STRAIGHT)), 1e-9);
    }

    @Test
    public void stairsFromAnyOtherSideOrShapeStayJumps() {
        assertEquals(JUMP_UP, ascendOnto(stair(Direction.WEST, Half.BOTTOM, StairsShape.STRAIGHT)), 1e-9); // the tall back
        assertEquals(JUMP_UP, ascendOnto(stair(Direction.NORTH, Half.BOTTOM, StairsShape.STRAIGHT)), 1e-9); // the side
        assertEquals(JUMP_UP, ascendOnto(stair(Direction.EAST, Half.TOP, StairsShape.STRAIGHT)), 1e-9); // upside down
        for (StairsShape corner : new StairsShape[]{StairsShape.INNER_LEFT, StairsShape.INNER_RIGHT, StairsShape.OUTER_LEFT, StairsShape.OUTER_RIGHT}) {
            assertEquals("corner " + corner, JUMP_UP, ascendOnto(stair(Direction.EAST, Half.BOTTOM, corner)), 1e-9);
        }
    }

    @Test
    public void aStaircaseKeepsWalkingAndSoulSandUnderneathDoesNot() {
        BlockState up = stair(Direction.EAST, Half.BOTTOM, StairsShape.STRAIGHT);
        // standing on a stair that goes the same way means we are up on its tall half
        MapWorld climbing = floor();
        climbing.set(0, 63, 0, up);
        climbing.set(1, 64, 0, up);
        assertEquals(WALK_ONE_BLOCK_COST, MovementAscend.cost(new Ctx(climbing), 0, 64, 0, 1, 0), 1e-9);
        // StairSteps (shared with the executor) only looks at how tall the floor under us is, and a stair is full height
        MapWorld turned = floor();
        turned.set(0, 63, 0, stair(Direction.NORTH, Half.BOTTOM, StairsShape.STRAIGHT));
        turned.set(1, 64, 0, up);
        assertEquals(WALK_ONE_BLOCK_COST, MovementAscend.cost(new Ctx(turned), 0, 64, 0, 1, 0), 1e-9);
        // soul sand is 0.875 tall so the lip is 0.625
        MapWorld sand = floor();
        sand.set(0, 63, 0, Blocks.SOUL_SAND.defaultBlockState());
        sand.set(1, 64, 0, up);
        assertEquals(JUMP_UP, MovementAscend.cost(new Ctx(sand), 0, 64, 0, 1, 0), 1e-9);
    }

    // ---- breaking ----

    // iron pickaxe on stone: 1.5 hardness, 6 speed, /30 is 0.1333 progress a tick, so 7.5 ticks of swinging
    private static final double IRON_ON_STONE = 6.0 / (1.5 * 30);

    private static final class PickCtx extends CalculationContext {
        PickCtx(BlockStateInterface bsi) {
            this(bsi, IRON_ON_STONE);
        }

        PickCtx(BlockStateInterface bsi, double progressPerTick) {
            super(null, true, null, null, bsi, new ToolSet(null) {
                @Override
                public double getStrVsBlock(BlockState state) {
                    return progressPerTick;
                }
            }, false, false, true, 0, 0.0f, 20.0, false);
        }
    }

    // the same wall at the destination, in front of the head and at the feet. blocks=1 leaves the head clear
    private static MapWorld tunnel(int blocks) {
        MapWorld w = floor();
        w.set(1, 64, 0, Blocks.STONE.defaultBlockState());
        if (blocks == 2) {
            w.set(1, 65, 0, Blocks.STONE.defaultBlockState());
        }
        return w;
    }

    @Test
    public void breakingRoundsUpToWholeTicksAndWaitsOutTheCooldown() {
        // BlockBreakHelper: a start tick that does nothing, ceil(T) ticks of progress, then blockBreakSpeed - 1 idle.
        // T = 1 / 0.1333 = 7.5 so 8, which is 8 + 1 + 5 = 14 a block. the walk happens in the last idle
        double penalty = settings.blockBreakAdditionalPenalty.value;
        double idle = settings.blockBreakSpeed.value - 1;
        double one = MovementTraverse.cost(new PickCtx(tunnel(1)), 0, 64, 0, 1, 0);
        assertEquals(8 + 1 + idle + penalty, one, 1e-9);
        double two = MovementTraverse.cost(new PickCtx(tunnel(2)), 0, 64, 0, 1, 0);
        // 2 * 14 = 28 and a real run of it is 27.6 (the vanilla timeline: start, break at +8, start at +14, break at +22, then walk)
        assertEquals(2 * (8 + 1 + idle + penalty), two, 1e-9);
        double realTwo = 8 + 6 + 8 + 1 + WALK_ONE_BLOCK_COST;
        assertEquals(realTwo + 2 * penalty, two, 0.5);
    }

    @Test
    public void anInstantBreakIsOneTickAndNeverWaits() {
        // progress of 1 or more a tick is over on the click, BlockBreakHelper sets no delay after one
        double penalty = settings.blockBreakAdditionalPenalty.value;
        double cost = MovementTraverse.cost(new PickCtx(tunnel(1), 1.5), 0, 64, 0, 1, 0);
        assertEquals(WALK_ONE_BLOCK_COST + 1 + penalty, cost, 1e-9);
    }

    // ---- jump and fall timing the one block costs are built on ----
    // vanilla tick: move with the velocity we have, then gravity 0.08 and drag 0.98 on y. the jump sets 0.42 before the move

    @Test
    public void aJumpGetsTheFeetOverTheLedgeInThreeTicksAndOnlyLandsInNine() {
        double y = 0;
        double vy = 0.42;
        int over = -1;
        double landed = -1;
        for (int tick = 1; tick <= 12 && landed < 0; tick++) {
            double before = y;
            y += vy;
            vy = (vy - 0.08) * 0.98;
            if (over < 0 && y > 1.0) {
                over = tick;
            }
            if (over > 0 && y <= 1.0) {
                // the fraction of this tick it took to come back down to the ledge
                landed = tick - 1 + (before - 1.0) / (before - y);
            }
        }
        assertEquals(3, over);
        assertEquals(8.1, landed, 0.05);
        // MovementAscend is done when the feet are over the ledge and inside the dest square, which is the 3 ticks (the
        // 3.16 is that time on the up leg of the parabola) and not the 8.1 of the whole hop. the rest of the hop is
        // spent already moving on to the next square, so a run with one step in it loses nothing, and only back to back
        // jumps (stairs of full blocks, which wait for the landing) pay the 8.1 each. the 6.63 sits between the two
        assertEquals(over, JUMP_ONE_BLOCK_COST, 0.5);
        assertTrue(Math.max(JUMP_ONE_BLOCK_COST, WALK_ONE_BLOCK_COST) + 2 < landed);
    }

    @Test
    public void aOneBlockDescendIsWhatTheTickGridGives() {
        // the box clears the edge somewhere in tick N (so ceil of WALK_OFF), then the first airborne tick moves 0.0784, and
        // it lands in the tick the total passes a block
        double fallen = 0;
        double vy = 0.0784;
        int airborne = 0;
        while (fallen < 1.0) {
            fallen += vy;
            vy = (vy + 0.08) * 0.98;
            airborne++;
        }
        assertEquals(5, airborne);
        double onTheGrid = Math.ceil(WALK_OFF_BLOCK_COST) + airborne;
        MutableMoveResult res = new MutableMoveResult();
        MovementDescend.cost(new Ctx(ledge(1)), 0, 64, 0, 1, 0, res);
        assertEquals(63, res.y);
        // the planner's FALL_N[1] counts a lead tick that does no falling, and that is the grid at work: ticks are whole,
        // so a continuous 4.6 is a 5 and the edge is crossed somewhere inside a tick. 9.3 against 9
        assertEquals(onTheGrid, res.cost, 0.5);
    }

    @Test
    public void diggingDownFallsDuringTheWaitAfterTheBreak() {
        // break (8 + 1), the block goes, and the 5 idle ticks pass during the 5.6 of falling a block
        MapWorld w = floor();
        w.set(0, 63, 0, Blocks.AIR.defaultBlockState());
        w.set(0, 64, 0, Blocks.STONE.defaultBlockState());
        w.set(0, 63, 0, Blocks.STONE.defaultBlockState());
        w.set(0, 62, 0, Blocks.STONE.defaultBlockState());
        double penalty = settings.blockBreakAdditionalPenalty.value;
        double cost = MovementDownward.cost(new PickCtx(w), 0, 64, 0);
        assertEquals(8 + 1 + penalty + FALL_N_BLOCKS_COST[1], cost, 1e-9);
    }

    @Test
    public void anAscendThatBreaksLetsTheJumpHappenInTheWait() {
        // a stone ceiling to break over the step: 8 + 1 + 5 idle, minus the 4.6 of walk the wait covers, plus the jump
        MapWorld w = floor();
        w.set(1, 64, 0, Blocks.STONE.defaultBlockState());
        w.set(0, 66, 0, Blocks.STONE.defaultBlockState());
        double penalty = settings.blockBreakAdditionalPenalty.value;
        double idle = settings.blockBreakSpeed.value - 1;
        double with = MovementAscend.cost(new PickCtx(w), 0, 64, 0, 1, 0);
        MapWorld clear = floor();
        clear.set(1, 64, 0, Blocks.STONE.defaultBlockState());
        double without = MovementAscend.cost(new PickCtx(clear), 0, 64, 0, 1, 0);
        assertEquals(8 + 1 + penalty + idle - WALK_ONE_BLOCK_COST, with - without, 1e-9);
    }
}
