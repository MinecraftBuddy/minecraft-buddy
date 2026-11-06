package adris.altoclef.util.helpers;

import baritone.pathing.movement.CliffAvoidance;
import baritone.pathing.movement.CliffAvoidance.Cell;
import baritone.pathing.movement.CliffAvoidance.Cells;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// the buddy's fork: one model of where to stand (v1, 6 Nov 2026). each situation had its own rule for the cell to stand on
// (BlazeCover, the pillbox's corner, SafeStand, RimRetreat, LavaShore, GhastCover, CreeperStep), each with its own radius and
// none knowing the others, so every death added an exception (a wither skeleton follows into the corner that hides from
// blazes). here every cell the buddy can walk to nearby gets one cost, in hp over the next HORIZON seconds:
//   harm  what the mobs about do to us standing there, by their rows (MobThreat): a shot needs a line from its eyes to ours
//         and lands less the further it flies; a melee mob needs a way to a cell from which its reach touches us
//   risk  a hit there that knocks us into lava, fire, the void or a fall that hurts (away from that mob, its push's reach)
//   hit   minus what we do to the target from there: in the sword's reach (3) and seen
//   way   the walk there: a cost a step, and the harm of each cell on the way
// the cheapest cell wins; a held cell gives way only to one clearly cheaper (SWITCH_ABS, SWITCH_REL), so the pick does not
// flip back and forth. SafeStand's worst case (an ordinary hit from any side) is a hard filter on the cell picked. pure: a
// cell world, the buddy and the mobs in, the cell and its costs out; StandPick brings the game in. hand set from the game's
// numbers, nothing learnt
public final class StandModel {
    // the costs are hp over this many seconds
    public static final double HORIZON = 5;
    // a step of the walk takes this long (sprinting is 5.6 blocks a second, walking 4.3, and a path has turns)
    static final double STEP_SECONDS = 0.25;
    // and costs this much besides the harm on the way: a long walk is a path that can fail, past mobs that move
    static final double STEP_COST = 0.1;
    // the cells walked out to: this far (flat) from the buddy, this many steps, this far up and down
    public static final int RADIUS = 6;
    public static final int MAX_STEPS = 12;
    static final int UP = 3;
    static final int DOWN = 3;
    // a hostile that is not after us (no aggressive flag, no line on us) counts this much: it may turn on us any moment
    static final double UNAWARE = 0.5;
    // a knock into lava or a fall that hurts is a death (20 hp); into lava under fire resistance a swim out; into fire 5 s
    // of burning and the fire's own hurt
    static final double DISASTER = 20;
    static final double DISASTER_FIRE_RES = 4;
    static final double BURN = 6;
    // what a cell in the sword's reach of the target is worth a second: about a blaze's own harm, so one blaze in reach is
    // fought and three that see the cell are hidden from
    static final double HIT_BACK = 1.5;
    // our reach (entityInteractionRange), and how far past it the worth fades to nothing (a step and a swing)
    static final double REACH = 3.0;
    static final double REACH_FADE = 1.0;
    // a held cell gives way only to one cheaper by this much, or by this share of its cost
    static final double SWITCH_ABS = 1.0;
    static final double SWITCH_REL = 0.2;
    // the cells tried against the hard filter, cheapest first
    static final int FILTER_TRIES = 40;
    // a flying shooter drifts: its eye is taken here and this far to each side, and a cell counts as seen by the share of the
    // three lines that are clear
    static final double DRIFT = 1.0;
    static final double EYE = 1.62;
    // a push is followed along three lines, straight away from the mob and this much to each side, in steps this long
    static final double PUSH_SPREAD = Math.toRadians(25);
    static final double PUSH_STEP = 0.25;
    static final double HALF_WIDTH = 0.3;
    // the walk a melee mob gets to us is looked for up to this many cells
    static final int MOB_NODES = 2000;
    // what a run takes as a place to stop: a cell where we still take more than this a second is none
    public static final double RUN_RATE = 0.75;

    private StandModel() {
    }

    // the drills' switch (tuning stand_model, 1 is on, default off): off, every caller keeps its own rule
    public static boolean on() {
        return Tuning.get("stand_model", 0) >= 1;
    }

    // a mob about: its kind (the entity type's path; "piglin_crossbow" for a piglin with a crossbow), its size (magma cubes),
    // its feet, and whether it is after us (aggressive, or a shooter with a line on us)
    public record Mob(String kind, int size, double x, double y, double z, boolean aware) {
    }

    // the one we are fighting: its feet and its box
    public record Target(double x, double y, double z, double width, double height) {
    }

    // the buddy: its feet, and fire resistance on (fire does nothing, lava is a swim)
    public record Buddy(double x, double y, double z, boolean fireRes) {
    }

    // a fire block at a cell (the cells world has no fire: it is open to walk through)
    public interface Fire {
        boolean at(int x, int y, int z);
    }

    public record View(Cells cells, Fire fire) {
        public static View of(Cells cells) {
            return new View(cells, (x, y, z) -> false);
        }
    }

    // what is asked: how far to look, how much hitting back counts (1 in a fight, less in a run), the most harm a second a
    // cell may have to be picked at all, cells to weigh besides the ones walked to (a corner, a pillbox), and cells left out
    public record Query(int radius, int maxSteps, double hitBack, double maxRate, List<int[]> extras, Set<Long> banned) {
        public static Query of() {
            return new Query(RADIUS, MAX_STEPS, 1, Double.POSITIVE_INFINITY, List.of(), Set.of());
        }

        public Query radius(int r) {
            return new Query(r, Math.max(maxSteps, 2 * r), hitBack, maxRate, extras, banned);
        }

        public Query hitBack(double w) {
            return new Query(radius, maxSteps, w, maxRate, extras, banned);
        }

        public Query maxRate(double r) {
            return new Query(radius, maxSteps, hitBack, r, extras, banned);
        }

        public Query extras(List<int[]> e) {
            return new Query(radius, maxSteps, hitBack, maxRate, e, banned);
        }

        public Query banned(Set<Long> b) {
            return new Query(radius, maxSteps, hitBack, maxRate, extras, b);
        }
    }

    // one cell's cost: steps to it, the harm a second there (rate) and the four terms, and the kind that adds most harm there
    public record Cost(int x, int y, int z, int steps, double rate, double harm, double risk, double hit, double way, String worst) {
        public double total() {
            return harm + risk - hit + way;
        }

        public boolean at(int[] c) {
            return c != null && c[0] == x && c[1] == y && c[2] == z;
        }

        public int[] cell() {
            return new int[]{x, y, z};
        }

        public String breakdown() {
            return String.format(Locale.ROOT, "cost %.1f: harm %.1f, risk %.1f, way %.1f, hit back %.1f; %.2f hp a second there%s; %d steps",
                    total(), harm, risk, way, hit, rate, worst == null ? "" : ", most from " + worst, steps);
        }
    }

    // the pick: the cell (null for none), the held cell's cost now (null with none held, or held no more), whether the pick
    // moved off the held cell, how many cells were weighed, why there is none, and what it took (cells read from the world,
    // lines of sight cast)
    public record Choice(Cost cell, Cost held, boolean changed, int looked, String why, int reads, int rays) {
    }

    // ---- the pick

    // every cell weighed, cheapest first, before the hard filter and the hold (for a look at why a cell won: the drills' logs,
    // a replay of a recorded picture)
    public static List<Cost> costs(View view, Buddy buddy, List<Mob> mobs, Target target, Query q) {
        return rank(view, buddy, mobs, target, q, null).costs;
    }

    private record Ranked(World w, List<Cost> costs, int looked) {
    }

    private static Ranked rank(View view, Buddy buddy, List<Mob> mobs, Target target, Query q, int[] held) {
        World w = new World(view, (int) Math.floor(buddy.x()), (int) Math.floor(buddy.y()), (int) Math.floor(buddy.z()));
        int[] start = feet(w, buddy);
        List<Foe> foes = foes(w, start, mobs, q);
        List<Node> nodes = walk(w, start, q);
        Long2IntOpenHashMap index = new Long2IntOpenHashMap();
        index.defaultReturnValue(-1);
        for (int i = 0; i < nodes.size(); i++) index.put(BlazeCover.key(nodes.get(i).x, nodes.get(i).y, nodes.get(i).z), i);
        // (a cell asked for besides the walk, the held one too: steps by the straight line, the harm on the way as the mean of
        // its ends)
        List<int[]> more = new ArrayList<>(q.extras());
        if (held != null) more.add(held);
        int walked = nodes.size();
        for (int[] e : more) {
            if (index.get(BlazeCover.key(e[0], e[1], e[2])) >= 0 || !w.standable(e[0], e[1], e[2])) continue;
            double flat = Math.hypot(e[0] - start[0], e[2] - start[2]);
            Node n = new Node(e[0], e[1], e[2], (int) Math.ceil(flat * 1.2) + Math.abs(e[1] - start[1]), -1);
            index.put(BlazeCover.key(n.x, n.y, n.z), nodes.size());
            nodes.add(n);
        }
        for (Node n : nodes) {
            n.each = new double[foes.size()];
            n.rate = rate(w, foes, n.x, n.y, n.z, buddy.fireRes(), n.each);
        }
        // (the walk's cells after the cell they are reached from; the others by the straight line)
        Node origin = nodes.get(0);
        for (int i = 1; i < nodes.size(); i++) {
            Node n = nodes.get(i);
            n.way = i < walked ? nodes.get(n.parent).way + n.rate * STEP_SECONDS + STEP_COST
                    : (origin.rate + n.rate) / 2 * n.steps * STEP_SECONDS + n.steps * STEP_COST;
        }
        List<Cost> costs = new ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
            Node n = nodes.get(i);
            if (q.banned().contains(BlazeCover.key(n.x, n.y, n.z))) continue;
            // (the start may be no cell to stand in: mid-jump, on an edge; it is weighed only when it is one)
            if (i == 0 && !w.standable(n.x, n.y, n.z)) continue;
            costs.add(cost(w, foes, n, target, q, buddy.fireRes()));
        }
        costs.sort(Comparator.comparingDouble(Cost::total).thenComparingInt(Cost::steps)
                .thenComparingInt(Cost::x).thenComparingInt(Cost::y).thenComparingInt(Cost::z));
        return new Ranked(w, costs, nodes.size());
    }

    // the cheapest cell for the buddy, the held one ({x, y, z}, or null) kept unless another is clearly cheaper
    public static Choice choose(View view, Buddy buddy, List<Mob> mobs, Target target, Query q, int[] held) {
        Ranked r = rank(view, buddy, mobs, target, q, held);
        World w = r.w();
        List<Cost> costs = new ArrayList<>(r.costs());
        Cost heldCost = null;
        if (held != null) {
            for (Cost c : costs) {
                if (c.at(held)) {
                    heldCost = c;
                    break;
                }
            }
            if (heldCost != null && (heldCost.rate() > q.maxRate() || !fits(w, held[0], held[1], held[2]))) heldCost = null;
        }
        costs.removeIf(c -> c.rate() > q.maxRate());
        Cost best = null;
        for (int i = 0; i < costs.size() && i < FILTER_TRIES; i++) {
            Cost c = costs.get(i);
            if (heldCost != null && c.at(held)) {
                best = c;
                break;
            }
            if (fits(w, c.x(), c.y(), c.z())) {
                best = c;
                break;
            }
        }
        if (best == null) {
            String why = costs.isEmpty() ? (r.costs().isEmpty() ? "no cell to stand in" : "every cell takes more than "
                    + String.format(Locale.ROOT, "%.2f", q.maxRate()) + " hp a second") : "no cell safe from a knock";
            return new Choice(null, heldCost, held != null, r.looked(), why, w.reads, w.rays);
        }
        if (heldCost != null) {
            double margin = Math.max(SWITCH_ABS, SWITCH_REL * Math.abs(heldCost.total()));
            if (best.total() > heldCost.total() - margin) return new Choice(heldCost, heldCost, false, r.looked(), null, w.reads, w.rays);
        }
        return new Choice(best, heldCost, held == null || !best.at(held), r.looked(), null, w.reads, w.rays);
    }

    // SafeStand's worst case and no fall of three right beside it (BlazeCover.fit): the hard filter on a cell picked
    static boolean fits(World w, int x, int y, int z) {
        return w.standable(x, y, z) && !LavaShore.dropBeside(w, x, y, z) && !SafeStand.unsafe(w, x, y, z);
    }

    // ---- the cells walked to

    static final class Node {
        final int x, y, z, parent;
        final int steps;
        // the harm a second there, each foe's share of it, and the walk's cost
        double rate, way;
        double[] each;

        Node(int x, int y, int z, int steps, int parent) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.steps = steps;
            this.parent = parent;
        }
    }

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    // the buddy's cell: the feet's block, or the first with a floor up to two below it (mid-jump, a slab)
    static int[] feet(World w, Buddy b) {
        int x = (int) Math.floor(b.x());
        int z = (int) Math.floor(b.z());
        int y = (int) Math.floor(b.y() + 0.1251);
        for (int d = 0; d <= 2; d++) {
            if (w.at(x, y - d - 1, z) == Cell.SOLID && w.at(x, y - d, z) != Cell.SOLID) return new int[]{x, y - d, z};
        }
        return new int[]{x, y, z};
    }

    // the cells a walk reaches from the start (a step to a side, up one or down two, as BlazeCover.steps), nearest first, each
    // with its step count and the cell it was reached from; the start first. a cell the walk passes is walkable (no lava by it,
    // no edge, no fire), as BlazeCover's walk to cover has it
    static List<Node> walk(World w, int[] s, Query q) {
        List<Node> out = new ArrayList<>();
        Long2IntOpenHashMap seen = new Long2IntOpenHashMap();
        seen.defaultReturnValue(-1);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        out.add(new Node(s[0], s[1], s[2], 0, -1));
        seen.put(BlazeCover.key(s[0], s[1], s[2]), 0);
        queue.add(0);
        double r2 = (double) q.radius() * q.radius();
        while (!queue.isEmpty()) {
            int ci = queue.poll();
            Node c = out.get(ci);
            if (c.steps >= q.maxSteps()) continue;
            boolean jump = w.at(c.x, c.y + 2, c.z) == Cell.OPEN;
            for (int[] side : SIDES) {
                int nx = c.x + side[0], nz = c.z + side[1];
                if ((nx - s[0]) * (nx - s[0]) + (nz - s[2]) * (nz - s[2]) > r2) continue;
                for (int dy = 1; dy >= -2; dy--) {
                    int ny = c.y + dy;
                    if (ny < s[1] - DOWN || ny > s[1] + UP) continue;
                    if (dy == 1 && !jump) continue;
                    boolean clear = true;
                    for (int k = ny + 2; k <= c.y + 1 && clear; k++) clear = w.at(nx, k, nz) == Cell.OPEN;
                    if (!clear || !w.walkable(nx, ny, nz)) continue;
                    long key = BlazeCover.key(nx, ny, nz);
                    if (seen.get(key) < 0) {
                        seen.put(key, out.size());
                        queue.add(out.size());
                        out.add(new Node(nx, ny, nz, c.steps + 1, ci));
                    }
                    // (one cell per column and side: the first height that fits is the step)
                    break;
                }
            }
        }
        return out;
    }

    // ---- the mobs

    static final class Foe {
        final MobThreat t;
        final double x, y, z, ex, ey, ez, weight;
        // the cells it can stand in on its walk to us, in steps (melee walkers within reach of the horizon), null for none
        Long2IntOpenHashMap steps;

        Foe(MobThreat t, Mob m, double weight) {
            this.t = t;
            this.x = m.x();
            this.y = m.y();
            this.z = m.z();
            this.ex = m.x();
            this.ey = m.y() + t.eye();
            this.ez = m.z();
            this.weight = weight;
        }
    }

    // the mobs with a row that can matter to a cell within the query's radius: in range for a shot, or a walk to us within
    // the horizon
    static List<Foe> foes(World w, int[] s, List<Mob> mobs, Query q) {
        List<Foe> out = new ArrayList<>();
        for (Mob m : mobs) {
            MobThreat t = MobThreat.of(m.kind(), m.size());
            if (t == null) continue;
            double weight = m.aware() ? 1 : t.neutral() ? 0 : UNAWARE;
            if (weight <= 0) continue;
            double d = Math.sqrt(sq(m.x() - s[0] - 0.5) + sq(m.y() - s[1]) + sq(m.z() - s[2] - 0.5));
            double meleeReach = t.melee() > 0 ? HORIZON * t.speed() + t.reach() : 0;
            double shotReach = t.shot() > 0 || t.burn() > 0 ? Math.min(t.range(), t.follow()) : 0;
            if (d > Math.max(meleeReach, shotReach) + q.radius() + 2) continue;
            Foe f = new Foe(t, m, weight);
            if (t.melee() > 0 && !t.flies()) f.steps = mobWalk(w, f, s, q);
            out.add(f);
        }
        return out;
    }

    // the cells a walking mob reaches from where it stands, in steps, within the steps the horizon gives it: the cells it fits
    // in (its height in open cells, its width over two columns when wider than one), a step to a side, up one or down three
    static Long2IntOpenHashMap mobWalk(World w, Foe f, int[] s, Query q) {
        Long2IntOpenHashMap steps = new Long2IntOpenHashMap();
        steps.defaultReturnValue(-1);
        int h = f.t.cellsHigh();
        boolean wide = f.t.width() > 1;
        int mx = (int) Math.floor(f.x);
        int mz = (int) Math.floor(f.z);
        int my = (int) Math.floor(f.y + 0.01);
        if (w.at(mx, my, mz) == Cell.SOLID) my++;
        int found = Integer.MIN_VALUE;
        for (int d = 0; d <= 3; d++) {
            if (w.at(mx, my - d - 1, mz) == Cell.SOLID) {
                found = my - d;
                break;
            }
        }
        if (found == Integer.MIN_VALUE) return null;
        my = found;
        int max = Math.min(24, (int) Math.ceil(HORIZON * f.t.speed()));
        double bound = sq(q.radius() + 10);
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        steps.put(BlazeCover.key(mx, my, mz), 0);
        queue.add(new int[]{mx, my, mz, 0});
        while (!queue.isEmpty() && steps.size() < MOB_NODES) {
            int[] c = queue.poll();
            if (c[3] >= max) continue;
            boolean jump = w.mobOpen(c[0], c[1] + h, c[2]) && (!wide || w.mobOpen(c[0] + 1, c[1] + h, c[2] + 1));
            for (int[] side : SIDES) {
                int nx = c[0] + side[0], nz = c[2] + side[1];
                if (sq(nx - s[0]) + sq(nz - s[2]) > bound) continue;
                for (int dy = 1; dy >= -3; dy--) {
                    int ny = c[1] + dy;
                    if (dy == 1 && !jump) continue;
                    boolean clear = true;
                    for (int k = ny + h; k <= c[1] + h - 1 && clear; k++) clear = w.mobOpen(nx, k, nz);
                    if (!clear || !fitsMob(w, nx, ny, nz, h, wide)) continue;
                    long key = BlazeCover.key(nx, ny, nz);
                    if (steps.get(key) < 0) {
                        steps.put(key, c[3] + 1);
                        queue.add(new int[]{nx, ny, nz, c[3] + 1});
                    }
                    break;
                }
            }
        }
        return steps;
    }

    // a cell a mob h cells high stands in: a floor, and open cells for its body (two columns each way for a wide one)
    static boolean fitsMob(World w, int x, int y, int z, int h, boolean wide) {
        if (w.at(x, y - 1, z) != Cell.SOLID) return false;
        for (int k = 0; k < h; k++) {
            if (!w.mobOpen(x, y + k, z)) return false;
            if (wide && (!w.mobOpen(x + 1, y + k, z) || !w.mobOpen(x, y + k, z + 1) || !w.mobOpen(x + 1, y + k, z + 1))) return false;
        }
        return true;
    }

    // ---- the cost of a cell

    // the harm a second at a cell from every foe (each foe's share into perFoe when given)
    static double rate(World w, List<Foe> foes, int x, int y, int z, boolean fireRes, double[] perFoe) {
        double sum = 0;
        for (int i = 0; i < foes.size(); i++) {
            double h = harm(w, foes.get(i), x, y, z, fireRes);
            if (perFoe != null) perFoe[i] = h;
            sum += h;
        }
        return sum;
    }

    // one foe's harm a second, on average over the horizon, at a cell
    static double harm(World w, Foe f, int x, int y, int z, boolean fireRes) {
        MobThreat t = f.t;
        double ex = x + 0.5, ey = y + EYE, ez = z + 0.5;
        double out = 0;
        if (t.shot() > 0 || t.burn() > 0) {
            double d = Math.sqrt(sq(f.ex - ex) + sq(f.ey - ey) + sq(f.ez - ez));
            if (d <= t.range() && d <= t.follow()) {
                double seen = seen(w, f, ex, ey, ez);
                if (seen > 0) {
                    double a = t.aimShare(d);
                    out += seen * (t.shot() * a + (fireRes ? 0 : t.burn() * Math.min(1, 3 * a)));
                }
            }
        }
        if (t.melee() > 0) {
            double arrive = arrive(w, f, x, y, z);
            if (arrive < HORIZON) out += t.melee() * (HORIZON - arrive) / HORIZON;
        }
        return out * f.weight;
    }

    // the share of the lines from a foe's eye (and, for a flyer, a drift to each side) to our eye at a cell that no block stops
    static double seen(World w, Foe f, double ex, double ey, double ez) {
        if (!f.t.flies()) return w.clear(f.ex, f.ey, f.ez, ex, ey, ez) ? 1 : 0;
        double dx = ex - f.ex, dz = ez - f.ez;
        double len = Math.hypot(dx, dz);
        double px = len < 1e-6 ? 1 : -dz / len, pz = len < 1e-6 ? 0 : dx / len;
        int n = 0;
        if (w.clear(f.ex, f.ey, f.ez, ex, ey, ez)) n++;
        if (w.clear(f.ex + px * DRIFT, f.ey, f.ez + pz * DRIFT, ex, ey, ez)) n++;
        if (w.clear(f.ex - px * DRIFT, f.ey, f.ez - pz * DRIFT, ex, ey, ez)) n++;
        return n / 3.0;
    }

    // how soon a melee foe can hit us at a cell, in seconds (infinite for never in the horizon): its walk to a cell from which
    // its reach touches our box, with the boxes overlapping in height and a line between the eyes. a flyer, or a walker with
    // no known walk, closes along the straight line
    static double arrive(World w, Foe f, int x, int y, int z) {
        MobThreat t = f.t;
        double ex = x + 0.5, ey = y + EYE, ez = z + 0.5;
        if (f.steps == null) {
            double gap;
            if (t.sphere()) {
                gap = Math.sqrt(sq(f.x - ex) + sq(f.y - y) + sq(f.z - ez)) - t.reach();
            } else {
                double dy = y - f.y;
                if (dy >= t.height() || dy <= -1.8) return Double.POSITIVE_INFINITY;
                gap = Math.max(Math.abs(f.x - ex), Math.abs(f.z - ez)) - t.reach();
            }
            if (!w.clear(f.ex, f.ey, f.ez, ex, ey, ez)) return Double.POSITIVE_INFINITY;
            return Math.max(0, gap) / Math.max(0.05, t.speed());
        }
        int k = t.reachCells();
        int dyLow = t.sphere() ? -k : -1;
        int dyHigh = t.sphere() ? k : (int) Math.ceil(t.height() - 1e-6) - 1;
        double best = Double.POSITIVE_INFINITY;
        for (int dy = dyLow; dy <= dyHigh; dy++) {
            int sy = y - dy;
            for (int sx = x - k; sx <= x + k; sx++) {
                for (int sz = z - k; sz <= z + k; sz++) {
                    int st = f.steps.get(BlazeCover.key(sx, sy, sz));
                    if (st < 0) continue;
                    if (t.sphere() && sq(sx - x) + sq(sy - y) + sq(sz - z) >= sq(t.reach())) continue;
                    double time = st / Math.max(0.05, t.speed());
                    if (time >= best) continue;
                    if (!w.clear(sx + 0.5, sy + t.eye(), sz + 0.5, ex, ey, ez)) continue;
                    best = time;
                }
            }
        }
        return best;
    }

    // the four terms of a node
    static Cost cost(World w, List<Foe> foes, Node n, Target target, Query q, boolean fireRes) {
        double rate = n.rate;
        double[] each = n.each;
        double walk = n.steps * STEP_SECONDS;
        double stay = Math.max(0, HORIZON - walk);
        double harm = rate * stay;
        double risk = 0;
        String worst = null;
        double worstHarm = 0;
        for (int i = 0; i < foes.size(); i++) {
            if (each[i] <= 0) continue;
            Foe f = foes.get(i);
            if (each[i] > worstHarm) {
                worstHarm = each[i];
                worst = f.t.kind();
            }
            double hits = Math.min(1, each[i] * HORIZON / Math.max(0.5, f.t.hit()));
            risk += hits * pushCost(w, f, n.x, n.y, n.z, fireRes);
        }
        double hit = 0;
        if (target != null && q.hitBack() > 0) {
            double ex = n.x + 0.5, ey = n.y + EYE, ez = n.z + 0.5;
            double hw = target.width() / 2;
            double px = clamp(ex, target.x() - hw, target.x() + hw);
            double py = clamp(ey, target.y(), target.y() + target.height());
            double pz = clamp(ez, target.z() - hw, target.z() + hw);
            double d = Math.sqrt(sq(ex - px) + sq(ey - py) + sq(ez - pz));
            if (d <= REACH + REACH_FADE && w.clear(ex, ey, ez, target.x(), target.y() + target.height() / 2, target.z())) {
                double share = d <= REACH ? 1 : 1 - (d - REACH) / REACH_FADE;
                hit = q.hitBack() * HIT_BACK * share * stay;
            }
        }
        return new Cost(n.x, n.y, n.z, n.steps, rate, harm, risk, hit, n.way, worst);
    }

    // what a hit of this foe at a cell costs by where it knocks us: the mean of its push lines (away from it, or any way
    // round), each the cost of what it runs into (DISASTER, BURN, nothing for a wall or firm ground)
    static double pushCost(World w, Foe f, int x, int y, int z, boolean fireRes) {
        double cx = x + 0.5, cz = z + 0.5;
        double dx = cx - f.x, dz = cz - f.z;
        double sum = 0;
        int lines;
        if (f.t.pushRound() || Math.hypot(dx, dz) < 0.05) {
            lines = 8;
            for (int i = 0; i < lines; i++) {
                double a = i * Math.PI / 4;
                sum += endCost(pushEnd(w, x, y, z, Math.cos(a), Math.sin(a), f.t.push()), fireRes);
            }
        } else {
            lines = 3;
            double base = Math.atan2(dz, dx);
            for (int i = -1; i <= 1; i++) {
                double a = base + i * PUSH_SPREAD;
                sum += endCost(pushEnd(w, x, y, z, Math.cos(a), Math.sin(a), f.t.push()), fireRes);
            }
        }
        return sum / lines;
    }

    static final int END_NONE = 0;
    static final int END_FIRE = 1;
    static final int END_LAVA = 2;
    static final int END_FALL = 3;

    static double endCost(int end, boolean fireRes) {
        return switch (end) {
            case END_FIRE -> fireRes ? 0 : BURN;
            case END_LAVA -> fireRes ? DISASTER_FIRE_RES : DISASTER;
            case END_FALL -> DISASTER;
            default -> 0;
        };
    }

    // a push from a cell's centre along (ux, uz), up to reach: what it runs into first. the box's front edge meets walls, lava
    // and fire; its back edge leaving the floor is the fall (a drop of HURTS or more, or onto lava). a drop of less is taken
    // as ground (v1: the push is not followed down)
    static int pushEnd(World w, int x, int y, int z, double ux, double uz, double reach) {
        double cx = x + 0.5, cz = z + 0.5;
        for (double s = PUSH_STEP; s <= reach + 1e-9; s += PUSH_STEP) {
            int fx = (int) Math.floor(cx + ux * (s + HALF_WIDTH));
            int fz = (int) Math.floor(cz + uz * (s + HALF_WIDTH));
            if (fx != x || fz != z) {
                int front = w.column(fx, y, fz);
                if (front == World.WALL) return END_NONE;
                if (front == World.LAVA) return END_LAVA;
                if (w.fire(fx, y, fz)) return END_FIRE;
            }
            int bx = (int) Math.floor(cx + ux * (s - HALF_WIDTH));
            int bz = (int) Math.floor(cz + uz * (s - HALF_WIDTH));
            if (bx != x || bz != z) {
                int back = w.column(bx, y, bz);
                if (back == World.FALL) return END_FALL;
                if (back == World.LAVA) return END_LAVA;
            }
        }
        return END_NONE;
    }

    static double sq(double v) {
        return v * v;
    }

    static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    // ---- the world, each cell read once an evaluation

    // the cells read through an array round the buddy (and a map past it), with the derived tests kept per cell: rays and walks
    // read the same cells many times
    static final class World implements Cells {
        private static final int R = 24;
        private static final int V = 12;
        private static final int SX = 2 * R + 1;
        private static final int SY = 2 * V + 1;
        private static final Cell[] CELLS = Cell.values();
        private final Cells cells;
        private final Fire fire;
        private final int ox, oy, oz;
        private final byte[] grid = new byte[SX * SY * SX];
        private final Long2ByteOpenHashMap far = new Long2ByteOpenHashMap();
        private final Long2ByteOpenHashMap walkable = new Long2ByteOpenHashMap();
        private final Long2ByteOpenHashMap columns = new Long2ByteOpenHashMap();
        private final Long2ByteOpenHashMap fires = new Long2ByteOpenHashMap();
        // cells read from the world and rays cast, for the cost of an evaluation
        int reads;
        int rays;

        World(View view, int ox, int oy, int oz) {
            this.cells = view.cells();
            this.fire = view.fire();
            this.ox = ox;
            this.oy = oy;
            this.oz = oz;
        }

        @Override
        public Cell at(int x, int y, int z) {
            int ix = x - ox + R, iy = y - oy + V, iz = z - oz + R;
            if (ix >= 0 && ix < SX && iy >= 0 && iy < SY && iz >= 0 && iz < SX) {
                int i = (ix * SY + iy) * SX + iz;
                byte b = grid[i];
                if (b == 0) {
                    reads++;
                    b = (byte) (cells.at(x, y, z).ordinal() + 1);
                    grid[i] = b;
                }
                return CELLS[b - 1];
            }
            long k = BlazeCover.key(x, y, z);
            byte b = far.get(k);
            if (b == 0) {
                reads++;
                b = (byte) (cells.at(x, y, z).ordinal() + 1);
                far.put(k, b);
            }
            return CELLS[b - 1];
        }

        boolean fire(int x, int y, int z) {
            long k = BlazeCover.key(x, y, z);
            byte b = fires.get(k);
            if (b == 0) {
                b = (byte) (fire.at(x, y, z) ? 2 : 1);
                fires.put(k, b);
            }
            return b == 2;
        }

        // open to a mob's body: air or water
        boolean mobOpen(int x, int y, int z) {
            Cell c = at(x, y, z);
            return c == Cell.OPEN || c == Cell.WATER;
        }

        // a cell to stand in: a floor, room for the body, no fire
        boolean standable(int x, int y, int z) {
            return at(x, y - 1, z) == Cell.SOLID && at(x, y, z) == Cell.OPEN && at(x, y + 1, z) == Cell.OPEN && !fire(x, y, z);
        }

        // BlazeCover.walkable, kept per cell (and no fire): a floor, room, no lava within a block (LavaShore.lavaNear) and no
        // edge (LavaShore.edgeNear: its samples within 1.5 of the centre are the columns round the cell)
        boolean walkable(int x, int y, int z) {
            long k = BlazeCover.key(x, y, z);
            byte b = walkable.get(k);
            if (b == 0) {
                boolean ok = standable(x, y, z);
                for (int dx = -1; dx <= 1 && ok; dx++) {
                    for (int dz = -1; dz <= 1 && ok; dz++) {
                        Cell feet = at(x + dx, y, z + dz);
                        Cell under = at(x + dx, y - 1, z + dz);
                        if (feet == Cell.LAVA || under == Cell.LAVA) ok = false;
                        else if (feet != Cell.SOLID && at(x + dx, y + 1, z + dz) != Cell.SOLID
                                && CliffAvoidance.columnUnsafe(this, x + dx, y, z + dz)) ok = false;
                    }
                }
                b = (byte) (ok ? 2 : 1);
                walkable.put(k, b);
            }
            return b == 2;
        }

        static final int GROUND = 1;
        static final int WALL = 2;
        static final int LAVA = 3;
        static final int FALL = 4;

        // what a column is to a body with its feet at y (SafeStand.hazard's reading): a wall (a block at the feet or head), lava
        // (at the feet or head, or under a drop), a fall (HURTS or more open below), or ground
        int column(int x, int y, int z) {
            long k = BlazeCover.key(x, y, z);
            byte b = columns.get(k);
            if (b == 0) {
                b = (byte) readColumn(x, y, z);
                columns.put(k, b);
            }
            return b;
        }

        private int readColumn(int x, int y, int z) {
            Cell body = at(x, y, z);
            Cell head = at(x, y + 1, z);
            if (body == Cell.SOLID || head == Cell.SOLID) return WALL;
            if (body == Cell.LAVA || head == Cell.LAVA) return LAVA;
            if (body == Cell.WATER) return GROUND;
            for (int d = 1; d <= SafeStand.HURTS; d++) {
                Cell c = at(x, y - d, z);
                if (c == Cell.LAVA) return LAVA;
                if (c != Cell.OPEN) return GROUND;
            }
            return FALL;
        }

        // no block between two points: the cells the line passes, the first left out (the eye's own), fluids no block
        // (ClipContext.Fluid.NONE, the game's line of sight)
        boolean clear(double ax, double ay, double az, double bx, double by, double bz) {
            rays++;
            int x = (int) Math.floor(ax), y = (int) Math.floor(ay), z = (int) Math.floor(az);
            int tx = (int) Math.floor(bx), ty = (int) Math.floor(by), tz = (int) Math.floor(bz);
            double dx = bx - ax, dy = by - ay, dz = bz - az;
            int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
            double nx = dx == 0 ? Double.POSITIVE_INFINITY : ((dx > 0 ? x + 1 - ax : ax - x) / Math.abs(dx));
            double ny = dy == 0 ? Double.POSITIVE_INFINITY : ((dy > 0 ? y + 1 - ay : ay - y) / Math.abs(dy));
            double nz = dz == 0 ? Double.POSITIVE_INFINITY : ((dz > 0 ? z + 1 - az : az - z) / Math.abs(dz));
            double ix = dx == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(dx);
            double iy = dy == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(dy);
            double iz = dz == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(dz);
            int n = Math.abs(tx - x) + Math.abs(ty - y) + Math.abs(tz - z);
            for (int i = 0; i < n; i++) {
                if (nx <= ny && nx <= nz) {
                    x += sx;
                    nx += ix;
                } else if (ny <= nz) {
                    y += sy;
                    ny += iy;
                } else {
                    z += sz;
                    nz += iz;
                }
                if (at(x, y, z) == Cell.SOLID) return false;
            }
            return true;
        }
    }
}
