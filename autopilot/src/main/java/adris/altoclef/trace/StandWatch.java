package adris.altoclef.trace;

// the bot standing still on a live path is the failure nothing else logs: every pause and cancel reason in baritone is a
// quiet debug line, and a path being drawn says nothing about the feet. this counts the ticks the feet have stayed put
// while a path was live and says when to speak: at 40 ticks, 100, 200 and then every 200, and once more when it is over.
// flag only, it never acts. pure: the caller reads the game once a tick and hands in the numbers
public final class StandWatch {

    public enum Event {
        NONE,
        // say the stand line now, ticks() is how long it has been
        FIRE,
        // the stand is over (the feet moved or the path went away), lastStand() is how long it was
        OVER
    }

    // closer than this to where the feet stopped is jitter (knockback nudges, a bobbing boat), not walking
    public static final double MOVED = 0.3;

    private static final int FIRST = 40;
    private static final int SECOND = 100;
    private static final int THIRD = 200;
    private static final int EVERY = 200;

    private double ax;
    private double ay;
    private double az;
    private boolean anchored;
    // ticks the feet stayed put, path or not (the vitals line wants this one)
    private int still;
    // the same, but only while a path was live: this is the stand
    private int standing;
    private int nextFire = FIRST;
    private boolean told;
    private int lastStand;
    private boolean overWasMoved;

    // once per client tick. the compare against the anchor is the whole cost on a tick that has nothing to say
    public Event tick(boolean live, double x, double y, double z) {
        Event out = Event.NONE;
        boolean moved = !anchored || dist2(x, y, z) >= MOVED * MOVED;
        if (moved) {
            ax = x;
            ay = y;
            az = z;
            anchored = true;
            still = 0;
        } else {
            still++;
        }
        if (moved || !live) {
            if (told) {
                lastStand = standing;
                overWasMoved = live;
                out = Event.OVER;
            }
            standing = 0;
            nextFire = FIRST;
            told = false;
            return out;
        }
        standing++;
        if (standing >= nextFire) {
            told = true;
            nextFire = standing < SECOND ? SECOND : standing < THIRD ? THIRD : standing + EVERY;
            return Event.FIRE;
        }
        return out;
    }

    // ticks the feet have been in the same place, with or without a path. 0 right after a step
    public int stillTicks() {
        return still;
    }

    // ticks of the stand so far (0 when there is none), the number for the line right after a FIRE
    public int ticks() {
        return standing;
    }

    // how long the last stand lasted, only meaningful right after OVER
    public int lastStand() {
        return lastStand;
    }

    // true when the last OVER was the feet moving, false when the path went away
    public boolean overBecauseMoved() {
        return overWasMoved;
    }

    // the dump is for the first look at a stand that is not going away, not for every firing
    public boolean wantsDump() {
        return standing == SECOND;
    }

    private double dist2(double x, double y, double z) {
        double dx = x - ax;
        double dy = y - ay;
        double dz = z - az;
        return dx * dx + dy * dy + dz * dz;
    }
}
