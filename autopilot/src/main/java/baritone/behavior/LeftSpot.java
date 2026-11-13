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

import baritone.api.utils.BetterBlockPos;

import java.util.List;

// (the buddy's fork, S137, the repair after smoke9; Tuning risk_trap_back, on under risk_flee) the spot a walk back left: every
// goal failed from around it (SpotFails), and the task's goal was one of them. a way to that goal found from outside the spot
// that leads back into it is no way: smoke9 r3 walked back 8 blocks from a rim its searches timed out on, the next search led
// straight back onto it, 8 more time-outs, a wander, and a ghast's ball knocked the buddy off it into a lava sea. pure, the
// goal is a key (its text), the clock ticks; every call holds this object's lock (the note comes from the game's thread, the
// question from the search's)
public final class LeftSpot {

    private record Spot(String goal, int x, int y, int z, long at) {
    }

    private final double range;
    private final long ticks;
    private Spot spot;

    // range: how close to the spot a way comes back into it; ticks: how long the spot is kept
    public LeftSpot(double range, long ticks) {
        this.range = range;
        this.ticks = ticks;
    }

    // a walk back leaves the spot (x, y, z) that this goal failed from, at this tick; one spot at a time, the newest
    public synchronized void left(String goal, int x, int y, int z, long now) {
        if (goal == null) return;
        spot = new Spot(goal, x, y, z, now);
    }

    // the way from start to this goal through these positions comes back into the spot it left lately: the start is out of
    // the spot, a position on the way is in it (a clock that went back counts as long ago)
    public synchronized boolean leadsBack(String goal, int sx, int sy, int sz, List<BetterBlockPos> way, long now) {
        if (spot == null || goal == null) return false;
        if (now < spot.at || now - spot.at > ticks) {
            spot = null;
            return false;
        }
        if (!spot.goal.equals(goal) || near(sx, sy, sz)) return false;
        for (BetterBlockPos p : way) {
            if (near(p.x, p.y, p.z)) return true;
        }
        return false;
    }

    // the ticks since the spot was left, -1 for none
    public synchronized long ago(long now) {
        return spot == null || now < spot.at ? -1 : now - spot.at;
    }

    // the spot as text, "-" for none
    public synchronized String where() {
        return spot == null ? "-" : spot.x + "," + spot.y + "," + spot.z;
    }

    private boolean near(int x, int y, int z) {
        double dx = spot.x - x;
        double dy = spot.y - y;
        double dz = spot.z - z;
        return dx * dx + dy * dy + dz * dz <= range * range;
    }
}
