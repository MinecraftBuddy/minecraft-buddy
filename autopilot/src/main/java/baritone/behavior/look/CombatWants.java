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

package baritone.behavior.look;

// who gets the head this tick. the kill task, the aura and self defense can all ask in the same tick and each used to just
// write the look, so the last one to run won and the camera flicked between two mobs. now they ask, the highest priority
// ask wins (a tie goes to whoever asked first) and the look gets resolved once. also remembers who we were looking at so
// the smoother only starts its slow first ticks for a new target and not every time somebody asks again
public final class CombatWants<T, P> {

    // this many ticks with nobody asking and the next ask is a fresh start, even for the same mob
    public static final int IDLE_RESET = 20;

    private T who;
    private P point;
    private int priority = Integer.MIN_VALUE;

    private T engaged;
    private int idle = Integer.MAX_VALUE / 2;

    public void want(T entity, int priority, P point) {
        if (entity == null) {
            return;
        }
        if (who == null || priority > this.priority) {
            this.who = entity;
            this.point = point;
            this.priority = priority;
        }
    }

    public T who() {
        return who;
    }

    public P point() {
        return point;
    }

    // forget this tick's asks, resolved or not
    public void clear() {
        who = null;
        point = null;
        priority = Integer.MIN_VALUE;
    }

    // the head goes to `entity` this tick. true when that's a new engagement and the smoother should start over
    public boolean engage(T entity) {
        boolean fresh = entity != engaged || idle > IDLE_RESET;
        engaged = entity;
        idle = 0;
        return fresh;
    }

    // a tick where combat aim didn't turn the head (nobody asked, or it yielded to a path)
    public void idle() {
        if (idle < Integer.MAX_VALUE / 2) {
            idle++;
        }
    }

    public void reset() {
        clear();
        engaged = null;
        idle = Integer.MAX_VALUE / 2;
    }
}
