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
import java.util.Iterator;

// (the buddy's fork, S137, the repair after smoke8; Tuning risk_goal_spot, on under risk_flee) the way the buddy came: the
// blocks it stood on, a new one each time it is STEP blocks from the last, the newest last. a spot every goal fails from is
// left back along it (SpotFails). pure, the clock is ticks; the game thread alone calls it
public final class Trail {
    // a new point once the feet are this far from the last one
    static final double STEP = 2;
    // at most this many kept, the oldest dropped first
    static final int MAX = 64;

    public record Point(int x, int y, int z, long at) {
    }

    private final ArrayDeque<Point> points = new ArrayDeque<>();

    // the feet on the ground at this tick
    public void note(int x, int y, int z, long now) {
        Point last = points.peekLast();
        if (last != null && now >= last.at && dist(last, x, y, z) < STEP) return;
        // (a clock that went back: a new world, the trail starts over)
        if (last != null && now < last.at) points.clear();
        points.addLast(new Point(x, y, z, now));
        while (points.size() > MAX) points.removeFirst();
    }

    // the newest point at least min from the feet and no older than maxAge ticks: where the buddy last stood before it came
    // here. null for none
    public Point back(int x, int y, int z, double min, long maxAge, long now) {
        for (Iterator<Point> it = points.descendingIterator(); it.hasNext(); ) {
            Point p = it.next();
            if (now < p.at || now - p.at > maxAge) return null;
            if (dist(p, x, y, z) >= min) return p;
        }
        return null;
    }

    public void clear() {
        points.clear();
    }

    private static double dist(Point p, int x, int y, int z) {
        double dx = p.x - x;
        double dy = p.y - y;
        double dz = p.z - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
