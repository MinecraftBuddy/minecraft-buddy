package adris.altoclef.tasks.speedrun.gamer.portal;

import adris.altoclef.tasks.speedrun.gamer.portal.LPortalPlan.Layout;
import adris.altoclef.tasks.speedrun.gamer.portal.LPortalPlan.P;
import adris.altoclef.tasks.speedrun.gamer.portal.LakeSurvey.Lake;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToIntFunction;

// the "where is the lava and how do we get there" half of the lava portal, with no minecraft in it so it can be tested.
// LPortalTask feeds it the scanned lava seeds and what the lake surveys found, it says walk, wait or give up (to the cast).
// every lake in the loaded chunks gets a verdict before the first walk, so a walk goes to a lake that passed and not to the
// cheapest seed. a lake at the view edge can't get one from here, so with nothing better on offer we go and read it
public final class LavaHunt {
    // only lakes this far above and below us count. a lake 50 blocks down is a cave lake and those always fail the floor rule
    public static final int BAND = 24;
    // the band never sinks below the surface. starting the portal from a cave (an item pickup at y 2, say) would centre it
    // on cave lava 20 blocks down, and the walk there is cliff rims over the pool
    static final int SURFACE_Y = 60;
    static final int PER_CHUNK = 3;
    // the far lakes have to make the list too, the scan keeps the nearest ones
    static final int SEED_MAX = 200;
    // a seed with no site found this close to it clears every seed in the same lake
    static final int SITE_RADIUS = 24;
    // a good lake this close is built on from where we stand, a farther one is walked to first. picking starts the clearing and
    // build clocks, and those are not for walking or digging in, so we have to basically be there already
    static final int PICK_REACH = 10;
    static final int PICK_DY = 6;
    // a lake under this much ground is not a surface lake with a roof, it's a mining trip with a portal at the end
    static final int MAX_DEPTH = 20;
    // a block of ground over the lake against a block of walking. digging in is slower than walking but it's not a whole lot
    // slower, and DY_COST already charges the climb from where we stand
    static final int DEPTH_COST = 2;
    // a lake at the view edge is well over 100 blocks off and we do under 2 blocks a second around the trees. running out of this
    // rejects the lake, so it has to be a walk nobody would really be on and not just a long one
    static final int WALK_TICKS = 20 * 120;
    // a walk that is not this much closer than its best in this long is stuck. the goal being re-issued does not count
    static final int PROGRESS_BLOCKS = 3;
    static final int PROGRESS_TICKS = 20 * 15;
    // a lake we could not read all of is looked at again after this
    static final int UNKNOWN_TICKS = 20 * 10;
    // going up or down costs more than going sideways, so a lake at our level wins over one under our feet
    static final int DY_COST = 4;
    // walking loads chunks, and new chunks show new half read lakes. this would go on forever, so a half read lake only gets
    // this many walks, then it counts as bad and the cast takes over
    static final int UNREAD_WALKS = 2;

    enum Move {WALK, WAIT, GIVE_UP}

    // at most PER_CHUNK hits per chunk, counted inside the scanner's filter (it stops a chunk at max, a count after the
    // fact would be too late). the filter runs one thread per chunk but computeIfAbsent can race, so the map is concurrent
    static final class ChunkCap {
        private final Map<Long, int[]> counts = new ConcurrentHashMap<>();

        private static long key(int x, int z) {
            return ((long) (x >> 4) << 32) ^ ((z >> 4) & 0xffffffffL);
        }

        boolean full(int x, int z) {
            int[] c = counts.get(key(x, z));
            return c != null && c[0] >= PER_CHUNK;
        }

        boolean take(int x, int z) {
            return ++counts.computeIfAbsent(key(x, z), k -> new int[1])[0] <= PER_CHUNK;
        }
    }

    public static int bandCentre(int py) {
        return Math.max(py, SURFACE_Y);
    }

    // a lava cell at this height is surface lava from where we stand, same band the scan keeps
    public static boolean inBand(int y, int py) {
        return Math.abs(y - bandCentre(py)) <= BAND;
    }

    // the nearest lake the cast could flood from, any verdict: the pool turned every one down, but the cast does not need a
    // shore like the pool does, only a flood of sources. lakeSources floods from a seed and says how many it found (a seed in a
    // lake it already counted says 0, that is lakeSize's own explored set)
    static P castLake(List<P> seeds, int px, int py, int pz, int minSources, ToIntFunction<P> lakeSources) {
        for (P p : byCost(seeds, px, py, pz)) {
            if (lakeSources.applyAsInt(p) >= minSources) {
                return p;
            }
        }
        return null;
    }

    static double cost(P seed, int px, int py, int pz) {
        return Math.hypot(seed.x() - px, seed.z() - pz) + DY_COST * Math.abs(seed.y() - py);
    }

    static List<P> byCost(List<P> seeds, int px, int py, int pz) {
        List<P> out = new ArrayList<>(new HashSet<>(seeds));
        out.sort(Comparator.comparingDouble((P p) -> cost(p, px, py, pz)).thenComparingInt(P::x).thenComparingInt(P::y).thenComparingInt(P::z));
        return out;
    }

    static P mid(Layout l) {
        return l.cell(1, 0, 0);
    }

    private record Entry(Lake lake, int tick) {
    }

    // the lakes we gave up on. the phase hands the list in, so a retry of the phase doesn't walk to the same ones again
    private final List<P> rejected;
    private final List<Entry> lakes = new ArrayList<>();
    private P target;
    // the target is a half read lake's anchor and not a site, so there is nothing to stay good yet
    private boolean blindWalk;
    private int unreadWalks;
    private int walkStart;
    private double bestDist;
    private int progressTick;

    LavaHunt() {
        this(new ArrayList<>());
    }

    LavaHunt(List<P> rejected) {
        this.rejected = rejected;
    }

    P target() {
        return target;
    }

    boolean blindWalk() {
        return blindWalk;
    }

    // what the give up says, so it is more than "no"
    String tally() {
        int unread = 0;
        for (Entry e : lakes) {
            if (e.lake().unknown()) {
                unread++;
            }
        }
        return lakes.size() + " lakes, " + unread + " half read, " + unreadWalks + " walks to read one";
    }

    // every seed we may still look at, cheapest first
    List<P> usable(List<P> seeds, int px, int py, int pz) {
        List<P> out = new ArrayList<>();
        for (P p : byCost(seeds, px, py, pz)) {
            if (!nearRejected(p)) {
                out.add(p);
            }
        }
        return out;
    }

    // the cheapest seed whose lake has no verdict yet, or null when every lake has one
    P nextToSurvey(List<P> seeds, int px, int py, int pz, int tick) {
        lakes.removeIf(e -> e.lake().unknown() && tick - e.tick() > UNKNOWN_TICKS);
        for (P p : usable(seeds, px, py, pz)) {
            if (!covered(p)) {
                return p;
            }
        }
        return null;
    }

    private boolean covered(P seed) {
        for (Entry e : lakes) {
            if (e.lake().cells().contains(seed)) {
                return true;
            }
        }
        return false;
    }

    void surveyed(Lake lake, int tick) {
        lakes.add(new Entry(lake, tick));
    }

    // a verdict that went stale between the survey and the pick. the lake is looked at again
    void drop(Layout site) {
        lakes.removeIf(e -> site.equals(e.lake().site()));
        target = null;
    }

    // terrain says good, the bag decides if we can build it right now. a lake we can't afford is skipped, never rejected
    private boolean usableLake(Lake l, int blocks) {
        return l.good() && !nearRejected(mid(l.site())) && blocks >= 8 + l.fills();
    }

    // the good lake to build on from here: close enough that the build clock isn't a walk, cheapest work first. while other lakes
    // are still being looked at only a perfect one is taken at once
    Layout pick(int px, int py, int pz, int blocks, boolean perfectOnly) {
        Layout best = null;
        int bestCost = 0;
        double bestFlat = 0;
        for (Entry e : lakes) {
            Lake l = e.lake();
            if (!usableLake(l, blocks)) {
                continue;
            }
            P m = mid(l.site());
            // the lake we walked to is the one lake that isn't made to wait for the others
            if (perfectOnly && l.cost() != 0 && !m.equals(target)) {
                continue;
            }
            double flat = Math.hypot(m.x() - px, m.z() - pz);
            if (flat > PICK_REACH || Math.abs(m.y() - py) > PICK_DY) {
                continue;
            }
            if (best == null || l.cost() < bestCost || (l.cost() == bestCost && flat < bestFlat)) {
                best = l.site();
                bestCost = l.cost();
                bestFlat = flat;
            }
        }
        return best;
    }

    // a site we picked turned out bad (lava showed up while clearing it, the clearing ran out of time, the walk went nowhere).
    // its lake is not visited again, by this task or by the retry after it
    void siteFailed(P around) {
        rejected.add(around);
        lakes.removeIf(e -> dist(e.lake().anchor(), around) <= SITE_RADIUS || e.lake().cells().contains(around)
                || (e.lake().good() && dist(mid(e.lake().site()), around) <= SITE_RADIUS));
        target = null;
    }

    private boolean nearRejected(P p) {
        for (P r : rejected) {
            if (dist(p, r) <= SITE_RADIUS) {
                return true;
            }
        }
        return false;
    }

    private static double dist(P a, P b) {
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        double dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private boolean stillGood(int blocks) {
        for (Entry e : lakes) {
            if (usableLake(e.lake(), blocks) && mid(e.lake().site()).equals(target)) {
                return true;
            }
        }
        return false;
    }

    // a walk to a half read lake lasts until that lake has a real verdict, or a lake we can use shows up (a site beats a maybe).
    // no entry for it at all is just its 10 s look again, the walk doesn't get to forget where it was going over that
    private boolean stillUnread(int blocks) {
        for (Entry e : lakes) {
            if (usableLake(e.lake(), blocks) || (!e.lake().unknown() && e.lake().cells().contains(target))) {
                return false;
            }
        }
        return true;
    }

    private P cheapestGood(int px, int py, int pz, int blocks) {
        P best = null;
        double bestCost = 0;
        for (Entry e : lakes) {
            if (!usableLake(e.lake(), blocks)) {
                continue;
            }
            P m = mid(e.lake().site());
            double c = cost(m, px, py, pz) + e.lake().cost() + DEPTH_COST * e.lake().depth();
            if (best == null || c < bestCost) {
                best = m;
                bestCost = c;
            }
        }
        return best;
    }

    private P nearestUnread(int px, int py, int pz) {
        if (unreadWalks >= UNREAD_WALKS) {
            return null;
        }
        P best = null;
        double bestCost = 0;
        for (Entry e : lakes) {
            P a = e.lake().anchor();
            if (!e.lake().unknown() || nearRejected(a)) {
                continue;
            }
            double c = cost(a, px, py, pz);
            if (best == null || c < bestCost) {
                best = a;
                bestCost = c;
            }
        }
        return best;
    }

    // a site first, a half read lake if there's no site, and false when it's neither
    private boolean startWalk(int px, int py, int pz, int tick, int blocks) {
        P to = cheapestGood(px, py, pz, blocks);
        blindWalk = to == null;
        if (blindWalk) {
            to = nearestUnread(px, py, pz);
            if (to == null) {
                return false;
            }
            unreadWalks++;
        }
        target = to;
        walkStart = tick;
        bestDist = dist(target, new P(px, py, pz));
        progressTick = tick;
        return true;
    }

    // what to do this tick, with nothing near enough to build on. scanned = a seed scan has finished, so an empty list means
    // there is really no lava in the loaded chunks and not that we haven't looked yet. giving up (to the cast) needs that, a
    // verdict on every lake, none of them good, and no half read lake left that we may still walk to
    Move decide(List<P> seeds, boolean scanned, boolean surveying, int px, int py, int pz, int tick, int blocks) {
        if (!scanned) {
            return Move.WAIT;
        }
        if (target != null && !(blindWalk ? stillUnread(blocks) : stillGood(blocks))) {
            target = null;
        }
        if (target == null) {
            // the first walk waits for every verdict, a walk already going doesn't stop for a new lake
            // surveying: the lake in the middle of a survey may have slid out of the newest seed list, it still has no verdict
            if (surveying || nextToSurvey(seeds, px, py, pz, tick) != null) {
                return Move.WAIT;
            }
            if (!startWalk(px, py, pz, tick, blocks)) {
                return Move.GIVE_UP;
            }
        }
        double d = dist(target, new P(px, py, pz));
        if (d < bestDist - PROGRESS_BLOCKS) {
            bestDist = d;
            progressTick = tick;
        }
        // the cap is on the whole walk: always a bit closer is still a walk nobody waits 2 minutes for
        if (tick - progressTick > PROGRESS_TICKS || tick - walkStart > WALK_TICKS) {
            siteFailed(target);
            return Move.WAIT;
        }
        return Move.WALK;
    }
}
