package adris.altoclef.util.helpers;

import net.minecraft.world.phys.AABB;

// the sword's swing rules, pure so they can be tested without a player. a sword does not crit hop: it waits for the charge
// and swings, and the swing is a sweep (hits everything beside the target too) when we are charged, not a crit and not
// sprinting. approach sprints, so sprint gets locked off shortly before the charge is full. next to a calm neutral mob the
// sweep is the thing to dodge, so there the swing goes out early, at a charge too low to sweep
public final class SwordSwing {
    private SwordSwing() {
    }

    // sprint goes off this many ticks before the charge is full, so the server has seen the stop by the swing tick
    public static final double LOCK_LEAD_TICKS = 2;

    // keep sprint off for this tick: a sword in hand, the target in reach, the charge about to be full (or already is). a calm
    // neutral mob beside the target would eat the sweep, and a charged sprint hit is the only swing that can't sweep
    public static boolean lockSprint(boolean sword, boolean inReach, double ticksToFull, boolean calmNeutralNearTarget) {
        return sword && inReach && ticksToFull <= LOCK_LEAD_TICKS && !calmNeutralNearTarget;
    }

    // a sweep needs a charge over 0.9. with a calm neutral mob beside the target we swing at this much instead of waiting for
    // full: about half the damage (0.2 + 0.8 * scale squared), but it can't sweep. a sword charges 0.08 a tick and the scale reads 0.60, 0.68, 0.76, 0.84, so
    // the early window is four ticks long. at 0.8 it was one (0.84), and an aim that was late for that tick swung at 1.00 and
    // swept the piglin standing next to the blaze
    public static final double CALM_SWING_SCALE = 0.6;

    // the vanilla line: fullStrengthAttack is a scale over this
    public static final double SWEEP_SCALE = 0.9;

    // Player.doSweepAttack: everything alive in the target's box grown by (1, 0.25, 1) and within 3 of us (squared, under 9)
    public static boolean inSweepBox(AABB target, AABB victim, double distanceSqToUs) {
        return target.inflate(1.0, 0.25, 1.0).intersects(victim) && distanceSqToUs < 9.0;
    }

    // "a calm neutral is near", deliberately fatter than the vanilla sweep box. the client's positions lag the server's, so a
    // mob the exact box calls outside got swept anyway. within 3 of us and inside the target's box grown (1.5, 0.75, 1.5)
    public static boolean calmNear(AABB target, AABB victim, double distanceSqToUs) {
        return target.inflate(1.5, 0.75, 1.5).intersects(victim) && distanceSqToUs < calmReach() * calmReach();
    }

    // (the buddy's fork: how far from us a calm neutral counts, 3 by default; Tuning calm_reach. A full charge swing at a
    // wither skeleton swept a calm zombified piglin the client saw at 3.1, and its pack killed the buddy, realfort-ab-fpcamp-3
    // run 7, 5 Nov 2026)
    public static double calmReach() {
        return Math.max(3.0, Tuning.get("calm_reach", 3.0));
    }

    // a calm neutral this close to us counts as near whatever the target's box says (the sweep reaches past where the client
    // thinks the target is)
    public static boolean calmByUs(double distanceSqToUs) {
        return distanceSqToUs < calmReach() * calmReach();
    }

    // the item swap that resets the charge has to be seen by the server too: both packets landing in one server tick is no
    // reset over there and the sweep comes back. the odd item stays in hand through this many ticks past the swap tick
    public static final long RESET_HOLD_TICKS = 3;

    // still holding the odd item from a swap at swappedAt
    public static boolean resetHeld(long now, long swappedAt) {
        return now >= swappedAt && now - swappedAt <= RESET_HOLD_TICKS;
    }

    // a charge reset that cannot find anything to swap to (or park on) for this long swings anyway: a bot that never swings
    // is worse than one that sweeps a calm mob, and the sweep box rules still pick the early window when the reset works
    public static final long RESET_STUCK_TICKS = 40;

    // stuckSince is the first tick the reset had no way out (-1: not stuck). the tick it starts is never a swing
    public static boolean resetGaveUp(long now, long stuckSince) {
        return stuckSince >= 0 && now - stuckSince >= RESET_STUCK_TICKS;
    }

    // a calm neutral is near and the charge is over the sweep line: this swing must never go out, throw the charge away instead
    public static boolean needsReset(double scale, boolean calmNeutralNear) {
        return calmNeutralNear && scale > SWEEP_SCALE;
    }

    // charged enough to swing. full charge normally. with a calm neutral mob beside the target only the early window, from the
    // early scale up to the sweep line: a full charge swing is never fine there, whatever the sprint and movement exemptions say
    public static boolean charged(double scale, boolean calmNeutralNearTarget) {
        if (calmNeutralNearTarget) {
            return scale >= CALM_SWING_SCALE && scale <= SWEEP_SCALE;
        }
        return scale >= 1;
    }

    // swing now: charged, and if we asked for sprint off it was already asked for on an earlier tick (the stop packet goes
    // out with the tick's movement, a same tick swing would still look like a sprint hit to the server)
    public static boolean swing(boolean attackReady, boolean lockAsked, boolean lockedEarlierTick) {
        return attackReady && (!lockAsked || lockedEarlierTick);
    }
}
