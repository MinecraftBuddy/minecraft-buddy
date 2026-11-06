package adris.altoclef.util.helpers;

// who owns the walk to a mob while we are close to it. no world, just answers, same deal as EntityBlockerRules.
//
// the approach child (GetToEntityTask) cuts the path when it starts and when it stops, so every start and stop is a
// walking step thrown away. the kill task used to stop it for any tick where a swing was not allowed yet (baritone
// halfway through a step, the cooldown, a hop) and start it again the moment the mob was a hair past the back-off
// line, about once a second. the rule is: out of reach we walk, in reach we stand, and a child that is already
// walking finishes its step instead of being cut off mid-stride. in reach with a clear shot there is nothing for a
// fresh child to do, so it is never started there
public final class ApproachRules {
    public enum Plan {
        // swing (or crit) now, the child is not wanted, stopping it is what lets go of the path
        INTERACT,
        // the child that is walking keeps walking, the swing has to wait for a safe moment
        KEEP_CHILD,
        // in reach and nothing to walk: stand still, no child
        WAIT,
        // out of reach (or no clear shot): the approach, the same child as last tick if there was one
        APPROACH,
        // too close, no clear shot, nothing mid-step: shuffle
        WANDER,
        // a kill task stood in reach for WAIT_TICKS without a swing going out: let go of the path and hit it
        SWING_NOW
    }

    // who is asking. the kill tasks are committed to the mob in front of them, so they never shuffle away from one that is in
    // reach, and (gates open) they swing through a stall instead of standing there. everyone else keeps the old rules
    public enum Policy {
        OTHER,
        // a kill task, but eating or the bucket has the gates shut right now: stand, do not swing, do not shuffle
        KILL_GATED,
        KILL
    }

    // 5 s. an unsafe step lasts a handful of ticks, an eating pause a couple of seconds, anything longer is not a hiccup
    public static final int WAIT_TICKS = 100;

    private ApproachRules() {
    }

    // childLive: the child was part of the tree last tick. inRange: within the reach setting. shot: the ray at the mob hits it.
    // ready: every gate on swinging except the path (eating, falling, the bucket, on the ground or hopping).
    // safeToCancel: baritone is not halfway through a step. tooClose: inside the back-off line. waitedLong: we have stood
    // WAIT_TICKS in a row already. a gate that stays shut (swimming, a ladder) must not freeze us in place for good, so
    // after that the old rules get a go again: walk if there is room, shuffle if not
    public static Plan plan(boolean childLive, boolean inRange, boolean shot, boolean ready, boolean safeToCancel, boolean tooClose, boolean waitedLong) {
        return plan(childLive, inRange, shot, ready, safeToCancel, tooClose, waitedLong, Policy.OTHER);
    }

    public static Plan plan(boolean childLive, boolean inRange, boolean shot, boolean ready, boolean safeToCancel, boolean tooClose,
                            boolean waitedLong, Policy policy) {
        return plan(childLive, inRange, shot, ready, safeToCancel, tooClose, waitedLong, policy, false);
    }

    // comesToUs: the mob teleports onto us (an enderman), so out of reach we stand and let it, a walk after it is a path to
    // wherever it just was. in reach with no clear shot is still the old rules' (a new angle)
    public static Plan plan(boolean childLive, boolean inRange, boolean shot, boolean ready, boolean safeToCancel, boolean tooClose,
                            boolean waitedLong, Policy policy, boolean comesToUs) {
        if (comesToUs && !inRange) {
            return Plan.WAIT;
        }
        if (inRange && shot) {
            // (first, before the swing is even considered: a kill task that was told to swing for this long and has not is
            // stuck in the swing's own gates too, a hop waiting on a fall that never comes, and no path is what blocks it)
            // an angry enderman gets it from the first tick: it hits for 3+ a go and teleports off once it's hurt, so waiting on
            // a hop or a path step to land is handing it the first hit. no crits on endermen, fine
            if (policy == Policy.KILL && (waitedLong || comesToUs)) {
                return Plan.SWING_NOW;
            }
            if (ready && safeToCancel) {
                return Plan.INTERACT;
            }
            if (childLive) {
                return Plan.KEEP_CHILD;
            }
            if (!waitedLong || policy != Policy.OTHER) {
                return Plan.WAIT;
            }
            if (!tooClose) {
                return Plan.APPROACH;
            }
            return safeToCancel ? Plan.WANDER : Plan.WAIT;
        }
        if (!tooClose) {
            return Plan.APPROACH;
        }
        if (!safeToCancel || (inRange && policy != Policy.OTHER)) {
            return childLive ? Plan.KEEP_CHILD : Plan.WAIT;
        }
        return Plan.WANDER;
    }

    // the kill tasks' waitedLong: the stand clock ran out, or something is biting us in reach and waiting out a path step
    // just feeds it free hits (not on a rim, there the rim step is the way off and has its own say)
    public static boolean swingWaited(boolean standWaited, boolean underFire, boolean rimWide) {
        return standWaited || (underFire && !rimWide);
    }

    // nothing to interact with this tick. a wander we already have as our child keeps going even when baritone says it is not
    // safe to cancel (its own explore path is what says that): returning null there stopped it, the next safe tick started a
    // new one, and it re-explored from scratch about twice a second. a wander is only started fresh when nothing is mid-stride
    public static boolean wanderWhenNoTarget(boolean wasWandering, boolean safeToCancel) {
        return wasWandering || safeToCancel;
    }
}
