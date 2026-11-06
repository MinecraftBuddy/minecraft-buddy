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

package baritone.pathing.calc;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalTwoBlocks;
import baritone.pathing.movement.CalculationContext;
import net.minecraft.world.level.block.Blocks;

// a nether portal has no collision box, so as far as every movement is concerned it is air with a nice texture. walking
// through one is harmless (the game wants 4 seconds inside before it sends you anywhere) but a path that stops in one, to
// place, to break, to wait for a replan, is a path that wakes up in the other dimension. so a node in a portal costs about
// what those 4 seconds do, unless that's where we're going: #gamer and GetToBlockProcess both aim goals into the portal
public final class PortalAvoidance {

    // 80 ticks is the survival portal delay, so a portal in the way is only worth it for a detour longer than the wait
    public static final double NODE_COST = 80;

    private PortalAvoidance() {}

    // the extra cost of landing a movement on (x, y, z), feet and head. two lookups the movement just made itself, so they're
    // cache hits, and the goal is only asked when there's actually a portal here
    public static double penalty(Goal goal, CalculationContext context, int x, int y, int z) {
        if (context.get(x, y, z).getBlock() != Blocks.NETHER_PORTAL && context.get(x, y + 1, z).getBlock() != Blocks.NETHER_PORTAL) {
            return 0;
        }
        return aimedAt(goal, x, y, z) ? 0 : NODE_COST;
    }

    // only a goal that is about the portal itself gets in free. GoalNear / GoalXZ and friends accept a portal cell too, and a
    // path that ends by standing in one by accident is exactly what this is for
    static boolean aimedAt(Goal goal, int x, int y, int z) {
        if (goal instanceof GoalComposite) {
            for (Goal part : ((GoalComposite) goal).goals()) {
                if (aimedAt(part, x, y, z)) {
                    return true;
                }
            }
            return false;
        }
        return (goal instanceof GoalBlock || goal instanceof GoalTwoBlocks) && goal.isInGoal(x, y, z);
    }
}
