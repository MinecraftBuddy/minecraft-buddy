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

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

// water with a low roof over it, where we and the server disagree about what shape we are. MixinEntity latches our swim
// state at the surface, but the server only starts swimming with its standing eye under water, so up there it still has
// us 1.8 tall. holding the waterline in a 0.6 box then asks for moves its box can't make, it rejects the whole position
// x and z included, and we hover in place feeling great about it. so under a roof like this we stay standing too
public final class LowRoof {

    static final double STANDING_HEIGHT = 1.8;
    static final double STANDING_EYE = 1.62;
    // vanilla checks the eye a ninth of a block below where it really is
    static final double EYE_CHECK_DROP = 0.11111111;

    // 0.875 blocks of sinking at no sprint in water, same budget as SwimEscape.SINK_TICKS. the room is about 0.6 lower at most
    static final int LIP_TICKS = 40;

    enum Forward {
        GO, HOLD, GIVE_UP
    }

    interface Column {
        // top of the water here, NaN when this column has no water at our level
        double waterTop();

        // block collisions only: a boat overhead is no reason to stand up, and an exact fit has to pass
        boolean standingFits(double feetY);

        // the lowest thing a standing box with its feet at this height runs into. only asked when standingFits said no
        double roofOver(double feetY);
    }

    private LowRoof() {}

    // where holdWaterline wants the feet. it asks here too, so the two can't drift apart
    static double waterline(double waterTop, int destY) {
        return Math.max(waterTop - Movement.WATERLINE_DEPTH, destY + Movement.DEST_CLEARANCE);
    }

    // the only question that matters is the server's: at the highest its standing box fits, is its eye under water. if it
    // is (a lily pad, a flooded tunnel) the server starts swimming too, we agree, keep swimming. if it isn't it stays 1.8
    // tall and we had better do the same
    static boolean tooLow(Column column, int destY) {
        double top = column.waterTop();
        if (Double.isNaN(top)) {
            return false;
        }
        double target = waterline(top, destY);
        if (column.standingFits(target)) {
            return false;
        }
        double cap = column.roofOver(target) - STANDING_HEIGHT;
        return cap + STANDING_EYE - EYE_CHECK_DROP >= top;
    }

    // walks up from the feet to the top of the water. the player's fluid height only knows cells in its own box and
    // state.is(WATER) misses kelp, seagrass and anything waterlogged, so the fluid state it is. feet can be above the top
    // and still count as in water, which is what the one block down is for
    static double waterTop(BlockGetter world, int x, int feetY, int z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, feetY, z);
        if (!isWater(world.getFluidState(pos))) {
            pos.setY(feetY - 1);
            if (!isWater(world.getFluidState(pos))) {
                return Double.NaN;
            }
        }
        BlockPos.MutableBlockPos above = new BlockPos.MutableBlockPos();
        for (int up = 0; up < Surfacing.REACH; up++) {
            above.set(pos.getX(), pos.getY() + 1, pos.getZ());
            if (!isWater(world.getFluidState(above))) {
                return pos.getY() + (double) world.getFluidState(pos).getHeight(world, pos);
            }
            pos.set(above);
        }
        return Double.NaN; // an ocean over our head is nobody's low roof
    }

    private static boolean isWater(FluidState fluid) {
        return fluid.getType() == Fluids.WATER || fluid.getType() == Fluids.FLOWING_WATER;
    }

    // only from under the roof, and only once we actually are a standing box. the client can sit in the swim pose with swim
    // off until the standing box fits, and holding jump then is what stops it ever sinking far enough to fit. a move going
    // down doesn't want it either, it would pin that one to the roof
    static boolean jump(boolean ownLow, boolean standingPose, double feetY, int destY) {
        return ownLow && standingPose && feetY < destY + Movement.BOB_CLEARANCE;
    }

    // traverse's wrong y branch lets go of W and waits for the bob to lift it to dest's level. under a roof that lift may
    // never come, a top slab over deep water caps the feet a block under dest. so we hold W for it. level moves only, a
    // climb has its own W, and hands off a forced aim: that's somebody opening a gate or placing a block, it needs the crosshair
    static boolean leanIn(Forward forward, boolean running, boolean forcedAim, int feetBlock, int srcY, int destY) {
        return forward == Forward.GO && running && !forcedAim && srcY == destY && feetBlock < destY;
    }

    // the walk in from open water. vanilla hops 0.3 out of a fluid whenever we're pressed against something with room
    // above us, so holding W at the lip of a low room hops forever and never gets under. sink first, then go
    static final class Lip {

        private int held;

        // running is false while the movement is still prepping: the keys stay off but there's no clock on mining a block
        Forward tick(boolean ownLow, boolean destLow, boolean fitsAtDest, boolean running) {
            if (ownLow || !destLow || fitsAtDest) {
                return Forward.GO;
            }
            if (!running) {
                return Forward.HOLD;
            }
            return held++ < LIP_TICKS ? Forward.HOLD : Forward.GIVE_UP;
        }
    }
}
