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

import baritone.api.pathing.goals.Goal;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

// the trip and every way it's allowed to change. pure, no minecraft in here, so the "never ask twice" promise is tested
// and not hoped for. every transition names the trip id it's for and is a compare-and-set, a click from the trip before
// (or one that raced a #stop) finds a different id and does nothing
public final class TripBook {

    private final AtomicReference<Trip> current = new AtomicReference<>();
    private final AtomicLong ids = new AtomicLong();

    // a user command just set this goal. whatever trip was going is over, its clicks are stale from here
    public Trip start(Goal goal, Object dimension) {
        Trip trip = new Trip(ids.incrementAndGet(), goal, dimension);
        current.set(trip);
        return trip;
    }

    public Trip current() {
        return current.get();
    }

    // the trip this goal belongs to, or null. identity on purpose, see Trip.goal
    public Trip forGoal(Goal goal) {
        Trip trip = current.get();
        return trip != null && goal != null && trip.goal == goal ? trip : null;
    }

    // arrived, stopped, died, changed dimension. returns the trip that just ended, null if there wasn't one
    public Trip end() {
        return current.getAndSet(null);
    }

    // only end it if it's still this one
    public boolean end(long id) {
        Trip trip = current.get();
        return trip != null && trip.id == id && current.compareAndSet(trip, null);
    }

    // UNASKED -> PENDING, once per trip, ever. null means somebody else got there first or it was already asked
    public Trip ask(long id, Trip.Ask what, GatherPlan plan) {
        return update(id, t -> t.decision == Trip.Decision.UNASKED ? t.asking(what, plan) : null);
    }

    // a click or a typed answer. only a pending question takes one, a second click on the same [Yes] is a no-op
    public Trip answer(long id, boolean yes) {
        return update(id, t -> t.decision == Trip.Decision.PENDING ? t.answered(yes) : null);
    }

    // a yes turns into a gather, at most maxGathers of them per trip. null when it isn't yes, one is already running,
    // or the cap is hit (the caller turns that last one into a no)
    public Trip startGather(long id, GatherPlan plan, int maxGathers) {
        return update(id, t -> t.decision == Trip.Decision.YES && !t.gathering && t.gathers < maxGathers ? t.gathering(plan) : null);
    }

    public Trip gatherDone(long id) {
        return update(id, t -> t.gathering ? t.gathered() : null);
    }

    // anything terminal on the way: inventory full, nothing to collect, the task failed, the cap. no from here on
    public Trip refuse(long id) {
        return update(id, t -> t.decision == Trip.Decision.NO && !t.gathering ? null : t.refused());
    }

    // the faster route check runs once per trip, whatever it finds
    public Trip checkFaster(long id) {
        return update(id, t -> t.fasterChecked ? null : t.fasterChecked());
    }

    // "you said no" gets said once per trip
    public Trip tellNo(long id) {
        return update(id, t -> t.decision == Trip.Decision.NO && !t.toldNo ? t.toldNo() : null);
    }

    private Trip update(long id, UnaryOperator<Trip> step) {
        while (true) {
            Trip trip = current.get();
            if (trip == null || trip.id != id) {
                return null;
            }
            Trip next = step.apply(trip);
            if (next == null) {
                return null;
            }
            if (current.compareAndSet(trip, next)) {
                return next;
            }
        }
    }
}
