package adris.altoclef.tasks.speedrun.gamer.tasks;

// a place the pearl hunt got stuck by lava (backing off for ever, or standing there letting a foe come). endermen near it are
// not worth the trip for a while, and every new one that spawns there would get its own fresh patience otherwise. one spot at a
// time, it times out by itself because the forest is the main source of endermen and giving it up for good costs the hunt
final class LavaSpot {
    // 3 min
    static final long LIFETIME_TICKS = 3 * 60 * 20;
    static final double RADIUS = 24;
    // 20 s of standing by the lava inside the 60 s window (LavaHoldClock)
    static final int HOLD_TRIGGER_TICKS = 20 * 20;

    private double x;
    private double y;
    private double z;
    private long armedAt = Long.MIN_VALUE;

    static boolean trigger(boolean awayGaveUp, int holdTicks) {
        return awayGaveUp || holdTicks >= HOLD_TRIGGER_TICKS;
    }

    boolean active(long now) {
        return armedAt != Long.MIN_VALUE && now >= armedAt && now - armedAt < LIFETIME_TICKS;
    }

    // a second trigger while one is live does not move it: false = nothing changed, the walk away was done once already.
    // unless we are standing inside it, then the clock restarts and the walk away runs again (true): a spot we never walked
    // out of did nothing, and the endermen right next to us were still fair game
    boolean arm(double x, double y, double z, long now) {
        if (active(now)) {
            if (!covers(x, y, z, now)) {
                return false;
            }
            armedAt = now;
            return true;
        }
        this.x = x;
        this.y = y;
        this.z = z;
        armedAt = now;
        return true;
    }

    // the walk to that enderman goes through the spot (or starts inside it). flat on purpose: covers only asks where the
    // enderman stands, and the one past the lava is the one that walks us straight back in
    boolean blocksWalk(double usX, double usZ, double manX, double manZ, long now) {
        if (!active(now)) {
            return false;
        }
        double walkX = manX - usX;
        double walkZ = manZ - usZ;
        double length = walkX * walkX + walkZ * walkZ;
        // closest point of the walk to the spot, 0 is us and 1 is the enderman
        double along = length == 0 ? 0 : Math.max(0, Math.min(1, ((x - usX) * walkX + (z - usZ) * walkZ) / length));
        double dx = usX + along * walkX - x;
        double dz = usZ + along * walkZ - z;
        return dx * dx + dz * dz <= RADIUS * RADIUS;
    }

    boolean covers(double ex, double ey, double ez, long now) {
        if (!active(now)) {
            return false;
        }
        double dx = ex - x;
        double dy = ey - y;
        double dz = ez - z;
        return dx * dx + dy * dy + dz * dz <= RADIUS * RADIUS;
    }
}
