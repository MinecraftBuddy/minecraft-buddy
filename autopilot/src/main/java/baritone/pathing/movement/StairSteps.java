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

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.phys.shapes.VoxelShape;

// the one answer to "is this a stair you just walk up". the executor doesn't press space for it and MovementAscend.cost
// prices it as a walk, so both have to ask here or they will disagree about what the same block is
public final class StairSteps {

    private StairSteps() {}

    // a bottom half, straight stair whose low side is the side we come in from. every half of it is half a block tall for
    // the player (the low step first, then the high one), which is under the 0.6 vanilla steps up by itself. everything else
    // stays a jump: corners and inner/outer shapes (part of the entry is a full block), upside down stairs (a ceiling lip),
    // coming in from the side or the high end (a full block wall)
    public static boolean walkUp(BlockState state, Direction heading) {
        return heading != null && heading.getAxis().isHorizontal() && state.getBlock() instanceof StairBlock
                && state.getValue(StairBlock.HALF) == Half.BOTTOM
                && state.getValue(StairBlock.SHAPE) == StairsShape.STRAIGHT
                && state.getValue(StairBlock.FACING) == heading;
    }

    // same, with the horizontal step of the movement as a delta. null (so false) for anything that is not one block along an axis
    public static boolean walkUp(BlockState state, int dx, int dz) {
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            if (dir.getStepX() == dx && dir.getStepZ() == dz) {
                return walkUp(state, dir);
            }
        }
        return false;
    }

    // the movement-shaped version: the stair is the block we climb onto, at dest.below(), and we come from src. the 0.5 lip is
    // only 0.5 when we stand at full height: from soul sand or mud (0.875 tall) the low half is a 0.625 step, over what
    // vanilla steps up, so the floor under src has to be a block whose top is the top of the cell
    public static boolean walkUp(BlockState stair, BlockPos src, BlockPos dest, BlockState srcFloor, BlockGetter level) {
        return walkUp(stair, dest.getX() - src.getX(), dest.getZ() - src.getZ()) && fullHeight(srcFloor, level, src.below());
    }

    public static boolean fullHeight(BlockState floor, BlockGetter level, BlockPos pos) {
        VoxelShape shape = floor.getCollisionShape(level, pos);
        return !shape.isEmpty() && shape.max(Direction.Axis.Y) >= 1.0 - 1e-9;
    }
}
