package adris.altoclef.trackers;

import baritone.api.utils.Dimension;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

// the one book for "leave that alone". there used to be nine, each with its own idea of when a ban is over, and the
// usual answer was never. every ban here has a key, a reason, and a way out: a clock, an event, or the run ending.
// the block and entity trackers ask this before they offer anything, so a task never has to keep its own set to stay
// off a thing somebody else already gave up on. pure (no world), the caller feeds it the clock and the events.
// synchronized because the block scan thread asks it too
public final class Bans {
    // what can end a ban besides its clock
    public enum Until {
        // a pick tier we did not have when it was banned. the old blacklist's "reset when you have a better tool"
        BETTER_TOOL,
        // its chunk unloaded and came back, i.e. we left and returned. a fresh look is worth a fresh try
        CHUNK_RELOAD,
        // an entity that hits us can clearly get to us, so the reverse is worth a try too
        HIT_US
    }

    public enum Kind {BLOCK, ENTITY}

    // ticks for "until the run is over". the run ends when the world goes away or a new #gamer starts (clearRun)
    public static final long RUN = Long.MAX_VALUE;

    // dim is null for entities, their ids are unique across the whole world session
    public record Key(Kind kind, Dimension dim, int x, int y, int z) {
        public static Key block(Dimension dim, int x, int y, int z) {
            return new Key(Kind.BLOCK, dim, x, y, z);
        }

        public static Key entity(int id) {
            return new Key(Kind.ENTITY, null, id, 0, 0);
        }

        @Override
        public String toString() {
            if (kind == Kind.ENTITY) {
                return "entity #" + x;
            }
            return "block " + x + " " + y + " " + z + " (" + (dim == null ? "?" : dim.name().toLowerCase()) + ")";
        }
    }

    // strike = this ban is the one the strike count earned (see strike), the count goes with it and nothing else.
    // scope null = everybody honours it (the trackers do), otherwise only a caller that asks for that scope sees it
    private record Ban(Key key, String reason, long until, EnumSet<Until> events, int tool, boolean strike, String scope) {
        boolean holds(long now, String asking) {
            return until > now && (scope == null || scope.equals(asking));
        }
    }

    // the "n failures then out" bookkeeping, see strike
    private static final class Strikes {
        int failures;
        long last;
        double bestDistSq = Double.POSITIVE_INFINITY;
        int bestTool;
    }

    private final Consumer<String> log;
    private final Map<Key, List<Ban>> bans = new HashMap<>();
    private final Map<Key, Strikes> strikes = new HashMap<>();
    // chunks that unloaded since, per dimension (packed x/z). a load only counts as a REload for these: the client also sends
    // a load for a chunk that is still there whenever a tracked block in it changes, a furnace going lit is enough
    private final Map<Dimension, Set<Long>> unloaded = new HashMap<>();
    private long now;
    // the pick tier we hold, fed by toolTier. a ban remembers it so a better one can end it
    private int tool;
    // nothing expires before this, so the sweep is a compare most ticks
    private long nextExpiry = Long.MAX_VALUE;

    public Bans(Consumer<String> log) {
        this.log = log;
    }

    // ---- the clock and the events

    // one game tick went by. bans whose clock ran out go, with their line
    public synchronized void tick(long gameTime) {
        // game time only goes backwards with a new world, and leaving the old one already cleared the book
        now = gameTime;
        if (now < nextExpiry) {
            return;
        }
        nextExpiry = Long.MAX_VALUE;
        expireIf(b -> b.until <= now, "expired");
        for (List<Ban> list : bans.values()) {
            for (Ban b : list) {
                nextExpiry = Math.min(nextExpiry, b.until);
            }
        }
    }

    // the best pick tier we hold right now (MiningRequirement ordinal). going up ends the BETTER_TOOL bans made below it
    public synchronized void toolTier(int tier) {
        if (tier > tool) {
            expireIf(b -> b.events.contains(Until.BETTER_TOOL) && tier > b.tool, "better tool");
        }
        tool = tier;
    }

    // only remembered for a chunk that holds a CHUNK_RELOAD ban, walking across the map unloads thousands. matched on the ban's
    // own dimension and not the caller's: through a portal the old level's forget packets land after we switched levels
    public synchronized void chunkUnloaded(int chunkX, int chunkZ) {
        for (List<Ban> list : bans.values()) {
            for (Ban b : list) {
                if (b.key.dim != null && inChunk(b, b.key.dim, chunkX, chunkZ)) {
                    unloaded.computeIfAbsent(b.key.dim, d -> new HashSet<>()).add(chunkKey(chunkX, chunkZ));
                }
            }
        }
    }

    // we left this dimension: all of it unloaded as far as we are concerned, whether or not a forget packet ever said so
    public synchronized void dimensionLeft(Dimension dim) {
        for (List<Ban> list : bans.values()) {
            for (Ban b : list) {
                if (b.key.dim == dim && b.events.contains(Until.CHUNK_RELOAD)) {
                    unloaded.computeIfAbsent(dim, d -> new HashSet<>()).add(chunkKey(b.key.x >> 4, b.key.z >> 4));
                }
            }
        }
    }

    public synchronized void chunkLoaded(Dimension dim, int chunkX, int chunkZ) {
        Set<Long> gone = unloaded.get(dim);
        if (gone == null || !gone.remove(chunkKey(chunkX, chunkZ))) {
            return;
        }
        expireIf(b -> inChunk(b, dim, chunkX, chunkZ), "chunk reloaded");
    }

    private static boolean inChunk(Ban b, Dimension dim, int chunkX, int chunkZ) {
        return b.events.contains(Until.CHUNK_RELOAD) && b.key.kind == Kind.BLOCK && b.key.dim == dim
                && (b.key.x >> 4) == chunkX && (b.key.z >> 4) == chunkZ;
    }

    public synchronized void hitBy(int entityId) {
        Key key = Key.entity(entityId);
        if (bans.containsKey(key)) {
            expireIf(b -> b.events.contains(Until.HIT_US) && b.key.equals(key), "it hit us");
        }
    }

    // the world went away, or a new run starts. everything goes, one line for the lot
    public synchronized void clearRun(String why) {
        int n = count();
        if (n > 0 || !strikes.isEmpty()) {
            log.accept("ban: run over (" + why + "), dropped " + n);
        }
        bans.clear();
        strikes.clear();
        unloaded.clear();
        nextExpiry = Long.MAX_VALUE;
    }

    // ---- adding and lifting

    // ban it now. the same key for the same reason again just keeps the ban that is there (DangerFilter asks every 2 s),
    // a different reason is a second ban that has to run out on its own
    public synchronized boolean ban(Key key, String reason, long ticks, Until... until) {
        return add(key, reason, ticks, false, null, false, until);
    }

    // a ban only callers asking for `scope` see (banned(key, scope)). the trackers never ask, so every other task still gets
    // offered the thing: the coal detour giving up on an ore says nothing about the fuel task that really needs coal
    public synchronized boolean banFor(String scope, Key key, String reason, long ticks, Until... until) {
        return add(key, reason, ticks, false, scope, false, until);
    }

    // a batch with one line for the lot ("ban: + N <summary>, <how long>") instead of one per key. an outpost is a hundred
    // logs and wool, and a hundred lines at once is a log nobody reads
    public synchronized int banAll(Iterable<Key> keys, String reason, long ticks, String summary) {
        int n = 0;
        for (Key key : keys) {
            if (add(key, reason, ticks, false, null, true)) {
                n++;
            }
        }
        if (n > 0) {
            log.accept("ban: + " + n + " " + summary + ", " + describe(ticks, EnumSet.noneOf(Until.class)));
        }
        return n;
    }

    private boolean add(Key key, String reason, long ticks, boolean strike, String scope, boolean quiet, Until... until) {
        List<Ban> list = bans.computeIfAbsent(key, k -> new ArrayList<>(1));
        for (Ban b : list) {
            if (b.reason.equals(reason) && Objects.equals(b.scope, scope) && b.until > now) {
                return false;
            }
        }
        long end = ticks == RUN ? RUN : now + ticks;
        EnumSet<Until> events = until.length == 0 ? EnumSet.noneOf(Until.class) : EnumSet.of(until[0], until);
        Ban b = new Ban(key, reason, end, events, tool, strike, scope);
        list.add(b);
        nextExpiry = Math.min(nextExpiry, end);
        if (!quiet) {
            log.accept("ban: + " + key + ", " + reason + ", " + describe(ticks, events) + (scope == null ? "" : ", only for " + scope));
        }
        return true;
    }

    // one more failure on this key. the old AbstractObjectBlacklist rule, now a policy: more than `allowed` failures and it
    // is banned, but a failure from closer than ever (by a block, squared) or with a better pick starts the count over,
    // because that try was a different try. true when this strike was the one that banned it
    public synchronized boolean strike(Key key, String reason, int allowed, double distSq, long ticks, Until... until) {
        // already out on strikes: one more is no news, and no second ban with a bigger number in its reason
        if (struckOut(key)) {
            return false;
        }
        Strikes s = strikes.computeIfAbsent(key, k -> new Strikes());
        // a count that sat as long as its ban would have lasted is old news, not two halves of one ban
        if (s.failures > 0 && ticks != RUN && now - s.last >= ticks) {
            strikes.remove(key);
            s = strikes.computeIfAbsent(key, k -> new Strikes());
        }
        s.last = now;
        if (tool > s.bestTool || distSq < s.bestDistSq - 1) {
            s.bestTool = Math.max(s.bestTool, tool);
            s.bestDistSq = Math.min(s.bestDistSq, distSq);
            s.failures = 0;
        }
        s.failures++;
        if (s.failures <= allowed) {
            return false;
        }
        return add(key, reason + " (" + s.failures + (s.failures == 1 ? " try)" : " tries)"), ticks, true, null, false, until);
    }

    private boolean struckOut(Key key) {
        List<Ban> list = bans.get(key);
        if (list != null) {
            for (Ban b : list) {
                if (b.strike && b.until > now) {
                    return true;
                }
            }
        }
        return false;
    }

    // lifts the bans with this reason that match, and only those. a block banned for something else stays banned.
    // the "until-event" for bans whose end only the caller can see (an outpost going quiet)
    public synchronized int lift(String reason, Predicate<Key> which) {
        return expireIf(b -> b.reason.equals(reason) && which.test(b.key), "lifted", false);
    }

    // the banAll of lifting, one line for the lot
    public synchronized int liftAll(String reason, Predicate<Key> which, String summary) {
        int n = expireIf(b -> b.reason.equals(reason) && which.test(b.key), "lifted", true);
        if (n > 0) {
            log.accept("ban: - " + n + " " + summary + " (lifted)");
        }
        return n;
    }

    // ---- the query

    // what everybody honours, the trackers ask this
    public boolean banned(Key key) {
        return banned(key, null);
    }

    // the everybody bans plus the ones only `scope` sees
    public synchronized boolean banned(Key key, String scope) {
        List<Ban> list = bans.get(key);
        if (list == null) {
            return false;
        }
        for (Ban b : list) {
            // a ban past its clock is over even if the sweep has not been round yet
            if (b.holds(now, scope)) {
                return true;
            }
        }
        return false;
    }

    public boolean blockBanned(Dimension dim, int x, int y, int z) {
        return banned(Key.block(dim, x, y, z));
    }

    public boolean entityBanned(int id) {
        return banned(Key.entity(id));
    }

    public synchronized int count() {
        int n = 0;
        for (List<Ban> list : bans.values()) {
            n += list.size();
        }
        return n;
    }

    // ---- inside

    private int expireIf(Predicate<Ban> gone, String why) {
        return expireIf(gone, why, false);
    }

    private int expireIf(Predicate<Ban> gone, String why, boolean quiet) {
        int n = 0;
        Iterator<Map.Entry<Key, List<Ban>>> it = bans.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Key, List<Ban>> e = it.next();
            Iterator<Ban> inner = e.getValue().iterator();
            while (inner.hasNext()) {
                Ban b = inner.next();
                if (gone.test(b)) {
                    inner.remove();
                    n++;
                    if (!quiet) {
                        log.accept("ban: - " + b.key + ", " + b.reason + " (" + why + ")");
                    }
                    // the count starts over when the ban it earned is over. a short ban some task put on the same key
                    // running out says nothing about the count, or "three stalls and it's the long one" never got to three
                    if (b.strike) {
                        strikes.remove(b.key);
                    }
                }
            }
            if (e.getValue().isEmpty()) {
                it.remove();
            }
        }
        return n;
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static String describe(long ticks, EnumSet<Until> events) {
        StringBuilder out = new StringBuilder(ticks == RUN ? "for the run" : seconds(ticks));
        for (Until u : events) {
            out.append(" or until ").append(switch (u) {
                case BETTER_TOOL -> "a better pick";
                case CHUNK_RELOAD -> "the chunk reloads";
                case HIT_US -> "it hits us";
            });
        }
        return out.toString();
    }

    private static String seconds(long ticks) {
        long s = ticks / 20;
        return s >= 120 && s % 60 == 0 ? (s / 60) + " min" : s + " s";
    }
}
