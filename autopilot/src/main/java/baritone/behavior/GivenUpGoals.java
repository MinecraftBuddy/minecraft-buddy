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

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

// (the buddy's fork, S137, the repair after smoke7; Tuning risk_goal_once, on under risk_flee) the goals given up from a block:
// a round of search from that block found no way to that goal, so the same goal is not searched again from there for a while,
// it fails at once. smoke7 r2 searched one camp walk 14 times from one block in 42 s (with blaze chases between, which took
// the single slot the repair after smoke6 kept), and r1 one brewing stand's sides 21 times from one block in 2.5 minutes. a
// task hears the failure at once; what it does with it (a ban, a wander, another camp) is its own. pure, S is the block and G
// the goal (both compared by equals), the clock is ticks; every call holds this object's lock (the note comes from the search's
// thread, the question from the game's)
public final class GivenUpGoals<S, G> {
    // at most this many kept, the oldest dropped first
    static final int MAX = 16;

    private record Key<S, G>(S start, G goal) {
    }

    private static final class Entry {
        final long at;
        boolean said;

        Entry(long at) {
            this.at = at;
        }
    }

    private final long ticks;
    private final Map<Key<S, G>, Entry> given = new LinkedHashMap<>();

    public GivenUpGoals(long ticks) {
        this.ticks = ticks;
    }

    // a round from start to goal found no way at this tick
    public synchronized void note(S start, G goal, long now) {
        if (start == null || goal == null) return;
        Key<S, G> key = new Key<>(start, goal);
        given.remove(key);
        given.put(key, new Entry(now));
        while (given.size() > MAX) {
            Iterator<Key<S, G>> oldest = given.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
    }

    // the goal was given up from start within the window (a clock that went back counts as long ago)
    public synchronized boolean holds(S start, G goal, long now) {
        if (start == null || goal == null) return false;
        Entry e = given.get(new Key<>(start, goal));
        if (e == null) return false;
        if (now < e.at || now - e.at > ticks) {
            given.remove(new Key<>(start, goal));
            return false;
        }
        return true;
    }

    // the ticks since it was given up, once per entry (for its one line); -1 after the first ask or with none held
    public synchronized long sayOnce(S start, G goal, long now) {
        if (!holds(start, goal, now)) return -1;
        Entry e = given.get(new Key<>(start, goal));
        if (e.said) return -1;
        e.said = true;
        return now - e.at;
    }

    public synchronized void clear() {
        given.clear();
    }

    synchronized int size() {
        return given.size();
    }
}
