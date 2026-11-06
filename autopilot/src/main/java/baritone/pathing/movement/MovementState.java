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

package baritone.pathing.movement;

import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class MovementState {

    private MovementStatus status;
    private MovementTarget target = new MovementTarget();
    private final Map<Input, Boolean> inputState = new HashMap<>();
    // consecutive ticks something stood in the cell we want to place into (see PlaceWait). lives here because the state
    // outlives the tick and dies with the movement
    private int placeBlockedTicks;

    // feed it this tick's answer to "is something in the cell", get back wait or give up
    public PlaceWait.Verdict placeBlocked(boolean blocked) {
        placeBlockedTicks = PlaceWait.next(blocked, placeBlockedTicks);
        return PlaceWait.judge(placeBlockedTicks, PlaceWait.MOVEMENT_PATIENCE);
    }

    // ticks spent punching critters out of the cell. never reset, so a bat flitting in and out still runs into the cap
    private int hitTicks;
    // tickCount of our last swing. an int's worth back counts as never, and a long so the subtraction can't wrap
    private long lastSwingTick = Integer.MIN_VALUE;

    // the hitting version of placeBlocked. critters get HitBlocker's own clock, corpses are waited out for free (they
    // vanish on their own), anything else goes to PlaceWait like before
    public HitBlocker.Action placeInto(HitBlocker.Cell cell) {
        switch (cell) {
            case CLEAR:
                placeBlockedTicks = 0;
                return HitBlocker.Action.PLACE;
            case CLEARING:
                return HitBlocker.Action.WAIT;
            case HIT:
                placeBlockedTicks = 0;
                hitTicks++;
                return hitTicks > HitBlocker.HIT_PATIENCE ? HitBlocker.Action.GIVE_UP : HitBlocker.Action.HIT;
            default:
                return placeBlocked(true) == PlaceWait.Verdict.GIVE_UP ? HitBlocker.Action.GIVE_UP : HitBlocker.Action.WAIT;
        }
    }

    // true means swing now, and remembers that we did
    public boolean swingDue(float attackStrength, long now) {
        if (!HitBlocker.swingNow(attackStrength, now, lastSwingTick)) {
            return false;
        }
        lastSwingTick = now;
        return true;
    }

    public MovementState setStatus(MovementStatus status) {
        this.status = status;
        return this;
    }

    public MovementStatus getStatus() {
        return status;
    }

    public MovementTarget getTarget() {
        return this.target;
    }

    public MovementState setTarget(MovementTarget target) {
        this.target = target;
        return this;
    }

    public MovementState setInput(Input input, boolean forced) {
        this.inputState.put(input, forced);
        return this;
    }

    public Map<Input, Boolean> getInputStates() {
        return this.inputState;
    }

    public static class MovementTarget {

        /**
         * Yaw and pitch angles that must be matched
         */
        public Rotation rotation;

        /**
         * Whether or not this target must force rotations.
         * <p>
         * {@code true} if we're trying to place or break blocks, {@code false} if we're trying to look at the movement location
         */
        private boolean forceRotations;

        public MovementTarget() {
            this(null, false);
        }

        public MovementTarget(Rotation rotation, boolean forceRotations) {
            this.rotation = rotation;
            this.forceRotations = forceRotations;
        }

        public final Optional<Rotation> getRotation() {
            return Optional.ofNullable(this.rotation);
        }

        public boolean hasToForceRotations() {
            return this.forceRotations;
        }
    }
}
