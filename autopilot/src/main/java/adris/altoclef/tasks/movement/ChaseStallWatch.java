package adris.altoclef.tasks.movement;

import adris.altoclef.util.helpers.GameClock;

// "i have been chasing this thing for 45 s and have not gone anywhere". the path checks all reset whenever baritone says it is
// pathing, and a swimmer that replans one move at a time says that forever. position does not lie
final class ChaseStallWatch {
    // a minute would be a long time to stand in a pit, 45 s is plenty for any chase that is going somewhere
    static final int WINDOW_TICKS = 900;
    // inside this many blocks of the spot we started counting is still "went nowhere"
    static final double RADIUS = 2;

    // a meal or a fight can borrow the bot for a while and the task starts again after, so that does not reset the count. a
    // long absence does, it is a different chase by then
    static final int GAP_TICKS = 600;

    private double x, y, z;
    private long since = -1;
    private long lastSeen = -1;

    void reset() {
        since = -1;
    }

    // call every tick of the chase. true once the bot has stayed inside RADIUS of one spot for the whole window
    boolean stalled(long now, double px, double py, double pz) {
        boolean gone = lastSeen >= 0 && GameClock.since(lastSeen, now) > GAP_TICKS;
        lastSeen = now;
        if (since < 0 || gone) {
            anchor(now, px, py, pz);
            return false;
        }
        double dx = px - x, dy = py - y, dz = pz - z;
        if (dx * dx + dy * dy + dz * dz > RADIUS * RADIUS) {
            anchor(now, px, py, pz);
            return false;
        }
        long waited = GameClock.since(since, now);
        if (waited == Long.MAX_VALUE) {
            // the tick counter restarted (a reconnect), the old stamp is from another world
            anchor(now, px, py, pz);
            return false;
        }
        return waited >= WINDOW_TICKS;
    }

    private void anchor(long now, double px, double py, double pz) {
        x = px;
        y = py;
        z = pz;
        since = now;
    }
}
