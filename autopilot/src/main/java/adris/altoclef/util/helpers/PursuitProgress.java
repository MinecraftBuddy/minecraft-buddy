package adris.altoclef.util.helpers;

import java.util.Objects;

// the closest-object tasks keep their pursuit until something twice as close shows up, and that rule has no clock, so a
// target the path keeps circling stays "the current pursuit" for as long as nothing beats it by 2x, with a perfectly good
// one a bit nearer the whole time. this is the clock: a pursuit that hasn't got half a block closer (or broken a block on the way)
// in 7 s is fair game for whatever the pick says is nearest now. pure, the caller ticks it, same counter idea as
// DropPatience so a meal or a fight in the middle doesn't count as getting nowhere, and coming back from one starts the
// distance over too (the fight may have dragged us off)
public final class PursuitProgress {
    // 7 s. a path calc that times out is 2 s, a long one is a few more, and the walk after it gets closer fast
    public static final int STALL_TICKS = 140;
    // same half block as DropPatience.progress
    public static final double MIN_GAIN = 0.5;
    // within swinging distance we are working on it, not walking to it: a cow that takes a few hits, a mob we hit from
    // range, a block the child is busy with. the child's own checks own that part, this only judges the walk
    public static final double IN_REACH = 4.5;
    // more game ticks than this between two of ours means the task was put aside for a while
    public static final int RESUME_GAP = 5;

    private Object target;
    private int waited;
    private double bestDist = Double.POSITIVE_INFINITY;
    private long lastTick;

    // one tick the task spent on `on`, `dist` away from it. brokeBlock: a block we were breaking just went (digging our way
    // there is progress even when the distance doesn't show it yet)
    public void tick(Object on, double dist, boolean brokeBlock, long now) {
        // nothing pursued yet is the target change below
        boolean resumed = target != null && now - lastTick > RESUME_GAP;
        lastTick = now;
        if (resumed || !Objects.equals(target, on)) {
            target = on;
            waited = 0;
            bestDist = dist;
            return;
        }
        if (brokeBlock || dist <= IN_REACH || dist < bestDist - MIN_GAIN) {
            bestDist = Math.min(bestDist, dist);
            waited = 0;
            return;
        }
        waited++;
    }

    public boolean stalled() {
        return target != null && waited >= STALL_TICKS;
    }

    public void clear() {
        target = null;
        waited = 0;
        bestDist = Double.POSITIVE_INFINITY;
        lastTick = 0;
    }

    // the re-pick rule: a stalled pursuit hands over to the pick's nearest, but only when that is something else (the
    // stuck one still being the nearest is the child's progress checks' problem) and only for tasks that allow it
    public static boolean repick(boolean allowed, boolean stalled, Object current, Object nearest) {
        return allowed && stalled && nearest != null && !nearest.equals(current);
    }
}
