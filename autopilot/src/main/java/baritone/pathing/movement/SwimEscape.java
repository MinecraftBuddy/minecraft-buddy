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

package baritone.pathing.movement;

// a swimmer whose cell keeps failing no matter which way out the planner picks (see FailedMoves) is not going to be
// saved by another replan, banning exits can't work when every path has to leave the start cell. holding jump only
// presses us into the same spot: floating that high there's no room to stand under a low ceiling, so the swim pose
// never lets go. let go of everything and sink instead. no game in here so it can be tested on its own
final class SwimEscape {

    enum Step {
        // not our problem, swim like normal
        NONE,
        // hands off the keys, gravity does the work
        SINK,
        // on the bottom, walk the move instead of swimming it. swimming from here is what floats us straight back up to
        // the spot we just left
        WALK,
        // sunk as far as this movement can use, hand the path back so the replan starts from down here
        GIVE_BACK
    }

    // two exits can be plain bad luck in a tight spot, and this costs two seconds of doing nothing
    static final int MIN_EXITS = 3;
    // gravity in water tops out at 0.025 a tick, so this is about 0.9 of a block. plenty to fit standing up, and from
    // the waterline it's just about the whole cell
    static final int SINK_TICKS = 40;

    private int left = SINK_TICKS;
    private boolean landed;
    private Step last = Step.NONE;
    private boolean changed;

    // exits is how many different moves out of src are failing. needsAir beats all of it, a sink never starts or carries
    // on while we're short of breath. belowMove is feet under both ends of the movement, it can't use us down there
    Step tick(int exits, boolean needsAir, boolean onGround, boolean belowMove) {
        Step step = decide(exits, needsAir, onGround, belowMove);
        changed = step != last;
        last = step;
        return step;
    }

    private Step decide(int exits, boolean needsAir, boolean onGround, boolean belowMove) {
        if (exits < MIN_EXITS || needsAir) {
            return Step.NONE;
        }
        if (belowMove) {
            return Step.GIVE_BACK;
        }
        // stays landed: a step up or a bob lifts us for a tick, no reason to take forward away again
        landed |= onGround;
        if (landed) {
            return Step.WALK;
        }
        return left-- > 0 ? Step.SINK : Step.GIVE_BACK;
    }

    // true on the tick the step differs from last tick's, for the log line
    boolean changed() {
        return changed;
    }
}
