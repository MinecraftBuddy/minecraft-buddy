package adris.altoclef.util.helpers;

import adris.altoclef.Debug;

/**
 * Tuning blaze_stand_ban (off; 12 Nov 2026, steering 175's "phase 6 faster"): no-path bans counted by where the buddy
 * stands, not only by the blaze. In phase-6 runs that ran out of potion 43% of the potion minutes went to no-path chases:
 * each blaze got two 2 s searches, half ended "couldn't get more than 0.0 blocks" (the buddy already at the nearest point
 * it could reach), the blaze was banned for 2 minutes, and with three or more about a ban ran out every 10 s or so, so the
 * fight never left its kill and the walk back to the camp never came (3.65 bans a potion-minute against 0.53 in passes).
 * With it on, a second blaze banned for no path within NEAR of the first ban's stand inside WINDOW holds that stand for
 * HOLD: only a blaze within a bite is picked there, so the fight lets go and the camp walk or the wait takes over.
 */
public final class StandBan {
    static final double NEAR = 2.5;
    static final long WINDOW = 60 * 20;
    static final long HOLD = 30 * 20;

    private static final java.util.ArrayDeque<double[]> RECENT = new java.util.ArrayDeque<>();
    private static long until = Long.MIN_VALUE / 2;
    private static double ux, uy, uz;

    private StandBan() {
    }

    public static boolean on() {
        return Tuning.get("blaze_stand_ban", 0) > 0;
    }

    /** A blaze banned for no path while the buddy stood at x y z; true when that made the stand a held one. */
    public static synchronized boolean noted(long now, double x, double y, double z) {
        RECENT.removeIf(r -> now < r[0] || now - (long) r[0] > WINDOW);
        int near = 1;
        for (double[] r : RECENT) {
            if (dist(r[1], r[2], r[3], x, y, z) <= NEAR) near++;
        }
        RECENT.add(new double[]{now, x, y, z});
        if (near < 2 || holds(now, x, y, z)) return false;
        until = now + HOLD;
        ux = x;
        uy = y;
        uz = z;
        Debug.logInternal(String.format(java.util.Locale.ROOT, "rods: %d blazes with no path from this stand in %d s, only a blaze within a bite here for %d s (blaze_stand_ban)",
                near, WINDOW / 20, HOLD / 20));
        return true;
    }

    /** Whether the stand x y z is held now: no blaze past a bite is picked there. */
    public static synchronized boolean holds(long now, double x, double y, double z) {
        return now < until && now >= until - HOLD && dist(ux, uy, uz, x, y, z) <= NEAR;
    }

    static synchronized void reset() {
        RECENT.clear();
        until = Long.MIN_VALUE / 2;
    }

    private static double dist(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = ax - bx, dy = ay - by, dz = az - bz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
