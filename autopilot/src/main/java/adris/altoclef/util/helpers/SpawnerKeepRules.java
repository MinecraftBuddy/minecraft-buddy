package adris.altoclef.util.helpers;

/**
 * (Tuning spawner_keep, a fix of 22 Nov 2026, on) The blaze spawner the rods came from, kept for the run. A trip home for
 * food makes a new rods task, which knew no spawner: back in the nether it searched again and took a spawner across the
 * lava sea, the bridges to it ran out 30 blocks short and the run stalled, while the one six rods had come from stood 300
 * blocks away (box 2 heel-220-mc2 r3, look 234). With one kept, a rods task with no spawner walks back to it before any
 * search, and the pick takes it before a nearer one once it is loaded.
 */
public final class SpawnerKeepRules {

    // a rod that comes in within this many blocks of the spawner counts as from it
    public static final double RODS_FROM = 32;
    // farther than this from the feet, the kept spawner is not walked back to (another part of the nether: the search)
    public static final double KEEP_RANGE = 512;
    // the walk back gives the kept spawner up when it came no nearer by GAIN blocks in STUCK_TICKS
    public static final double GAIN = 4;
    public static final long STUCK_TICKS = 2400;

    private SpawnerKeepRules() {
    }

    public static boolean on() {
        return Tuning.get("spawner_keep", 0) > 0;
    }

    // (Tuning spawner_keep_found, a fix of 23 Nov 2026, on) the first spawner found is kept too, before any rod came from it:
    // after a trip home for food the route's phase 3 began again with "no spawner yet" and searched 11 to 20 minutes away from
    // a spawner found before the trip, until the stall (4 of 9 phase-3 stalls on 22 Nov: box 1 heel-209-mc1 r1, box 2
    // heel-193-mc2 r4 and heel-215-mc2 r4, cascade heel-180 r1). A rod from another spawner still takes its place
    public static boolean foundOn() {
        return on() && Tuning.get("spawner_keep_found", 0) > 0;
    }

    // whether a spawner just found is kept for the run: none is kept yet
    public static boolean keepFound(boolean on, boolean kept) {
        return on && !kept;
    }

    // whether a rod that came in now marks the spawner as the one the rods come from
    public static boolean rodFrom(boolean on, int rodsBefore, int rodsNow, boolean spawnerKnown, double fromSpawner) {
        return on && rodsBefore >= 0 && rodsNow > rodsBefore && spawnerKnown && fromSpawner <= RODS_FROM;
    }

    // whether a rods task with no spawner walks back to the kept one
    public static boolean walkBack(boolean on, boolean kept, boolean unreachable, boolean inNether, double away) {
        return on && kept && !unreachable && inNether && away <= KEEP_RANGE;
    }

    // whether the walk back is stuck: no gain of GAIN blocks on the best distance since bestAt for STUCK_TICKS
    public static boolean stuck(long bestAt, long now) {
        return now >= bestAt && now - bestAt > STUCK_TICKS;
    }
}
