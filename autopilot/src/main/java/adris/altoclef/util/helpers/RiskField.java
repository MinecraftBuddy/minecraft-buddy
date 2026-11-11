package adris.altoclef.util.helpers;

import baritone.pathing.movement.CliffAvoidance.Cells;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;

import java.util.HashMap;
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
//     depends on where we went, and the next version says. a blaze's bite only within its reach: it does not chase
//   shots: its hp a second at that distance (the aimed share), when the cell is in its range and in sight of its eye: no
//     block that spans its cell on the line (Cells.blocksSight; fire and a fence are no cover)
// both times its want (DangerMeter.wantsToHurt) and its armour scale for us (DangerMeter.scale)
public final class RiskField {

    // a mob that moved less than this since the last version keeps that version
    public static final double MOVE = 1;
    // where our eye is above the feet, for a shooter's line
    static final double OUR_EYE = 1.62;

    // a mob as the field prices it: its entity id, its row, its feet, how much it wants us (0.5 or 1), the ratio its
    // iron-and-Normal numbers take for our armour and the difficulty, and whether it has a way to us on foot
    public record Source(int id, MobThreat row, double x, double y, double z, double want, double scale, boolean way) {}

    // one version of the field: its number, the game tick it was built at, the world it is of (a search in another one
    // sees no mobs), whether we had fire resistance then, and the mobs
    public record Version(long id, long tick, Object world, boolean fireRes, List<Source> sources) {
        public Source source(int id) {
            for (Source s : sources) {
                if (s.id() == id) return s;
            }
            return null;
        }
    }

    public static final Version NONE = new Version(0, 0, null, false, List.of());

    private static volatile Version current = NONE;
    private static long ids;

    private RiskField() {}

    public static Version current() {
        return current;
    }

    // the game thread's: the mobs that want us now. true when that made a new version
    public static synchronized boolean offer(long tick, Object world, boolean fireRes, List<Source> sources) {
        Version old = current;
        if (old.world() == world && old.fireRes() == fireRes && !differs(old.sources(), sources, MOVE)) {
            return false;
        }
        current = new Version(++ids, tick, world, fireRes, List.copyOf(sources));
        return true;
    }

    // no field: out of the dimensions the meter reads, dead, or between worlds
    public static synchronized void clear(long tick) {
        if (current.world() != null || !current.sources().isEmpty()) {
            current = new Version(++ids, tick, null, false, List.of());
        }
    }

    // a mob came or went, or one changed its want, its way or its scale, or moved more than a block
    static boolean differs(List<Source> was, List<Source> now, double move) {
        if (was.size() != now.size()) return true;
        Map<Integer, Source> byId = new HashMap<>();
        for (Source s : was) byId.put(s.id(), s);
        for (Source s : now) {
            Source o = byId.get(s.id());
            if (o == null || o.row() != s.row() || o.want() != s.want() || o.way() != s.way()
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
                    // (a mob that holds its place, a blaze, bites only where it already reaches: MobThreat.holds)
                    double toReach = row.holds() ? (d <= row.reach() ? 0 : Double.POSITIVE_INFINITY)
                            : Math.max(0, d - row.reach()) / Math.max(0.05, row.speed()) * 20;
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
                        if (shots + burn > 0 && seen(i, s, ey, x, y, z)) {
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
