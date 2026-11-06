package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.tasks.speedrun.gamer.EndermanNook;

import java.util.HashMap;
import java.util.Map;

// the pearl hunt's stare to pull: look a calm enderman in the eyes from far off so it comes to us, instead of walking up to
// it and provoking it at point blank. every decision is here with no minecraft in it, PearlHuntTask looks and logs
final class PullStare {
    // inside this the walk up is cheap and staring from close only makes it blink away
    static final double MIN_RANGE = 12;
    static final double MAX_RANGE = EndermanNook.STARE_RANGE;
    static final int STARE_TICKS = EndermanNook.STARE_TICKS;
    // an enderman that ignored a stare is left alone this long, same as the nook's bait skip
    static final long SKIP_TICKS = 400;
    // this many ignored stares in a row and the hunt goes back to walking up for a while
    static final int IGNORED_LIMIT = 2;
    static final long COOLDOWN_TICKS = 600;
    // a stare that was not ticked for this long was interrupted, not answered or ignored
    static final long STALE_TICKS = 5;

    private final Map<Integer, Long> skipUntil = new HashMap<>();
    private int id = -1;
    private int ticks;
    private long lastAt = Long.MIN_VALUE / 2;
    private int ignoredInARow;
    private long coolUntil = Long.MIN_VALUE;

    // an enderman worth pulling: calm, in sight, far enough that the walk up is not cheap and near enough to see us, not ignoring
    // us already and not the one the loot walk is on
    static boolean candidate(double distance, boolean alive, boolean calm, boolean sees, boolean skipped, boolean lootWalkOnIt) {
        return alive && calm && sees && !skipped && !lootWalkOnIt && distance >= MIN_RANGE && distance <= MAX_RANGE;
    }

    // the hunt may stare at all right now: nether only, not on a rim (a pulled one hits and the knock goes over the edge) and
    // not in the cool down after a couple of ignored stares
    static boolean allowed(boolean nether, boolean onRim, boolean cooling) {
        return nether && !onRim && !cooling;
    }

    boolean cooling(long now) {
        return now < coolUntil;
    }

    boolean skipped(int manId, long now) {
        return skipUntil.getOrDefault(manId, 0L) > now;
    }

    // one more tick of staring at this one. false once it has had its STARE_TICKS and ignored us: it is skipped for a while,
    // and the second one in a row starts the cool down
    boolean stare(int manId, long now) {
        if (now - lastAt > STALE_TICKS || manId != id) {
            id = manId;
            ticks = 0;
        }
        lastAt = now;
        if (++ticks <= STARE_TICKS) {
            return true;
        }
        if (skipUntil.size() > 64) {
            skipUntil.clear();
        }
        skipUntil.put(manId, now + SKIP_TICKS);
        if (++ignoredInARow >= IGNORED_LIMIT) {
            coolUntil = now + COOLDOWN_TICKS;
            ignoredInARow = 0;
        }
        id = -1;
        ticks = 0;
        return false;
    }

    // the one we were staring at turned angry: how long that took, -1 when this was not the stare in progress
    int answered(int manId, long now) {
        if (manId != id || now - lastAt > STALE_TICKS) {
            return -1;
        }
        int took = ticks;
        ignoredInARow = 0;
        id = -1;
        ticks = 0;
        return took;
    }

    int staringAt() {
        return id;
    }

    // a lava target only goes by the wayside when there is a dry one to take instead, if every candidate is by lava the hunt
    // still gets one (a hunt with nothing to pick starves)
    static boolean lavaFilterSkips(boolean byLava, boolean anyDryCandidate) {
        return byLava && anyDryCandidate;
    }
}
