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

package baritone.utils;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BrushableBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.state.BlockState;

// sand and gravel are a column, not a block. take out the bottom one and the whole stack comes down the hole, onto
// whatever stands in it. this is the question "where does it land and does that hit us" with the world taken out, so the
// pathing side (a BlockStateInterface) and the altoclef side (a ClientLevel) ask it the same way and the tests need no minecraft
public final class FallingColumn {

    // what the helper needs to know about a cell. both are about the block right now, the helper does the thinking
    public interface Cells {
        // drops when nothing holds it up: sand, gravel, concrete powder, anvils, suspicious blocks, loose scaffolding
        boolean falls(int x, int y, int z);

        // stops a falling block. FallingBlock.isFree says no for air, fire, liquids and anything replaceable
        boolean stops(int x, int y, int z);

        // a stalactite: pointed dripstone growing down. these hang from the cell above, so it is the block over them
        // that you must not break. default false so a world that doesn't care doesn't have to answer
        default boolean hangs(int x, int y, int z) {
            return false;
        }
    }

    // nobody is stacking 64 sand over a mine entrance and a world with that much in one column deserves what it gets
    public static final int MAX_HEIGHT = 16;
    // how far down a column is followed before we call it "falls forever" (void, caves). also bounds the loops
    public static final int MAX_DROP = 64;
    // a block that has lost its support gets a scheduled tick two ticks later, and that is when it turns into an entity
    public static final int SCHEDULE_TICKS = 2;
    // what the pathing cost pays on top of the swing for every block of the stack: the scheduled tick and a short drop.
    // a column isn't mined while it is still coming down, and that wait is the only part of it that isn't swinging
    public static final double LAND_WAIT_TICKS = 4;

    private FallingColumn() {
    }

    // the block classes, so the two worlds agree on what counts. FallingBlock is sand, gravel, powder, anvils and the dragon
    // egg, BrushableBlock is the suspicious ones (it ticks into a falling entity on its own). these fall every time
    public static boolean isGravity(Block block) {
        return block instanceof FallingBlock || block instanceof BrushableBlock;
    }

    // plus scaffolding, but only a piece already sitting at distance 7, which vanilla turns into a falling block on its
    // next tick. that is rare (cut a tower and the rest mostly pops into items, which hurts nobody), but healthy scaffolding
    // hangs over air all day long and treating that as sand made every scaffold tower look like a column about to drop
    public static boolean isFalling(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof ScaffoldingBlock) {
            return state.getValue(ScaffoldingBlock.DISTANCE) >= LOOSE_SCAFFOLDING;
        }
        return isGravity(block);
    }

    private static final int LOOSE_SCAFFOLDING = 7;

    // a stalactite is the only dripstone that comes down, and only when what it hangs from goes
    public static boolean isStalactite(BlockState state) {
        return state.getBlock() instanceof PointedDripstoneBlock
                && state.getValue(PointedDripstoneBlock.TIP_DIRECTION) == net.minecraft.core.Direction.DOWN;
    }

    // a way to read a block, so one adapter serves a BlockStateInterface, a ClientLevel and a test map
    @FunctionalInterface
    public interface StateAt {
        BlockState at(int x, int y, int z);
    }

    // the real thing over any block reader. stops() is exactly what FallingBlock itself asks before it lets go
    public static Cells over(StateAt world) {
        return new Cells() {
            @Override
            public boolean falls(int x, int y, int z) {
                return isFalling(world.at(x, y, z));
            }

            @Override
            public boolean stops(int x, int y, int z) {
                return !FallingBlock.isFree(world.at(x, y, z));
            }

            @Override
            public boolean hangs(int x, int y, int z) {
                return isStalactite(world.at(x, y, z));
            }
        };
    }

    // how many falling blocks sit on top of (x,y,z), one right on the other. 0 when nothing does
    public static int heightAbove(Cells w, int x, int y, int z) {
        int height = 0;
        while (height < MAX_HEIGHT && w.falls(x, y + 1 + height, z)) {
            height++;
        }
        return height;
    }

    // the y the bottom of the stack ends up at once (x,y,z) is open: it keeps going down until something stops it. if
    // (x,y,z) is already open that is still where it lands, the stack is above it
    public static int landingY(Cells w, int x, int y, int z) {
        int landing = y;
        while (y - landing < MAX_DROP && !w.stops(x, landing - 1, z)) {
            landing--;
        }
        return landing;
    }

    // the stack above (x,y,z) lands in [landingY, landingY + height - 1]. does that touch the cells from lowY to highY
    // in this column? that is "it falls on us", with our feet and head as the two cells
    public static boolean landsOn(Cells w, int x, int y, int z, int lowY, int highY) {
        int height = heightAbove(w, x, y, z);
        if (height == 0) {
            return false;
        }
        int landing = landingY(w, x, y, z);
        return landing <= highY && landing + height - 1 >= lowY;
    }

    // landsOn for a body instead of two cell numbers: a player is 0.6 wide so it can stand in two or four columns at once,
    // and only the columns it actually overlaps count. the epsilon is so a box that ends exactly on a block face
    // doesn't claim the next block over (standing on a block top at y 64.0 is cell 64, not 63)
    public static boolean landsOnBox(Cells w, int x, int y, int z, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        if (!inBox(x, z, minX, minZ, maxX, maxZ)) {
            return false;
        }
        return landsOn(w, x, y, z, (int) Math.floor(minY + EPS), (int) Math.floor(maxY - EPS));
    }

    // and for stalactites, same box
    public static boolean hangersFallOnBox(Cells w, int x, int y, int z, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        if (!inBox(x, z, minX, minZ, maxX, maxZ)) {
            return false;
        }
        return hangersFallOn(w, x, y, z, (int) Math.floor(minY + EPS), (int) Math.floor(maxY - EPS));
    }

    private static final double EPS = 1.0E-7;

    private static boolean inBox(int x, int z, double minX, double minZ, double maxX, double maxZ) {
        return x >= Math.floor(minX + EPS) && x <= Math.floor(maxX - EPS) && z >= Math.floor(minZ + EPS) && z <= Math.floor(maxZ - EPS);
    }

    // the same question for a stalactite: how many hang straight down from the cell under (x,y,z). they let go together
    public static int hangingBelow(Cells w, int x, int y, int z) {
        int count = 0;
        while (count < MAX_HEIGHT && w.hangs(x, y - 1 - count, z)) {
            count++;
        }
        return count;
    }

    // breaking (x,y,z) drops the stalactites under it, and they take everything from there down to the floor with them.
    // true when that path crosses the cells lowY..highY of this column
    public static boolean hangersFallOn(Cells w, int x, int y, int z, int lowY, int highY) {
        int count = hangingBelow(w, x, y, z);
        if (count == 0) {
            return false;
        }
        int landing = landingY(w, x, y - count, z);
        return landing <= highY && y - 1 >= lowY;
    }

    // ticks from the support going to the whole stack sitting still: each block ticks two ticks after the one under it
    // left, then the last one has to drop. gravity is 0.04 and drag 0.98, same as FallingBlockEntity
    public static int settleTicks(int height, int drop) {
        if (height <= 0) {
            return 0;
        }
        return SCHEDULE_TICKS * height + dropTicks(drop);
    }

    // ticks for one falling block to cover `drop` blocks. applyGravity, move, then drag, in that order
    public static int dropTicks(int drop) {
        double velocity = 0;
        double fallen = 0;
        int ticks = 0;
        while (fallen < drop && ticks < 200) {
            velocity -= 0.04;
            fallen -= velocity;
            velocity *= 0.98;
            ticks++;
        }
        return ticks;
    }
}
