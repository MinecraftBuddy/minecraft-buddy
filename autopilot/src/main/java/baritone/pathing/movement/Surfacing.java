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

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

// going up for air. holding jump in water is the one thing that works from any depth, but under an overhang it just pins
// us to the ceiling while the way out is sideways, so only when there's a straight shot up
public final class Surfacing {

    // how far up we bother looking for the surface. not a "too deep to make it" cutoff: in open water up is the only way
    // to air no matter how far it is, the ceiling is the one thing worth knowing about. a clutch into a pool at full
    // speed can end up 15-20 under
    static final int REACH = 64;

    enum Cell {
        AIR, WATER, BLOCKED
    }

    interface Column {
        // up is 0 for the cell our eyes are in
        Cell at(int up);
    }

    private Surfacing() {}

    // low on air, eyes under, and nothing but water between us and the sky. the column is only looked at once it matters
    public static boolean wantsAir(LocalPlayer player) {
        return needsAir(player.getAirSupply(), player.isUnderWater()) && openToAir(player.level(), BlockPos.containing(player.getEyePosition()));
    }

    static boolean needsAir(int air, boolean underWater) {
        return underWater && air < Movement.SURFACE_AT_AIR;
    }

    // straight up from the eye cell: water (kelp and seagrass too, they don't stop you) until open air
    public static boolean openToAir(BlockGetter world, BlockPos eye) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        return openToAir(up -> cell(world, pos.set(eye.getX(), eye.getY() + up, eye.getZ())));
    }

    static boolean openToAir(Column column) {
        for (int up = 0; up <= REACH; up++) {
            switch (column.at(up)) {
                case AIR:
                    return true;
                case BLOCKED:
                    return false;
                default:
                    break;
            }
        }
        return false; // an ocean 64 deep, or the column runs out of loaded world. either way not ours to fix
    }

    static Cell cell(BlockGetter world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (!state.getCollisionShape(world, pos).isEmpty()) {
            return Cell.BLOCKED;
        }
        FluidState fluid = state.getFluidState();
        if (fluid.isEmpty()) {
            return Cell.AIR;
        }
        // lava over our head is not the way out, whatever the air bar says
        boolean water = fluid.getType() == Fluids.WATER || fluid.getType() == Fluids.FLOWING_WATER;
        return water ? Cell.WATER : Cell.BLOCKED;
    }
}
