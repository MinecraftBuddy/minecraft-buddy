package adris.altoclef.util.helpers;

/**
 * Where a ghast's ball punched back flies, as a capsule: from the ball along the look out to a length, a radius round it. A
 * ball sent back that misses its ghast flies on and bursts by whatever it meets; a zombified piglin it hits counts the punch
 * as the buddy's, and its pack bites. In the route series of 13 Nov 2026 two phase-2 deaths began so: a ball punched back
 * hit a zombified piglin for 5.5 to 6 about 20 blocks off, and the pack was on the buddy 1 to 6 s later.
 */
public final class PunchLine {
    public static final double LENGTH = 48;
    public static final double RADIUS = 3;

    private PunchLine() {
    }

    /**
     * True when the point lies within radius of the segment from (fx, fy, fz) along (dx, dy, dz) out to length: the
     * distance to the nearest point of the segment, so a point just behind the ball counts as at the ball.
     */
    public static boolean near(double fx, double fy, double fz, double dx, double dy, double dz, double length, double radius,
                               double px, double py, double pz) {
        double n = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (n < 1e-9) return false;
        dx /= n;
        dy /= n;
        dz /= n;
        double vx = px - fx, vy = py - fy, vz = pz - fz;
        double t = Math.max(0, Math.min(length, vx * dx + vy * dy + vz * dz));
        double cx = vx - t * dx, cy = vy - t * dy, cz = vz - t * dz;
        return cx * cx + cy * cy + cz * cz <= radius * radius;
    }
}
