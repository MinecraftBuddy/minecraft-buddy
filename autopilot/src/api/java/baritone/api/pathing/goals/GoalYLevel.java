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

package baritone.api.pathing.goals;

import baritone.api.pathing.movement.ActionCosts;
import baritone.api.utils.SettingsUtil;

/**
 * Useful for mining (getting to diamond / iron level)
 *
 * @author leijurv
 */
public class GoalYLevel implements Goal, ActionCosts {

    /**
     * The target Y level
     */
    public final int level;

    // what a block of descent costs at the cheapest, ticks and the hearts a fall past 3 blocks takes. by default that's the
    // free 3 block fall, 3.2 a block. the path finder tells us for the search it is running, see
    // CalculationContext.descentTicksPerBlock (the bucket and the ladder clutch are left out of it on purpose, see there).
    // it used to be half of a two block fall, 3.9 a block, which is what a staircase costs and not what a drop costs, so a
    // ten block drop looked like 39 ticks when it takes 17 and A* tried every staircase it could find before it would
    // consider jumping. the exception is a fall into deep water, which has no cap and gets down toward 0.3 a block
    private static final double DEFAULT_DESCENT_TICKS_PER_BLOCK = FALL_N_BLOCKS_COST[3] / 3;
    private static final ThreadLocal<double[]> DESCENT_TICKS_PER_BLOCK = ThreadLocal.withInitial(() -> new double[]{DEFAULT_DESCENT_TICKS_PER_BLOCK});

    /**
     * Sets what the heuristic charges per block of descent on this thread, the cheapest plain fall the search can plan,
     * with the bucket and the ladder clutch left out. Path finders call this around a search, everything else gets the
     * default of a free 3 block fall.
     *
     * @param ticksPerBlock the cheapest a block of descent can be, in ticks
     */
    public static void setDescentTicksPerBlock(double ticksPerBlock) {
        DESCENT_TICKS_PER_BLOCK.get()[0] = ticksPerBlock;
    }

    /**
     * Puts the descent estimate back to its default on this thread
     */
    public static void resetDescentTicksPerBlock() {
        DESCENT_TICKS_PER_BLOCK.get()[0] = DEFAULT_DESCENT_TICKS_PER_BLOCK;
    }

    public GoalYLevel(int level) {
        this.level = level;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        return y == level;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        return calculate(level, y);
    }

    public static double calculate(int goalY, int currentY) {
        if (currentY > goalY) {
            // need to descend
            return DESCENT_TICKS_PER_BLOCK.get()[0] * (currentY - goalY);
        }
        if (currentY < goalY) {
            // need to ascend
            return (goalY - currentY) * JUMP_ONE_BLOCK_COST;
        }
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        GoalYLevel goal = (GoalYLevel) o;
        return level == goal.level;
    }

    @Override
    public int hashCode() {
        return level * 1271009915;
    }

    @Override
    public String toString() {
        return String.format(
                "GoalYLevel{y=%s}",
                SettingsUtil.maybeCensor(level)
        );
    }
}
