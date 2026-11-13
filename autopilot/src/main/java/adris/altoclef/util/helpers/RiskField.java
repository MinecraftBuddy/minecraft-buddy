package adris.altoclef.util.helpers;

import baritone.pathing.movement.CliffAvoidance.Cells;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

// the buddy's fork (S137 step 2, risk in the path search): the live mob part of the risk field.
// the game side (DangerShadow, every five ticks) hands over the mobs that want us. a new version goes out only when one came,
// went, changed its want or moved more than a block; a version is immutable, so the search thread reads one whole while the
// next is built. a search takes the newest at its start (RiskCost.start), and the walk checks the rest of its path against
// each new one (RiskWatch), so the field is never frozen for longer than a search.
// what a mob costs at a cell is what DangerMeter reckons from its MobThreat row, per cell and aware of time:
//   melee: its hp a second for the time we spend in the cell, when it can be there by the time we are (its distance less its
//     reach, at its speed, against our arrival). past the horizon its reach stops growing: where a chasing mob is by then
//     depends on where we went, and the next version says. a blaze's bite only within its reach: it does not chase, except
//     one in its bite, a step past it (MobThreat.biting)
//   shots: its hp a second at that distance (the aimed share), when the cell is in its range and in sight of its eye: no
//     block that spans its cell on the line (Cells.blocksSight; fire and a fence are no cover)
// both times its want (DangerMeter.wantsToHurt) and its armour scale for us (DangerMeter.scale)
// the memory (S137, a place goal by a safer route; Tuning risk_memory_s, under risk_flee): the live field forgets a mob a few
// seconds after it stops wanting the buddy, so a route given up for one group of blazes was free again as soon as the buddy
// looked at the next group, and the walk went to and fro between them. the mobs a route was given up for (a run for the
// danger meter began, the walk planned again for more risk, one of them hit) are remembered: once such a mob is out of the
// live field it stays a source at the place it was last seen, at the want it had, for risk_memory_full_s, then fading to
// nothing at risk_memory_s. the next search still pays for the first group while it looks at the second. a mob seen dead,
// or gone while the buddy is near its place, is forgotten at once (the game side says, DangerShadow)
public final class RiskField {

    // a mob that moved less than this since the last version keeps that version
    public static final double MOVE = 1;
    // where our eye is above the feet, for a shooter's line
    static final double OUR_EYE = 1.62;

    // a mob as the field prices it: its entity id, its row, its feet, how much it wants us (0.5 or 1), the ratio its
    // iron-and-Normal numbers take for our armour and the difficulty, and whether it has a way to us on foot. kept: not in
    // the live field now, remembered (the memory): its last place, its want faded
    public record Source(int id, MobThreat row, double x, double y, double z, double want, double scale, boolean way, boolean kept) {
        public Source(int id, MobThreat row, double x, double y, double z, double want, double scale, boolean way) {
            this(id, row, x, y, z, want, scale, way, false);
        }
    }

    // one version of the field: its number, the game tick it was built at, the world it is of (a search in another one
    // sees no mobs), whether we had fire resistance then, and the mobs
    public record Version(long id, long tick, Object world, boolean fireRes, List<Source> sources) {
        public Source source(int id) {
            for (Source s : sources) {
                if (s.id() == id) return s;
            }
            return null;
        }

        // a mob in it is a remembered one
        public boolean remembers() {
            for (Source s : sources) {
                if (s.kept()) return true;
            }
            return false;
        }
    }

    public static final Version NONE = new Version(0, 0, null, false, List.of());

    private static volatile Version current = NONE;
    private static long ids;

    private RiskField() {}

    public static Version current() {
        return current;
    }

    // the game thread's: the mobs that want us now. the remembered ones that are not among them are added (the memory).
    // true when that made a new version
    public static synchronized boolean offer(long tick, Object world, boolean fireRes, List<Source> sources) {
        Version old = current;
        // (another world's mobs are not this one's)
        if (old.world() != world) memory.clear();
        sources = withMemory(sources, tick);
        if (old.world() == world && old.fireRes() == fireRes && !differs(old.sources(), sources, MOVE)) {
            return false;
        }
        current = new Version(++ids, tick, world, fireRes, List.copyOf(sources));
        return true;
    }

    // no field: out of the dimensions the meter reads, dead, or between worlds. the memory goes with it
    public static synchronized void clear(long tick) {
        memory.clear();
        if (current.world() != null || !current.sources().isEmpty()) {
            current = new Version(++ids, tick, null, false, List.of());
        }
    }

    // ---- the meter's word for the searches

    // the danger meter is over calm (the level the runs go by, DangerShadow): under risk_flee the walk's own searches price
    // the field then (RiskCost.route)
    private static volatile boolean alert;

    public static void alert(boolean on) {
        alert = on;
    }

    public static boolean alert() {
        return alert;
    }

    // the task has the wheel (TaskGoal): a search that starts now is the task's own walk, not a fight's or a run's
    private static volatile boolean taskWalk;

    public static void taskWalk(boolean on) {
        taskWalk = on;
    }

    public static boolean taskWalk() {
        return taskWalk;
    }

    // ---- the memory

    // how long a mob a route was given up for stays in the field after it left the live one, in seconds, and how long of
    // that at its full want (Tuning risk_memory_s, 0 is no memory, and risk_memory_full_s)
    // (a minute, half of it full: smoke5 of S137 step 3 with 180 and 60 priced 154 of 206 task walks in calm by the memory
    // alone, 12 to 14 mobs a search, and 43 of them found no path)
    public static final double MEMORY_S = 60;
    public static final double MEMORY_FULL_S = 30;
    // the fading goes in this many steps: each step is a new version of the field, and a version a tick would have every
    // walk check its path again each time
    static final int FADE_STEPS = 4;
    // at most this many are remembered, the oldest dropped first
    static final int MEMORY_MAX = 24;

    // a remembered mob: as it was last seen, and the tick it was last a reason to give a route up
    private record Kept(Source source, long at) {}

    private static final Map<Integer, Kept> memory = new HashMap<>();

    public static boolean memoryOn() {
        return Tuning.get("risk_flee", 0) >= 1 && Tuning.get("risk_memory_s", MEMORY_S) > 0;
    }

    // a route was given up for what is in the field now (a run for the danger meter began, the walk planned again for more
    // risk): the live mobs in it that counted in the meter's T at the time (counted, by entity; null: every one) are
    // remembered from this tick, not the whole field (smoke5: every live source was, 77 mobs). the number that were not
    // remembered before
    public static int remember(long tick) {
        return remember(tick, null);
    }

    public static synchronized int remember(long tick, java.util.Set<Integer> counted) {
        if (!memoryOn()) return 0;
        int fresh = 0;
        for (Source s : current.sources()) {
            if (s.kept() || (counted != null && !counted.contains(s.id()))) continue;
            if (memory.put(s.id(), new Kept(s, tick)) == null) fresh++;
        }
        trim();
        return fresh;
    }

    // the same for one mob of the live field (the one that hit us). true when it was not remembered before
    public static synchronized boolean remember(int id, long tick) {
        if (!memoryOn()) return false;
        Source s = current.source(id);
        if (s == null || s.kept()) return false;
        boolean fresh = memory.put(id, new Kept(s, tick)) == null;
        trim();
        return fresh;
    }

    private static void trim() {
        while (memory.size() > MEMORY_MAX) {
            Integer oldest = null;
            for (Map.Entry<Integer, Kept> e : memory.entrySet()) {
                if (oldest == null || e.getValue().at() < memory.get(oldest).at()) oldest = e.getKey();
            }
            memory.remove(oldest);
        }
    }

    // (the repair after smoke6) this mob is one a route was given up for, still remembered: the blaze fight's way back asks it
    public static synchronized boolean remembers(int id) {
        return memoryOn() && memory.containsKey(id);
    }

    // the remembered mobs as last seen, for the game side's look at each: where it is now, or that it is gone
    public static synchronized List<Source> remembered() {
        List<Source> out = new ArrayList<>(memory.size());
        for (Kept k : memory.values()) out.add(k.source());
        return out;
    }

    // a remembered mob seen out of the live field: where it is now
    public static synchronized void moved(int id, double x, double y, double z) {
        Kept k = memory.get(id);
        if (k == null) return;
        Source s = k.source();
        memory.put(id, new Kept(new Source(s.id(), s.row(), x, y, z, s.want(), s.scale(), s.way()), k.at()));
    }

    // dead, or gone while we were near its place
    public static synchronized void forget(int id) {
        memory.remove(id);
    }

    // the live mobs with the remembered ones that are not among them, each at its last place with its want faded. a
    // remembered mob that is live keeps its place and its want up to date
    private static List<Source> withMemory(List<Source> live, long tick) {
        if (memory.isEmpty()) return live;
        double total = memoryOn() ? Tuning.get("risk_memory_s", MEMORY_S) * 20 : 0;
        if (total <= 0) {
            memory.clear();
            return live;
        }
        double full = Math.min(total, Math.max(0, Tuning.get("risk_memory_full_s", MEMORY_FULL_S) * 20));
        Map<Integer, Source> byId = new HashMap<>();
        for (Source s : live) byId.put(s.id(), s);
        List<Source> out = new ArrayList<>(live);
        for (Iterator<Map.Entry<Integer, Kept>> it = memory.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Kept> e = it.next();
            Kept k = e.getValue();
            Source now = byId.get(e.getKey());
            if (now != null) {
                e.setValue(new Kept(now, k.at()));
                continue;
            }
            double fade = tick < k.at() ? 0 : fade(tick - k.at(), full, total);
            if (fade <= 0) {
                it.remove();
                continue;
            }
            Source s = k.source();
            out.add(new Source(s.id(), s.row(), s.x(), s.y(), s.z(), s.want() * fade, s.scale(), s.way(), true));
        }
        return out;
    }

    // 1 up to full, then down to 0 at total in FADE_STEPS steps
    static double fade(double age, double full, double total) {
        if (age >= total) return 0;
        if (age <= full) return 1;
        return Math.ceil((total - age) / (total - full) * FADE_STEPS) / FADE_STEPS;
    }

    // a mob came or went, or one changed its want, its way or its scale, or moved more than a block
    static boolean differs(List<Source> was, List<Source> now, double move) {
        if (was.size() != now.size()) return true;
        Map<Integer, Source> byId = new HashMap<>();
        for (Source s : was) byId.put(s.id(), s);
        for (Source s : now) {
            Source o = byId.get(s.id());
            if (o == null || o.row() != s.row() || o.want() != s.want() || o.way() != s.way() || o.kept() != s.kept()
                    || Math.abs(o.scale() - s.scale()) > 0.05) {
                return true;
            }
            double dx = o.x() - s.x(), dy = o.y() - s.y(), dz = o.z() - s.z();
            if (dx * dx + dy * dy + dz * dz > move * move) return true;
        }
        return false;
    }

    // one search's view of a version: the mobs that count for it (not the ones it goes to on purpose) and the sight lines
    // it has cast. one thread only
    public static final class Pricer {
        public final Version version;
        final Source[] mobs;
        final Cells cells;
        // the ticks before the search's start node is reached (a search that plans ahead starts where the path being walked
        // ends), and the horizon in ticks
        final double offset;
        final double horizon;
        final int rayBudget;
        // per shooter, per cell: 1 in sight, 2 not
        final Long2ByteOpenHashMap[] sight;
        public int rays;

        public Pricer(Version version, Cells cells, double offset, double horizon, int rayBudget, Predicate<Source> skip) {
            this.version = version;
            this.mobs = version.sources().stream().filter(s -> s.row() != null && s.want() > 0 && !skip.test(s)).toArray(Source[]::new);
            this.cells = cells;
            this.offset = Math.max(0, offset);
            this.horizon = horizon;
            this.rayBudget = rayBudget;
            this.sight = new Long2ByteOpenHashMap[mobs.length];
        }

        public boolean isEmpty() {
            return mobs.length == 0;
        }

        public int size() {
            return mobs.length;
        }

        public int id(int i) {
            return mobs[i].id();
        }

        // the hp the mobs are expected to take from us while we spend `exposure` ticks with our feet in (x, y, z), arriving
        // `arrival` ticks after the search's start. perMob, when given, gets each mob's share added
        public double hp(int x, int y, int z, double arrival, double exposure, double[] perMob) {
            double total = 0;
            double cx = x + 0.5, cz = z + 0.5;
            double when = Math.min(arrival + offset, horizon);
            boolean fireRes = version.fireRes();
            for (int i = 0; i < mobs.length; i++) {
                Source s = mobs[i];
                MobThreat row = s.row();
                double hp = 0;
                if (row.melee() > 0 && (s.way() || row.flies())) {
                    double d = length(s.x() - cx, s.y() - y, s.z() - cz);
                    // (a mob that holds its place, a blaze, bites only where it already reaches, or a little past it in its
                    // bite: MobThreat.toReach)
                    double toReach = row.toReach(d) * 20;
                    if (toReach <= when) {
                        hp = row.melee() * s.scale() * exposure / 20;
                        // a creeper blasts once
                        if (row.kind().equals("creeper")) hp = Math.min(hp, row.hit() * s.scale());
                    }
                }
                if (row.shot() > 0) {
                    double ey = s.y() + row.eye();
                    double d = length(s.x() - cx, ey - (y + OUR_EYE), s.z() - cz);
                    if (d <= row.range() && (row.follow() <= 0 || d <= row.follow())) {
                        double share = row.aimShare(d);
                        double shots = fireRes && row.fireShot() ? 0 : row.shot() * s.scale() * share;
                        double burn = fireRes ? 0 : row.burn() * share;
                        // (a remembered mob by its range alone, no sight lines: its place is a guess, and its rays were
                        // most of the budget, smoke5: 14 searches at the cap)
                        if (shots + burn > 0 && (s.kept() || seen(i, s, ey, x, y, z))) {
                            hp += (shots + burn) * exposure / 20;
                        }
                    }
                }
                hp *= s.want();
                if (hp > 0) {
                    total += hp;
                    if (perMob != null) perMob[i] += hp;
                }
            }
            return total;
        }

        // the shooter's eye to ours with our feet in (x, y, z), once a cell. past the budget a cell counts as seen: a
        // search that ran out of rays prices the shots where it cannot tell
        private boolean seen(int i, Source s, double eyeY, int x, int y, int z) {
            Long2ByteOpenHashMap memo = sight[i];
            if (memo == null) {
                memo = sight[i] = new Long2ByteOpenHashMap();
            }
            long key = pack(x, y, z);
            byte known = memo.get(key);
            if (known != 0) return known == 1;
            if (rays >= rayBudget) return true;
            rays++;
            boolean clear = clear(cells, s.x(), eyeY, s.z(), x + 0.5, y + OUR_EYE, z + 0.5);
            memo.put(key, clear ? (byte) 1 : (byte) 2);
            return clear;
        }
    }

    static double length(double dx, double dy, double dz) {
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) y & 0xFFFL) << 26 | ((long) z & 0x3FFFFFFL);
    }

    // a straight line from a to b through the cells: false at the first cell on the way that stops a shot (Cells.blocksSight:
    // a block that spans its cell; not fire, which the walk cannot pass and a fireball can). the cell a starts in and the
    // cell b ends in are not looked at (the eye's own cell, and the cell we would stand in). fluids do not block, as in
    // StandModel's line
    static boolean clear(Cells cells, double ax, double ay, double az, double bx, double by, double bz) {
        int x = floor(ax), y = floor(ay), z = floor(az);
        int ex = floor(bx), ey = floor(by), ez = floor(bz);
        double dx = bx - ax, dy = by - ay, dz = bz - az;
        int sx = sign(dx), sy = sign(dy), sz = sign(dz);
        double deltaX = sx != 0 ? Math.abs(1 / dx) : Double.POSITIVE_INFINITY;
        double deltaY = sy != 0 ? Math.abs(1 / dy) : Double.POSITIVE_INFINITY;
        double deltaZ = sz != 0 ? Math.abs(1 / dz) : Double.POSITIVE_INFINITY;
        // how far along the line (0 at a, 1 at b) the next cell border on each axis is
        double maxX = sx > 0 ? (x + 1 - ax) / dx : sx < 0 ? (x - ax) / dx : Double.POSITIVE_INFINITY;
        double maxY = sy > 0 ? (y + 1 - ay) / dy : sy < 0 ? (y - ay) / dy : Double.POSITIVE_INFINITY;
        double maxZ = sz > 0 ? (z + 1 - az) / dz : sz < 0 ? (z - az) / dz : Double.POSITIVE_INFINITY;
        int steps = Math.abs(ex - x) + Math.abs(ey - y) + Math.abs(ez - z);
        for (int i = 0; i < steps; i++) {
            if (maxX < maxY && maxX < maxZ) {
                x += sx;
                maxX += deltaX;
            } else if (maxY < maxZ) {
                y += sy;
                maxY += deltaY;
            } else {
                z += sz;
                maxZ += deltaZ;
            }
            if (x == ex && y == ey && z == ez) return true;
            if (cells.blocksSight(x, y, z)) return false;
        }
        return true;
    }

    private static int sign(double v) {
        return v > 0 ? 1 : v < 0 ? -1 : 0;
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }
}
