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

package baritone.pathing.movement.movements;

// who has a fall: baritone's MovementFall (it planned it) or altoclef's MLGBucketFallChain (it grabs any fall that looks bad).
// pure so the chain's stand down is testable without a world, same idea as LadderClutch
final class FallCover {

    // how far outside the src/dest cells we still count as "in the fall". walking off an edge drifts about this much
    private static final double SIDE = 0.3;
    // knockback is a 0.4 shove, a walk off a ledge is 0.28 at a sprint. past this it wasn't our idea
    private static final double MAX_DRIFT = 0.45;

    private FallCover() {}

    // are we where a planned fall from src to dest would have us. the executor itself is happy with the valid positions
    // (src plus the dest column) but we're mid air going diagonal off an edge, so use the box around both cells instead.
    // anything knocked out of the box, or shoved sideways hard enough, is an unplanned fall and altoclef gets it back
    static boolean inColumn(int srcX, int srcY, int srcZ, int destX, int destY, int destZ, double px, double py, double pz, double mx, double mz) {
        if (Math.hypot(mx, mz) > MAX_DRIFT) {
            return false;
        }
        if (px < Math.min(srcX, destX) - SIDE || px > Math.max(srcX, destX) + 1 + SIDE) {
            return false;
        }
        if (pz < Math.min(srcZ, destZ) - SIDE || pz > Math.max(srcZ, destZ) + 1 + SIDE) {
            return false;
        }
        // well under the landing floor means the plan is long gone, a couple above the ledge is just a jump off it
        return py >= destY - 1 && py <= srcY + 2;
    }

    // does baritone do something about this fall itself, so altoclef's mlg should keep its hands off.
    // a safe fall and a clutch are baritone's. the bucket is too when it has the bucket on the hotbar to click (it needs
    // that anyway), otherwise alto's slot handler gets it. a clutch that gave up has nobody saving us, so anything alto
    // has (hay, a bucket from the inventory) is welcome.
    // a fall we planned to eat (fastMode) is ours while we can still afford the hearts: there is nothing to
    // clutch with, so alto would only cancel the path and shout "epic gaemer moment" at the plan. if a hit since then
    // makes it too expensive it's an emergency again and alto can have it
    static boolean handles(MovementFall.FallMode mode, boolean gaveUp, boolean bucketOnHotbar, boolean altoDoesBuckets, boolean hurtAffordable) {
        switch (mode) {
            case NONE:
                return !gaveUp;
            case CLUTCH:
                return true;
            case BUCKET:
                return bucketOnHotbar || !altoDoesBuckets;
            case HURT:
                return hurtAffordable;
            default:
                return false;
        }
    }

    // the water at the end of a fall is baritone's to pick up when it's the dest cell, baritone poured it there itself and
    // will fetch it (MovementFall.canFetch: still a source, not paused) and there's an empty bucket on the hotbar to do it
    // with, so the chain's own pickup would be a second click on the same water. anything else (water somewhere else,
    // water the chain poured, no hotbar bucket) is still the chain's
    static boolean pickupIsBaritones(boolean placedAtDest, boolean baritoneFetches, boolean emptyBucketOnHotbar) {
        return placedAtDest && baritoneFetches && emptyBucketOnHotbar;
    }

    // standing still in a still water source reads -0.005 at the start of a tick: the floor zeroes y, then water gravity
    // (0.08 / 16) goes on after. so the old y >= 0 only ever came true when the swim jump bobbed us up. sinking with no
    // floor tops out at -0.025, a bit past that is still nobody falling. anything faster is the drop's momentum.
    // on dry ground it's worse, -0.0784 every tick (gravity then drag after the floor), so on the ground is just down
    static final double NOT_FALLING_Y = -0.05;
    // a hair over the floor still counts, a fall this close lands next tick anyway
    static final double RESTING_ON_FLOOR = 0.05;

    // sneaking on a ladder cancels the downward motion, so feet resting right on the floor don't collide with it and it reads as
    // off the ground for as long as the sneak lasts. the sneak is held by whoever needs it (the magma rule, say), so a clutch waiting
    // for onGround to fetch its ladder would wait forever
    static boolean restingOnLadder(boolean onClimbable, double feetOverFloor) {
        return onClimbable && feetOverFloor <= RESTING_ON_FLOOR;
    }

    static boolean notFalling(boolean onGround, boolean inWater, double motionY) {
        return onGround || inWater && motionY > NOT_FALLING_Y;
    }

    // our feet in dest or a block next to it, same level. drifting one over while the water slows us is normal, and the
    // executor doesn't call that off path either (it's under MAX_DIST_FROM_PATH)
    static boolean nextToDest(int feetX, int feetY, int feetZ, int destX, int destY, int destZ) {
        return feetY == destY && Math.abs(feetX - destX) <= 1 && Math.abs(feetZ - destZ) <= 1;
    }

    // MovementFall's bucket pickup: there's a source at dest to take, we can reach it, we're down and done falling, and
    // the empty bucket is on the hotbar. pure so it's testable, the movement hands in what it sees
    static boolean pickupReady(boolean destIsSource, boolean nextToDest, double distToWater, double reach, boolean onGround,
                               boolean inWater, double motionY, boolean emptyBucketOnHotbar) {
        return destIsSource && nextToDest && distToWater <= reach && notFalling(onGround, inWater, motionY) && emptyBucketOnHotbar;
    }
}
