package adris.altoclef.util.helpers;

/**
 * Punch or shield, for one ghast's ball, chosen while the shield still has time to come up (Tuning ghast_ball_plan).
 *
 * The punch takes a ball only on a tick its gap to the eye lies between ghast_punch_min (3.3) and the arm's reach (4.0):
 * 0.7 of a block, while the ball closes 1.1 to 1.7 a tick, so about half the balls jump the window. Those were left to
 * the shield at the first tick inside 3.3, two or three ticks from the hit, and a raised shield blocks only after 0.25 s
 * (the shield's blocks_attacks, five ticks): it never blocked one. In one dead run eight such balls took 20 hp in 24 s on
 * open ground with the shield in the off hand (look 123, 18 Nov 2026).
 *
 * So the gaps of the coming ticks are worked out from the gap now and how fast it closes, the ball speeding up as the game
 * moves it (each tick its velocity grows by 0.1 along its heading and is scaled by 0.95,
 * AbstractHurtingProjectile.applyInertia). A ball with no tick inside the window, a margin in from each end, gets the
 * shield once its hit is within the shield's lead; the rest are left to the punch.
 */
public final class GhastBallPlan {
    static final double ACCEL = 0.1;
    static final double INERTIA = 0.95;
    // a raised shield blocks after 0.25 s, 26.3's Items.SHIELD
    public static final int SHIELD_RAISE_TICKS = 5;
    // the guess is a few ticks ahead and the buddy moves too: a tick near either end of the window is not counted on
    static final double MARGIN = 0.15;
    // the eye's gap to the ball's box when the ball meets the body (aimed at the middle, box 1 wide)
    static final double HIT_GAP = 0.5;
    // closing slower than this is no ball coming at us
    static final double MIN_CLOSING = 0.05;
    static final int MAX_TICKS = 60;

    private GhastBallPlan() {
    }

    /** The ball's speed on the next tick, from its speed on the last. */
    static double nextSpeed(double speed) {
        return INERTIA * (speed + ACCEL);
    }

    /**
     * Ticks until the ball is expected at the hit gap: gap now, how much of it closed last tick, the ball's own speed last
     * tick. MAX_TICKS + 1 when not within MAX_TICKS or not closing.
     */
    public static int ticksToHit(double gap, double closing, double speed) {
        if (closing < MIN_CLOSING) return MAX_TICKS + 1;
        double g = gap, c = closing, v = Math.max(speed, 1e-6);
        for (int k = 0; k <= MAX_TICKS; k++) {
            if (g <= HIT_GAP) return k;
            double nv = nextSpeed(v);
            c *= nv / v;
            v = nv;
            g -= c;
        }
        return MAX_TICKS + 1;
    }

    /** True when the gap now or the gap of a coming tick before the hit lies in [min + MARGIN, reach - MARGIN]. */
    public static boolean punchable(double gap, double closing, double speed, double min, double reach) {
        double lo = min + MARGIN, hi = reach - MARGIN;
        if (hi < lo) return false;
        double g = gap, c = closing, v = Math.max(speed, 1e-6);
        for (int k = 0; k <= MAX_TICKS; k++) {
            if (g >= lo && g <= hi) return true;
            if (g < lo || closing < MIN_CLOSING) return false;
            double nv = nextSpeed(v);
            c *= nv / v;
            v = nv;
            g -= c;
        }
        return false;
    }

    /**
     * The shield for this ball, now: it is coming, expected to hit within leadTicks, and no tick of its way is a sure
     * punch. leadTicks is the shield's five plus how far the server's ball is ahead of ours, plus room for the tick the hand takes to leave a bow and for the
     * first tick inside the lead to come late.
     */
    public static boolean shield(double gap, double closing, double speed, double min, double reach, double leadTicks) {
        if (closing < MIN_CLOSING) return false;
        if (ticksToHit(gap, closing, speed) > leadTicks) return false;
        return !punchable(gap, closing, speed, min, reach);
    }
}
