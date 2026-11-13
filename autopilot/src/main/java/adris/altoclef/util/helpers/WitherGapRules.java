package adris.altoclef.util.helpers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// the buddy's fork, Tuning wither_gap (off; 13 Nov 2026, his plan): with two or three wither skeletons near and the buddy at
// good health, the fight is from a cell they cannot enter instead of a run. the rig's numbers that day: a run from 2 or more
// wither skeletons at 15 hp or more was followed by death within 60 s 67 times in 302 (22%), a fight one on one at that hp
// in 4%; in 6 of 18 deaths read, the buddy ran from 2 or 3 of them 4 to 6 blocks off at 15 hp or more and was hit in the
// back and cornered 20 to 50 blocks on.
// the cell: a wither skeleton is 2.4 tall and 0.7 wide, the buddy 1.8. its melee is its own box widened 0.828 sideways and
// not at all up or down (26.3 Mob.getAttackBoundingBox), against the buddy's box, a strict intersect
// (Mob.isWithinMeleeAttackRange; GolemRules has the long version). so it hits only with its box in one of the 8 columns
// round the buddy's (a box kept out of all of them stops 0.17 short of the buddy's column), and only at a height where its
// 2.4 overlaps the buddy's 1.8. a cell with a full block 2 over its floor, and every column round it shut to a box 2.4 tall
// at every such height, is out of its reach, while the buddy's sword (3 from the eye) reaches one pressed against the mouth
// of a tunnel 1.5 off. typically the dead end of a 1 wide, 2 high tunnel 2 deep, dug into a wall where none is found.
// no minecraft in here: the brain reads the blocks into a Grid, the tests make one up
public final class WitherGapRules {
    // the knobs, each a Tuning key with these as its fallback
    public static final double START_HP = 14;
    public static final double LEAVE_HP = 8;
    public static final int SEARCH = 8;
    public static final int DIG_RANGE = 3;
    public static final int ROOF_MAX = 3;
    public static final double START_RANGE = 10;
    public static final double STAY_RANGE = 12;
    public static final double CAP_S = 90;
    public static final double BUILD_S = 6;

    // two or three of them: one is the plain fight (CombatCommit.witherComing), four or more the old way
    public static final int MIN_WITHERS = 2;
    public static final int MAX_WITHERS = 3;
    // "on our floor", as CombatCommit.witherNear counts them: the ones through a fortress floor are not on us
    public static final double FLOOR_BAND = 4;
    // none of the gap's wither skeletons within the stay range this long: they are dead or gone, the task goes on
    public static final long QUIET_TICKS = 40;
    // a gap that failed (not reached, given up, the cap) is not tried again for this long: the old way gets its turn
    public static final long RETRY_TICKS = 600;
    // a wither skeleton this close to the straight way to the cell bars it: the walk would pass within its reach
    public static final double WAY_CLEAR = 2;
    // a block a pickaxe takes in a moment: nether bricks are 2, netherrack 0.4, the nether's ores 3
    public static final double DIG_HARDNESS = 3;

    private WitherGapRules() {
    }

    public static boolean on() {
        return Tuning.get("wither_gap", 0) > 0;
    }

    public static double startHp() {
        return Tuning.get("wither_gap_hp", START_HP);
    }

    public static double leaveHp() {
        return Tuning.get("wither_gap_leave_hp", LEAVE_HP);
    }

    // how far off a cell is looked for (the walk to it)
    public static int searchRadius() {
        return (int) Math.round(Tuning.get("wither_gap_search", SEARCH));
    }

    // how far off the wall a tunnel is dug into may be (the stand it is dug from)
    public static int digRange() {
        return (int) Math.round(Tuning.get("wither_gap_dig", DIG_RANGE));
    }

    // the most blocks a roof may take
    public static int roofMax() {
        return (int) Math.round(Tuning.get("wither_gap_roof", ROOF_MAX));
    }

    // the wither skeletons that count for the start are within this
    public static double startRange() {
        return Tuning.get("wither_gap_range", START_RANGE);
    }

    // the buddy stays in the cell while one of them lives within this
    public static double stayRange() {
        return Tuning.get("wither_gap_stay", STAY_RANGE);
    }

    public static long capTicks() {
        return Math.round(Tuning.get("wither_gap_cap_s", CAP_S) * 20);
    }

    // the cell not reached (dug, roofed) in this long: the old way
    public static long buildTicks() {
        return Math.round(Tuning.get("wither_gap_build_s", BUILD_S) * 20);
    }

    // ---- the start and the leave

    // null when the gap may start, else why not. withers: wither skeletons on us (the brain's foes) within the start range on
    // our floor; all: every wither skeleton tracked within the start range, foe or not; others: other melee foes within it (a
    // zombified piglin fits in the tunnel, the gap is no answer to it); sword: one in the bag (the hand counts)
    public static String startNo(int withers, int all, int others, double hp, boolean sword) {
        if (withers < MIN_WITHERS) return "fewer than " + MIN_WITHERS + " wither skeletons";
        if (Math.max(withers, all) > MAX_WITHERS) return "more than " + MAX_WITHERS + " wither skeletons";
        if (others > 0) return "another melee mob on us";
        if (hp < startHp()) return "hp " + num(hp) + " under " + num(startHp());
        if (!sword) return "no sword";
        return null;
    }

    // what the brain reads off the world and the gap's task every tick of a gap, for CombatCommit. inCell: the buddy stands in
    // the cell now; everIn: it has been in it in this gap; failed: why the task gave the cell up, null while it has not;
    // withers: the gap's wither skeletons alive within the stay range
    public record Facts(boolean inCell, boolean everIn, String failed, int withers) {
    }

    // why the buddy leaves the gap now, null to stay. inGap: ticks since the gap began; quiet: ticks none of its wither
    // skeletons has been within the stay range (0 while one is)
    public static String leave(Facts f, double hp, long inGap, long quiet) {
        if (f == null) return "no facts";
        if (f.failed() != null) return f.failed();
        if (hp < leaveHp()) return "hp " + num(hp) + " under " + num(leaveHp());
        if (!f.everIn() && inGap >= buildTicks()) return "the cell not reached in " + num(buildTicks() / 20.0) + " s";
        if (inGap >= capTicks()) return num(capTicks() / 20.0) + " s in the gap";
        if (quiet >= QUIET_TICKS) return "no wither skeleton left within " + num(stayRange());
        return null;
    }

    // a leave for this reason is the old way for RETRY_TICKS: the cell did not work out, or the cap came with them still about.
    // low hp is the old low hp way at once (and no gap below the start hp), a quiet end is the gap done
    public static boolean failedLeave(Facts f, double hp, long inGap) {
        if (f == null || f.failed() != null) return true;
        if (hp < leaveHp()) return false;
        return !f.everIn() && inGap >= buildTicks() || inGap >= capTicks();
    }

    // ---- the cell

    // what a block is to the rules. full: DIG, HARD, FALLS and HOT, a whole block a mob's box cannot share
    public enum Cell {
        // nothing to collide with and no fluid (air, a torch, a wart)
        OPEN,
        // a full block that stays put and a pickaxe takes in a moment (netherrack, nether bricks, blackstone, an ore)
        DIG,
        // a full block that stays put and is not dug here (obsidian, bedrock, a chest, soul soil)
        HARD,
        // a full block that falls once what is under it is gone (gravel)
        FALLS,
        // a full block that burns what stands on it (magma)
        HOT,
        // collides as less than a whole block (a slab, a stair, a fence, soul sand): no floor, and no wall to a mob
        PARTIAL,
        // no collision, but not a place to stand
        FIRE,
        LAVA,
        WATER
    }

    public interface Grid {
        Cell at(int x, int y, int z);
    }

    public static boolean full(Cell c) {
        return c == Cell.DIG || c == Cell.HARD || c == Cell.FALLS || c == Cell.HOT;
    }

    static boolean floor(Cell c) {
        return c == Cell.DIG || c == Cell.HARD || c == Cell.FALLS;
    }

    static boolean fluid(Cell c) {
        return c == Cell.LAVA || c == Cell.WATER;
    }

    // null when a buddy with its feet in (x, y, z) is out of every wither skeleton's reach, else why not: a floor, room for
    // the buddy's 1.8, a full block 2 over the floor, every column round it shut to a box 2.4 tall (standsBeside) and no lava
    // in the 3 by 3 round its feet, head and ceiling
    public static String cellNo(Grid g, int x, int y, int z) {
        if (!floor(g.at(x, y - 1, z))) return "no floor";
        if (g.at(x, y, z) != Cell.OPEN || g.at(x, y + 1, z) != Cell.OPEN) return "no room";
        if (!full(g.at(x, y + 2, z))) return "no ceiling at 2";
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if ((dx != 0 || dz != 0) && standsBeside(g, x + dx, y, z + dz)) return "open beside";
            }
        }
        if (lavaNear(g, x, y, z)) return "lava by it";
        return null;
    }

    // a wither skeleton's box (2.4 tall) fits in column (x, z) at a height where it overlaps the buddy's (feet at y, 1.8 tall):
    // its feet from y - 2.4 to y + 1.8, which for any such height leaves three cells k, k + 1, k + 2 with k from y - 2 to y + 1
    // free of a full block. the ground it would stand on is not asked: one standing in the next column can lean in
    static boolean standsBeside(Grid g, int x, int y, int z) {
        for (int k = y - 2; k <= y + 1; k++) {
            if (!full(g.at(x, k, z)) && !full(g.at(x, k + 1, z)) && !full(g.at(x, k + 2, z))) return true;
        }
        return false;
    }

    static boolean lavaNear(Grid g, int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    if (g.at(x + dx, y + dy, z + dz) == Cell.LAVA) return true;
                }
            }
        }
        return false;
    }

    // a way in for the buddy: a side cell with room for 1.8 on a floor at the cell's height
    static boolean wayIn(Grid g, int x, int y, int z) {
        for (int[] d : SIDES) {
            if (standable(g, x + d[0], y, z + d[1])) return true;
        }
        return false;
    }

    static boolean standable(Grid g, int x, int y, int z) {
        return floor(g.at(x, y - 1, z)) && g.at(x, y, z) == Cell.OPEN && g.at(x, y + 1, z) == Cell.OPEN;
    }

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    // ---- where: found, dug or roofed

    public enum Kind {
        FOUND, DUG, ROOFED;

        public String word() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    // the cell the buddy fights from (its feet at x y z); dig: the blocks to dig out, in order; fill: the cells to put a block
    // in, in order; stand: where the digging is done from (the cell itself for the rest); walk: about how far it is
    public record Plan(Kind kind, int x, int y, int z, List<int[]> dig, List<int[]> fill, int standX, int standY, int standZ, double walk) {
        public int blocks() {
            return dig.size() + fill.size();
        }

        public String at() {
            return x + " " + y + " " + z;
        }
    }

    // the plan, or null and why there is none
    public record Search(Plan plan, String no) {
    }

    // the cell to fight from: first one there is (within radius of the buddy, its walk clear of the wither skeletons), else
    // one made, dug or roofed, whichever takes fewer blocks (the dig on a tie). px py pz: the buddy's feet; withers: x and z
    // of each wither skeleton near; pickaxe: a dig only with one; bag: the blocks a roof may take
    public static Search search(Grid g, double px, double py, double pz, List<double[]> withers, int radius, int digRange,
                                int roofMax, boolean pickaxe, int bag) {
        int fx = (int) Math.floor(px);
        int fy = (int) Math.floor(py + 0.01);
        int fz = (int) Math.floor(pz);
        Plan found = null;
        Map<String, Integer> foundNo = new HashMap<>();
        int twoHigh = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    int x = fx + dx;
                    int y = fy + dy;
                    int z = fz + dz;
                    double walk = Math.hypot(x + 0.5 - px, z + 0.5 - pz) + Math.abs(dy);
                    if (walk > radius || (found != null && walk >= found.walk())) continue;
                    String no = cellNo(g, x, y, z);
                    if (no != null) {
                        if (no.equals("open beside") || no.equals("lava by it")) {
                            twoHigh++;
                            foundNo.merge(no, 1, Integer::sum);
                        }
                        continue;
                    }
                    twoHigh++;
                    if (!wayIn(g, x, y, z) && !(dx == 0 && dz == 0 && dy == 0)) {
                        foundNo.merge("no way in", 1, Integer::sum);
                        continue;
                    }
                    if (!wayClear(px, pz, x + 0.5, z + 0.5, withers)) {
                        foundNo.merge("a wither skeleton on the way", 1, Integer::sum);
                        continue;
                    }
                    found = new Plan(Kind.FOUND, x, y, z, List.of(), List.of(), x, y, z, walk);
                }
            }
        }
        if (found != null) return new Search(found, null);
        Plan dug = null;
        double dugCost = Double.POSITIVE_INFINITY;
        Map<String, Integer> digNo = new HashMap<>();
        if (pickaxe) {
            for (int dx = -digRange; dx <= digRange; dx++) {
                for (int dz = -digRange; dz <= digRange; dz++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        int ox = fx + dx;
                        int oy = fy + dy;
                        int oz = fz + dz;
                        double walk = Math.hypot(ox + 0.5 - px, oz + 0.5 - pz) + Math.abs(dy);
                        if (walk > digRange + 0.5 || !standable(g, ox, oy, oz)) continue;
                        if (!wayClear(px, pz, ox + 0.5, oz + 0.5, withers)) {
                            digNo.merge("a wither skeleton on the way", 1, Integer::sum);
                            continue;
                        }
                        for (int[] d : SIDES) {
                            Object t = tunnel(g, ox, oy, oz, d[0], d[1], walk);
                            if (t instanceof String why) {
                                digNo.merge(why, 1, Integer::sum);
                                continue;
                            }
                            Plan p = (Plan) t;
                            double cost = p.walk() + 2 * p.blocks();
                            if (cost < dugCost) {
                                dugCost = cost;
                                dug = p;
                            }
                        }
                    }
                }
            }
        }
        Plan roofed = null;
        Map<String, Integer> roofNo = new HashMap<>();
        int roofCap = Math.min(roofMax, bag);
        if (roofCap > 0) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int x = fx + dx;
                    int z = fz + dz;
                    double walk = Math.hypot(x + 0.5 - px, z + 0.5 - pz);
                    Object r = roof(g, x, fy, z, roofCap, walk);
                    if (r instanceof String why) {
                        roofNo.merge(why, 1, Integer::sum);
                        continue;
                    }
                    Plan p = (Plan) r;
                    if (!wayClear(px, pz, x + 0.5, z + 0.5, withers)) {
                        roofNo.merge("a wither skeleton on the way", 1, Integer::sum);
                        continue;
                    }
                    if (roofed == null || p.blocks() < roofed.blocks() || p.blocks() == roofed.blocks() && p.walk() < roofed.walk()) roofed = p;
                }
            }
        }
        if (roofed != null && (dug == null || roofed.blocks() < dug.blocks())) return new Search(roofed, null);
        if (dug != null) return new Search(dug, null);
        String a = twoHigh == 0 ? "no 2-high cell" : twoHigh + " 2-high cells, " + top(foundNo);
        String b = !pickaxe ? "no pickaxe to dig" : digNo.isEmpty() ? "no wall to dig within " + digRange : "no tunnel: " + top(digNo);
        String c = roofCap <= 0 ? (bag <= 0 ? "no blocks for a roof" : "no roof") : roofNo.isEmpty() ? "no roof" : "no roof: " + top(roofNo);
        return new Search(null, a + "; " + b + "; " + c);
    }

    // the commonest reason of a tally
    private static String top(Map<String, Integer> no) {
        String best = "none";
        int n = -1;
        for (Map.Entry<String, Integer> e : no.entrySet()) {
            if (e.getValue() > n || e.getValue() == n && e.getKey().compareTo(best) < 0) {
                best = e.getKey();
                n = e.getValue();
            }
        }
        return best;
    }

    // a tunnel 1 wide and 2 high, 2 deep from the stand (ox, oy, oz) along (ddx, ddz): its mouth M and back B, the buddy in B.
    // a Plan, or why not: every cell of it open or a quick dig and never a falling block, a full block over each (a falling
    // one would come down into it), a floor under each (no drop let in), no fluid beside a block dug (it would run in), and B
    // a cell by cellNo once the digging is done (the wall round it thick enough)
    static Object tunnel(Grid g, int ox, int oy, int oz, int ddx, int ddz, double walk) {
        int mx = ox + ddx;
        int mz = oz + ddz;
        int bx = ox + 2 * ddx;
        int bz = oz + 2 * ddz;
        int[][] cells = {{mx, oy, mz}, {mx, oy + 1, mz}, {bx, oy, bz}, {bx, oy + 1, bz}};
        List<int[]> dig = new ArrayList<>();
        for (int[] c : cells) {
            Cell k = g.at(c[0], c[1], c[2]);
            if (k == Cell.OPEN) continue;
            if (k != Cell.DIG) return "not a quick dig";
            dig.add(c);
        }
        if (dig.isEmpty()) return "open already";
        for (int[] c : new int[][]{{mx, mz}, {bx, bz}}) {
            Cell lid = g.at(c[0], oy + 2, c[1]);
            if (lid != Cell.DIG && lid != Cell.HARD && lid != Cell.HOT) return "no lid";
            if (!floor(g.at(c[0], oy - 1, c[1]))) return "no floor";
        }
        for (int[] c : dig) {
            for (int[] n : new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) {
                if (fluid(g.at(c[0] + n[0], c[1] + n[1], c[2] + n[2]))) return "a fluid behind the wall";
            }
        }
        Grid after = with(g, dig, Cell.OPEN);
        String no = cellNo(after, bx, oy, bz);
        if (no != null) return no.equals("open beside") ? "the wall too thin" : no;
        return new Plan(Kind.DUG, bx, oy, bz, dig, List.of(), ox, oy, oz, walk + 2);
    }

    // a roof over the cell (x, y, z) and its columns round it that a wither skeleton could stand in: a block 2 over the floor
    // of each, put from inside the cell. a Plan, or why not: the cell a place for the buddy, each block's cell empty, a floor
    // under each such column (a roof does not shut a drop), each block with a face to click that the eye in the cell is in
    // front of (BlazePillboxTask.facesEye), no more than cap blocks, and the cell a cell by cellNo with the roof on
    static Object roof(Grid g, int x, int y, int z, int cap, double walk) {
        if (!standable(g, x, y, z)) return "no room";
        List<int[]> fill = new ArrayList<>();
        Grid after = g;
        if (!full(g.at(x, y + 2, z))) {
            if (g.at(x, y + 2, z) != Cell.OPEN) return "the roof's cell not empty";
            fill.add(new int[]{x, y + 2, z});
            after = with(g, fill, Cell.DIG);
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if ((dx == 0 && dz == 0) || !standsBeside(after, x + dx, y, z + dz)) continue;
                if (g.at(x + dx, y + 2, z + dz) != Cell.OPEN) return "the roof's cell not empty";
                fill.add(new int[]{x + dx, y + 2, z + dz});
                after = with(g, fill, Cell.DIG);
                if (standsBeside(after, x + dx, y, z + dz)) return "open under the roof";
                if (fill.size() > cap) return "more than " + cap + " blocks";
            }
        }
        if (fill.isEmpty()) return "a cell already";
        if (fill.size() > cap) return "more than " + cap + " blocks";
        String no = cellNo(after, x, y, z);
        if (no != null) return no;
        if (!clickable(g, fill, x + 0.5, z + 0.5)) return "nothing to put the roof against";
        return new Plan(Kind.ROOFED, x, y, z, List.of(), fill, x, y, z, walk);
    }

    // every block of the roof, in its order, has a full side neighbour (the ones put before it count) whose face toward it the
    // eye at (ex, ez) is in front of
    static boolean clickable(Grid g, List<int[]> fill, double ex, double ez) {
        List<int[]> placed = new ArrayList<>();
        for (int[] c : fill) {
            Grid now = with(g, placed, Cell.DIG);
            boolean face = false;
            for (int[] d : SIDES) {
                if (full(now.at(c[0] + d[0], c[1], c[2] + d[1])) && facesEye(ex, ez, c[0], c[2], d[0], d[1])) {
                    face = true;
                    break;
                }
            }
            if (!face) return false;
            placed.add(c);
        }
        return true;
    }

    // the face between cell (cx, cz) and its neighbour (cx + dx, cz + dz) has the eye on the cell's side (as
    // BlazePillboxTask.facesEye): a click from there can hit it
    static boolean facesEye(double ex, double ez, int cx, int cz, int dx, int dz) {
        if (dx != 0) {
            double plane = cx + (dx > 0 ? 1 : 0);
            return dx > 0 ? ex < plane : ex > plane;
        }
        double plane = cz + (dz > 0 ? 1 : 0);
        return dz > 0 ? ez < plane : ez > plane;
    }

    // no wither skeleton within WAY_CLEAR of the straight way from the buddy to (tx, tz). a cell within a block of the buddy
    // needs no walk, whoever is about
    static boolean wayClear(double px, double pz, double tx, double tz, List<double[]> withers) {
        if (Math.hypot(tx - px, tz - pz) <= 1) return true;
        for (double[] w : withers) {
            if (segment(px, pz, tx, tz, w[0], w[1]) < WAY_CLEAR) return false;
        }
        return true;
    }

    // the distance from (wx, wz) to the segment from (ax, az) to (bx, bz)
    static double segment(double ax, double az, double bx, double bz, double wx, double wz) {
        double vx = bx - ax;
        double vz = bz - az;
        double len = vx * vx + vz * vz;
        double t = len <= 0 ? 0 : Math.max(0, Math.min(1, ((wx - ax) * vx + (wz - az) * vz) / len));
        return Math.hypot(ax + t * vx - wx, az + t * vz - wz);
    }

    // the grid with these cells changed to `as` (the plan's digging or blocks put)
    static Grid with(Grid g, List<int[]> cells, Cell as) {
        if (cells.isEmpty()) return g;
        Map<Long, Cell> over = new HashMap<>();
        for (int[] c : cells) over.put(key(c[0], c[1], c[2]), as);
        return (x, y, z) -> {
            Cell c = over.get(key(x, y, z));
            return c != null ? c : g.at(x, y, z);
        };
    }

    // x and z in 26 bits each, y in 12, as a block position packs
    public static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
    }

    static String num(double v) {
        return Math.abs(v - Math.rint(v)) < 1e-9 ? String.valueOf((long) Math.rint(v)) : String.format(Locale.ROOT, "%.1f", v);
    }

    // the line of the start (the brain writes it after "combat: wither gap: ")
    public static String startLine(int withers, double far, double hp, Plan plan) {
        return withers + " wither skeletons within " + Math.round(far) + " at " + String.format(Locale.ROOT, "%.1f", hp)
                + " hp, the safe cell at " + plan.at() + " (" + plan.kind().word() + ", " + plan.blocks() + " blocks), going in";
    }
}
