package adris.altoclef.tasks.speedrun.gamer.portal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// pure: the lava bucket portal, built in the pool the way the speedrun does it. the frame stands in the shore row of a
// lava pool: four lava sources in a row, shore right behind them, a one deep pool. no world access in here, everything
// reads a Terrain, so the whole build runs in a test
//
// coordinates are (u, y, d): u runs along the row, y is up (0 = the lava surface layer, the same as the shore's top
// layer), d is toward the shore (so the pool is at d < 0). d=0 is the frame plane. Layout turns those into world cells.
//
// base: a throwaway block goes into the lava at (1,0,0), water goes in beside it at (2,0,0). the water cements
// the corner (3,0,0) and the cell in front of it, then the block comes out, the water runs into its cell and cements the
// other corner (0,0,0) and the cell in front of that. the water goes back in the bucket. what is left is two obsidian
// corners, an empty pit between them over the pool floor, and two obsidian cells in the pool in front of the pit to stand
// on (the platform)
//
// walls: seven cobble blocks and nothing else. on the shore behind the frame (d=1) a 2 tall pillar behind the left corner
// with a step beside it, a 3 tall pillar behind the right corner, and an arm on its top in the frame plane. the lava goes
// in by clicking their pool facing sides from the platform, and the water spills down onto them and into the pit
//
// the portal is the frame one layer lower than the corners: its bottom is the pool floor under the pit (broken out and
// filled with lava while the water pours in), the corners are the lowest cells of the side columns
//
// water: one source behind the top right of the interior. it falls behind the frame and spills into it at the top, and over
// the pit, and a spill over the top left falls onto the step and comes into the frame over the left half of the pit. lava goes
// into a cell only while there is water next to it, and the casts that need water to keep running go first
public final class LPortalPlan {
    public enum Mat {
        // FALLS = sand, gravel and friends: solid until you take the block under them. OBSIDIAN also stands in for anything
        // unbreakable (bedrock), solid and not ours to mine
        AIR, SOLID, FALLS, OBSIDIAN, LAVA, LAVA_FLOW, WATER, WATER_FLOW, PORTAL;

        public boolean solid() {
            return this == SOLID || this == OBSIDIAN || this == FALLS;
        }

        public boolean wet() {
            return this == WATER || this == WATER_FLOW;
        }

        public boolean lava() {
            return this == LAVA || this == LAVA_FLOW;
        }
    }

    public interface Terrain {
        Mat at(int x, int y, int z);
    }

    public record P(int x, int y, int z) {
    }

    // what is in the bag. hasSpot = there is a lava source to scoop, from where we stand or from a stand we can walk to
    public record Inv(int lava, boolean water, int empty, int blocks, boolean hasSpot) {
    }

    public enum Kind {
        SCOOP, PLACE_BLOCK, PLACE_STACK, BREAK, WATER_PLACE, LAVA_PLACE, LAVA_UNDO, WATER_SCOOP, LIGHT, WAIT, STUCK, DONE, ABANDON
    }

    // cell = what the step is about, support + face = the block we click and which side of it (world offset), stand =
    // where we want our feet (null = the task picks)
    public record Step(Kind kind, P cell, P support, int fx, int fy, int fz, P stand, String why) {
        public String toString() {
            return kind + (cell == null ? "" : " " + cell) + (why == null ? "" : " (" + why + ")");
        }
    }

    // one cast: lava goes into t, clicking the face of support that looks at it, from stand
    public record Cast(String name, int[] t, int[] support, int[] stand, String[] after) {
        public Cast(String name, int[] t, int[] support, int[] stand) {
            this(name, t, support, stand, new String[0]);
        }
    }

    public record Scoop(P source, P stand) {
    }

    // where we stand on the platform, one cell in front of the frame
    private static final int[] LEFT_STAND = {1, 1, -1};
    private static final int[] RIGHT_STAND = {2, 1, -1};
    // on the shore edge, right behind the row: from further back the shore edge is in the way of the floor and the lava cells
    private static final int[] SHORE_LEFT = {1, 1, 1};
    private static final int[] SHORE_RIGHT = {3, 1, 1};

    // the bot stands higher on the left stairs (step, then the pillar top) to take the source back, it is out of sight from the platform
    private static final int[] HIGH_STAND = {0, 3, 1};

    // one water source does it all. it is poured behind the frame, at the top right of the interior's shore row, against the
    // side of the right pillar's top block. it falls down behind the right interior column and spreads into the frame plane
    // at the top and at the bottom of that fall (over the pit), which wets the right column and the top. and its spread over the
    // top reaches the cell behind the top left, which falls onto the step: the step turns that water into the frame plane over
    // the left half of the pit, which wets the left column. the floor under the pit is broken out before it goes in, so the
    // bottom cells are cast under the water. the right column goes before the top right, which cuts its water off
    public record Round(String name, int[] source, int[] sourceSupport, int[] stand, int[] scoopStand, List<Cast> casts) {
    }

    public static final List<Round> ROUNDS = List.of(
            new Round("a", new int[]{2, 3, 1}, new int[]{3, 3, 1}, RIGHT_STAND, HIGH_STAND, List.of(
                    new Cast("r1", new int[]{3, 1, 0}, new int[]{3, 1, 1}, RIGHT_STAND),
                    new Cast("r2", new int[]{3, 2, 0}, new int[]{3, 2, 1}, RIGHT_STAND),
                    new Cast("l1", new int[]{0, 1, 0}, new int[]{0, 1, 1}, LEFT_STAND),
                    new Cast("l2", new int[]{0, 2, 0}, new int[]{0, 2, 1}, LEFT_STAND),
                    new Cast("b2", new int[]{2, -1, 0}, new int[]{2, -1, 1}, LEFT_STAND),
                    new Cast("b1", new int[]{1, -1, 0}, new int[]{1, -1, 1}, LEFT_STAND),
                    // against the arm, then t1 against t2
                    new Cast("t2", new int[]{2, 3, 0}, new int[]{3, 3, 0}, RIGHT_STAND, new String[]{"r1", "r2"}),
                    new Cast("t1", new int[]{1, 3, 0}, new int[]{2, 3, 0}, LEFT_STAND))));

    // the cobble on the shore behind the frame, seven blocks with the arm: a 2 tall pillar behind the left corner and a step
    // beside it toward the frame, a 3 tall pillar behind the right corner, and the arm on its top, in the frame plane over
    // the right corner. the builder places the shore ones, the arm goes in from the platform against the top of the pillar
    public static final int[][] WALLS = {
            {0, 1, 1}, {0, 2, 1}, {1, 1, 1},
            {3, 1, 1}, {3, 2, 1}, {3, 3, 1}};
    public static final int[] ARM = {3, 3, 0};
    public static final int[] ARM_SUPPORT = {3, 3, 1};
    public static final int[][] INTERIOR = {{1, 0, 0}, {2, 0, 0}, {1, 1, 0}, {2, 1, 0}, {1, 2, 0}, {2, 2, 0}};
    // the pool floor under the pit, which becomes the bottom of the frame
    public static final int[][] BOTTOM = {{1, -1, 0}, {2, -1, 0}};

    // the base: where the water goes in and the block it is poured against, the corners it makes
    public static final int[] PLACEHOLDER = {1, 0, 0};
    public static final int[] BASE_WATER = {2, 0, 0};
    public static final int[] CORNER_RIGHT = {3, 0, 0};
    public static final int[] CORNER_LEFT = {0, 0, 0};
    public static final int[] PLATFORM_LEFT = {1, 0, -1};
    public static final int[] PLATFORM_RIGHT = {2, 0, -1};

    // eight casts, and a source taken is gone, so some spare for a miss or an undo
    public static final int SUPPLY_NEEDED = 10;
    // how far around the row the build looks for lava, also what the task scans
    public static final int SCAN = 22;

    private LPortalPlan() {
    }

    // turns (u, y, d) into world cells. (ax, az) is the unit step along u, (nx, nz) the unit step toward the shore
    public record Layout(int ox, int oy, int oz, int ax, int az, int nx, int nz) {
        public P cell(int u, int y, int d) {
            return new P(ox + u * ax + d * nx, oy + y, oz + u * az + d * nz);
        }

        public P cell(int[] c) {
            return cell(c[0], c[1], c[2]);
        }

        // a local offset as a world offset
        public int[] dir(int du, int dy, int dd) {
            return new int[]{du * ax + dd * nx, dy, du * az + dd * nz};
        }
    }

    private static Mat m(Layout l, Terrain t, int[] c) {
        P p = l.cell(c);
        return t.at(p.x(), p.y(), p.z());
    }

    private static Mat m(Layout l, Terrain t, int u, int y, int d) {
        P p = l.cell(u, y, d);
        return t.at(p.x(), p.y(), p.z());
    }

    // ---- is this a place for it ----

    // the cheap part: four lava sources in a row with shore right behind them, lava in front of the middle two, and the
    // pool floor under the row. real lakes have wobbly edges, so a notch or two of lava in the shore row is allowed: a block
    // on the rock under it and it's shore like any other
    static boolean quick(Layout l, Terrain t) {
        return quickWhy(l, t) == null;
    }

    // the same rule, with the first thing it said no to. null = passed. the log reads this and not a copy, so the two can't drift
    static String quickWhy(Layout l, Terrain t) {
        int notches = 0;
        for (int u = 0; u <= 3; u++) {
            // sand and gravel hold up a shore just fine, only the floor under the row gets broken out so it stays plain rock
            if (m(l, t, u, 0, 0) != Mat.LAVA) {
                return "row_not_lava";
            }
            if (m(l, t, u, -1, 0) != Mat.SOLID) {
                return "row_floor";
            }
            Mat shore = m(l, t, u, 0, 1);
            if (shore == Mat.LAVA && m(l, t, u, -1, 1).solid()) {
                notches++;
            } else if (!shore.solid()) {
                return "shore_not_solid";
            }
        }
        if (notches > MAX_NOTCHES) {
            return "notches";
        }
        for (int u = 1; u <= 2; u++) {
            if (m(l, t, u, 0, -1) != Mat.LAVA) {
                return "lane_not_lava";
            }
            if (m(l, t, u, -1, -1) != Mat.SOLID) {
                return "lane_floor";
            }
        }
        return null;
    }

    // the perfect site: nothing to clear, nothing to fill
    public static boolean fits(Layout l, Terrain t) {
        Survey s = survey(l, t);
        return s.ok() && s.mines() == 0 && s.fills() == 0;
    }

    // what a site needs before it is a site. mines = solids in the way (inside the build's air, the arm cell aside),
    // prepMines = the ones behind the frame (d >= 1) that get cleared before the base, fills = holes in the backing row to plug
    // and lava notches in the shore row to fill. why = the first thing it said no to, null on a yes
    public record Survey(boolean ok, int mines, int prepMines, int fills, String why) {
    }

    private static Survey no(String why) {
        return new Survey(false, 0, 0, 0, why);
    }

    // nearly no bank out there is flush with the lava, and the cheap dig back is what lets a real lake pass at all
    static final int MAX_MINES = 36;
    static final int MAX_FILLS = 4;
    // three of the four and it isn't a shore with notches, it's more lake
    static final int MAX_NOTCHES = 2;
    // the mines in the frame plane and the lane are dug after the base, on the build clock
    static final int MAX_FRONT_MINES = 8;
    // a gravel column this high over a cell we open would come down on us
    private static final int FALL_CHECK_TOP = 8;
    // gravel over open air has the whole drop to come down, so this looks further up than the one over a rock
    private static final int HANG_CHECK_TOP = 11;
    public static final String PREP_CLEAR = "clearing the spot";
    public static final String PREP_FILL = "filling the shore";

    // every cell the finished build wants empty: the shore row up to y=4 (we stand up there to take the water back), the frame
    // plane, the row behind the shore and the platform lane up to y=3. the walls and the arm go into some of these
    private static final List<int[]> NEED_AIR = needAir();
    // the layer over the frame plane and the lane. the frame tops out at y=3 and we stand on y=1, so rock up here can just stay.
    // real lakes have a low roof a lot, and having to clear it turned down most of them
    private static final List<int[]> ROOF = roof();

    private static List<int[]> needAir() {
        List<int[]> cells = new ArrayList<>();
        for (int u = 0; u <= 3; u++) {
            for (int y = 1; y <= 4; y++) {
                if (y <= 3) {
                    cells.add(new int[]{u, y, 0});
                }
                cells.add(new int[]{u, y, 1});
                if (y <= 3) {
                    cells.add(new int[]{u, y, 2});
                }
            }
        }
        for (int u = 1; u <= 2; u++) {
            for (int y = 1; y <= 3; y++) {
                cells.add(new int[]{u, y, -1});
            }
        }
        return cells;
    }

    private static List<int[]> roof() {
        List<int[]> cells = new ArrayList<>();
        for (int u = 0; u <= 3; u++) {
            cells.add(new int[]{u, 4, 0});
        }
        for (int u = 1; u <= 2; u++) {
            cells.add(new int[]{u, 4, -1});
        }
        return cells;
    }

    // gravel with nothing but air under it, worldgen leaves these around. it hangs there until something bumps it, and we are
    // about to spend a minute pouring things right under it. gravel sitting on rock we don't dig is just gravel
    private static boolean hangsOver(Layout l, Terrain t, int[] c) {
        for (int y = c[1] + 1; y <= HANG_CHECK_TOP; y++) {
            Mat now = m(l, t, c[0], y, c[2]);
            if (now == Mat.FALLS) {
                return true;
            }
            if (now != Mat.AIR) {
                return false;
            }
        }
        return false;
    }

    private static final int[][] NEIGHBOURS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    // fits() with a bit of work allowed: a bump, a low ceiling, a hole in the backing. the hard no's stay no's: anything that
    // would put lava, gravel or a wet cell in our face once the rock is out, or that we can't fix with a pickaxe and a block
    public static Survey survey(Layout l, Terrain t) {
        return survey(l, t, true);
    }

    private static Survey survey(Layout l, Terrain t, boolean withSupply) {
        String quickNo = quickWhy(l, t);
        if (quickNo != null) {
            return no(quickNo);
        }
        int fills = 0;
        for (int u = 0; u <= 3; u++) {
            // air under the shore is a cave under our feet, nothing to fill there
            if (!m(l, t, u, -1, 1).solid()) {
                return no("air_under_shore");
            }
            Mat back = m(l, t, u, 0, 2);
            if (back == Mat.AIR) {
                // a plug over air would just be a hole in the shore with a block missing under it
                if (!m(l, t, u, -1, 2).solid()) {
                    return no("back_hole");
                }
                fills++;
            } else if (!back.solid()) {
                return no("back_bad");
            }
            if (m(l, t, u, 0, 1) == Mat.LAVA) {
                fills++;
            }
        }
        if (fills > MAX_FILLS) {
            return no("fills_cap");
        }
        for (int u = -2; u <= 5; u++) {
            for (int d = -3; d <= 3; d++) {
                for (int y = -2; y <= 6; y++) {
                    Mat now = m(l, t, u, y, d);
                    if (now.wet() || now == Mat.PORTAL) {
                        return no("wet_box");
                    }
                }
            }
        }
        for (int[] c : ROOF) {
            Mat now = m(l, t, c);
            // rock can stay, this can't: gravel lands on us in the lane and lava lands on everything
            if (now == Mat.FALLS || now.lava()) {
                return no("roof_gravel_or_lava");
            }
            if (now == Mat.AIR && hangsOver(l, t, c)) {
                return no("hanging_gravel");
            }
        }
        int mines = 0;
        int prepMines = 0;
        int frontMines = 0;
        for (int[] c : NEED_AIR) {
            Mat now = m(l, t, c);
            if (now == Mat.AIR) {
                if (hangsOver(l, t, c)) {
                    return no("hanging_gravel");
                }
                continue;
            }
            // lava, gravel, obsidian, bedrock in there: not ours to clear
            if (now != Mat.SOLID) {
                return no("need_air_is_" + now.name().toLowerCase(Locale.ROOT));
            }
            if (c[0] == ARM[0] && c[1] == ARM[1] && c[2] == ARM[2]) {
                continue;
            }
            // a block sitting on a source: the lava has to stay put and we don't dig at d <= 0 before the base
            if (c[1] == 1 && c[2] <= 0) {
                return no("block_on_source");
            }
            if (++mines > MAX_MINES) {
                return no("mines_cap");
            }
            if (c[2] >= 1) {
                prepMines++;
            } else if (++frontMines > MAX_FRONT_MINES) {
                return no("front_cap");
            }
            for (int[] n : NEIGHBOURS) {
                if (m(l, t, c[0] + n[0], c[1] + n[1], c[2] + n[2]).lava()) {
                    return no("mine_by_lava");
                }
            }
            for (int y = c[1] + 1; y <= FALL_CHECK_TOP; y++) {
                if (m(l, t, c[0], y, c[2]) == Mat.FALLS) {
                    return no("gravel_over");
                }
            }
        }
        if (withSupply && supply(l, t) < SUPPLY_NEEDED) {
            return no("supply");
        }
        return new Survey(true, mines, prepMines, fills, null);
    }

    // the lava sources we could scoop (the same ones scoopSpot takes) in the layer of the row around it, which is the stretch the
    // build looks at, counted from the world so a far lake or a short tracker list changes nothing
    static int supply(Layout l, Terrain t) {
        P mid = l.cell(1, 0, 0);
        int n = 0;
        for (int x = mid.x() - SCAN; x <= mid.x() + SCAN; x++) {
            for (int z = mid.z() - SCAN; z <= mid.z() + SCAN; z++) {
                if (eligible(t, l, new P(x, mid.y(), z))) {
                    n++;
                }
            }
        }
        return n;
    }

    // layouts worth a full look, nearest first. sources = lava source cells. tally gets one count per layout that didn't make it,
    // under quickWhy's reason, this is the only place those are seen
    public static List<Layout> candidates(Terrain t, List<P> sources, int px, int pz, Map<String, Integer> tally) {
        Set<Layout> seen = new HashSet<>();
        List<Layout> out = new ArrayList<>();
        int[][] axes = {{1, 0}, {0, 1}};
        for (P s : sources) {
            for (int[] a : axes) {
                for (int k = 0; k < 4; k++) {
                    for (int sign = -1; sign <= 1; sign += 2) {
                        Layout l = new Layout(s.x() - k * a[0], s.y(), s.z() - k * a[1], a[0], a[1], -a[1] * sign, a[0] * sign);
                        if (seen.add(l)) {
                            String why = quickWhy(l, t);
                            if (why == null) {
                                out.add(l);
                            } else {
                                tally.merge(why, 1, Integer::sum);
                            }
                        }
                    }
                }
            }
        }
        out.sort(Comparator.comparingDouble(l -> {
            P c = l.cell(1, 0, 0);
            return sq(c.x() - px) + sq(c.z() - pz);
        }));
        return out;
    }

    private static double sq(double v) {
        return v * v;
    }

    // ---- scooping ----

    // how far from the eyes we scoop. the bucket reaches 4.5, and the bot is never exactly where it was planned from
    private static final double SCOOP_REACH = 4.0;
    private static final double EYES = 1.62;

    // the shore cell beside a source, or null. the click is then a short straight one and nothing we built is in the way
    static P standFor(Terrain t, P p) {
        if (t.at(p.x(), p.y(), p.z()) != Mat.LAVA || t.at(p.x(), p.y() + 1, p.z()) != Mat.AIR) {
            return null;
        }
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] dir : dirs) {
            int sx = p.x() + dir[0];
            int sz = p.z() + dir[1];
            if (t.at(sx, p.y(), sz).solid() && t.at(sx, p.y() + 1, sz) == Mat.AIR && t.at(sx, p.y() + 2, sz) == Mat.AIR) {
                return new P(sx, p.y() + 1, sz);
            }
        }
        return null;
    }

    // a lava source we may take: open on top, and not one of the cells the frame is built on (the row and the platform in
    // front of it) or a notch in the shore behind it, that one is a block soon. once the base is set the lava around them has
    // nothing to do with the build
    private static boolean eligible(Terrain t, Layout l, P p) {
        if (t.at(p.x(), p.y(), p.z()) != Mat.LAVA || t.at(p.x(), p.y() + 1, p.z()) != Mat.AIR) {
            return false;
        }
        if (l != null) {
            for (int u = 0; u <= 3; u++) {
                for (int d = -1; d <= 1; d++) {
                    if (l.cell(u, 0, d).equals(p)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    // a straight line from the eyes to the middle of the cell that no solid block is on
    private static boolean seen(Terrain t, double ex, double ey, double ez, P target) {
        double tx = target.x() + 0.5;
        double ty = target.y() + 0.5;
        double tz = target.z() + 0.5;
        double len = Math.sqrt(sq(tx - ex) + sq(ty - ey) + sq(tz - ez));
        int steps = (int) Math.ceil(len / 0.2);
        for (int i = 0; i < steps; i++) {
            double f = (double) i / steps;
            int x = (int) Math.floor(ex + (tx - ex) * f);
            int y = (int) Math.floor(ey + (ty - ey) * f);
            int z = (int) Math.floor(ez + (tz - ez) * f);
            if (x == target.x() && y == target.y() && z == target.z()) {
                return true;
            }
            if (t.at(x, y, z).solid()) {
                return false;
            }
        }
        return true;
    }

    // where to take the next bucket of lava. first from right where we stand, nothing to walk. else from one of the platform
    // stands the casts use (we are going there anyway), else from the shore cell beside a source, whichever is the shortest
    // walk. (ex, ey, ez) = our eyes, skip = sources that did not work from where we stood. stand null = scoop in place
    public static Scoop scoopSpot(Terrain t, Iterable<P> lava, Layout l, double ex, double ey, double ez, Set<P> skip) {
        List<P> sources = new ArrayList<>();
        for (P p : lava) {
            if (eligible(t, l, p) && !skip.contains(p)) {
                sources.add(p);
            }
        }
        P near = null;
        double nearD = SCOOP_REACH * SCOOP_REACH;
        for (P p : sources) {
            double d = sq(p.x() + 0.5 - ex) + sq(p.y() + 0.5 - ey) + sq(p.z() + 0.5 - ez);
            if (d <= nearD && seen(t, ex, ey, ez, p)) {
                nearD = d;
                near = p;
            }
        }
        if (near != null) {
            return new Scoop(near, null);
        }
        List<P> stands = new ArrayList<>();
        if (l != null) {
            for (int[] s : new int[][]{LEFT_STAND, RIGHT_STAND}) {
                P c = l.cell(s);
                if (t.at(c.x(), c.y(), c.z()) == Mat.AIR && t.at(c.x(), c.y() + 1, c.z()) == Mat.AIR && t.at(c.x(), c.y() - 1, c.z()).solid()) {
                    stands.add(c);
                }
            }
        }
        Scoop best = null;
        double bestCost = Double.MAX_VALUE;
        for (P p : sources) {
            for (P s : stands) {
                double sx = s.x() + 0.5;
                double sy = s.y() + EYES;
                double sz = s.z() + 0.5;
                double reach = sq(p.x() + 0.5 - sx) + sq(p.y() + 0.5 - sy) + sq(p.z() + 0.5 - sz);
                double cost = sq(sx - ex) + sq(sz - ez);
                if (reach <= SCOOP_REACH * SCOOP_REACH && cost < bestCost && seen(t, sx, sy, sz, p)) {
                    bestCost = cost;
                    best = new Scoop(p, s);
                }
            }
            P shore = standFor(t, p);
            if (shore != null) {
                // the shore stand only wins when it is clearly the shorter walk
                double cost = sq(shore.x() + 0.5 - ex) + sq(shore.z() + 0.5 - ez) + 1.0;
                if (cost < bestCost) {
                    bestCost = cost;
                    best = new Scoop(p, shore);
                }
            }
        }
        return best;
    }

    // ---- what to do next ----

    // water above the cell or beside it (any of the four sides)
    public static boolean wet(Layout l, Terrain t, int[] c) {
        int u = c[0];
        int y = c[1];
        int d = c[2];
        return m(l, t, u, y + 1, d).wet() || m(l, t, u + 1, y, d).wet() || m(l, t, u - 1, y, d).wet()
                || m(l, t, u, y, d + 1).wet() || m(l, t, u, y, d - 1).wet();
    }

    // the casts this one has to come after are all obsidian
    private static boolean madeBefore(Layout l, Terrain t, Round round, Cast c) {
        for (String name : c.after()) {
            for (Cast other : round.casts()) {
                if (other.name().equals(name) && m(l, t, other.t()) != Mat.OBSIDIAN) {
                    return false;
                }
            }
        }
        return true;
    }

    static boolean roundDone(Layout l, Terrain t, Round round) {
        for (Cast c : round.casts()) {
            if (m(l, t, c.t()) != Mat.OBSIDIAN) {
                return false;
            }
        }
        return true;
    }

    public static boolean allMade(Layout l, Terrain t) {
        for (Round r : ROUNDS) {
            if (!roundDone(l, t, r)) {
                return false;
            }
        }
        return true;
    }

    private static boolean anyMade(Layout l, Terrain t) {
        for (Round round : ROUNDS) {
            for (Cast c : round.casts()) {
                if (m(l, t, c.t()) == Mat.OBSIDIAN) {
                    return true;
                }
            }
        }
        return false;
    }

    // corners set, the two cells in front of the pit are solid to stand on, the pit has no lava or water source in it
    static boolean baseDone(Layout l, Terrain t) {
        if (m(l, t, CORNER_LEFT) != Mat.OBSIDIAN || m(l, t, CORNER_RIGHT) != Mat.OBSIDIAN) {
            return false;
        }
        if (!m(l, t, PLATFORM_LEFT).solid() || !m(l, t, PLATFORM_RIGHT).solid()) {
            return false;
        }
        for (int u = 1; u <= 2; u++) {
            Mat now = m(l, t, u, 0, 0);
            if (now.lava() || now == Mat.WATER) {
                return false;
            }
        }
        return true;
    }

    private static Step stuck(String why) {
        return new Step(Kind.STUCK, null, null, 0, 0, 0, null, why);
    }

    private static Step wait(String why) {
        return new Step(Kind.WAIT, null, null, 0, 0, 0, null, why);
    }

    private static Step click(Layout l, Kind kind, int[] target, int[] support, int[] stand, String why) {
        int[] f = l.dir(target[0] - support[0], target[1] - support[1], target[2] - support[2]);
        return new Step(kind, l.cell(target), l.cell(support), f[0], f[1], f[2], stand == null ? null : l.cell(stand), why);
    }

    private static Step place(Layout l, int[] cell, String why) {
        return new Step(Kind.PLACE_BLOCK, l.cell(cell), null, 0, 0, 0, null, why);
    }

    // the cells that have to stay empty: the frame plane above the pit and the platform lane in front of it
    public static List<int[]> keepClear() {
        List<int[]> cells = new ArrayList<>();
        for (int u = 0; u <= 3; u++) {
            for (int y = 1; y <= 4; y++) {
                boolean arm = u == ARM[0] && y == ARM[1];
                boolean wall = false;
                for (int[] w : WALLS) {
                    wall |= w[0] == u && w[1] == y && w[2] == 1;
                }
                if (!arm) {
                    cells.add(new int[]{u, y, 0});
                }
                // behind the frame, where the stairs and the walls are, is ours too
                if (!wall) {
                    cells.add(new int[]{u, y, 1});
                }
            }
        }
        for (int u = 1; u <= 2; u++) {
            for (int y = 1; y <= 4; y++) {
                cells.add(new int[]{u, y, -1});
            }
        }
        // and the row behind the walls, the bot climbs and stands in it
        for (int u = 0; u <= 3; u++) {
            for (int y = 1; y <= 3; y++) {
                cells.add(new int[]{u, y, 2});
            }
        }
        return cells;
    }

    // anything solid and not ours in those, and in the pit
    // the roof stays in keepClear so nobody puts a block up there or digs it during the prep, it just isn't junk: it's rock over
    // our heads that was never in the way
    private static int[] junk(Layout l, Terrain t) {
        for (int[] c : keepClear()) {
            if (c[1] == 4 && c[2] <= 0) {
                continue;
            }
            Mat now = m(l, t, c);
            if (now == Mat.SOLID || now == Mat.FALLS) {
                return c;
            }
        }
        for (int u = 1; u <= 2; u++) {
            if (m(l, t, u, 0, 0) == Mat.SOLID) {
                return new int[]{u, 0, 0};
            }
        }
        return null;
    }

    // the pit has not been touched: no placeholder, no water, no corner. the prep only happens then
    private static boolean pitUntouched(Layout l, Terrain t) {
        return m(l, t, PLACEHOLDER) == Mat.LAVA && m(l, t, BASE_WATER) == Mat.LAVA
                && m(l, t, CORNER_LEFT) != Mat.OBSIDIAN && m(l, t, CORNER_RIGHT) != Mat.OBSIDIAN;
    }

    // the work a rough site wants before the base: the rock behind the frame out, top down so nothing is dug from under
    // its own feet, then the holes in the backing plugged, then the notches in the shore (we stand on the backing for those).
    // nothing at d <= 0 is touched, that is the lava. null = ready.
    // no supply check, the lava around does not change while we dig
    private static Step prep(Layout l, Terrain t, Inv inv) {
        Survey s = survey(l, t, false);
        if (!s.ok()) {
            return new Step(Kind.ABANDON, null, null, 0, 0, 0, null, "the spot went bad");
        }
        if (s.prepMines() > 0) {
            for (int y = 4; y >= 1; y--) {
                // the row behind the walls only counts up to y=3, the survey never looked above that
                for (int d = 1; d <= (y <= 3 ? 2 : 1); d++) {
                    for (int u = 0; u <= 3; u++) {
                        if (m(l, t, u, y, d) == Mat.SOLID) {
                            return new Step(Kind.BREAK, l.cell(u, y, d), null, 0, 0, 0, null, PREP_CLEAR);
                        }
                    }
                }
            }
        }
        if (s.fills() > 0) {
            for (int u = 0; u <= 3; u++) {
                if (m(l, t, u, 0, 2) == Mat.AIR) {
                    if (inv.blocks() < 1) {
                        return stuck("no block to plug the shore");
                    }
                    return new Step(Kind.PLACE_BLOCK, l.cell(u, 0, 2), null, 0, 0, 0, null, PREP_FILL);
                }
            }
            for (int u = 0; u <= 3; u++) {
                if (m(l, t, u, 0, 1) == Mat.LAVA) {
                    if (inv.blocks() < 1) {
                        return stuck("no block to plug the shore");
                    }
                    // not the builder, it would stroll along the lava's edge looking for an angle. same click as the placeholder
                    return click(l, Kind.PLACE_STACK, new int[]{u, 0, 1}, new int[]{u, -1, 1}, new int[]{u, 1, 2}, PREP_FILL);
                }
            }
        }
        return null;
    }

    // everything before the first pour is done: base, walls, floor
    private static boolean readyToPour(Layout l, Terrain t) {
        if (!baseDone(l, t)) {
            return false;
        }
        for (int[] w : WALLS) {
            if (!m(l, t, w).solid()) {
                return false;
            }
        }
        if (!m(l, t, ARM).solid()) {
            return false;
        }
        for (int[] b : BOTTOM) {
            if (m(l, t, b) == Mat.SOLID) {
                return false;
            }
        }
        return true;
    }

    // lava only when the next thing needs it and the bag has none. one bucket at a time: the other stays free for the water.
    // the base needs no lava, so the first action is the placeholder
    private static boolean scoopWanted(Inv inv, boolean needsLava) {
        return needsLava && inv.hasSpot() && inv.empty() >= 1 && inv.lava() == 0;
    }

    // the base, one step of it: the throwaway block into the lava, the water beside it, the block out, the water back
    private static Step base(Layout l, Terrain t, Inv inv) {
        Mat water = m(l, t, BASE_WATER);
        // the throwaway can be sand or gravel, and that is a block too
        boolean block = m(l, t, PLACEHOLDER).solid();
        if (water == Mat.WATER) {
            if (block) {
                if (m(l, t, CORNER_RIGHT) == Mat.OBSIDIAN && !m(l, t, PLATFORM_RIGHT).lava()) {
                    return new Step(Kind.BREAK, l.cell(PLACEHOLDER), null, 0, 0, 0, l.cell(SHORE_LEFT), "placeholder");
                }
                return wait("the right corner is cooking");
            }
            if (m(l, t, CORNER_LEFT) == Mat.OBSIDIAN && !m(l, t, PLATFORM_LEFT).lava() && !m(l, t, PLACEHOLDER).lava()) {
                return click(l, Kind.WATER_SCOOP, BASE_WATER, PLACEHOLDER, SHORE_RIGHT, "base water");
            }
            return wait("the left corner is cooking");
        }
        if (water == Mat.LAVA) {
            if (block) {
                if (!inv.water()) {
                    return stuck("no water bucket");
                }
                return click(l, Kind.WATER_PLACE, BASE_WATER, PLACEHOLDER, SHORE_RIGHT, "base water");
            }
            if (inv.blocks() < 1) {
                return stuck("no block for the placeholder");
            }
            return click(l, Kind.PLACE_STACK, PLACEHOLDER, new int[]{1, -1, 0}, SHORE_LEFT, "placeholder");
        }
        return stuck("the pit holds " + water + " before the base");
    }

    // what a build that gave up leaves behind that has to come back: water sources and lava that never cemented. null when
    // there is nothing, or no empty bucket to take it with
    public static Step cleanup(Layout l, Terrain t, int empty) {
        if (empty < 1) {
            return null;
        }
        // lava first, it is the one that runs and the one the cast fallback can't live next to
        for (Round r : ROUNDS) {
            for (Cast c : r.casts()) {
                if (m(l, t, c.t()) == Mat.LAVA) {
                    return click(l, Kind.LAVA_UNDO, c.t(), c.support(), c.stand(), "loose lava");
                }
            }
        }
        if (m(l, t, BASE_WATER) == Mat.WATER) {
            return click(l, Kind.WATER_SCOOP, BASE_WATER, PLACEHOLDER, SHORE_RIGHT, "loose water");
        }
        for (Round r : ROUNDS) {
            if (m(l, t, r.source()) == Mat.WATER) {
                return click(l, Kind.WATER_SCOOP, r.source(), r.sourceSupport(), r.scoopStand(), "loose water");
            }
        }
        return null;
    }

    public static Step next(Layout l, Terrain t, Inv inv) {
        for (int[] c : INTERIOR) {
            if (m(l, t, c) == Mat.PORTAL) {
                return new Step(Kind.DONE, null, null, 0, 0, 0, null, null);
            }
        }
        boolean allMade = allMade(l, t);
        boolean sourceUp = false;
        for (Round r : ROUNDS) {
            sourceUp |= m(l, t, r.source()) == Mat.WATER;
        }
        boolean casting = sourceUp || anyMade(l, t);
        // lava that went in with no water next to it runs, so it comes back before anything else, the bot is standing next to it
        if (!allMade && inv.empty() >= 1) {
            for (Round r : ROUNDS) {
                for (Cast c : r.casts()) {
                    if (m(l, t, c.t()) == Mat.LAVA) {
                        return click(l, Kind.LAVA_UNDO, c.t(), c.support(), c.stand(), c.name());
                    }
                }
            }
        }
        if (!allMade && scoopWanted(inv, casting || readyToPour(l, t))) {
            return new Step(Kind.SCOOP, null, null, 0, 0, 0, null, "lava");
        }
        if (!allMade && !baseDone(l, t) && !casting) {
            if (pitUntouched(l, t)) {
                Step ready = prep(l, t, inv);
                if (ready != null) {
                    return ready;
                }
            }
            return base(l, t, inv);
        }
        if (!allMade) {
            for (int[] w : WALLS) {
                if (!m(l, t, w).solid()) {
                    if (inv.blocks() < 1) {
                        return stuck("no blocks for the walls");
                    }
                    return place(l, w, "wall");
                }
            }
            // the builder scaffolds in the frame while it climbs for the walls, so the clearing waits for them to be done. and it
            // goes before the arm: that is clicked from the platform lane, a tree in the lane or the frame plane is in the way
            int[] bad = junk(l, t);
            if (bad != null) {
                return new Step(Kind.BREAK, l.cell(bad), null, 0, 0, 0, null, "in the way");
            }
            if (!m(l, t, ARM).solid()) {
                if (inv.blocks() < 1) {
                    return stuck("no block for the arm");
                }
                return click(l, Kind.PLACE_STACK, ARM, ARM_SUPPORT, RIGHT_STAND, "arm");
            }
            if (!m(l, t, PLATFORM_LEFT).solid() || !m(l, t, PLATFORM_RIGHT).solid()) {
                return stuck("the platform is gone");
            }
            // the floor under the pit comes out before any water pours in, so it can be filled with lava under the water
            if (!casting) {
                for (int[] b : BOTTOM) {
                    if (m(l, t, b) == Mat.SOLID) {
                        return new Step(Kind.BREAK, l.cell(b), null, 0, 0, 0, l.cell(LEFT_STAND), "floor");
                    }
                }
            }
            Round round = null;
            for (Round r : ROUNDS) {
                if (round == null && !roundDone(l, t, r)) {
                    round = r;
                }
            }
            if (m(l, t, round.source()) != Mat.WATER) {
                if (!inv.water()) {
                    return stuck("no water bucket");
                }
                if (!m(l, t, round.sourceSupport()).solid()) {
                    return stuck("nothing to pour round " + round.name() + " against");
                }
                return click(l, Kind.WATER_PLACE, round.source(), round.sourceSupport(), round.stand(), "source " + round.name());
            }
            // the first cast in the list that can go now: its click block is there and water is next to it. the ones whose water
            // has not shown up yet wait, and the order only holds where it matters (see the round)
            String waiting = null;
            for (Cast c : round.casts()) {
                Mat now = m(l, t, c.t());
                if (now == Mat.OBSIDIAN) {
                    continue;
                }
                if (now == Mat.LAVA) {
                    // the lava went in with no water next to it. take it back before it runs
                    if (inv.empty() < 1) {
                        return stuck(c.name() + " holds lava and there is no bucket for it");
                    }
                    return click(l, Kind.LAVA_UNDO, c.t(), c.support(), c.stand(), c.name());
                }
                if (now != Mat.AIR && now != Mat.WATER_FLOW) {
                    return stuck(c.name() + " holds " + now);
                }
                if (!madeBefore(l, t, round, c)) {
                    if (waiting == null) {
                        waiting = c.name() + " goes after " + String.join(", ", c.after());
                    }
                    continue;
                }
                if (!m(l, t, c.support()).solid()) {
                    if (waiting == null) {
                        waiting = c.name() + " has nothing to click yet";
                    }
                    continue;
                }
                if (!wet(l, t, c.t())) {
                    if (waiting == null) {
                        waiting = c.name() + " is dry";
                    }
                    continue;
                }
                if (inv.lava() < 1) {
                    return stuck("out of lava and nowhere to scoop it");
                }
                return click(l, Kind.LAVA_PLACE, c.t(), c.support(), c.stand(), c.name());
            }
            if (waiting != null) {
                return wait(waiting);
            }
        }
        for (Round r : ROUNDS) {
            if (m(l, t, r.source()) == Mat.WATER) {
                if (inv.empty() < 1) {
                    return stuck("no empty bucket for the water");
                }
                return click(l, Kind.WATER_SCOOP, r.source(), r.sourceSupport(), r.scoopStand(), "last source");
            }
        }
        for (int[] c : INTERIOR) {
            if (m(l, t, c).wet()) {
                return wait("water still draining");
            }
        }
        P bottom = l.cell(1, -1, 0);
        return new Step(Kind.LIGHT, l.cell(1, 0, 0), bottom, 0, 1, 0, null, null);
    }
}
