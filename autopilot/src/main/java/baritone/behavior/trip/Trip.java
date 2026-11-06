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

// one user trip (#goto, #come, waypoint goto, click to go) and what the user said about collecting blocks for it.
// immutable on purpose: the click lands on the client thread, the what-if answer comes back on its own thread, and
// the only thing they share is the one reference in TripBook. a stale click holds an old id and can't touch a new trip
public final class Trip {

    public enum Decision {
        UNASKED,
        PENDING,
        YES,
        NO
    }

    // which question got asked. there is only ever one per trip, whichever came first
    public enum Ask {
        STUCK,
        FASTER
    }

    public final long id;
    // the exact object the command handed to the custom goal process. identity, not equals: an altoclef task that wants
    // the same block is not this trip
    public final Goal goal;
    // whatever says "which dimension". an Object so the tests don't need a level
    public final Object dimension;
    public final Decision decision;
    public final Ask asked;
    // what the question offered, kept so a yes can start exactly that
    public final GatherPlan plan;
    public final int prompts;
    public final int gathers;
    public final boolean gathering;
    public final boolean fasterChecked;
    public final boolean toldNo;

    Trip(long id, Goal goal, Object dimension) {
        this(id, goal, dimension, Decision.UNASKED, null, null, 0, 0, false, false, false);
    }

    private Trip(long id, Goal goal, Object dimension, Decision decision, Ask asked, GatherPlan plan, int prompts, int gathers, boolean gathering, boolean fasterChecked, boolean toldNo) {
        this.id = id;
        this.goal = goal;
        this.dimension = dimension;
        this.decision = decision;
        this.asked = asked;
        this.plan = plan;
        this.prompts = prompts;
        this.gathers = gathers;
        this.gathering = gathering;
        this.fasterChecked = fasterChecked;
        this.toldNo = toldNo;
    }

    Trip asking(Ask what, GatherPlan plan) {
        return new Trip(id, goal, dimension, Decision.PENDING, what, plan, prompts + 1, gathers, gathering, fasterChecked, toldNo);
    }

    Trip answered(boolean yes) {
        return new Trip(id, goal, dimension, yes ? Decision.YES : Decision.NO, asked, plan, prompts, gathers, gathering, fasterChecked, toldNo);
    }

    Trip gathering(GatherPlan plan) {
        return new Trip(id, goal, dimension, decision, asked, plan, prompts, gathers + 1, true, fasterChecked, toldNo);
    }

    Trip gathered() {
        return new Trip(id, goal, dimension, decision, asked, plan, prompts, gathers, false, fasterChecked, toldNo);
    }

    // a gather that can't finish, or one too many. the trip goes on without blocks and never asks again
    Trip refused() {
        return new Trip(id, goal, dimension, Decision.NO, asked, plan, prompts, gathers, false, fasterChecked, toldNo);
    }

    Trip fasterChecked() {
        return new Trip(id, goal, dimension, decision, asked, plan, prompts, gathers, gathering, true, toldNo);
    }

    Trip toldNo() {
        return new Trip(id, goal, dimension, decision, asked, plan, prompts, gathers, gathering, fasterChecked, true);
    }

    @Override
    public String toString() {
        return "trip " + id + " " + decision + (asked == null ? "" : " " + asked) + (gathering ? " gathering" : "") + " gathers=" + gathers;
    }
}
