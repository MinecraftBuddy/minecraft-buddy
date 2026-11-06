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

package baritone.utils;

import baritone.Baritone;
import net.minecraft.world.level.Level;

// fastMode is a preset, not a setting rewrite: the real settings never change, so switching it off hands the
// user their own choices back. everything that has an opinion about one of these reads it through here and nowhere else
// (the planner snapshots them into CalculationContext, the executor asks directly), so there's exactly one list to keep honest
public final class ExperimentalMovement {

    // vanilla safe fall distance, and a sanity bound so nobody plans a leap off the nether roof (23 is exactly 20 hp of it)
    public static final int SAFE_FALL = 3;
    public static final int MAX_HURT_FALL = 23;
    // hp we must still have after a missed bucket, so only the misses that would kill us are refused
    public static final double BUCKET_MISS_FLOOR = 1;

    // how far ahead combat keeps pushing the no jumps stamp each tick it wants it. a stamp further out than this was
    // written against some other clock (new world, game time starts over) and is junk, see jumpMoveAllowed
    public static final long JUMPS_OFF_TICKS = 2;

    private ExperimentalMovement() {}

    // the jump family (parkour, neos, climbs, momentum) gate: fastMode or the setting turns it on, and combat's stamp turns it
    // off again while it is still ahead of the clock. the nether turns it off for good, whatever anybody set: a jump that
    // comes up short there is the lava sea, and fastMode was handing out jumps the settings said no to. pure so the expiry can be tested without a world
    public static boolean jumpMoveAllowed(boolean nether, boolean fastMode, boolean setting, long jumpsOffUntil, long now) {
        boolean off = jumpsOffUntil >= now && jumpsOffUntil - now <= JUMPS_OFF_TICKS;
        return !nether && !off && (fastMode || setting);
    }

    // fastMode's falls that hurt (the ones past maxFallHeightNoWater). the nether has no water to bail out with and the floor
    // down there is lava, so only the falls the user raised the limit for are left. pure for the same reason as above
    public static boolean hurtingFallsOk(boolean nether, boolean fastMode) {
        return fastMode && !nether;
    }

    // null world is a test with no game running, which is not the nether
    public static boolean inNether(Level world) {
        return world != null && world.dimension() == Level.NETHER;
    }

    // the same for the End
    public static boolean inEnd(Level world) {
        return world != null && world.dimension() == Level.END;
    }

    public static boolean on() {
        return Baritone.settings().fastMode.value;
    }

    public static boolean allowParkour() {
        return on() || Baritone.settings().allowParkour.value;
    }

    public static boolean allowParkourAscend() {
        return on() || Baritone.settings().allowParkourAscend.value;
    }

    public static boolean allowParkourPlace() {
        return on() || Baritone.settings().allowParkourPlace.value;
    }

    // the planner (CalculationContext), the hotbar keeper (InventoryBehavior) and the mid air probe (LadderClutchFallTask)
    // all have to agree on this or fast mode plans a clutch that nobody keeps a ladder on the hotbar for
    public static boolean allowLadderClutch() {
        return on() || Baritone.settings().allowLadderClutch.value;
    }

    public static boolean allowNeos() {
        return on() || Baritone.settings().allowNeos.value;
    }

    public static boolean allowClimbJumps() {
        return on() || Baritone.settings().allowClimbJumps.value;
    }

    public static boolean allowMomentumJumps() {
        return on() || Baritone.settings().allowMomentumJumps.value;
    }

    public static boolean allowDiagonalAscend() {
        return on() || Baritone.settings().allowDiagonalAscend.value;
    }

    public static boolean allowDiagonalDescend() {
        return on() || Baritone.settings().allowDiagonalDescend.value;
    }

    public static boolean sprintJumping() {
        return on() || Baritone.settings().sprintJumping.value;
    }

    // the executor's version: it reads the preset live, so it has to say no to the nether live too (the planner already did)
    public static boolean sprintJumping(Level world) {
        return !inNether(world) && sprintJumping();
    }

    // a log, not a movement: the fast preset is for moving like a speedrunner, it should not also spam the console
    public static boolean sprintTelemetry() {
        return Baritone.settings().sprintTelemetry.value;
    }

    public static boolean headHitters() {
        return on() || Baritone.settings().headHitters.value;
    }

    public static boolean headHitters(Level world) {
        return !inNether(world) && headHitters();
    }

    public static boolean allowGroundShortcuts() {
        return on() || Baritone.settings().allowGroundShortcuts.value;
    }

    public static boolean allowSwimming() {
        return on() || Baritone.settings().allowSwimming.value;
    }

    public static boolean allowBoats() {
        return on() || Baritone.settings().allowBoats.value;
    }

    public static boolean elytraAutoJump() {
        return on() || Baritone.settings().elytraAutoJump.value;
    }

    public static boolean allowGodBridge() {
        return on() || Baritone.settings().allowGodBridge.value;
    }

    public static boolean preferFasterPathing() {
        return on() || Baritone.settings().preferFasterPathing.value;
    }

    // min so somebody who already set a cheaper penalty keeps it
    public static double blockPlacementPenalty() {
        double penalty = Baritone.settings().blockPlacementPenalty.value;
        return on() ? Math.min(penalty, Baritone.settings().experimentalBlockPlacementPenalty.value) : penalty;
    }

    public static double jumpBias() {
        return on() ? Baritone.settings().experimentalJumpBias.value : 1;
    }

    // LivingEntity.calculateFallDamage is floor(fallDistance + 1e-6 - safeFallDistance) on 26.3 (ceil, minus the landing move,
    // on 1.21.4), and a drop of n whole blocks is exactly n of fallDistance now, so it comes out to n - 3 either way. before
    // armor and feather falling, which only ever make it smaller. so the number is a ceiling on what we'll lose and that's the
    // right direction to be wrong in
    public static int fallDamage(int blocksFallen) {
        return Math.max(0, blocksFallen - SAFE_FALL);
    }

    // the gate every damage fall goes through, in the planner and again when the movement starts
    public static boolean canAffordFall(double health, double damage, double minHealth) {
        return health - damage >= minHealth;
    }

    // how low a fall that hurts may leave us. a fall inside maxFallHeightNoWater is one the user raised the limit for on
    // purpose (it used to be free), so it only has to not kill. the longer ones fastMode adds keep fastModeMinHealth. the
    // planner and MovementFall.ownsFall both ask, so they can't disagree about who owns a fall that was planned
    public static double hurtFloor(boolean inPlainLimit, double fastModeMinHealth) {
        return inPlainLimit ? BUCKET_MISS_FLOOR : fastModeMinHealth;
    }

    // the water bucket is not a gamble, but it can still miss (a bad aim, a block in the way, lag). if it does the fall is the
    // whole fall, so a drop is only offered while surviving a miss still leaves us alive. much looser than the clutch gate
    public static boolean canAffordBucketMiss(double health, int blocksFallen) {
        int damage = fallDamage(blocksFallen);
        // a drop that can't hurt has nothing to miss, even at half a heart
        return damage == 0 || canAffordFall(health, damage, BUCKET_MISS_FLOOR);
    }

    // a ladder clutch is a fall that only doesn't hurt if the timing works out. below the same floor a miss is the whole fall
    // at once, usually a funeral, so it's not offered at all there
    public static boolean canAffordClutch(double health, double minHealth) {
        return canAffordFall(health, 0, minHealth);
    }
}
