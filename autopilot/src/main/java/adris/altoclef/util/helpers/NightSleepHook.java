package adris.altoclef.util.helpers;

import adris.altoclef.tasks.speedrun.gamer.NightSleep;

// the one fact the mob defense chain needs from the night trip: which stage it is in, null when there is no trip. same shape as
// StationHook, the owner (the gamer, or a schematic build) writes it every tick and clears it when it lets go. the chain asks
// NightSleep.standsDown with the live health, so the low health bail does not wait for the next owner tick
public final class NightSleepHook {
    // a stage nobody has written in this many game ticks is a leak (an owner that got dropped without saying so), not a trip.
    // ten seconds: long enough for a meal or a fire to have the wheel for a bit, short enough that mob defense isn't stood
    // down all night for nobody
    public static final long STALE_TICKS = 200;

    private static volatile Stamp stamp;

    private record Stamp(NightSleep.Stage stage, long at) {
    }

    private NightSleepHook() {
    }

    // now is the level's game time, the same clock the chain reads with
    public static void set(NightSleep.Stage now, long gameTime) {
        stamp = now == null ? null : new Stamp(now, gameTime);
    }

    public static void clear() {
        stamp = null;
        meleeAt = Long.MIN_VALUE;
    }

    // a mob's own swing landing on us, not an arrow. the lift starts at the foot of the pillar and the first blocks are still in
    // a zombie's reach: being hit there means we are not up yet, so the stand down waits this long after the last one. 5 s, a
    // zombie swings every second while it can reach us
    public static final long MELEE_LIFT_TICKS = 100;
    private static volatile long meleeAt = Long.MIN_VALUE;

    public static void noteMeleeHit(long gameTime) {
        meleeAt = gameTime;
    }

    static boolean meleeHitLately(long gameTime) {
        long at = meleeAt;
        return at != Long.MIN_VALUE && gameTime >= at && gameTime - at <= MELEE_LIFT_TICKS;
    }

    public static NightSleep.Stage stage(long gameTime) {
        Stamp s = stamp;
        // (a time from before the stamp is another world after a relog, just as stale)
        if (s == null || gameTime < s.at() || gameTime - s.at() > STALE_TICKS) {
            return null;
        }
        return s.stage();
    }

    public static boolean standsDown(float health, long gameTime) {
        // (sealed in a hole: no health line, the hole is where low health wants to be. the shelter lifts it itself when hit)
        if (sheltered(gameTime)) {
            return true;
        }
        NightSleep.Stage now = stage(gameTime);
        // (still on the climb and something is hitting us from the ground: that zombie is a fight, not the reason we are up here.
        // twenty to five with no swing back was the alternative)
        if (now == NightSleep.Stage.LIFT && meleeHitLately(gameTime)) {
            return false;
        }
        return now != null && NightSleep.standsDown(now, health);
    }

    // the night shelter's own stamp, separate from the bed trip's stage so clear() on a tick with no trip does not eat it
    private static volatile long shelterAt = Long.MIN_VALUE;

    public static void setSheltered(long gameTime) {
        shelterAt = gameTime;
    }

    public static void clearSheltered() {
        shelterAt = Long.MIN_VALUE;
    }

    public static boolean sheltered(long gameTime) {
        long at = shelterAt;
        return at != Long.MIN_VALUE && gameTime >= at && gameTime - at <= STALE_TICKS;
    }
    // (Tuning bed_failed_hole, a fix of 21 Nov 2026, on) the game time the last night trip ended given up (the night did not
    // pass with others awake, woken, no room): a bed in the bag that could not end the night is no bed for the buddy's night
    // hole (Obtain.holeNight). the hole waited on that bed, the trip gave up twice and the buddy worked the night in the open
    // with no armour till a creeper blew (heel-123-mc2 r2)
    private static volatile long bedGaveUpAt = Long.MIN_VALUE;

    public static void noteBedGaveUp(long gameTime) {
        bedGaveUpAt = gameTime;
    }

    public static long bedGaveUpAt() {
        return bedGaveUpAt;
    }

    // (Tuning bed_failed_slept, a fix of 22 Nov 2026, on) a give-up counts against the bed when the buddy lay in it (the
    // night did not pass, woken early) or when no other try comes tonight: a give-up before lying down with tries left is the
    // place's ("standing in liquid" by a water pocket at the feet) and the next try may still end the night. counted, it shut
    // the bed out for the night, the shelter's hand digs failed in the water and the buddy waited 11 minutes in the open
    // (look 220, heel-201-mc3 r1)
    public static boolean countsAgainstTheBed(boolean on, boolean layInIt, boolean retryLikely) {
        return !on || layInIt || !retryLikely;
    }

    // whether a trip gave up within the span of one night before now
    public static boolean bedGaveUpTonight(long gameTime, long nightSpan) {
        long at = bedGaveUpAt;
        return Tuning.get("bed_failed_hole", 0) > 0 && at != Long.MIN_VALUE && gameTime >= at && gameTime - at < nightSpan;
    }
}
