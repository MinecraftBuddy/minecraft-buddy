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

package baritone.behavior;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

// (the buddy's fork, S137, the repair after smoke8; Tuning risk_goal_spot, on under risk_flee) the failed searches by the spot
// they started from, whatever their goal: a timed out one and one exhausted at once count here too. a spot that many goals
// failed from is the trouble, not the goals: smoke8 r3's wart errand failed 47 times from one patch of 4 blocks in 7 minutes,
// its 24 look points each a new goal failing one round (2 s time-outs), and 179's give-up per goal never held there. pure:
// the spot is three ints, the goal a key (its text: a goal built anew per request is still the same goal), the clock ticks;
// every call holds this object's lock (the note comes from the search's thread, the question from the game's)
public final class SpotFails {
    // at most this many kept, the oldest dropped first
    static final int MAX = 64;

    private record Fail(int x, int y, int z, String goal, long at) {
    }

    private final ArrayDeque<Fail> fails = new ArrayDeque<>();
    private final double range;
    private final long ticks;

    // range: how far apart two starts are one spot; ticks: how long a failure counts
    public SpotFails(double range, long ticks) {
        this.range = range;
        this.ticks = ticks;
    }

    public synchronized void note(int x, int y, int z, String goal, long now) {
        if (goal == null) return;
        fails.addLast(new Fail(x, y, z, goal, now));
        while (fails.size() > MAX) fails.removeFirst();
    }

    // the goals that failed from within range of this spot within the window (a clock that went back counts as long ago)
    public synchronized int goals(int x, int y, int z, long now) {
        Set<String> seen = new HashSet<>();
        for (Iterator<Fail> it = fails.iterator(); it.hasNext(); ) {
            Fail f = it.next();
            if (now < f.at || now - f.at > ticks) {
                it.remove();
                continue;
            }
            double dx = f.x - x;
            double dy = f.y - y;
            double dz = f.z - z;
            if (dx * dx + dy * dy + dz * dz <= range * range) seen.add(f.goal);
        }
        return seen.size();
    }

    // (the repair after smoke9, risk_goal_rounds) the rounds this goal failed from within range of this spot within the window
    public synchronized int rounds(int x, int y, int z, String goal, long now) {
        if (goal == null) return 0;
        int n = 0;
        for (Fail f : fails) {
            if (now < f.at || now - f.at > ticks || !f.goal.equals(goal)) continue;
            double dx = f.x - x;
            double dy = f.y - y;
            double dz = f.z - z;
            if (dx * dx + dy * dy + dz * dz <= range * range) n++;
        }
        return n;
    }

    public synchronized void clear() {
        fails.clear();
    }
}
