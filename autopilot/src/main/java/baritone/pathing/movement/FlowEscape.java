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

import baritone.utils.BlockStateInterface;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

// (the buddy's fork, Tuning flow_escape, a fix of 19 Nov 2026, on) out of a current the way a player wades out. the search
// refuses flowing water (MovementHelper.canWalkThroughPosition), so a buddy standing in a current with water on every side
// had no first move at all: look 135 stood 14 minutes beside lava on a natural obsidian floor while about 7900 searches
// came back with 0 nodes, and the run stalled. a search that starts with the feet in flowing water notes the spot; for
// HOLD_TICKS after, every context built lets the water within RANGE of it be walked through at the water price, and the
// executor wades through water in the way instead of clicking at it (water cannot be broken). lava is never in it.
public final class FlowEscape {
    public static final int RANGE = 5;
    static final int BELOW = 2;
    static final int ABOVE = 3;
    public static final long HOLD_TICKS = 600;

    // the spot a search started from with the feet in a current
    public record Box(int x, int y, int z) {
        public boolean passes(int px, int py, int pz, BlockState state) {
            if (!isWater(state)) {
                return false;
            }
            int dy = py - y;
            return Math.abs(px - x) <= RANGE && Math.abs(pz - z) <= RANGE && dy >= -BELOW && dy <= ABOVE;
        }
    }

    private static volatile Box box;
    private static volatile long noted = Long.MIN_VALUE;

    private FlowEscape() {
    }

    public static boolean on() {
        return adris.altoclef.util.helpers.Tuning.get("flow_escape", 0) > 0;
    }

    // the water block itself, source or flowing; not a waterlogged block, not a bubble column (a column drags)
    public static boolean isWater(BlockState state) {
        return state.getBlock() == Blocks.WATER;
    }

    // the feet in a current: water that flows or has a current beside it, as the search's own test reads it
    public static boolean inCurrent(BlockStateInterface bsi, int x, int y, int z) {
        BlockState feet = bsi.get0(x, y, z);
        return isWater(feet) && MovementHelper.isFlowing(x, y, z, feet, bsi);
    }

    // a search starts at (x, y, z) with the feet in a current: note it. true when the spot is new or the last note had lapsed
    public static synchronized boolean note(int x, int y, int z, long now) {
        Box was = box;
        boolean fresh = was == null || now < noted || now - noted > HOLD_TICKS || was.x != x || was.y != y || was.z != z;
        box = new Box(x, y, z);
        noted = now;
        return fresh;
    }

    // the box every context built now carries, or null
    public static Box current(long now) {
        Box b = box;
        long at = noted;
        if (b == null || now < at || now - at > HOLD_TICKS) {
            return null;
        }
        return b;
    }

    // with the feet in water, a water block in the way is waded through, never clicked at
    public static boolean wades(BlockState feet, BlockState inTheWay) {
        return isWater(feet) && isWater(inTheWay);
    }

    // tests
    static synchronized void reset() {
        box = null;
        noted = Long.MIN_VALUE;
    }
}
