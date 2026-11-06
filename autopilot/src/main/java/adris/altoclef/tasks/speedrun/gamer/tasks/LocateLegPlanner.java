package adris.altoclef.tasks.speedrun.gamer.tasks;

// the decisions of the locate loop that are not about the world: how long a walk leg may take, when a leg
// counts as stuck, how many bad throws we put up with. pure, the task feeds it clock and distances
public final class LocateLegPlanner {
    // no progress toward the leg target for this long = give up the leg and throw from here
    public static final double STALL_SECONDS = 60;
    // closer than this to the leg target is arrived
    public static final double ARRIVE_BLOCKS = 6;
    // getting this much closer counts as progress
    static final double PROGRESS_BLOCKS = 3;
    // after a failed re-estimate we step this far sideways before the first throw
    public static final double RELOCATE_BLOCKS = 150;

    // two throws closer than this say the same thing (the estimator keeps this rule for its own advice)
    public static final double MIN_THROW_GAP = 10;
    // a forced walk after a throw that told us nothing: 26 so that arriving "within 6" is still 20 blocks from the old spot
    public static final double FORCED_WALK_BLOCKS = 26;

    // NaN last throw = nothing thrown yet
    public static boolean tooCloseToLastThrow(double px, double pz, double lastX, double lastZ) {
        return !Double.isNaN(lastX) && Math.hypot(px - lastX, pz - lastZ) < MIN_THROW_GAP;
    }

    // there is an eye of ours out there (flying, or lying within reach) and we hold fewer than before the throw
    public static boolean collectPending(int baseline, int eyesHeld, boolean flying, boolean dropNearby) {
        return baseline > 0 && eyesHeld < baseline && (flying || dropNearby);
    }

    // the eye item needs this long after the entity vanishes to fall into the tracker's grounded window
    public static final double DROP_SETTLE_SECONDS = 3.0;

    // keep waiting for the drop: the eye is gone, not seen on the ground yet, and not gone for too long.
    // NaN sinceGone fails the < and stops the wait, so callers stamp the gone time first
    static boolean settleWait(boolean flying, double sinceGone, boolean dropSeen) {
        return !flying && !dropSeen && sinceGone < DROP_SETTLE_SECONDS;
    }

    // where to go after a throw that gave no bearing: toward the guess if we have one (progress at least), else
    // along the ring tangent like a second attempt. never the same spot
    public static double[] forcedWalkTarget(double px, double pz, boolean hasEstimate, double estX, double estZ) {
        if (hasEstimate) {
            double dx = estX - px;
            double dz = estZ - pz;
            double len = Math.hypot(dx, dz);
            if (len > 1) {
                // always the full step, even past a close guess: a short hop would land under the 10 block gap and loop
                return new double[]{px + dx / len * FORCED_WALK_BLOCKS, pz + dz / len * FORCED_WALK_BLOCKS};
            }
        }
        return relocateTarget(px, pz, FORCED_WALK_BLOCKS);
    }

    // generous: baritone walks ~4.3 blocks/s, digging and detours eat the rest
    public static double legBudgetSeconds(double distance) {
        return 60 + distance / 2.5;
    }

    // a walk leg with its own clock. all times are seconds from whatever clock the caller likes
    public static final class Leg {
        private final double startTime;
        private final double budget;
        private double bestDistance;
        private double lastProgress;

        public Leg(double now, double distanceToTarget) {
            startTime = now;
            budget = legBudgetSeconds(distanceToTarget);
            bestDistance = distanceToTarget;
            lastProgress = now;
        }

        public void update(double now, double distanceToTarget) {
            if (distanceToTarget < bestDistance - PROGRESS_BLOCKS) {
                bestDistance = distanceToTarget;
                lastProgress = now;
            }
        }

        public boolean expired(double now) {
            return now - lastProgress >= STALL_SECONDS || now - startTime >= budget;
        }

        public String why(double now) {
            return now - lastProgress >= STALL_SECONDS ? "no progress for " + (int) STALL_SECONDS + " s" : "took too long";
        }
    }

    // counts throws and bad throws. a throw only counts once an eye entity showed up, a dud is free but capped in a row
    public static final class ThrowLedger {
        private final int maxThrows;
        private final int maxEmptyInRow;
        private int counted;
        private int emptyInRow;

        public ThrowLedger(int alreadyCounted, int maxThrows, int maxEmptyInRow) {
            this.counted = alreadyCounted;
            this.maxThrows = maxThrows;
            this.maxEmptyInRow = maxEmptyInRow;
        }

        public void eyeAppeared() {
            counted++;
            emptyInRow = 0;
        }

        public void empty() {
            emptyInRow++;
        }

        public int counted() {
            return counted;
        }

        public boolean tooManyEmpty() {
            return emptyInRow >= maxEmptyInRow;
        }

        public boolean outOfThrows() {
            return counted >= maxThrows;
        }
    }

    // where to stand for the first throw of a second attempt: along the ring tangent, not out and not in,
    // so the new rays still see the same ring of strongholds. at the origin there is no tangent, +x it is
    public static double[] relocateTarget(double fromX, double fromZ, double distance) {
        double len = Math.hypot(fromX, fromZ);
        if (len < 1) {
            return new double[]{fromX + distance, fromZ};
        }
        return new double[]{fromX - fromZ / len * distance, fromZ + fromX / len * distance};
    }

    private LocateLegPlanner() {
    }
}
