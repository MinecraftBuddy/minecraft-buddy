package adris.altoclef.util.helpers;

import baritone.behavior.look.CombatAimMath;

// the buddy's fork, Tuning rod_snowball, off by default: the numbers of a snowball thrown at a blaze, with no minecraft in
// here so the tests can throw numbers at it. points and vectors are {x, y, z} in blocks and blocks a tick; angles are degrees
// in minecraft's convention (yaw 0 = +z, pitch down is positive), as in CombatAimMath.
// from the game's code (Snowball, ThrowableProjectile.tick, Projectile.shootFromRotation): the ball leaves 0.1 under the eye at
// 1.5 blocks a tick along the look, plus the thrower's own movement (its y only off the ground); every tick gravity first takes
// 0.03 off its y speed, then the drag keeps 0.99 of the speed, then it moves. a hit on a blaze does 3 (seven kill one), and a
// hit while the blaze's hurt clock is above 10 ticks does nothing (LivingEntity.hurtServer: the same damage again)
public final class SnowballAim {

    public static final double SPEED = 1.5;
    public static final double GRAVITY = 0.03;
    public static final double DRAG = 0.99;
    public static final double ORIGIN_DROP = 0.1;
    // a flight longer than this is no throw (far past any range thrown at: 24 blocks take about 17 ticks)
    public static final int MAX_TICKS = 60;
    // the hurt clock: set to 20 by a hit, damage counts again once it is down to 10 (LivingEntity.damageCooldownTime)
    public static final int HURT_CLOCK_OPEN = 10;

    private SnowballAim() {
    }

    // the sum of DRAG^k for k = 1..n: how far a unit of launch speed has carried the ball after n ticks (n may be fractional)
    public static double carried(double n) {
        return DRAG * (1 - Math.pow(DRAG, n)) / (1 - DRAG);
    }

    // how far gravity has pulled the ball down after n ticks, in units of GRAVITY
    public static double fallen(double n) {
        return DRAG / (1 - DRAG) * (n - carried(n));
    }

    // where a ball launched from origin with velocity v0 is after n ticks
    public static double[] at(double[] origin, double[] v0, double n) {
        double a = carried(n);
        double b = fallen(n);
        return new double[]{origin[0] + v0[0] * a, origin[1] + v0[1] * a - GRAVITY * b, origin[2] + v0[2] * a};
    }

    // the ball's points at every whole tick from 0 up to the flight, the last one at the flight itself: the arc's segments,
    // for the caller's look for blocks in the way
    public static double[][] path(double[] origin, double[] v0, double flight) {
        int whole = (int) Math.ceil(flight - 1e-9);
        double[][] out = new double[whole + 1][];
        for (int k = 0; k < whole; k++) out[k] = at(origin, v0, k);
        out[whole] = at(origin, v0, flight);
        return out;
    }

    // the launch velocity of the thrower's own (the look's part, before the thrower's movement is added) that puts the ball on
    // the target at tick n, the target at target now moving at vel a tick
    static double[] launch(double[] origin, double[] target, double[] vel, double[] move, double n) {
        double a = carried(n);
        double b = fallen(n);
        return new double[]{
                (target[0] + vel[0] * n - origin[0]) / a - move[0],
                (target[1] + vel[1] * n - origin[1] + GRAVITY * b) / a - move[1],
                (target[2] + vel[2] * n - origin[2]) / a - move[2]};
    }

    // how much faster than SPEED the ball would have to leave to be on the target at tick n: at most 0 is a throw that gets there
    static double overSpeed(double[] origin, double[] target, double[] vel, double[] move, double n) {
        double[] u = launch(origin, target, vel, move, n);
        return Math.sqrt(u[0] * u[0] + u[1] * u[1] + u[2] * u[2]) - SPEED;
    }

    // the throw at a target at target now, moving at vel a tick, from origin (the ball's start, 0.1 under the eye) with the thrower
    // moving at move: {yaw, pitch, flight ticks}, the low arc (the first tick the ball can be there). null when no throw gets there
    // within MAX_TICKS
    public static double[] solve(double[] origin, double[] target, double[] vel, double[] move) {
        double before = 1e-3;
        for (int n = 1; n <= MAX_TICKS; n++) {
            if (overSpeed(origin, target, vel, move, n) <= 0) {
                double lo = before;
                double hi = n;
                for (int i = 0; i < 40; i++) {
                    double mid = (lo + hi) / 2;
                    if (overSpeed(origin, target, vel, move, mid) <= 0) hi = mid;
                    else lo = mid;
                }
                double[] u = launch(origin, target, vel, move, hi);
                float[] rot = CombatAimMath.rotationTo(new double[]{0, 0, 0}, u);
                return new double[]{rot[0], rot[1], hi};
            }
            before = n;
        }
        return null;
    }

    // the ball's start velocity for a look (the look at SPEED plus the thrower's movement), as the game makes it bar the spread
    public static double[] velocity(float yaw, float pitch, double[] move) {
        double[] d = CombatAimMath.direction(yaw, pitch);
        return new double[]{d[0] * SPEED + move[0], d[1] * SPEED + move[1], d[2] * SPEED + move[2]};
    }

    // a target's speed from two of its points ticks apart, each part capped at cap (a jump of the client's interpolation, or a
    // blaze knocked back, is no course to lead); none for points less than two ticks apart
    public static double[] targetVelocity(double[] older, double[] newer, long ticks, double cap) {
        if (older == null || newer == null || ticks < 2) return new double[]{0, 0, 0};
        double[] v = new double[3];
        for (int i = 0; i < 3; i++) v[i] = Math.max(-cap, Math.min(cap, (newer[i] - older[i]) / ticks));
        return v;
    }

    // ---- the gate of a throw

    // why a blaze is no target from here, null when it is: within its distance (from minDist, the melee's, to range), a line from
    // the eye to it, a throw that gets there, and its arc clear of blocks
    public static String whyNot(double distance, double minDist, double range, boolean sight, boolean solved, boolean arcClear) {
        if (distance < minDist) return "close";
        if (distance > range) return "far";
        if (!sight) return "no line";
        if (!solved) return "no throw";
        if (!arcClear) return "arc blocked";
        return null;
    }

    // at most one throw every gap ticks (a clock that went back, a new world, lets one go)
    public static boolean rateOk(long now, long lastThrow, int gap) {
        return now < lastThrow || now - lastThrow >= gap;
    }

    // the ball lands while the blaze takes damage: its hurt clock (counting down a tick a tick) is open by the time it lands, and
    // the landing is gap ticks or more after the landing of the last ball thrown at it, until the client has had LANDING_SEEN
    // ticks to hear of that one's hit (which sets the clock)
    public static final int LANDING_SEEN = 3;

    public static boolean hurtOk(int hurtClock, double flight, long now, long lastLanding, int gap) {
        if (hurtClock - flight > HURT_CLOCK_OPEN) return false;
        return lastLanding == Long.MIN_VALUE || now < lastLanding - MAX_TICKS || now >= lastLanding + LANDING_SEEN
                || now + flight - lastLanding >= gap;
    }

    // the look the last tick ended on is within max degrees of the throw's, both ways: the throw goes out of a look that has
    // settled on the blaze, not out of a turn
    public static boolean settled(float yawWas, float pitchWas, float yaw, float pitch, double max) {
        return Math.abs(CombatAimMath.wrap(yaw - yawWas)) <= max && Math.abs(pitch - pitchWas) <= max;
    }
}
