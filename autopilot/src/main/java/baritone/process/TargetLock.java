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

package baritone.process;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

// a goal composite of every player takes the min heuristic, so whoever is cheapest at the next path
// boundary wins. two players on opposite sides = the bot turns around mid run, over and over.
// pick one and stick with it. no minecraft in here so the tests don't need a world
final class TargetLock<T> {

    // the client drops and re-adds entities at chunk edges, don't re-pick over a one tick gap
    static final int GRACE_TICKS = 20;

    private final Function<T, Object> key;
    private final ToDoubleFunction<T> distSqr;
    private final Comparator<T> tieBreak;
    private T locked;
    private int lostAt = -1;

    TargetLock(Function<T, Object> key, ToDoubleFunction<T> distSqr, Comparator<T> tieBreak) {
        this.key = key;
        this.distSqr = distSqr;
        this.tieBreak = tieBreak;
    }

    T locked() {
        return locked;
    }

    void clear() {
        locked = null;
        lostAt = -1;
    }

    // now has to be a tick counter, isActive and onTick both scan every tick and the grace would run out at double speed otherwise
    // gone = no point waiting out the grace: dead, or still right there but not something we follow anymore
    T update(List<T> candidates, int now, boolean gone) {
        T same = locked == null ? null : find(candidates, key.apply(locked));
        if (same != null) {
            // same key can be a fresh entity object after a re-add, follow the live one
            locked = same;
            lostAt = -1;
            return locked;
        }
        if (locked != null && !gone) {
            if (lostAt < 0) {
                lostAt = now;
            }
            // now < lostAt means tickCount started over (respawn, new dimension), that old entity isn't coming back
            if (now >= lostAt && now - lostAt < GRACE_TICKS) {
                return locked;
            }
        }
        locked = nearest(candidates, distSqr, tieBreak);
        lostAt = -1;
        return locked;
    }

    private T find(List<T> candidates, Object k) {
        for (T t : candidates) {
            if (Objects.equals(key.apply(t), k)) {
                return t;
            }
        }
        return null;
    }

    static <T> T nearest(List<T> candidates, ToDoubleFunction<T> distSqr, Comparator<T> tieBreak) {
        T best = null;
        double bestDist = Double.POSITIVE_INFINITY;
        for (T t : candidates) {
            double d = distSqr.applyAsDouble(t);
            // ties go to the comparator, not to whatever order the entity map felt like today
            if (best == null || d < bestDist || (d == bestDist && tieBreak.compare(t, best) < 0)) {
                best = t;
                bestDist = d;
            }
        }
        return best;
    }
}
