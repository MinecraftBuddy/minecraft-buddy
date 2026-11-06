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

package baritone.pathing.movement.movements;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.VoxelShape;

// does the water at the bottom of this fall already save it. one ray down the middle of us said no to a flowing cell the
// hitbox was half in, so this looks at every column the 0.6 wide box covers, the way vanilla's fluid check does.
// vanilla 26.3: being in water (any level, flowing, waterlogged, as long as its top is above feet + 0.001) wipes the fall
// distance, and the landing tick runs that check again where we stop. lava wipes nothing. water passed through way up
// the column only buys a reset there, the rest of the fall still counts
public final class LandingWater {

    // what vanilla shrinks the box by before it goes looking for fluid
    private static final double FLUID_INSET = 0.001;
    private static final double HALF_WIDTH = 0.3, HEIGHT = 1.8;
    // a fall this long or shorter after the last reset is free (it's free under 4 really, see LadderClutch, but slack)
    private static final double FREE_FALL = 3;

    private LandingWater() {}

    // pure so the tests don't need a world
    interface Cells {
        // top of the collision box in this cell (can be 1.5 for a fence), NaN if we'd fall right through it
        double floorTop(int x, int y, int z);

        // where the water stops in this cell, 0 to 1. NaN for no water, lava is not water
        double waterTop(int x, int y, int z);
    }

    public static boolean landsInWater(BlockGetter world, double x, double y, double z, double vy, int lookDown) {
        return landsInWater(new Cells() {
            private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

            @Override
            public double floorTop(int cx, int cy, int cz) {
                pos.set(cx, cy, cz);
                VoxelShape shape = world.getBlockState(pos).getCollisionShape(world, pos);
                return shape.isEmpty() ? Double.NaN : shape.max(Direction.Axis.Y);
            }

            @Override
            public double waterTop(int cx, int cy, int cz) {
                pos.set(cx, cy, cz);
                BlockState state = world.getBlockState(pos);
                FluidState fluid = state.getFluidState();
                // the fluid itself and not FluidTags.WATER, same as MovementHelper.isWater. tags are empty off a real world
                return fluid.getType() == Fluids.WATER || fluid.getType() == Fluids.FLOWING_WATER ? fluid.getHeight(world, pos) : Double.NaN;
            }
        }, x, y, z, vy, lookDown);
    }

    // feet at x y z falling at vy (blocks per tick, negative). true if a tick of the rest of this fall starts with the
    // hitbox in water within FREE_FALL of the floor, the landing tick included. the floor is the highest one under any
    // column the box covers, that's the one we stop on, so a deeper pool next to it never comes into it
    static boolean landsInWater(Cells cells, double x, double y, double z, double vy, int lookDown) {
        int x0 = floor(x - HALF_WIDTH + FLUID_INSET), x1 = floor(x + HALF_WIDTH - FLUID_INSET);
        int z0 = floor(z - HALF_WIDTH + FLUID_INSET), z1 = floor(z + HALF_WIDTH - FLUID_INSET);
        double landing = Double.NEGATIVE_INFINITY;
        for (int cx = x0; cx <= x1; cx++) {
            for (int cz = z0; cz <= z1; cz++) {
                landing = Math.max(landing, floorUnder(cells, cx, cz, y, lookDown));
            }
        }
        if (landing == Double.NEGATIVE_INFINITY) {
            return false; // no floor in sight, nothing to land in either
        }
        // played out tick by tick, because the fluid check only looks where a tick starts. at 3.9 a tick a thin layer a
        // couple blocks up gets jumped clean over, and then it saved nothing
        double feet = y;
        double v = vy;
        for (int tick = 0; tick < 400 && feet > landing; tick++) {
            if (feet - landing <= FREE_FALL && touchesWater(cells, x0, x1, z0, z1, feet)) {
                return true;
            }
            feet += v;
            v = (v - LadderClutch.GRAVITY) * LadderClutch.DRAG;
        }
        // the landing tick asks again from where we stopped
        return touchesWater(cells, x0, x1, z0, z1, Math.max(feet, landing));
    }

    // the first floor we'd stand on in this column, as a height. the cell our feet are in only counts if its top is under them
    private static double floorUnder(Cells cells, int cx, int cz, double y, int lookDown) {
        int start = floor(y);
        for (int cy = start; cy >= start - lookDown; cy--) {
            double top = cells.floorTop(cx, cy, cz);
            if (!Double.isNaN(top) && cy + top <= y + FLUID_INSET) {
                return cy + top;
            }
        }
        return Double.NEGATIVE_INFINITY;
    }

    // vanilla's check, with the box shrunk the same way: any water cell in the box whose top reaches the bottom of the box
    private static boolean touchesWater(Cells cells, int x0, int x1, int z0, int z1, double feet) {
        double bottom = feet + FLUID_INSET;
        int y0 = floor(bottom), y1 = floor(feet + HEIGHT - FLUID_INSET);
        for (int cx = x0; cx <= x1; cx++) {
            for (int cz = z0; cz <= z1; cz++) {
                for (int cy = y0; cy <= y1; cy++) {
                    double top = cells.waterTop(cx, cy, cz);
                    if (!Double.isNaN(top) && cy + top >= bottom) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static int floor(double d) {
        return (int) Math.floor(d);
    }
}
