/*
 * This file is part of Soprano.
 *
 * Soprano is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Soprano is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Soprano.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.pathing.movement;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

// world gen lava doesn't move. a source with open air right beside it can sit there forever, nobody ever told it to flow.
// putting a block next to it does tell it: a place updates its neighbours, the fluid tick gets scheduled and out it comes,
// usually onto whoever is standing on that block. no water in the nether and iron armor is not a plan, so we don't poke it
public final class LavaWake {

    public enum Cell { SOURCE, FLOW, AIR, OTHER }

    public interface Cells {
        Cell at(int x, int y, int z);
    }

    private LavaWake() {}

    public static Cell cell(BlockState state) {
        Fluid fluid = state.getFluidState().getType();
        if (fluid == Fluids.LAVA) {
            return Cell.SOURCE;
        }
        if (fluid == Fluids.FLOWING_LAVA) {
            return Cell.FLOW;
        }
        return state.isAir() ? Cell.AIR : Cell.OTHER;
    }

    // would a block at (x, y, z) start something? only a source that has somewhere to go, which is why a bridge over a lake is
    // still fine: the surface has air above it and lava doesn't climb
    public static boolean wakes(Cells cells, int x, int y, int z) {
        return exposedSource(cells, x + 1, y, z, x, y, z)
                || exposedSource(cells, x - 1, y, z, x, y, z)
                || exposedSource(cells, x, y, z + 1, x, y, z)
                || exposedSource(cells, x, y, z - 1, x, y, z)
                || exposedSource(cells, x, y + 1, z, x, y, z)
                || exposedSource(cells, x, y - 1, z, x, y, z);
    }

    // (fx, fy, fz) is the cell being filled. it's air right now, it won't be in a second, and a source walled in by our block
    // has nowhere to go
    private static boolean exposedSource(Cells cells, int x, int y, int z, int fx, int fy, int fz) {
        if (cells.at(x, y, z) != Cell.SOURCE) {
            return false;
        }
        return open(cells, x + 1, y, z, fx, fy, fz)
                || open(cells, x - 1, y, z, fx, fy, fz)
                || open(cells, x, y, z + 1, fx, fy, fz)
                || open(cells, x, y, z - 1, fx, fy, fz)
                || open(cells, x, y - 1, z, fx, fy, fz);
    }

    private static boolean open(Cells cells, int x, int y, int z, int fx, int fy, int fz) {
        return (x != fx || y != fy || z != fz) && cells.at(x, y, z) == Cell.AIR;
    }

    // feet at (x, y, z). lava right beside our feet or head when we come down. walking past it is one thing, we can stop,
    // but you can't change your mind in the air
    public static boolean besideLanding(Cells cells, int x, int y, int z) {
        return lavaAround(cells, x, y, z) || lavaAround(cells, x, y + 1, z);
    }

    private static boolean lavaAround(Cells cells, int x, int y, int z) {
        return lava(cells.at(x + 1, y, z)) || lava(cells.at(x - 1, y, z)) || lava(cells.at(x, y, z + 1)) || lava(cells.at(x, y, z - 1));
    }

    private static boolean lava(Cell cell) {
        return cell == Cell.SOURCE || cell == Cell.FLOW;
    }
}
