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
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

// the floors the sprint jump sim is exact about. the executor (SprintJump) and the planner (MovementParkour pricing a hop)
// both ask here, because pricing a sprint hop where the executor then won't take one is how costs and movement drift apart
public final class SolidFloor {

    private SolidFloor() {}

    // ice/slime change friction, soul sand/honey/farmland aren't full height, and hopping on magma without sneaking hurts
    public static boolean plain(BlockState state, BlockGetter level, BlockPos pos) {
        return state.getBlock().getFriction() == 0.6F && state.getBlock().getSpeedFactor() == 1.0F && state.getBlock().getJumpFactor() == 1.0F
                && !state.is(Blocks.MAGMA_BLOCK) && state.isCollisionShapeFullBlock(level, pos);
    }
}
