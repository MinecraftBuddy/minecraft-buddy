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

package baritone.behavior.trip;

import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.pathing.goals.GoalYLevel;
import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.PathCalculationResult;
import baritone.api.utils.interfaces.IGoalRenderPos;
import baritone.pathing.calc.AStarPathFinder;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.utils.pathing.Favoring;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

// one search from where we stand, and what its path would ask of the bag. runs on the trip executor, never on the
// pathing pool: a what-if must not hold up the real search the bot is actually waiting on
public final class WhatIfSearch {

    // what one search found, read off the path on the search thread so nobody has to keep the path around
    public record Result(boolean found, boolean reachesGoal, double estTicks, double startHeuristic, double endHeuristic, int placements, boolean breaksStone) {

        static Result none(double startHeuristic) {
            return new Result(false, false, Double.POSITIVE_INFINITY, startHeuristic, startHeuristic, 0, false);
        }
    }

    private WhatIfSearch() {
    }

    // same simplification PathingBehavior.createPathfinder does, a block goal in an unloaded chunk is searched as an xz
    static Goal searchGoal(Goal goal, CalculationContext context) {
        if (Baritone.settings().simplifyUnloadedYCoord.value && goal instanceof IGoalRenderPos render) {
            BlockPos pos = render.getGoalPos();
            if (!context.bsi.worldContainsLoadedChunk(pos.getX(), pos.getZ())) {
                return new GoalXZ(pos.getX(), pos.getZ());
            }
        }
        return goal;
    }

    // the search thread. the context has to be safeForThreadedUse and nobody else's
    public static Result run(CalculationContext context, BetterBlockPos start, Goal goal, long timeoutMs) {
        // GoalYLevel prices a drop off the context's descent cost, and only inside this window. the search opens its own,
        // the before and after numbers need it too or estTicks isn't in the same units the search used
        GoalYLevel.setDescentTicksPerBlock(context.descentTicksPerBlock());
        try {
            return search(context, start, goal, timeoutMs);
        } finally {
            GoalYLevel.resetDescentTicksPerBlock();
        }
    }

    private static Result search(CalculationContext context, BetterBlockPos start, Goal goal, long timeoutMs) {
        Goal searched = searchGoal(goal, context);
        double startH = searched.heuristic(start);
        // no favoring and no mob avoidance: both the real and the what-if search get the same nothing, so the comparison
        // is between bags and not between moods
        AStarPathFinder finder = new AStarPathFinder(start, start.x, start.y, start.z, searched, new Favoring(null, context), context);
        finder.quiet();
        PathCalculationResult result = finder.calculate(timeoutMs, timeoutMs);
        // the finder resets the window on its way out, put it back for the end heuristic
        GoalYLevel.setDescentTicksPerBlock(context.descentTicksPerBlock());
        Optional<IPath> path = result.getPath();
        if (path.isEmpty()) {
            return Result.none(startH);
        }
        return analyze(path.get(), searched, context, startH);
    }

    static Result analyze(IPath path, Goal goal, CalculationContext context, double startH) {
        // g + h of where the path ends: what it cost to get there plus the heuristic's guess for the rest. both in ticks
        double cost = path.ticksRemainingFrom(0);
        BetterBlockPos dest = path.getDest();
        double endH = goal.heuristic(dest);
        Set<BlockPos> place = new HashSet<>();
        boolean stone = false;
        for (IMovement m : path.movements()) {
            if (!(m instanceof Movement movement)) {
                continue;
            }
            // toPlace asks the world as it is now. a pillar or a bridge never places on the same spot twice, the set is
            // only there for the odd path that crosses itself
            place.addAll(movement.toPlace(context.bsi));
            if (!stone) {
                for (BlockPos b : movement.toBreak(context.bsi)) {
                    if (context.bsi.get0(b.getX(), b.getY(), b.getZ()).is(BlockTags.MINEABLE_WITH_PICKAXE)) {
                        stone = true;
                        break;
                    }
                }
            }
        }
        return new Result(true, goal.isInGoal(dest), cost + endH, startH, endH, place.size(), stone);
    }
}
