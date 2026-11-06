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

package baritone.pathing.sim;

// the keys a hop up a step asked for, checked against the sim one tick at a time. a staircase of full blocks lands you a
// fraction of a block from the next face every time, so "W and space" clips it on every single step (a wall hit that vanilla
// does not call minor, which ends the sprint). the sim says when that is the case and what to press instead: let go of W for
// a tick, or stand still until the next hop is a clean one. in the sim sweep of a 6 step staircase this was zero wall hits
// from every start in about as many ticks on average as holding W and clipping, which was about one clip a step. the sprint
// is off for about a tick a step either way (letting go of W costs it as much as the wall did), the staircase is just like
// that, so what this saves is the dead stop against the face and not sprint drops.
// AscendHop picks the moment on the ground, this is the safety net under it for whatever it does not know (chains of steps,
// a landing that is too close)
public final class AscendGuard {

    public enum Move {
        AS_WANTED, // the keys are fine, or the sim has no say
        NO_FORWARD, // let go of W for this tick, space stays as it was
        STAND_STILL // let go of W and space, wait for the next tick
    }

    // a hop up a block is about 9 ticks from space to landing, plus a couple for the move into it
    public static final int HORIZON = 12;
    // walking on the ground without a jump is only about walking into the face: the movement decides when to jump, and a look
    // ahead that jumps early would brake for a hop it was going to wait for. two ticks because the first one into a wall is
    // the minor one
    private static final int WALK_HORIZON = 2;

    private AscendGuard() {}

    // sim is the player at the start of the tick the keys are for. hopY is the floor height of the step being hopped onto,
    // where the look ahead ends
    public static Move decide(PlayerSim sim, PlayerSim.Controls wanted, double hopY) {
        boolean walking = sim.onGround && !wanted.jump();
        int horizon = walking ? WALK_HORIZON : HORIZON;
        if (clean(sim, wanted, wanted, hopY, horizon, !walking) != 0) {
            return Move.AS_WANTED; // clean, or the sim gave up and has no say
        }
        if (wanted.forward() == 0 && !wanted.jump()) {
            return Move.AS_WANTED; // there is nothing left to take away
        }
        PlayerSim.Controls released = new PlayerSim.Controls(0, wanted.left(), wanted.jump(), wanted.sprint(), wanted.moveYaw(), wanted.viewYaw());
        if (wanted.forward() != 0 && clean(sim, released, wanted, hopY, horizon, !walking) == 1) {
            return Move.NO_FORWARD;
        }
        // nothing clean to do with space, so no space: waiting a tick is how the next hop gets a window. the caller gives up on
        // the guard after a few ticks of this
        return Move.STAND_STILL;
    }

    // 1 no wall hit before we are up (or the horizon ends), 0 a wall hit, -1 the sim gave up. `first` is this tick's keys, after
    // that the player keeps going the way a hop chain would: W, space whenever grounded and not yet up (if hops are the plan),
    // W let go on the tick that would hit a wall
    private static int clean(PlayerSim start, PlayerSim.Controls first, PlayerSim.Controls wanted, double hopY, int horizon, boolean hops) {
        PlayerSim sim = start.copy();
        PlayerSim.Controls keys = first;
        for (int i = 0; i < horizon; i++) {
            if (!sim.tick(keys)) {
                return -1;
            }
            if (wall(sim)) {
                return 0;
            }
            if (sim.onGround && sim.y >= hopY - 1.0E-3) {
                return 1; // up, nothing past the step is ours to judge
            }
            keys = follow(sim, wanted, hopY, hops);
        }
        return 1;
    }

    private static PlayerSim.Controls follow(PlayerSim sim, PlayerSim.Controls wanted, double hopY, boolean hops) {
        PlayerSim.Controls keys = new PlayerSim.Controls(1, wanted.left(), hops && sim.onGround && sim.y < hopY - 1.0E-3, wanted.sprint(), wanted.moveYaw(), wanted.viewYaw());
        PlayerSim probe = sim.copy();
        if (probe.tick(keys) && wall(probe)) {
            return new PlayerSim.Controls(0, wanted.left(), keys.jump(), keys.sprint(), keys.moveYaw(), keys.viewYaw());
        }
        return keys;
    }

    // a horizontal collision that vanilla does not wave through as a graze, same test AscendHop uses
    private static boolean wall(PlayerSim sim) {
        return sim.horizontalCollision && !sim.minorHorizontalCollision;
    }
}
