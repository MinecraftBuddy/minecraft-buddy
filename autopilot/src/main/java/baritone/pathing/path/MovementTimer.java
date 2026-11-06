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

package baritone.pathing.path;

import baritone.Baritone;
import baritone.api.pathing.movement.ActionCosts;
import baritone.behavior.PathingBehavior;
import baritone.pathing.movement.Movement;
import baritone.pathing.timing.TimingSample;
import baritone.pathing.timing.TimingSample.Floor;
import baritone.pathing.timing.TimingSink;
import baritone.utils.BlockStateInterface;
import baritone.utils.ExperimentalMovement;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.phys.Vec3;

// what PathExecutor feeds to find out how long movements really take. it holds at most one movement at a time, the one
// whose first tick it saw, and only turns it into a sample if that same movement says SUCCESS having been ticked on
// every single game tick in between. the executor's own ticksOnCurrent is that tick count (it goes up once per update()
// that didn't finish, and is zeroed whenever the position changes), and the player's tickCount is the game's clock, so
// the two agreeing means nothing paused, resynced or skipped in between. no per tick work lives here at all
final class MovementTimer {

    private static final ClassValue<String> KINDS = new ClassValue<>() {
        @Override
        protected String computeValue(Class<?> type) {
            String name = type.getSimpleName();
            if (name.isEmpty()) {
                return type.getName();
            }
            return name.startsWith("Movement") && name.length() > "Movement".length() ? name.substring("Movement".length()) : name;
        }
    };
    // squared blocks per tick. walking is 0.2 a tick, so this is well under a step
    private static final double STILL_SPEED_SQ = 0.03 * 0.03;

    private Movement pending;
    // the last few movements begun, by identity, see begin()
    private final Movement[] seen = new Movement[16];
    private int seenAt;
    private int startTick;
    private float planned;
    private float recost;
    private int breaks;
    private int places;
    private boolean sprint;
    private boolean water;
    private boolean still;
    private boolean fast;
    private Floor floor;
    private TimingSink sink;

    // first update() of a movement, called before it runs. planned is the cost from when the path was calculated,
    // recost is what the executor's recheck just came up with
    void begin(PathingBehavior behavior, Movement movement, BlockStateInterface bsi, double plannedCost, double recheckedCost) {
        pending = null;
        if (!Baritone.settings().recordMovementTimings.value) {
            return;
        }
        // a movement we've started before (a resync back, a flight or a boat landing in its src again) has half its work
        // done and its cached cost has been overwritten by the executor's rechecks, so it isn't what the planner charged
        boolean again = false;
        for (Movement seenBefore : seen) {
            again |= seenBefore == movement;
        }
        seen[seenAt] = movement;
        seenAt = (seenAt + 1) % seen.length;
        if (again) {
            return;
        }
        // a movement that wasn't started from its own src (a skip ahead, a resync, the sprint tricks that hop a position)
        // would time a part of it and look fast
        if (!behavior.ctx.playerFeet().equals(movement.getSrc())) {
            return;
        }
        var player = behavior.ctx.player();
        // eating or drawing a bow slows the walk, and that isn't the movement's doing
        if (player.isUsingItem()) {
            return;
        }
        planned = (float) plannedCost;
        recost = recheckedCost < ActionCosts.COST_INF ? (float) recheckedCost : -1;
        breaks = movement.toBreak(bsi).size();
        places = movement.toPlace(bsi).size();
        sprint = player.isSprinting();
        water = player.isInWater();
        Vec3 velocity = player.getDeltaMovement();
        still = velocity.x * velocity.x + velocity.z * velocity.z < STILL_SPEED_SQ;
        fast = ExperimentalMovement.on();
        floor = floorUnder(bsi, movement);
        startTick = player.tickCount;
        pending = movement;
    }

    // shouldSprintNextTick can skip ahead and tick the next movement from inside it, and then the outer frame bumps
    // ticksOnCurrent a second time for that movement. its count is one ahead of the clock from then on, which a one tick
    // pause would cancel out, so a pending movement that isn't the one this frame just ran is forgotten
    void afterSprint(Movement ran) {
        if (pending != ran) {
            pending = null;
        }
    }

    // the movement just reported SUCCESS. ticks is the executor's count of updates it needed before this one
    void finish(PathingBehavior behavior, Movement movement, int ticks) {
        if (pending != movement) {
            return;
        }
        pending = null;
        // anything that ate a game tick without calling update() on this movement (a pause, the falling block hold, a
        // chain taking control, a shortcut or a flight) leaves the clock ahead of the count
        if (behavior.ctx.player().tickCount - startTick != ticks) {
            return;
        }
        // succeeding on the very tick it started means we were already standing where it ends, nothing was timed.
        // the falling block hold does call update() every tick (it comes back PREPPING), so the clock can't see it
        if (ticks == 0 || movement.wasHeldForFall() || behavior.ctx.player().isUsingItem()) {
            return;
        }
        if (sink == null) {
            sink = TimingSink.shared(behavior.baritone.getDirectory());
        }
        var src = movement.getSrc();
        var dest = movement.getDest();
        sink.offer(new TimingSample(KINDS.get(movement.getClass()), dest.x - src.x, dest.y - src.y, dest.z - src.z,
                planned, ticks, recost, breaks, places, sprint, water, still, floor, fast));
    }

    private static Floor floorUnder(BlockStateInterface bsi, Movement movement) {
        var src = movement.getSrc();
        Block block = bsi.get0(src.x, src.y - 1, src.z).getBlock();
        if (block == Blocks.SOUL_SAND) {
            return Floor.SOUL_SAND;
        }
        if (block == Blocks.MAGMA_BLOCK) {
            return Floor.MAGMA;
        }
        if (block == Blocks.ICE || block == Blocks.PACKED_ICE || block == Blocks.BLUE_ICE || block == Blocks.FROSTED_ICE) {
            return Floor.ICE;
        }
        if (block instanceof StairBlock) {
            return Floor.STAIRS;
        }
        if (block instanceof SlabBlock) {
            return Floor.SLAB;
        }
        return Floor.PLAIN;
    }
}
