package adris.altoclef.util.helpers;

import baritone.pathing.movement.CliffAvoidance.Cell;
import baritone.pathing.movement.CliffAvoidance.Cells;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.world.phys.Vec3;

// the buddy's fork: the pillbox, a corner built where a fortress has none (steering 21, 4 Nov 2026: the pillbox, with the
// volley, works better in real nether fortresses). in a real fortress BlazeCover.corner finds no
// cell by the spawner that the spawn area cannot see, and the fight fell back to the open stance (0 of 8 in the drill room).
// so: a cell CELL_MIN to CELL_MAX from the spawner, walked to from it, with a side left open as the look out and the other
// three walled two high and a roof over it, the fewest blocks that hide it from every spawn eye while the look out stays in
// sight of one. this is the plan only (the world as cells, tested without one); BlazePillboxTask builds it
public final class BlazePillbox {
    static final double CELL_MIN = 4;
    static final double CELL_MAX = 9;
    // the walk out from the spawner for candidates, in steps
    static final int WALK = 14;
    // a plan with more than this to place is no plan (seven is three walls of two and a roof)
    static final int MOST = 8;

    private BlazePillbox() {
    }

    // where to stand, the look out beside it, and the blocks to place there in order (low walls, high walls, the roof). front
    // is the bent box's front cell, where the roof row is placed from (null for the one-cell box: all from the cell)
    // stands, for a raised box only, is where each block is placed from (null: BlazePillboxTask's own rule); raise is how far its
    // cells stand over the ground they were planned on
    public record Plan(int[] cell, int[] lookout, List<int[]> blocks, int[] front, List<int[]> stands, int raise) {
        public Plan(int[] cell, int[] lookout, List<int[]> blocks) {
            this(cell, lookout, blocks, null, null, 0);
        }

        public Plan(int[] cell, int[] lookout, List<int[]> blocks, int[] front) {
            this(cell, lookout, blocks, front, null, 0);
        }
    }

    static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    // the cheapest pillbox, or null with none: start is a walkable cell by the spawner (as the corner camp's), eyes the
    // spawn area's (BlazeCover.spawnEyes)
    public static Plan plan(Cells cells, int[] start, int sx, int sy, int sz, List<Vec3> eyes) {
        Plan best = null;
        double bestScore = Double.MAX_VALUE;
        java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        queue.add(new int[]{start[0], start[1], start[2], 0});
        seen.add(BlazeCover.key(start[0], start[1], start[2]));
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            int x = c[0], y = c[1], z = c[2];
            double flat = Math.hypot(x + 0.5 - sx - 0.5, z + 0.5 - sz - 0.5);
            if (flat >= CELL_MIN && flat <= CELL_MAX && BlazeCover.walkable(cells, x, y, z)) {
                for (int[] open : SIDES) {
                    Plan p = planAt(cells, x, y, z, open, eyes, sx, sz);
                    if (p == null) continue;
                    // (fewer blocks first: each goes in under fire; then the nearer to the spawner, the blazes come sooner)
                    double score = p.blocks().size() * 10 + flat;
                    if (score < bestScore) {
                        bestScore = score;
                        best = p;
                    }
                }
            }
            if (c[3] >= WALK) continue;
            for (int[] n : BlazeCover.steps(cells, x, y, z, start[1])) {
                if (Math.hypot(n[0] + 0.5 - sx - 0.5, n[2] + 0.5 - sz - 0.5) > CELL_MAX + 2 || !seen.add(BlazeCover.key(n[0], n[1], n[2]))) continue;
                queue.add(new int[]{n[0], n[1], n[2], c[3] + 1});
            }
        }
        return best;
    }

    // the bent pillbox (steering 25 and 29, 4 Nov 2026: a bigger pillbox, with a corner inside to hide in): a one-cell box on
    // an open platform was no cover, a blaze in front of its opening or above it
    // saw the whole cell (merged-e, 4 of 4 dead in it). so two cells: the back one B, the front one F beside it, the opening on
    // F's side (the look out O), walls two high round both and a roof over both, so B is round a corner from the opening. B must
    // be hidden from a ring of eyes all round (ringHidden: where blazes drift, not only the spawn area), O seen by a spawn eye.
    // with a wing (a wall two high beyond the opening, in line with F's far wall) when B is not hidden without it. the cheapest
    // that passes, or null
    static final int MOST_BENT = 16;

    // (steering 30, 4 Nov 2026, realfort-a-bent-a: the blazes sat high on their platform, and a box farther away would draw
    // them better. every box of that block stood five out from a
    // spawner three up, where the platform's edge hides the look out from the blazes on it, and a blaze that sees no target
    // stays where it spawned: half the block waited. so the look out must be seen by SEEN_MIN of the spawn eyes, a box
    // farther out scores better, and the reach grows with the spawner's height over the box's floor, LIFT a block of it,
    // up to FAR: the spawner spawns only with us within 16)
    static final double SEEN_MIN = 0.5;
    static final double LIFT = 2;
    static final double FAR = 13;
    static final double LURE = 9;
    // (what the look out sees over the build out of sight: the build-out-of-sight term alone put every box of realfort-a-bent-f
    // at the platform's foot again, six out, entry 30's fault)
    static final double SEEN_WEIGHT = 60;
    static final double BUILD_WEIGHT = 30;

    static double reach(int sy, int y) {
        return Math.min(FAR, CELL_MAX + LIFT * Math.max(0, sy - y - 1));
    }

    public static Plan planBent(Cells cells, int[] start, int sx, int sy, int sz, List<Vec3> eyes) {
        return planBent(cells, start, sx, sy, sz, eyes, 0);
    }

    // raise above 0: only the raised box (planRaisedAt), null when none fits
    public static Plan planBent(Cells cells, int[] start, int sx, int sy, int sz, List<Vec3> eyes, int raise) {
        return planBent(cells, start, sx, sy, sz, eyes, raise, eyes);
    }

    // lookEyes for a raised box's look out: what it must see and the share its score weighs, the finer grid (fineEyes). of the
    // nine only four were free in set b's fortresses and a look out two up was seen by none of them in 96 of its sites, while
    // the grid of 25 showed it seeing half the spawn area (realfort-b-raised2, "O unseen"). its floor RAISED_SEEN_MIN, as no
    // place sees much more than half of the grid
    static final double RAISED_SEEN_MIN = 0.4;

    public static Plan planBent(Cells cells, int[] start, int sx, int sy, int sz, List<Vec3> eyes, int raise, List<Vec3> lookEyes) {
        if (raise > 0) java.util.Arrays.fill(why, 0);
        List<Plan> found = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        int walk = WALK + (int) Math.ceil(FAR - CELL_MAX) * 2;
        for (int[] c : candidates(cells, start, sx, sz, FAR, walk)) {
            double flat = Math.hypot(c[0] + 0.5 - sx - 0.5, c[2] + 0.5 - sz - 0.5);
            if (flat > reach(sy, c[1])) continue;
            for (int[] a : SIDES) {
                for (int[] p : SIDES) {
                    if (a[0] * p[0] + a[1] * p[1] != 0) continue;
                    // (always with the wing: without it a line from B through the opening's far side is open, and the
                    // blazes on the raised middle hit the buddy in B through it, bent-b run 4, the first box built whole.
                    // the ring's eyes missed it, one in the spawner's block, one behind it)
                    for (boolean wing : new boolean[]{true}) {
                        Plan plan = raise > 0 ? planRaisedAt(cells, c[0], c[1], c[2], a, p, raise, eyes, lookEyes, sx, sz)
                                : planBentAt(cells, c[0], c[1], c[2], a, p, wing, eyes, sx, sz);
                        if (plan == null) continue;
                        double seen = seenShare(cells, plan, raise > 0 ? lookEyes : eyes);
                        if (seen < (raise > 0 ? RAISED_SEEN_MIN : SEEN_MIN)) {
                            if (raise > 0) why[8]++;
                            continue;
                        }
                        found.add(plan);
                        // (a block is 10, the look out seen by every eye against half of them 20, a block farther out 3 up to
                        // LURE: beyond it the walk to the rods and the open ground round the box only grow. and the box built
                        // out of the blazes' sight, steering 31: the share of the spawn eyes that see where it is built from,
                        // B or F as the world is now, 60 at all of them: best is a pillbox that stands before the fight
                        // begins)
                        scores.add(plan.blocks().size() * 10 + (1 - seen) * SEEN_WEIGHT - Math.min(flat, LURE) * 3 + buildSeen(cells, plan, eyes) * BUILD_WEIGHT);
                    }
                }
            }
        }
        // (the ring's test is the dear one: only the cheapest first, till one passes)
        Integer[] order = new Integer[found.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, java.util.Comparator.comparingDouble(scores::get));
        int tried = 0;
        for (int i : order) {
            Plan plan = found.get(i);
            if (ringHidden(after(cells, plan.blocks()), plan.cell()[0], plan.cell()[1], plan.cell()[2])) return plan;
            if (raise > 0) why[9]++;
            if (++tried >= RING_TRIES) break;
        }
        return null;
    }

    static final int RING_TRIES = 60;

    // ---- the far box (the buddy's fork, Tuning far_box, off by default, steering 89): the bent box planned outside the
    // spawner's range, so nothing spawns while the buddy is in it or builds it. the spawner spawns only with a player's feet
    // within SPAWN_RANGE of its middle, so the blazes come by spawn trips (FarTrip): a walk into range to the trip's cell
    // until one has spawned and seen the buddy, then back into the box, where the lure (BlazeLureTask) draws them to the
    // opening as from the near box. planBent and the near box's numbers are untouched: with the switch off nothing here runs

    // BaseSpawner's requiredPlayerRange, measured from the spawner's middle to the player's feet
    public static final double SPAWN_RANGE = 16;

    // min and max: how far B, F and the look out stand from the spawner (all three at min or more, B at max or less); seenMin:
    // the share of the spawn eyes that must see the look out once the box stands (a line to where the blazes fly); depth:
    // the trip's cell within this of the spawner; tripWalk: the trip's cell within this many steps of the look out
    public record FarRule(double min, double max, double seenMin, double depth, int tripWalk) {
        public static final FarRule DEFAULT = new FarRule(17, 30, 0.3, 14, 32);
    }

    // the block under a cell's floor is the fortress's own (nether bricks), not netherrack or a block the buddy placed. given
    // the cell's own coordinates; the block asked about is (x, y - 1, z)
    public interface Floor {
        boolean fortress(int x, int y, int z);
    }

    // what the world says of a cell that Cells calls SOLID. Cells is Baritone's walk rule, under which a fire, a cobweb or a
    // slab is no way through and reads SOLID as a wall does (the first far box live, 7 Nov 2026: a wall cell the plan stood
    // on was no block, so the high block over it had no face to go against and the box was left two blocks open). wall: a
    // block the blazes' fire does not pass (any collision); face: a whole block, whose top a click puts a block on
    public interface Sound {
        boolean wall(int x, int y, int z);

        boolean face(int x, int y, int z);
    }

    // the plan as the world has it, not only as Cells has it: each cell of its walls and roof that it places nothing in is a
    // wall (sound.wall); each block it places in a wall stands on a whole block (sound.face: the floor under a low one, the
    // wall's low cell under a high one it does not place), and so does the block on top of B's back wall that B's roof goes
    // against when the plan does not place it. sound null: Cells alone (true)
    static boolean sound(Plan plan, Sound sound) {
        if (sound == null) return true;
        int[] b = plan.cell(), f = plan.front(), o = plan.lookout();
        int y = b[1];
        int ax = f[0] - b[0], az = f[2] - b[2], px = o[0] - f[0], pz = o[2] - f[2];
        Set<Long> placed = new HashSet<>();
        for (int[] k : plan.blocks()) placed.add(BlazeCover.key(k[0], k[1], k[2]));
        int[][] walls = {{b[0] - ax, b[2] - az}, {b[0] + px, b[2] + pz}, {b[0] - px, b[2] - pz}, {f[0] + ax, f[2] + az}, {f[0] - px, f[2] - pz},
                {o[0] + ax, o[2] + az}};
        for (int[] w : walls) {
            boolean low = placed.contains(BlazeCover.key(w[0], y, w[1])), high = placed.contains(BlazeCover.key(w[0], y + 1, w[1]));
            if (low ? !sound.face(w[0], y - 1, w[1]) : !sound.wall(w[0], y, w[1])) return false;
            if (high ? !low && !sound.face(w[0], y, w[1]) : !sound.wall(w[0], y + 1, w[1])) return false;
        }
        for (int[] r : new int[][]{{b[0], b[2]}, {f[0], f[2]}}) {
            if (!placed.contains(BlazeCover.key(r[0], y + 2, r[1])) && !sound.wall(r[0], y + 2, r[1])) return false;
        }
        int bx = b[0] - ax, bz = b[2] - az;
        return !placed.contains(BlazeCover.key(b[0], y + 2, b[2])) || placed.contains(BlazeCover.key(bx, y + 2, bz)) || sound.face(bx, y + 2, bz);
    }

    // the far plan: the box, B's distance from the spawner, the trip's cell {x, y, z} and the walk's steps to it from the look out
    public record Far(Plan plan, double distance, int[] trip, int tripSteps) {
    }

    // from the feet in cell (x, y, z) to the spawner's middle, as the spawner measures it
    public static double spawnerDistance(int x, int y, int z, int sx, int sy, int sz) {
        double dx = x - sx, dy = y - (sy + 0.5), dz = z - sz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // a block farther out than min costs this much: every block of it is walked twice on each spawn trip
    static final double FAR_WEIGHT = 3;

    // why the far sites of the last planFar were turned down, for the log
    static final String[] FAR_WHY = {"out of range", "floor", "look out unseen", "box", "look out's share", "ring", "no trip", "not sound"};
    static final int[] farWhy = new int[FAR_WHY.length];

    public static String whyNotFar() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < FAR_WHY.length; i++) if (farWhy[i] > 0) sb.append(sb.length() == 0 ? "" : ", ").append(FAR_WHY[i]).append(' ').append(farWhy[i]);
        return sb.length() == 0 ? "no site" : sb.toString();
    }

    // the cheapest bent box (planBentAt, always with its wing) whose three cells stand rule.min to rule.max from the spawner on
    // the fortress's floor (floor null: any floor), its look out seen by rule.seenMin of the eyes once it stands, its back cell
    // hidden from the ring (ringHidden), and a trip's cell within reach of its look out (tripCell); null with none. start and
    // eyes as for planBent
    public static Far planFar(Cells cells, int[] start, int sx, int sy, int sz, List<Vec3> eyes, FarRule rule, Floor floor) {
        return planFar(cells, start, sx, sy, sz, eyes, rule, floor, null);
    }

    // sound: the plan checked against the world's blocks (sound), null for Cells alone
    public static Far planFar(Cells cells, int[] start, int sx, int sy, int sz, List<Vec3> eyes, FarRule rule, Floor floor, Sound sound) {
        java.util.Arrays.fill(farWhy, 0);
        List<Plan> found = new ArrayList<>();
        List<Double> scores = new ArrayList<>(), dists = new ArrayList<>();
        // (the look out's sight before the box, once per cell: the box's blocks only take lines away, so a cell no eye sees now
        // is no look out, and the far ring is four times the near one's cells)
        java.util.Map<Long, Double> sight = new java.util.HashMap<>();
        for (int[] c : candidates(cells, start, sx, sz, rule.max(), WALK + (int) Math.ceil(rule.max()))) {
            int x = c[0], y = c[1], z = c[2];
            double d = spawnerDistance(x, y, z, sx, sy, sz);
            if (d < rule.min() || d > rule.max()) {
                farWhy[0]++;
                continue;
            }
            if (floor != null && !floor.fortress(x, y, z)) {
                farWhy[1]++;
                continue;
            }
            for (int[] a : SIDES) {
                for (int[] p : SIDES) {
                    if (a[0] * p[0] + a[1] * p[1] != 0) continue;
                    int fx = x + a[0], fz = z + a[1], ox = fx + p[0], oz = fz + p[1];
                    if (spawnerDistance(fx, y, fz, sx, sy, sz) < rule.min() || spawnerDistance(ox, y, oz, sx, sy, sz) < rule.min()) {
                        farWhy[0]++;
                        continue;
                    }
                    if (floor != null && (!floor.fortress(fx, y, fz) || !floor.fortress(ox, y, oz))) {
                        farWhy[1]++;
                        continue;
                    }
                    double before = sight.computeIfAbsent(BlazeCover.key(ox, y, oz), k -> seenAt(cells, eyes, ox, y, oz));
                    if (before < rule.seenMin()) {
                        farWhy[2]++;
                        continue;
                    }
                    Plan plan = planBentAt(cells, x, y, z, a, p, true, eyes, sx, sz);
                    if (plan == null) {
                        farWhy[3]++;
                        continue;
                    }
                    if (!sound(plan, sound)) {
                        farWhy[7]++;
                        continue;
                    }
                    double seen = seenShare(cells, plan, eyes);
                    if (seen < rule.seenMin()) {
                        farWhy[4]++;
                        continue;
                    }
                    found.add(plan);
                    dists.add(d);
                    // (as planBent's score, but nearer is better here: the trips are the open ground)
                    scores.add(plan.blocks().size() * 10 + (1 - seen) * SEEN_WEIGHT + buildSeen(cells, plan, eyes) * BUILD_WEIGHT
                            + (d - rule.min()) * FAR_WEIGHT);
                }
            }
        }
        Integer[] order = new Integer[found.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, java.util.Comparator.comparingDouble(scores::get));
        int tried = 0;
        for (int i : order) {
            if (tried++ >= RING_TRIES) break;
            Plan plan = found.get(i);
            Cells after = after(cells, plan.blocks());
            if (!ringHidden(after, plan.cell()[0], plan.cell()[1], plan.cell()[2])) {
                farWhy[5]++;
                continue;
            }
            int[] trip = tripCell(after, plan.lookout(), sx, sy, sz, eyes, rule);
            if (trip == null) {
                farWhy[6]++;
                continue;
            }
            return new Far(plan, dists.get(i), new int[]{trip[0], trip[1], trip[2]}, trip[3]);
        }
        return null;
    }

    // the spawn trip's cell: walked out from the look out on the cells with the box standing, the first cell within rule.depth
    // of the spawner that a spawn eye sees (the blazes must see the buddy there to come after it), else the first within
    // rule.depth; {x, y, z, steps}, null with none within rule.tripWalk steps
    static int[] tripCell(Cells after, int[] from, int sx, int sy, int sz, List<Vec3> eyes, FarRule rule) {
        int[] first = null;
        java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        queue.add(new int[]{from[0], from[1], from[2], 0});
        seen.add(BlazeCover.key(from[0], from[1], from[2]));
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            if (spawnerDistance(c[0], c[1], c[2], sx, sy, sz) <= rule.depth()) {
                if (seenAt(after, eyes, c[0], c[1], c[2]) > 0) return c;
                if (first == null) first = c;
            }
            if (c[3] >= rule.tripWalk()) continue;
            for (int[] n : BlazeCover.steps(after, c[0], c[1], c[2], from[1])) {
                if (!seen.add(BlazeCover.key(n[0], n[1], n[2]))) continue;
                queue.add(new int[]{n[0], n[1], n[2], c[3] + 1});
            }
        }
        return first;
    }

    // what a plan scores, for the log: the look out's sight, the build's, how far and how far down from the spawner
    public static String describe(Cells cells, Plan plan, List<Vec3> eyes, int sx, int sy, int sz) {
        int[] c = plan.cell();
        return String.format(java.util.Locale.ROOT, "look out seen by %d%% of the spawn eyes, the build by %d%%, %.1f out, %d down",
                Math.round(seenShare(cells, plan, eyes) * 100), Math.round(buildSeen(cells, plan, eyes) * 100),
                Math.hypot(c[0] + 0.5 - sx - 0.5, c[2] + 0.5 - sz - 0.5), sy - c[1]) + (plan.raise() > 0 ? ", raised " + plan.raise() : "");
    }

    // the spawn area as a grid two apart, four out each way, at the spawn height's eyes: where blazes spawn (SpawnRange 4)
    public static List<Vec3> fineEyes(int sx, int sy, int sz) {
        List<Vec3> out = new ArrayList<>();
        for (int dx = -4; dx <= 4; dx += 2) {
            for (int dz = -4; dz <= 4; dz += 2) out.add(new Vec3(sx + 0.5 + dx, sy + 1.5, sz + 0.5 + dz));
        }
        return out;
    }

    // the raised box's measure, for the log only (5 Nov 2026: a look out at the platform's foot saw about a quarter of the
    // spawn area in the fortresses of set a, bent-r): the share of the eyes that would see a body at the plan's look out
    // standing h higher, h 0 to RAISE_MAX ("-" where the two cells over the raised floor are not open), then the best any
    // walkable cell within FAR would see raised the same, with how far out and down it is
    static final int RAISE_MAX = 3;

    public static String raisedSight(Cells cells, Plan plan, int[] start, List<Vec3> eyes, int sx, int sy, int sz) {
        Cells after = after(cells, plan.blocks());
        int[] o = plan.lookout();
        StringBuilder sb = new StringBuilder("look out raised 0/1/2/3 seen by ");
        for (int h = 0; h <= RAISE_MAX; h++) {
            if (h > 0) sb.append('/');
            sb.append(raisedOpen(after, o[0], o[1], o[2], h) ? Long.toString(Math.round(seenAt(after, eyes, o[0], o[1] + h, o[2]) * 100)) : "-");
        }
        sb.append("%; the best cell within ").append((int) FAR).append(" raised 0/1/2/3: ");
        List<int[]> cands = candidates(cells, start, sx, sz, FAR, WALK + (int) Math.ceil(FAR - CELL_MAX) * 2);
        for (int h = 0; h <= RAISE_MAX; h++) {
            double best = -1;
            int[] at = null;
            for (int[] c : cands) {
                if (!raisedOpen(cells, c[0], c[1], c[2], h)) continue;
                double s = seenAt(cells, eyes, c[0], c[1] + h, c[2]);
                if (s > best) {
                    best = s;
                    at = c;
                }
            }
            if (h > 0) sb.append(", ");
            if (at == null) sb.append('-');
            else sb.append(String.format(java.util.Locale.ROOT, "%d%% (%.1f out, %d down)", Math.round(best * 100),
                    Math.hypot(at[0] + 0.5 - sx - 0.5, at[2] + 0.5 - sz - 0.5), sy - at[1]));
        }
        return sb.toString();
    }

    // the two cells a body stands in at (x, y + h, z) are open
    static boolean raisedOpen(Cells cells, int x, int y, int z, int h) {
        return cells.at(x, y + h, z) == Cell.OPEN && cells.at(x, y + h + 1, z) == Cell.OPEN;
    }

    // the share of the eyes (those in an open cell) that see a body standing in (x, y, z)
    static double seenAt(Cells cells, List<Vec3> eyes, int x, int y, int z) {
        int all = 0, seen = 0;
        for (Vec3 e : eyes) {
            if (cells.at((int) Math.floor(e.x), (int) Math.floor(e.y), (int) Math.floor(e.z)) != Cell.OPEN) continue;
            all++;
            if (!hidden(cells, List.of(e), x, y, z)) seen++;
        }
        return all == 0 ? 0 : (double) seen / all;
    }

    // the share of the spawn eyes (those in an open cell once the box stands) that see a body in the look out
    static double seenShare(Cells cells, Plan plan, List<Vec3> eyes) {
        Cells after = after(cells, plan.blocks());
        int all = 0, seen = 0;
        int[] o = plan.lookout();
        for (Vec3 e : eyes) {
            if (after.at((int) Math.floor(e.x), (int) Math.floor(e.y), (int) Math.floor(e.z)) != Cell.OPEN) continue;
            all++;
            if (!hidden(after, List.of(e), o[0], o[1], o[2])) seen++;
        }
        return all == 0 ? 0 : (double) seen / all;
    }

    static List<int[]> candidates(Cells cells, int[] start, int sx, int sz) {
        return candidates(cells, start, sx, sz, CELL_MAX, WALK);
    }

    // the share of the spawn eyes (in an open cell now) that see a body in B or in F before a block of the box is in
    static double buildSeen(Cells cells, Plan plan, List<Vec3> eyes) {
        int all = 0, seen = 0;
        int[] b = plan.cell(), f = plan.front();
        for (Vec3 e : eyes) {
            if (cells.at((int) Math.floor(e.x), (int) Math.floor(e.y), (int) Math.floor(e.z)) != Cell.OPEN) continue;
            all++;
            List<Vec3> one = List.of(e);
            if (!hidden(cells, one, b[0], b[1], b[2]) || (f != null && !hidden(cells, one, f[0], f[1], f[2]))) seen++;
        }
        return all == 0 ? 0 : (double) seen / all;
    }

    // the walkable cells CELL_MIN to max from the spawner, walked to from start within walk steps
    static List<int[]> candidates(Cells cells, int[] start, int sx, int sz, double max, int walk) {
        List<int[]> out = new ArrayList<>();
        java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        queue.add(new int[]{start[0], start[1], start[2], 0});
        seen.add(BlazeCover.key(start[0], start[1], start[2]));
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            double flat = Math.hypot(c[0] + 0.5 - sx - 0.5, c[2] + 0.5 - sz - 0.5);
            if (flat >= CELL_MIN && flat <= max && BlazeCover.walkable(cells, c[0], c[1], c[2])) out.add(new int[]{c[0], c[1], c[2]});
            if (c[3] >= walk) continue;
            for (int[] n : BlazeCover.steps(cells, c[0], c[1], c[2], start[1])) {
                if (Math.hypot(n[0] + 0.5 - sx - 0.5, n[2] + 0.5 - sz - 0.5) > max + 2 || !seen.add(BlazeCover.key(n[0], n[1], n[2]))) continue;
                queue.add(new int[]{n[0], n[1], n[2], c[3] + 1});
            }
        }
        return out;
    }

    // the cell outside the look out's open side, where the way into a standing box starts. the bent box's look out is open
    // straight on from its front cell (planBentAt: O + p, the side opposite F); the one-cell box's look out is outside it
    // already. null for the raised box, whose look out stands over the ground (6 Nov 2026: a walk aimed at the cell itself
    // from outside drew the buddy to the wall nearest it, and every block of the box is protected)
    public static int[] door(Plan plan) {
        if (plan.raise() > 0) return null;
        int[] o = plan.lookout();
        if (plan.front() == null) return o.clone();
        int[] f = plan.front();
        int dx = o[0] - f[0], dz = o[2] - f[2];
        if (Math.abs(dx) + Math.abs(dz) != 1 || o[1] != f[1]) return null;
        return new int[]{o[0] + dx, o[1], o[2] + dz};
    }

    // B at (x, y, z), F at B + a, the look out at F + p (p across a)
    static Plan planBentAt(Cells cells, int x, int y, int z, int[] a, int[] p, boolean wing, List<Vec3> eyes, int sx, int sz) {
        int fx = x + a[0], fz = z + a[1];
        int ox = fx + p[0], oz = fz + p[1];
        if (!BlazeCover.walkable(cells, x, y, z) || !BlazeCover.walkable(cells, fx, y, fz) || !BlazeCover.walkable(cells, ox, y, oz)) return null;
        if (dropNear(cells, x, y, z, DROP_CLEAR) || dropNear(cells, fx, y, fz, DROP_CLEAR)) return null;
        List<int[]> walls = new ArrayList<>(List.of(
                new int[]{x - a[0], z - a[1]}, new int[]{x + p[0], z + p[1]}, new int[]{x - p[0], z - p[1]},
                new int[]{fx + a[0], fz + a[1]}, new int[]{fx - p[0], fz - p[1]}));
        if (wing) walls.add(new int[]{ox + a[0], oz + a[1]});
        List<int[]> low = new ArrayList<>(), high = new ArrayList<>();
        for (int[] w : walls) {
            if (cells.at(w[0], y, w[1]) == Cell.OPEN) {
                if (cells.at(w[0], y - 1, w[1]) != Cell.SOLID) return null;
                low.add(new int[]{w[0], y, w[1]});
            } else if (cells.at(w[0], y, w[1]) != Cell.SOLID) {
                return null;
            }
            if (cells.at(w[0], y + 1, w[1]) == Cell.OPEN) high.add(new int[]{w[0], y + 1, w[1]});
            else if (cells.at(w[0], y + 1, w[1]) != Cell.SOLID) return null;
        }
        // (the walls that face the spawner first, as the one-cell box's: begun() reads the first)
        java.util.Comparator<int[]> nearest = java.util.Comparator.comparingDouble(b -> Math.hypot(b[0] - sx, b[2] - sz));
        low.sort(nearest);
        high.sort(nearest);
        List<int[]> blocks = new ArrayList<>(low);
        blocks.addAll(high);
        // the roof row: B's needs a block beside it at its height to be clicked against, the one on top of B's back wall (placed
        // at a jump's top, BlazePillboxTask); F's then goes against B's
        if (cells.at(x, y + 2, z) == Cell.OPEN) {
            int bx = x - a[0], bz = z - a[1];
            if (cells.at(bx, y + 2, bz) == Cell.OPEN) blocks.add(new int[]{bx, y + 2, bz});
            else if (cells.at(bx, y + 2, bz) != Cell.SOLID) return null;
            blocks.add(new int[]{x, y + 2, z});
        } else if (cells.at(x, y + 2, z) != Cell.SOLID) return null;
        if (cells.at(fx, y + 2, fz) == Cell.OPEN) blocks.add(new int[]{fx, y + 2, fz});
        else if (cells.at(fx, y + 2, fz) != Cell.SOLID) return null;
        if (blocks.size() > MOST_BENT) return null;
        Cells after = after(cells, blocks);
        // (no eye inside a block the plan puts there: a ray's end cells do not count, so one in a wall saw through it)
        List<Vec3> free = new ArrayList<>();
        for (Vec3 e : eyes) if (after.at((int) Math.floor(e.x), (int) Math.floor(e.y), (int) Math.floor(e.z)) == Cell.OPEN) free.add(e);
        if (!hiddenWide(after, free, x, y, z)) return null;
        if (hidden(after, free, ox, y, oz)) return null;
        return new Plan(new int[]{x, y, z}, new int[]{ox, y, oz}, blocks, new int[]{fx, y, fz});
    }

    // the raised bent box (5 Nov 2026): the bent box's three cells lifted h, so the look out sees over a spawner platform's edge
    // (from a platform's foot it saw about a quarter of the spawn area, bent-r to bent-t). steering 33: a pillar of one or two is
    // within a wither skeleton's reach, so the box keeps its walls and roof. B, F and O are planned on the ground at y and stand
    // at y + h; the walls h + 2 high from the ground, O's floor a column of h, F's and B's floors at y + h - 1 alone (the climb
    // to F up there is Baritone's pillar), a step out of O for the way back up, the roof at y + h + 2. the order, each from
    // where its face is in sight: the walls' lower h rows from B on the ground, the step and O's column from F (the box is shut
    // at the ground then), F's floor (the walk to F up there pillars), B's floor from F against the inside of B's walls, the
    // walls' top two rows from B up there, the roof from F as the bent box's. h at most RAISE_PLAN: three needs walls five
    // high and O's top block clicked on a face above a standing eye
    static final int RAISE_PLAN = 2;
    static final int MOST_RAISED = 34;

    // why the raised sites of the last planBent with a raise were turned down, for the log ("no raised pillbox fits"): every
    // raised2 plan of realfort-a-raised2 fell back to the ground box
    static final String[] WHY = {"ground", "columns", "wall", "step", "roof", "blocks", "B seen", "O unseen", "O's share", "ring"};
    static final int[] why = new int[WHY.length];

    public static String whyNotRaised() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < WHY.length; i++) if (why[i] > 0) sb.append(sb.length() == 0 ? "" : ", ").append(WHY[i]).append(' ').append(why[i]);
        return sb.length() == 0 ? "no site" : sb.toString();
    }

    static Plan planRaisedAt(Cells cells, int x, int y, int z, int[] a, int[] p, int h, List<Vec3> eyes, List<Vec3> lookEyes, int sx, int sz) {
        if (h < 1 || h > RAISE_PLAN) return null;
        int fx = x + a[0], fz = z + a[1];
        int ox = fx + p[0], oz = fz + p[1];
        int top = y + h;
        if (!BlazeCover.walkable(cells, x, y, z) || !BlazeCover.walkable(cells, fx, y, fz) || !BlazeCover.walkable(cells, ox, y, oz)
                || dropNear(cells, x, y, z, DROP_CLEAR) || dropNear(cells, fx, y, fz, DROP_CLEAR)) return no(0);
        // (the three columns open from the ground to under the roof: the climb, O's column and the bodies up there)
        for (int k = 0; k <= h + 1; k++) {
            if (cells.at(x, y + k, z) != Cell.OPEN || cells.at(fx, y + k, fz) != Cell.OPEN || cells.at(ox, y + k, oz) != Cell.OPEN) return no(1);
        }
        int[][] walls = {{x - a[0], z - a[1]}, {x + p[0], z + p[1]}, {x - p[0], z - p[1]}, {fx + a[0], fz + a[1]}, {fx - p[0], fz - p[1]},
                {ox + a[0], oz + a[1]}};
        List<List<int[]>> rows = new ArrayList<>();
        for (int k = 0; k <= h + 1; k++) rows.add(new ArrayList<>());
        for (int[] w : walls) {
            // (each open cell of a wall's column on the one under it, placed bottom up)
            boolean under = cells.at(w[0], y - 1, w[1]) == Cell.SOLID;
            for (int k = 0; k <= h + 1; k++) {
                Cell c = cells.at(w[0], y + k, w[1]);
                if (c == Cell.OPEN) {
                    if (!under) return no(2);
                    rows.get(k).add(new int[]{w[0], y + k, w[1]});
                } else if (c != Cell.SOLID) {
                    return no(2);
                }
                under = true;
            }
        }
        java.util.Comparator<int[]> nearest = java.util.Comparator.comparingDouble(b -> Math.hypot(b[0] - sx, b[2] - sz));
        for (List<int[]> row : rows) row.sort(nearest);
        int[] bGround = {x, y, z}, fGround = {fx, y, fz}, bUp = {x, top, z}, fUp = {fx, top, fz};
        List<int[]> blocks = new ArrayList<>(), stands = new ArrayList<>();
        for (int k = 0; k < h; k++) {
            for (int[] b : rows.get(k)) {
                blocks.add(b);
                stands.add(bGround);
            }
        }
        // the step out of O: for h 2 one block beyond O on the ground, a jump up from the ground and one onto O's floor. where
        // the cell beyond O already gives a place to stand one up to one down from O (the side of the spawner's platform, which
        // the look out faces: every site of set a's fortresses 1, 2 and 4), that is the way up and no step is placed. a site with
        // neither is no site: in a fight nothing is placed at our feet and up but the box's own blocks
        if (h == 2) {
            int qx = ox + p[0], qz = oz + p[1];
            boolean way = false;
            for (int k = top - 1; k <= top + 1 && !way; k++) {
                way = cells.at(qx, k - 1, qz) == Cell.SOLID && cells.at(qx, k, qz) == Cell.OPEN && cells.at(qx, k + 1, qz) == Cell.OPEN;
            }
            if (!way) {
                if (cells.at(qx, y - 1, qz) != Cell.SOLID || cells.at(qx, y, qz) != Cell.OPEN || cells.at(qx, y + 1, qz) != Cell.OPEN
                        || cells.at(qx, y + 2, qz) != Cell.OPEN || cells.at(qx, y + 3, qz) != Cell.OPEN) return no(3);
                blocks.add(new int[]{qx, y, qz});
                stands.add(fGround);
            }
        }
        for (int k = 0; k < h; k++) {
            blocks.add(new int[]{ox, y + k, oz});
            stands.add(fGround);
        }
        blocks.add(new int[]{fx, top - 1, fz});
        stands.add(fUp);
        blocks.add(new int[]{x, top - 1, z});
        stands.add(fUp);
        for (int k = h; k <= h + 1; k++) {
            for (int[] b : rows.get(k)) {
                blocks.add(b);
                stands.add(bUp);
            }
        }
        // the roof row as the bent box's: B's against the block on top of B's back wall (placed at a jump's top), F's against B's
        int bx = x - a[0], bz = z - a[1];
        if (cells.at(x, top + 2, z) == Cell.OPEN) {
            if (cells.at(bx, top + 2, bz) == Cell.OPEN) {
                blocks.add(new int[]{bx, top + 2, bz});
                stands.add(fUp);
            } else if (cells.at(bx, top + 2, bz) != Cell.SOLID) return no(4);
            blocks.add(new int[]{x, top + 2, z});
            stands.add(fUp);
        } else if (cells.at(x, top + 2, z) != Cell.SOLID) return no(4);
        if (cells.at(fx, top + 2, fz) == Cell.OPEN) {
            blocks.add(new int[]{fx, top + 2, fz});
            stands.add(fUp);
        } else if (cells.at(fx, top + 2, fz) != Cell.SOLID) return no(4);
        if (blocks.size() > MOST_RAISED) return no(5);
        Cells after = after(cells, blocks);
        List<Vec3> free = new ArrayList<>();
        for (Vec3 e : eyes) if (after.at((int) Math.floor(e.x), (int) Math.floor(e.y), (int) Math.floor(e.z)) == Cell.OPEN) free.add(e);
        if (!hiddenWide(after, free, x, top, z)) return no(6);
        List<Vec3> lookFree = new ArrayList<>();
        for (Vec3 e : lookEyes) if (after.at((int) Math.floor(e.x), (int) Math.floor(e.y), (int) Math.floor(e.z)) == Cell.OPEN) lookFree.add(e);
        if (hidden(after, lookFree, ox, top, oz)) return no(7);
        return new Plan(bUp, new int[]{ox, top, oz}, blocks, fUp, stands, h);
    }

    static Plan no(int reason) {
        why[reason]++;
        return null;
    }

    static Cells after(Cells cells, List<int[]> blocks) {
        Set<Long> placed = new HashSet<>();
        for (int[] b : blocks) placed.add(BlazeCover.key(b[0], b[1], b[2]));
        return (cx, cy, cz) -> placed.contains(BlazeCover.key(cx, cy, cz)) ? Cell.SOLID : cells.at(cx, cy, cz);
    }

    // the eyes a blaze about the box could have: rings RING_R out, RING_STEPS round, RING_H over the floor; nearer is the
    // sword's (a blaze within two bites, BlazeFight's pocket rule)
    static final double[] RING_R = {3, 4.5, 6, 8};
    static final double[] RING_H = {1.5, 3, 4.5};
    static final int RING_STEPS = 32;

    // no eye of the ring (in an open cell) sees the eyes of a body standing in (x, y, z). the eyes alone, not the five head
    // points: through a one-wide opening round a corner a sliver of the cell's near edge is always in some line, and the
    // buddy holds to the cell's middle
    static final double[][] RING_HEAD = {{0, 0}};

    static boolean ringHidden(Cells cells, int x, int y, int z) {
        for (double r : RING_R) {
            for (int k = 0; k < RING_STEPS; k++) {
                double ang = k * 2 * Math.PI / RING_STEPS;
                for (double h : RING_H) {
                    Vec3 eye = new Vec3(x + 0.5 + r * Math.cos(ang), y + h, z + 0.5 + r * Math.sin(ang));
                    if (cells.at((int) Math.floor(eye.x), (int) Math.floor(eye.y), (int) Math.floor(eye.z)) != Cell.OPEN) continue;
                    for (double[] hd : RING_HEAD) {
                        if (clearWide(cells, eye, new Vec3(x + 0.5 + hd[0], y + BlazeCover.EYE, z + 0.5 + hd[1]))) return false;
                    }
                }
            }
        }
        return true;
    }

    // no eye sees a body standing in (x, y, z) by a ray a fireball's width (clearWide)
    static boolean hiddenWide(Cells cells, List<Vec3> eyes, int x, int y, int z) {
        for (Vec3 eye : eyes) {
            for (double[] h : RING_HEAD) {
                Vec3 head = new Vec3(x + 0.5 + h[0], y + BlazeCover.EYE, z + 0.5 + h[1]);
                if (eye.distanceTo(head) > BlazeCover.SIGHT) continue;
                if (clearWide(cells, eye, head)) return false;
            }
        }
        return true;
    }

    // half a blaze's small fireball (0.3125 across)
    static final double BALL = 0.16;

    // a fireball's way from a to b: the ray and four beside it, BALL off to each side and up and down, all clear. a thin ray
    // along a diagonal slips past a wall's edge through the corner point of four cells, which no fireball does
    static boolean clearWide(Cells cells, Vec3 a, Vec3 b) {
        if (!clear(cells, a, b)) return false;
        double dx = b.x - a.x, dz = b.z - a.z, len = Math.hypot(dx, dz);
        Vec3 side = len < 1e-6 ? new Vec3(BALL, 0, 0) : new Vec3(-dz / len * BALL, 0, dx / len * BALL);
        Vec3 up = new Vec3(0, BALL, 0);
        return clear(cells, a.add(side), b.add(side)) && clear(cells, a.subtract(side), b.subtract(side))
                && clear(cells, a.add(up), b.add(up)) && clear(cells, a.subtract(up), b.subtract(up));
    }

    // the pillbox at (x, y, z) open to the side open: null when the look out is no walkable cell, more than MOST blocks are
    // needed, a block would have nothing to stand on, or it still leaves the cell in sight of an eye (or the look out in none)
    static Plan planAt(Cells cells, int x, int y, int z, int[] open, List<Vec3> eyes, int sx, int sz) {
        int ox = x + open[0], oz = z + open[1];
        if (!BlazeCover.walkable(cells, ox, y, oz)) return null;
        // (no drop within DROP_CLEAR of the cell: by a platform's rim the builder stood on the rim's fence to set the wall above
        // it and fell into the lava, corner23c)
        if (dropNear(cells, x, y, z, DROP_CLEAR)) return null;
        List<int[]> low = new ArrayList<>(), high = new ArrayList<>();
        for (int[] s : SIDES) {
            if (s == open) continue;
            int wx = x + s[0], wz = z + s[1];
            if (cells.at(wx, y, wz) == Cell.OPEN) {
                // (a low wall needs a floor under it to click on)
                if (cells.at(wx, y - 1, wz) != Cell.SOLID) return null;
                low.add(new int[]{wx, y, wz});
            } else if (cells.at(wx, y, wz) != Cell.SOLID) {
                return null;
            }
            if (cells.at(wx, y + 1, wz) == Cell.OPEN) high.add(new int[]{wx, y + 1, wz});
            else if (cells.at(wx, y + 1, wz) != Cell.SOLID) return null;
        }
        // (the walls that face the spawner first: the cell is in the spawn area's sight least long, corner23's first check died
        // at its unbuilt cell)
        java.util.Comparator<int[]> nearest = java.util.Comparator.comparingDouble(b -> Math.hypot(b[0] - sx, b[2] - sz));
        low.sort(nearest);
        high.sort(nearest);
        List<int[]> blocks = new ArrayList<>(low);
        blocks.addAll(high);
        if (cells.at(x, y + 2, z) == Cell.OPEN) {
            // (the roof is placed by a click on a face beside it: one on top of the wall opposite the opening first, unless
            // a block is there already)
            int bx = x - open[0], bz = z - open[1];
            if (cells.at(bx, y + 2, bz) == Cell.OPEN) blocks.add(new int[]{bx, y + 2, bz});
            else if (cells.at(bx, y + 2, bz) != Cell.SOLID) return null;
            blocks.add(new int[]{x, y + 2, z});
        } else if (cells.at(x, y + 2, z) != Cell.SOLID) return null;
        if (blocks.size() > MOST) return null;
        Set<Long> placed = new HashSet<>();
        for (int[] b : blocks) placed.add(BlazeCover.key(b[0], b[1], b[2]));
        Cells after = (cx, cy, cz) -> placed.contains(BlazeCover.key(cx, cy, cz)) ? Cell.SOLID : cells.at(cx, cy, cz);
        if (!hidden(after, eyes, x, y, z)) return null;
        if (hidden(after, eyes, ox, y, oz)) return null;
        return new Plan(new int[]{x, y, z}, new int[]{ox, y, oz}, blocks);
    }

    static final int DROP_CLEAR = 2;

    // a cell within r of (x, y, z) at its level that is open with no floor under it: a drop
    static boolean dropNear(Cells cells, int x, int y, int z, int r) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (cells.at(x + dx, y, z + dz) == Cell.OPEN && cells.at(x + dx, y - 1, z + dz) != Cell.SOLID) return true;
            }
        }
        return false;
    }

    // no eye sees any head point of a body standing in (x, y, z), by the cells (BlazeCover.hidden's test without a world)
    static boolean hidden(Cells cells, List<Vec3> eyes, int x, int y, int z) {
        for (Vec3 eye : eyes) {
            for (double[] h : BlazeCover.HEAD) {
                Vec3 head = new Vec3(x + 0.5 + h[0], y + BlazeCover.EYE, z + 0.5 + h[1]);
                if (eye.distanceTo(head) > BlazeCover.SIGHT) continue;
                if (clear(cells, eye, head)) return false;
            }
        }
        return true;
    }

    // nothing solid between a and b, the cells walked voxel by voxel (Amanatides and Woo); the two end cells do not count
    static boolean clear(Cells cells, Vec3 a, Vec3 b) {
        int x = (int) Math.floor(a.x), y = (int) Math.floor(a.y), z = (int) Math.floor(a.z);
        int ex = (int) Math.floor(b.x), ey = (int) Math.floor(b.y), ez = (int) Math.floor(b.z);
        double dx = b.x - a.x, dy = b.y - a.y, dz = b.z - a.z;
        int stepX = dx > 0 ? 1 : -1, stepY = dy > 0 ? 1 : -1, stepZ = dz > 0 ? 1 : -1;
        double tDeltaX = dx == 0 ? Double.MAX_VALUE : Math.abs(1 / dx);
        double tDeltaY = dy == 0 ? Double.MAX_VALUE : Math.abs(1 / dy);
        double tDeltaZ = dz == 0 ? Double.MAX_VALUE : Math.abs(1 / dz);
        double tMaxX = dx == 0 ? Double.MAX_VALUE : ((dx > 0 ? x + 1 - a.x : a.x - x) * tDeltaX);
        double tMaxY = dy == 0 ? Double.MAX_VALUE : ((dy > 0 ? y + 1 - a.y : a.y - y) * tDeltaY);
        double tMaxZ = dz == 0 ? Double.MAX_VALUE : ((dz > 0 ? z + 1 - a.z : a.z - z) * tDeltaZ);
        for (int i = 0; i < 256; i++) {
            if (x == ex && y == ey && z == ez) return true;
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                x += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxY < tMaxZ) {
                y += stepY;
                tMaxY += tDeltaY;
            } else {
                z += stepZ;
                tMaxZ += tDeltaZ;
            }
            if (x == ex && y == ey && z == ez) return true;
            if (cells.at(x, y, z) == Cell.SOLID) return false;
        }
        return true;
    }
}
