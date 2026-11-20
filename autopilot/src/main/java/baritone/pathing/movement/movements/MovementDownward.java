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

import baritone.api.IBaritone;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import com.google.common.collect.ImmutableSet;
import java.util.Set;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public class MovementDownward extends Movement {

    private int numTicks = 0;

    public MovementDownward(IBaritone baritone, BetterBlockPos start, BetterBlockPos end) {
        super(baritone, start, end, new BetterBlockPos[]{end});
    }

    @Override
    public void reset() {
        super.reset();
        numTicks = 0;
    }

    @Override
    public double calculateCost(CalculationContext context) {
        return cost(context, src.x, src.y, src.z);
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        return ImmutableSet.of(src, dest);
    }

    public static double cost(CalculationContext context, int x, int y, int z) {
        if (!context.allowDownward) {
            return COST_INF;
        }
        if (!MovementHelper.canWalkOn(context, x, y - 2, z)) {
            return COST_INF;
        }
        BlockState down = context.get(x, y - 1, z);
        Block downBlock = down.getBlock();
        if (downBlock == Blocks.LADDER || downBlock == Blocks.VINE) {
            return LADDER_DOWN_ONE_COST;
        } else {
            // we're standing on it, while it might be block falling, it'll be air by the time we get here in the movement
            // we fall the moment the block goes, and the wait after the break is spent falling
            double hardness = MovementHelper.getMiningDurationTicks(context, x, y - 1, z, down, false);
            // diving out from under a boat is leaving it, same as climbing out. the cell below has water over it so it is never a row
            return FALL_N_BLOCKS_COST[1] + context.boatHandoff(x, y, z, x, y - 1, z) + MovementHelper.waterBreakCost(context, x, y, z, hardness) - MovementHelper.breakWaitCredit(context, hardness, FALL_N_BLOCKS_COST[1]);
        }
    }

    // (Tuning downward_on_cell, a fix of 20 Nov 2026, on) the block under src is broken only with the feet on src: a descend that
    // landed 0.68 past its cell left the buddy's middle over a 28-block shaft beside it, and the break of the block it rested on
    // dropped it down the shaft (heel-103-mc3). off the cell with the block still there, the walk back onto it first
    @Override
    protected boolean prepared(MovementState state) {
        BetterBlockPos feet = ctx.playerFeet();
        if (state.getStatus() != MovementStatus.WAITING && adris.altoclef.util.helpers.Tuning.get("downward_on_cell", 0) > 0
                && !feet.equals(src) && !feet.equals(dest) && !MovementHelper.canWalkThrough(ctx, positionsToBreak[0])) {
            MovementHelper.moveTowards(ctx, state, src);
            return false;
        }
        return super.prepared(state);
    }

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }

        if (ctx.playerFeet().equals(dest)) {
            return state.setStatus(MovementStatus.SUCCESS);
        } else if (!playerInValidPosition()) {
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        double diffX = ctx.player().position().x - (dest.getX() + 0.5);
        double diffZ = ctx.player().position().z - (dest.getZ() + 0.5);
        double ab = Math.sqrt(diffX * diffX + diffZ * diffZ);

        if (numTicks++ < 10 && ab < 0.2) {
            return state;
        }
        MovementHelper.moveTowards(ctx, state, positionsToBreak[0]);
        return state;
    }
}
