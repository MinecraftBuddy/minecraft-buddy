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

// the numbers behind "should we even ask". pure, everything in ticks
public final class TripMath {

    // a what-if path has to get at least this many blocks' worth of heuristic closer to count as getting somewhere.
    // same five blocks A* wants before it calls something a path (MIN_DIST_PATH)
    static final double MIN_PROGRESS_BLOCKS = 5;

    private TripMath() {
    }

    // a couple extra for the block that gets dropped in lava or the bridge that turns out one longer. ten percent on
    // top for long ones, a 100 block bridge is not going to be exactly 100
    public static int buffer(int placements) {
        return placements <= 0 ? 0 : 2 + placements / 10;
    }

    // how many to go and get. what we hold already counts
    public static int deficit(int placements, int have) {
        if (placements <= 0) {
            return 0;
        }
        return Math.max(0, placements + buffer(placements) - Math.max(0, have));
    }

    // what the gather itself costs, so a "faster" path that needs ten minutes of digging first isn't faster
    public static double gatherTicks(int blocks, boolean pickaxe, double ticksPerBlock, double pickaxeTicks) {
        return Math.max(0, blocks) * Math.max(0, ticksPerBlock) + (pickaxe ? Math.max(0, pickaxeTicks) : 0);
    }

    // both thresholds have to hold: a third faster on a 20 second walk is not worth a question, and neither is
    // 30 seconds saved on an hour long trip. the gather counts against the faster path
    public static boolean worthAsking(double realTicks, double whatIfTicks, double gatherTicks, double minPercent, double minSeconds) {
        if (!finite(realTicks) || !finite(whatIfTicks) || !finite(gatherTicks) || realTicks <= 0) {
            return false;
        }
        double saved = realTicks - (whatIfTicks + gatherTicks);
        double need = Math.max(realTicks * Math.max(0, minPercent) / 100, Math.max(0, minSeconds) * 20);
        return saved > 0 && saved >= need;
    }

    // a stuck what-if is only worth a question if it actually gets us somewhere: reaches the goal, or ends a good few
    // blocks closer than where we stand
    public static boolean madeProgress(boolean reachesGoal, double startHeuristic, double endHeuristic, double ticksPerBlock) {
        if (reachesGoal) {
            return true;
        }
        if (!finite(startHeuristic) || !finite(endHeuristic)) {
            return false;
        }
        return startHeuristic - endHeuristic >= MIN_PROGRESS_BLOCKS * ticksPerBlock;
    }

    private static boolean finite(double d) {
        return !Double.isNaN(d) && !Double.isInfinite(d);
    }
}
