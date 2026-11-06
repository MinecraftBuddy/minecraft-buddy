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

package baritone.pathing.movement;

import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

// the predicate is checked against the stair's actual collision box, not against what we think facing means
public class StairStepsTest {

    // vanilla steps up by itself under this (the player's step height attribute)
    private static final double STEP = 0.6;

    @BeforeClass
    public static void bootstrap() {
        ItemFixture.boot();
    }

    private static BlockState stair(Direction facing, Half half, StairsShape shape) {
        return Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, facing).setValue(StairBlock.HALF, half).setValue(StairBlock.SHAPE, shape);
    }

    // how tall the block is in the first (near) and second (far) half along the heading, each as the tallest part of that half
    private static double[] profile(BlockState state, Direction heading) {
        VoxelShape shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        double[] out = new double[2];
        for (int half = 0; half < 2; half++) {
            double from = half == 0 ? 0 : 0.5;
            AABB box = heading.getAxis() == Direction.Axis.X
                    ? new AABB(heading.getStepX() > 0 ? from : 1 - from - 0.5, 0, 0, heading.getStepX() > 0 ? from + 0.5 : 1 - from, 1, 1)
                    : new AABB(0, 0, heading.getStepZ() > 0 ? from : 1 - from - 0.5, 1, 1, heading.getStepZ() > 0 ? from + 0.5 : 1 - from);
            VoxelShape part = Shapes.join(shape, Shapes.create(box), BooleanOp.AND);
            out[half] = part.isEmpty() ? 0 : part.max(Direction.Axis.Y);
        }
        return out;
    }

    @Test
    public void walkUpMeansEveryStepIsUnderTheStepHeight() {
        for (Direction heading : Direction.Plane.HORIZONTAL) {
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                for (Half half : Half.values()) {
                    for (StairsShape shape : StairsShape.values()) {
                        BlockState state = stair(facing, half, shape);
                        double[] p = profile(state, heading);
                        boolean climbs = p[0] <= 0.5 + 1e-9 && p[1] - p[0] <= STEP && p[0] > 0
                                // every cell across the entry has to be that low, and the box has to reach the top step too
                                && p[1] == 1.0;
                        // the predicate is the narrow, safe subset: it must never say yes where the real shape has a full block lip
                        if (StairSteps.walkUp(state, heading)) {
                            assertTrue(facing + " " + half + " " + shape + " heading " + heading + " " + p[0] + "/" + p[1], climbs);
                        }
                    }
                }
            }
        }
    }

    @Test
    public void exactlyTheStraightBottomStairFacingTheWayWeWalk() {
        for (Direction heading : Direction.Plane.HORIZONTAL) {
            int yes = 0;
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                for (Half half : Half.values()) {
                    for (StairsShape shape : StairsShape.values()) {
                        if (StairSteps.walkUp(stair(facing, half, shape), heading)) {
                            yes++;
                            assertEquals(heading, facing);
                            assertEquals(Half.BOTTOM, half);
                            assertEquals(StairsShape.STRAIGHT, shape);
                        }
                    }
                }
            }
            assertEquals(1, yes);
        }
    }

    @Test
    public void theLowSideOfThePredicateReallyIsHalfABlock() {
        for (Direction heading : Direction.Plane.HORIZONTAL) {
            BlockState state = stair(heading, Half.BOTTOM, StairsShape.STRAIGHT);
            double[] p = profile(state, heading);
            assertEquals(0.5, p[0], 1e-9);
            assertEquals(1.0, p[1], 1e-9);
            // and the other way round (the high end first) is a wall
            assertEquals(1.0, profile(state, heading.getOpposite())[0], 1e-9);
        }
    }

    @Test
    public void notAStairOrNotAnAxisIsNo() {
        assertFalse(StairSteps.walkUp(Blocks.STONE.defaultBlockState(), Direction.EAST));
        assertFalse(StairSteps.walkUp(Blocks.OAK_SLAB.defaultBlockState(), Direction.EAST));
        BlockState east = stair(Direction.EAST, Half.BOTTOM, StairsShape.STRAIGHT);
        assertFalse(StairSteps.walkUp(east, Direction.UP));
        assertFalse(StairSteps.walkUp(east, null));
        assertTrue(StairSteps.walkUp(east, 1, 0));
        assertFalse(StairSteps.walkUp(east, 1, 1)); // a diagonal is not a way onto a stair
        assertFalse(StairSteps.walkUp(east, 0, 0));
        BlockPos src = new BlockPos(3, 64, 3);
        BlockState stone = Blocks.STONE.defaultBlockState();
        assertTrue(StairSteps.walkUp(east, src, new BlockPos(4, 64, 3), stone, EmptyBlockGetter.INSTANCE));
        assertFalse(StairSteps.walkUp(east, src, new BlockPos(2, 64, 3), stone, EmptyBlockGetter.INSTANCE));
    }

    @Test
    public void aFloorShorterThanTheCellMakesTheLipTooHigh() {
        BlockState east = stair(Direction.EAST, Half.BOTTOM, StairsShape.STRAIGHT);
        BlockPos src = new BlockPos(3, 64, 3);
        BlockPos dest = new BlockPos(4, 64, 3);
        // soul sand and mud are 0.875 tall, so the 0.5 lip is a 0.625 step. slabs are worse, a path is 0.9375
        for (BlockState low : new BlockState[]{Blocks.SOUL_SAND.defaultBlockState(), Blocks.MUD.defaultBlockState(),
                Blocks.OAK_SLAB.defaultBlockState(), Blocks.DIRT_PATH.defaultBlockState(), Blocks.AIR.defaultBlockState()}) {
            assertFalse(low.toString(), StairSteps.walkUp(east, src, dest, low, EmptyBlockGetter.INSTANCE));
        }
        // the high half of the stair before this one is full height, so a flight stays a flight
        assertTrue(StairSteps.walkUp(east, src, dest, stair(Direction.EAST, Half.BOTTOM, StairsShape.STRAIGHT), EmptyBlockGetter.INSTANCE));
    }
}
