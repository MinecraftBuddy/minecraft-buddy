package adris.altoclef.tasks.speedrun.gamer.portal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// pure: the lava pool portal. a 4 wide lava row is the bottom of the frame, a solid 2x4 slab of throwaway blocks stands
// where the opening goes, and the obsidian is cast around that slab one cell at a time (lava in, water on top, scoop
// the water back). no world access in here, everything reads a Terrain, so the whole build can be run in a test
//
// coordinates are (u, h, d): u runs along the 4 wide row, h is up (0 = the lava surface), d is toward the shore we
// stand on (d=0 is the frame plane, d=1 the first shore row). Layout turns those into world cells.
//
//   side view, looking along u from the left (d to the right, shore on the right)
//
//      h=5
//      h=4   F  .  .
//      h=3   F  .  .
//      h=2   F  .  .       F = frame cell of the left column
//      h=1   F  .  P       P = pad we stand on for the top row
//      h=0   O  L  S       O = base obsidian, L = lava, S = shore
//           d=0 -1  +1
public final class PortalMold {
    public enum Mat {
        AIR, STONE, OBSIDIAN, LAVA, LAVA_FLOW, WATER, WATER_FLOW, PORTAL;

        public boolean solid() {
            return this == STONE || this == OBSIDIAN;
        }
    }

    public interface Terrain {
        Mat at(int x, int y, int z);
    }

    public record P(int x, int y, int z) {
        P add(int dx, int dy, int dz) {
            return new P(x + dx, y + dy, z + dz);
        }
    }

    // what is in the bags right now: a lava bucket, a water bucket, and how many empty buckets
    public record Inv(boolean lava, boolean water, int empty) {
    }

    public enum Kind {
        // water on the ground upwind of the pool so it washes over the base cells and turns them to obsidian
        SKIN_PLACE, SKIN_WAIT, SKIN_SCOOP,
        GUIDE_PLACE, GUIDE_BREAK,
        LAVA_SCOOP, LAVA_PLACE, LAVA_UNDO,
        WATER_PLACE, WATER_SCOOP,
        LIGHT, WAIT, STUCK, DONE
    }

    // cell = what the step is about, support + face = the block we click and which side of it (as a world offset),
    // stand = where we want our feet, cast = which obsidian cell this is for (the fallback casts it the slow way)
    public record Step(Kind kind, P cell, P support, int fx, int fy, int fz, P stand, Cast cast, String why) {
        public String toString() {
            return kind + (cell == null ? "" : " " + cell) + (why == null ? "" : " (" + why + ")");
        }
    }

    // one cast: lava goes into t, water goes into w which is above t or beside it. the click is "this face of that
    // block", offsets are in (u, h, d)
    public static final class Cast {
        public final String name;
        public final int[] t;
        public final int[] w;
        public final int[] lavaSupport;
        public final int[] waterSupport;
        public final int[] stand;
        public final boolean top;
        // the shore block we stand on to see the corner (top row only)
        public final int[] pad;

        Cast(String name, int[] t, int[] w, int[] lavaSupport, int[] waterSupport, int[] stand, boolean top, int[] pad) {
            this.name = name;
            this.t = t;
            this.w = w;
            this.lavaSupport = lavaSupport;
            this.waterSupport = waterSupport;
            this.stand = stand;
            this.top = top;
            this.pad = pad;
        }
    }

    // where the slab goes, bottom up. the top two are frame cells too, they get swapped for obsidian at the end
    public static final int[][] SLAB = {{1, 1, 0}, {2, 1, 0}, {1, 2, 0}, {2, 2, 0}, {1, 3, 0}, {2, 3, 0}, {1, 4, 0}, {2, 4, 0}};
    public static final int[][] INTERIOR = {{1, 1, 0}, {2, 1, 0}, {1, 2, 0}, {2, 2, 0}, {1, 3, 0}, {2, 3, 0}};
    public static final int[][] BASE = {{1, 0, 0}, {2, 0, 0}};
    public static final int[][] FRAME = {
            {1, 0, 0}, {2, 0, 0},
            {0, 1, 0}, {0, 2, 0}, {0, 3, 0},
            {3, 1, 0}, {3, 2, 0}, {3, 3, 0},
            {1, 4, 0}, {2, 4, 0}};
    public static final List<Cast> CASTS = casts();

    // the longest we babysit the skin water, in ticks. it is taken back sooner than this the moment the base is set
    public static final int SKIN_MAX_TICKS = 110;
    // lava we want to be able to scoop after the skin is done: 6 side casts and 2 top ones, plus a couple spare
    public static final int SCOOP_NEEDED = 10;

    private PortalMold() {
    }

    private static List<Cast> casts() {
        List<Cast> out = new ArrayList<>();
        // the side casts stand two rows back. one row back is right beside t, baritone doesn't centre us, and with a 0.3
        // half width our side pokes into t, so the lava went into the bot instead of the frame. from d=2 every click is
        // still the face centre within 3.2 blocks, the left3/right3 water just needs the cell over our head (see roomForIt)
        for (int h = 1; h <= 3; h++) {
            out.add(new Cast("left" + h, new int[]{0, h, 0}, new int[]{0, h + 1, 0}, new int[]{1, h, 0}, new int[]{1, h + 1, 0},
                    new int[]{0, 1, 2}, false, null));
        }
        for (int h = 1; h <= 3; h++) {
            out.add(new Cast("right" + h, new int[]{3, h, 0}, new int[]{3, h + 1, 0}, new int[]{2, h, 0}, new int[]{2, h + 1, 0},
                    new int[]{3, 1, 2}, false, null));
        }
        // top row: the water goes in the corner next to the cell (clicking the guide that is still in the cell), then the
        // guide comes out and the lava goes in through the other guide's side. the pad lifts our eyes over the column
        out.add(new Cast("topleft", new int[]{1, 4, 0}, new int[]{0, 4, 0}, new int[]{2, 4, 0}, new int[]{1, 4, 0},
                new int[]{0, 2, 1}, true, new int[]{0, 1, 1}));
        out.add(new Cast("topright", new int[]{2, 4, 0}, new int[]{3, 4, 0}, new int[]{1, 4, 0}, new int[]{2, 4, 0},
                new int[]{3, 2, 1}, true, new int[]{3, 1, 1}));
        return List.copyOf(out);
    }

    // turns (u, h, d) into world cells. (ax, az) is the unit step along u, (nx, nz) the unit step toward the shore
    public record Layout(int ox, int oy, int oz, int ax, int az, int nx, int nz) {
        public P cell(int u, int h, int d) {
            return new P(ox + u * ax + d * nx, oy + h, oz + u * az + d * nz);
        }

        public P cell(int[] c) {
            return cell(c[0], c[1], c[2]);
        }

        // the way back from a world cell to (u, h, d). the two axes are unit and square to each other, so it's just dots
        public int[] local(P p) {
            int dx = p.x() - ox;
            int dz = p.z() - oz;
            return new int[]{dx * ax + dz * az, p.y() - oy, dx * nx + dz * nz};
        }

        // a face given as a (u, h, d) offset, in world offsets
        public int[] face(int[] from, int[] to) {
            int du = to[0] - from[0];
            int dh = to[1] - from[1];
            int dd = to[2] - from[2];
            return new int[]{du * ax + dd * nx, dh, du * az + dd * nz};
        }
    }

    // ---- reading the world ----

    private static Mat m(Layout l, Terrain t, int[] c) {
        P p = l.cell(c);
        return t.at(p.x(), p.y(), p.z());
    }

    private static Mat m(Layout l, Terrain t, int u, int h, int d) {
        P p = l.cell(u, h, d);
        return t.at(p.x(), p.y(), p.z());
    }

    private static boolean sameCell(int[] a, int[] b) {
        return a[0] == b[0] && a[1] == b[1] && a[2] == b[2];
    }

    // ---- the pool row finder ----

    // layouts worth a full look, nearest to (px, pz) first. seeds are lava source cells to try as part of the row. this
    // is only the cheap part (the row, the box, the shore), fits() is the expensive rest
    public static List<Layout> candidates(Terrain t, Iterable<P> seeds, int px, int pz) {
        Set<Layout> seen = new HashSet<>();
        List<Layout> good = new ArrayList<>();
        int[][] axes = {{1, 0}, {0, 1}};
        for (P s : seeds) {
            for (int[] a : axes) {
                for (int k = 0; k < 4; k++) {
                    for (int sign = -1; sign <= 1; sign += 2) {
                        int nx = -a[1] * sign;
                        int nz = a[0] * sign;
                        Layout l = new Layout(s.x() - k * a[0], s.y(), s.z() - k * a[1], a[0], a[1], nx, nz);
                        if (seen.add(l) && roomForIt(l, t)) {
                            good.add(l);
                        }
                    }
                }
            }
        }
        good.sort(Comparator.comparingDouble(l -> {
            P c = l.cell(1, 0, 0);
            double dx = c.x() - px;
            double dz = c.z() - pz;
            return dx * dx + dz * dz;
        }));
        return good;
    }

    // layouts that fit, nearest first. the slow way, tests and one-offs use it, the task walks candidates() a few a tick
    public static List<Layout> find(Terrain t, Iterable<P> seeds, int px, int pz, int max) {
        List<Layout> good = new ArrayList<>();
        for (Layout l : candidates(t, seeds, px, pz)) {
            if (fits(l, t)) {
                good.add(l);
                if (good.size() >= max) {
                    break;
                }
            }
        }
        return good;
    }

    // the row, the empty box over it and the shore row. the cheap checks
    private static boolean roomForIt(Layout l, Terrain t) {
        for (int u = 0; u <= 3; u++) {
            if (m(l, t, u, 0, 0) != Mat.LAVA || m(l, t, u, 1, 0) != Mat.AIR) {
                return false;
            }
        }
        // the frame box has to be empty, the slab and the cast need the room
        for (int u = 0; u <= 3; u++) {
            for (int h = 1; h <= 4; h++) {
                if (m(l, t, u, h, 0) != Mat.AIR) {
                    return false;
                }
            }
        }
        // the shore row we stand on, and head room over it
        for (int u = -2; u <= 5; u++) {
            if (!m(l, t, u, 0, 1).solid() || m(l, t, u, 1, 1) != Mat.AIR || m(l, t, u, 2, 1) != Mat.AIR) {
                return false;
            }
        }
        // the side cast stands one row further back, and from there the upper clicks climb: the left3/right3 water goes
        // through the cell over our head and then h=3 and h=4 of the shore row. a cave roof there and the click never lands
        for (int u = 0; u <= 3; u += 3) {
            if (!m(l, t, u, 0, 2).solid() || m(l, t, u, 1, 2) != Mat.AIR || m(l, t, u, 2, 2) != Mat.AIR || m(l, t, u, 3, 2) != Mat.AIR
                    || m(l, t, u, 3, 1) != Mat.AIR || m(l, t, u, 4, 1) != Mat.AIR) {
                return false;
            }
        }
        return true;
    }

    // everything the build needs to be true before it is worth walking over
    public static boolean fits(Layout l, Terrain t) {
        if (!roomForIt(l, t)) {
            return false;
        }
        // water or a flowing lava edge anywhere near means somebody else's fluid is about to join the party
        for (int u = -9; u <= 12; u++) {
            for (int d = -4; d <= 10; d++) {
                for (int h = 0; h <= 6; h++) {
                    Mat x = m(l, t, u, h, d);
                    if (x == Mat.WATER || x == Mat.WATER_FLOW) {
                        return false;
                    }
                    if (x == Mat.LAVA_FLOW && h <= 1 && u >= -2 && u <= 5 && d <= 1) {
                        return false;
                    }
                }
            }
        }
        List<P> skin = skinSources(l, t);
        if (skin.isEmpty()) {
            return false;
        }
        return scoopCells(l, t, skinObsidian(l, t, skin.get(0))).size() >= SCOOP_NEEDED;
    }

    // ---- the skin: water on the shore that runs over the pool and cements the two base cells ----

    private static WaterFlow.World flowWorld(Terrain t) {
        return (x, y, z) -> switch (t.at(x, y, z)) {
            case AIR, WATER_FLOW -> WaterFlow.Kind.AIR;
            case LAVA -> WaterFlow.Kind.LAVA;
            case WATER -> WaterFlow.Kind.WATER;
            default -> WaterFlow.Kind.SOLID;
        };
    }

    // where to put the water source so the base gets wet and as little else as possible. best first
    public static List<P> skinSources(Layout l, Terrain t) {
        WaterFlow.World w = flowWorld(t);
        P b1 = l.cell(1, 0, 0);
        P b2 = l.cell(2, 0, 0);
        List<P> ranked = new ArrayList<>();
        List<Integer> cost = new ArrayList<>();
        for (int d = 1; d <= 9; d++) {
            for (int u = -8; u <= 11; u++) {
                P s = l.cell(u, 1, d);
                if (t.at(s.x(), s.y(), s.z()) != Mat.AIR || !t.at(s.x(), s.y() - 1, s.z()).solid()) {
                    continue;
                }
                WaterFlow.Result r = WaterFlow.run(w, new int[][]{{s.x(), s.y(), s.z()}});
                if (r.hitBudget || !r.isObsidian(b1.x(), b1.y(), b1.z()) || !r.isObsidian(b2.x(), b2.y(), b2.z())) {
                    continue;
                }
                // fewer converted cells beats everything, then the shorter walk to the frame
                int c = r.obsidian.size() * 100 + Math.abs(u - 1) + d;
                int at = 0;
                while (at < cost.size() && cost.get(at) <= c) {
                    at++;
                }
                cost.add(at, c);
                ranked.add(at, s);
            }
        }
        return ranked;
    }

    // the lava cells that skin source turns to obsidian
    public static Set<P> skinObsidian(Layout l, Terrain t, P source) {
        WaterFlow.Result r = WaterFlow.run(flowWorld(t), new int[][]{{source.x(), source.y(), source.z()}});
        Set<P> out = new HashSet<>();
        for (long k : r.obsidian) {
            out.add(new P(WaterFlow.x(k), WaterFlow.y(k), WaterFlow.z(k)));
        }
        return out;
    }

    // ---- the lava we scoop ----

    // lava sources we can scoop from the shore row, none of them part of the base. nearest to uFrom first
    public static List<P> scoopCells(Layout l, Terrain t, Set<P> skin) {
        List<P> out = new ArrayList<>();
        for (int u = -2; u <= 5; u++) {
            for (int d = 0; d >= -2; d--) {
                if (d == 0 && (u == 1 || u == 2)) {
                    continue;
                }
                P c = l.cell(u, 0, d);
                if (skin.contains(c)) {
                    continue;
                }
                if (t.at(c.x(), c.y(), c.z()) == Mat.LAVA && t.at(c.x(), c.y() + 1, c.z()) == Mat.AIR) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    // closest scoopable cell to a column. the first one in the list at the same distance wins so it is stable
    private static int[] pickScoop(Layout l, Terrain t, int fromU) {
        int[] best = null;
        double bestDist = Double.MAX_VALUE;
        for (int u = -2; u <= 5; u++) {
            for (int d = 0; d >= -2; d--) {
                if (d == 0 && (u == 1 || u == 2)) {
                    continue;
                }
                P c = l.cell(u, 0, d);
                if (t.at(c.x(), c.y(), c.z()) == Mat.LAVA && t.at(c.x(), c.y() + 1, c.z()) == Mat.AIR) {
                    double dist = Math.abs(u - fromU) * 1.0 + (-d) * 0.7;
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = new int[]{u, 0, d};
                    }
                }
            }
        }
        return best;
    }

    // ---- staying out of the lava ----

    // our hitbox in world coordinates, the bits of an AABB this needs
    public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        // strict, like AABB.intersects: flush against a cell isn't in it. the scoop stands flush against the pool
        public boolean touches(P c) {
            return maxX > c.x() && minX < c.x() + 1 && maxY > c.y() && minY < c.y() + 1 && maxZ > c.z() && minZ < c.z() + 1;
        }
    }

    // the pour goes into t and the water into w. a body poking into either is a body the bucket lands on
    public static boolean inTheWay(Layout l, Box body, Cast c) {
        return body.touches(l.cell(c.t)) || body.touches(l.cell(c.w));
    }

    private static boolean isLava(Mat m) {
        return m == Mat.LAVA || m == Mat.LAVA_FLOW;
    }

    // lava in a cell our hitbox is in (feet and head cells included, they're in the box). lava next to us doesn't count,
    // the lava scoop stands right beside the pool on purpose
    public static boolean lavaOnUs(Terrain t, Box body) {
        for (int x = (int) Math.floor(body.minX()); x <= (int) Math.floor(body.maxX()); x++) {
            for (int y = (int) Math.floor(body.minY()); y <= (int) Math.floor(body.maxY()); y++) {
                for (int z = (int) Math.floor(body.minZ()); z <= (int) Math.floor(body.maxZ()); z++) {
                    if (body.touches(new P(x, y, z)) && isLava(t.at(x, y, z))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // somewhere to stand that isn't about to be lava: a floor, air for feet and head, and no lava beside either. fits()
    // only looked at the first two shore rows, so anything past those gets asked here
    public static boolean safeToStand(Terrain t, P feet) {
        if (!t.at(feet.x(), feet.y() - 1, feet.z()).solid()) {
            return false;
        }
        for (int dy = 0; dy <= 1; dy++) {
            if (t.at(feet.x(), feet.y() + dy, feet.z()) != Mat.AIR) {
                return false;
            }
            int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] s : sides) {
                if (isLava(t.at(feet.x() + s[0], feet.y() + dy, feet.z() + s[1]))) {
                    return false;
                }
            }
        }
        return true;
    }

    // where to run when lava is on us: back up our own column to d>=4, or sideways along u until we're past the flow.
    // nearest first, the column wins a tie. null = nowhere, the survival chain's lava escape gets it
    public static P retreatSpot(Layout l, Terrain t, P feet) {
        int[] me = l.local(feet);
        List<int[]> spots = new ArrayList<>();
        for (int d = Math.max(4, me[2] + 1); d <= 8; d++) {
            spots.add(new int[]{me[0], d});
        }
        for (int k = 1; k <= 6; k++) {
            spots.add(new int[]{me[0] - k, me[2]});
            spots.add(new int[]{me[0] + k, me[2]});
        }
        P best = null;
        int bestDist = Integer.MAX_VALUE;
        for (int[] s : spots) {
            // the shore can step up or down a block, the walk there handles that
            for (int dh : new int[]{0, 1, -1}) {
                P p = l.cell(s[0], me[1] + dh, s[1]);
                if (!safeToStand(t, p)) {
                    continue;
                }
                int dist = Math.abs(s[0] - me[0]) + Math.abs(s[1] - me[2]) + Math.abs(dh);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = p;
                }
                break;
            }
        }
        return best;
    }

    // a cell next to a stand to step off to, so the walk back puts us on the stand from a fresh angle. one row back
    // first, then the two cells beside it, one down too since the top row stands on a pad. null = nothing standable
    public static P stepBackSpot(Layout l, Terrain t, int[] stand) {
        int[][] offs = {{0, 1}, {-1, 0}, {1, 0}};
        for (int dh = 0; dh >= -1; dh--) {
            for (int[] o : offs) {
                P p = l.cell(stand[0] + o[0], stand[1] + dh, stand[2] + o[1]);
                if (safeToStand(t, p)) {
                    return p;
                }
            }
        }
        return null;
    }

    // ---- what to do next ----

    // the whole state machine, as a function of the world. nothing is remembered between calls but the skin spot and
    // how long that water has been out (-1 when it is not), so an interruption just re-reads and carries on
    public static Step next(Layout l, Terrain t, Inv inv, P skin, long skinAge) {
        for (int[] c : INTERIOR) {
            if (m(l, t, c) == Mat.PORTAL) {
                return step(Kind.DONE, null, "portal is lit");
            }
        }
        boolean base = m(l, t, BASE[0]) == Mat.OBSIDIAN && m(l, t, BASE[1]) == Mat.OBSIDIAN;
        Mat atSkin = skin == null ? Mat.AIR : t.at(skin.x(), skin.y(), skin.z());
        if (!base) {
            if (skin == null) {
                return step(Kind.STUCK, null, "nowhere to wash the base from");
            }
            if (atSkin == Mat.WATER) {
                // an age of -1 means we lost track of it (relog, interruption), take it back and look at what it did
                if (skinAge < 0 || skinAge >= SKIN_MAX_TICKS) {
                    return skinScoop(l, skin, "base never set");
                }
                return step(Kind.SKIN_WAIT, skin, "washing");
            }
            if (!inv.water()) {
                return step(Kind.STUCK, null, "no water bucket for the skin");
            }
            return skinPlace(l, t, skin);
        }
        if (atSkin == Mat.WATER) {
            return skinScoop(l, skin, "base is set");
        }

        // the slab. a top cell that is empty on purpose (its corner water is out) is left alone, and once every cell is cast
        // the slab is on its way out so it is not ours to rebuild
        boolean cast = true;
        for (Cast c : CASTS) {
            cast &= m(l, t, c.t) == Mat.OBSIDIAN;
        }
        for (int[] g : cast ? new int[0][] : SLAB) {
            Mat x = m(l, t, g);
            if (x == Mat.AIR || x == Mat.WATER_FLOW) {
                if (isTopCell(g) && m(l, t, cornerOf(g)) == Mat.WATER) {
                    continue;
                }
                return guidePlace(l, g, "slab");
            }
            if (x == Mat.LAVA || x == Mat.LAVA_FLOW || x == Mat.WATER) {
                // a stray source or lava in the slab: only the top cells are allowed to be mid-cast
                if (!isTopCell(g)) {
                    return step(Kind.WAIT, l.cell(g), "fluid in the slab");
                }
            }
        }

        for (Cast c : CASTS) {
            Step s = castStep(l, t, inv, c);
            if (s != null) {
                return s;
            }
        }

        // loose water that no cast was waiting on
        if (!inv.water()) {
            P loose = looseWater(l, t);
            if (loose != null) {
                return step(Kind.WATER_SCOOP, loose, "loose water");
            }
            return step(Kind.STUCK, null, "lost the water bucket");
        }

        for (int i = INTERIOR.length - 1; i >= 0; i--) {
            Mat x = m(l, t, INTERIOR[i]);
            if (x.solid()) {
                return new Step(Kind.GUIDE_BREAK, l.cell(INTERIOR[i]), null, 0, 0, 0, null, null, "slab out");
            }
            if (x != Mat.AIR) {
                return step(Kind.WAIT, l.cell(INTERIOR[i]), "something in the opening");
            }
        }
        P lightOn = l.cell(1, 0, 0);
        return new Step(Kind.LIGHT, lightOn, lightOn, 0, 1, 0, l.cell(1, 1, 1), null, "flint and steel");
    }

    private static Step step(Kind k, P cell, String why) {
        return new Step(k, cell, null, 0, 0, 0, null, null, why);
    }

    private static Step skinPlace(Layout l, Terrain t, P skin) {
        P ground = skin.add(0, -1, 0);
        return new Step(Kind.SKIN_PLACE, skin, ground, 0, 1, 0, null, null, "wash the base");
    }

    private static Step skinScoop(Layout l, P skin, String why) {
        return new Step(Kind.SKIN_SCOOP, skin, null, 0, 0, 0, null, null, why);
    }

    private static Step guidePlace(Layout l, int[] g, String why) {
        return new Step(Kind.GUIDE_PLACE, l.cell(g), null, 0, 0, 0, null, null, why);
    }

    private static boolean isTopCell(int[] g) {
        return g[1] == 4;
    }

    // the corner next to a top cell, where its water goes
    private static int[] cornerOf(int[] g) {
        return g[0] == 1 ? new int[]{0, 4, 0} : new int[]{3, 4, 0};
    }

    // a water source anywhere around the build, nearest the frame first. flowing water cannot be scooped
    private static P looseWater(Layout l, Terrain t) {
        P best = null;
        double bestDist = Double.MAX_VALUE;
        for (int u = -4; u <= 7; u++) {
            for (int h = 0; h <= 6; h++) {
                for (int d = -3; d <= 4; d++) {
                    if (m(l, t, u, h, d) == Mat.WATER) {
                        double dist = Math.abs(u - 1.5) + h * 0.5 + Math.abs(d);
                        if (dist < bestDist) {
                            bestDist = dist;
                            best = l.cell(u, h, d);
                        }
                    }
                }
            }
        }
        return best;
    }

    private static Step lavaScoopStep(Layout l, Terrain t, Inv inv, Cast c) {
        if (inv.empty() < 1) {
            return new Step(Kind.STUCK, null, null, 0, 0, 0, null, c, "no empty bucket for the lava");
        }
        int[] pick = pickScoop(l, t, c.stand[0]);
        if (pick == null) {
            return new Step(Kind.STUCK, null, null, 0, 0, 0, null, c, "ran out of lava to scoop");
        }
        // stand on the shore row right in front of it, one cell back from the edge
        return new Step(Kind.LAVA_SCOOP, l.cell(pick), null, 0, 0, 0, l.cell(pick[0], 1, 1), c, "lava for " + c.name);
    }

    private static Step lavaPlaceStep(Layout l, Cast c) {
        int[] f = l.face(c.lavaSupport, c.t);
        P sup = l.cell(c.lavaSupport);
        return new Step(Kind.LAVA_PLACE, l.cell(c.t), sup, f[0], f[1], f[2], l.cell(c.stand), c, "lava into " + c.name);
    }

    private static Step waterPlaceStep(Layout l, Cast c) {
        int[] f = l.face(c.waterSupport, c.w);
        P sup = l.cell(c.waterSupport);
        return new Step(Kind.WATER_PLACE, l.cell(c.w), sup, f[0], f[1], f[2], l.cell(c.stand), c, "water for " + c.name);
    }

    private static Step waterScoopStep(Layout l, Cast c, String why) {
        return new Step(Kind.WATER_SCOOP, l.cell(c.w), null, 0, 0, 0, l.cell(c.stand), c, why);
    }

    // null = this cast is finished, look at the next one
    private static Step castStep(Layout l, Terrain t, Inv inv, Cast c) {
        Mat tm = m(l, t, c.t);
        Mat wm = m(l, t, c.w);
        P tp = l.cell(c.t);
        P wp = l.cell(c.w);
        if (tm == Mat.OBSIDIAN) {
            if (wm == Mat.WATER && !topOwnsCorner(l, t, c)) {
                if (inv.water() && inv.empty() < 1) {
                    return null;
                }
                return waterScoopStep(l, c, "water back from " + c.name);
            }
            return null;
        }
        if (tm == Mat.WATER) {
            return new Step(Kind.WATER_SCOOP, tp, null, 0, 0, 0, l.cell(c.stand), c, "water in " + c.name);
        }
        if (tm == Mat.LAVA_FLOW || tm == Mat.WATER_FLOW && !c.top) {
            return new Step(Kind.WAIT, tp, null, 0, 0, 0, null, c, "flow in " + c.name);
        }
        if (tm == Mat.LAVA) {
            if (wm == Mat.WATER) {
                return new Step(Kind.WAIT, tp, null, 0, 0, 0, null, c, "obsidian is setting");
            }
            if (inv.water() && (wm == Mat.AIR || wm == Mat.WATER_FLOW)) {
                return waterPlaceStep(l, c);
            }
            return new Step(Kind.LAVA_UNDO, tp, null, 0, 0, 0, l.cell(c.stand), c, "lava with no water coming");
        }
        return c.top ? topStep(l, t, inv, c, tm, wm) : sideStep(l, t, inv, c, tm, wm);
    }

    // the third cell of a column drops its water in the corner and the top row picks that corner up as its own. once the
    // pad is down the top row is running and its water in the corner is not leftovers
    private static boolean topOwnsCorner(Layout l, Terrain t, Cast c) {
        if (c.top || c.w[1] != 4) {
            return false;
        }
        for (Cast top : CASTS) {
            if (top.top && sameCell(top.w, c.w)) {
                return m(l, t, top.pad).solid() && m(l, t, top.t) != Mat.OBSIDIAN;
            }
        }
        return false;
    }

    private static Step sideStep(Layout l, Terrain t, Inv inv, Cast c, Mat tm, Mat wm) {
        P tp = l.cell(c.t);
        if (tm.solid()) {
            return new Step(Kind.GUIDE_BREAK, tp, null, 0, 0, 0, l.cell(c.stand), c, "junk in " + c.name);
        }
        // t is air here
        if (!inv.water()) {
            if (wm == Mat.WATER) {
                return waterScoopStep(l, c, "water back before the lava");
            }
            return new Step(Kind.STUCK, null, null, 0, 0, 0, null, c, "lost the water bucket");
        }
        if (wm.solid()) {
            return new Step(Kind.GUIDE_BREAK, l.cell(c.w), null, 0, 0, 0, l.cell(c.stand), c, "junk over " + c.name);
        }
        if (inv.lava()) {
            return lavaPlaceStep(l, c);
        }
        return lavaScoopStep(l, t, inv, c);
    }

    private static Step topStep(Layout l, Terrain t, Inv inv, Cast c, Mat tm, Mat wm) {
        P tp = l.cell(c.t);
        P pad = l.cell(c.pad);
        if (!m(l, t, c.pad).solid()) {
            if (m(l, t, c.pad) != Mat.AIR) {
                return step(Kind.WAIT, pad, "pad is wet");
            }
            return new Step(Kind.GUIDE_PLACE, pad, null, 0, 0, 0, null, c, "pad for " + c.name);
        }
        if (tm.solid()) {
            // the guide is still in the cell. lava in the bag first, then the corner water through the guide
            if (wm == Mat.WATER) {
                return new Step(Kind.GUIDE_BREAK, tp, null, 0, 0, 0, l.cell(c.stand), c, "open " + c.name);
            }
            if (wm.solid()) {
                return new Step(Kind.GUIDE_BREAK, l.cell(c.w), null, 0, 0, 0, l.cell(c.stand), c, "junk in the corner");
            }
            if (!inv.lava()) {
                return lavaScoopStep(l, t, inv, c);
            }
            if (!inv.water()) {
                return new Step(Kind.STUCK, null, null, 0, 0, 0, null, c, "lost the water bucket");
            }
            return waterPlaceStep(l, c);
        }
        // the guide is out, the lava has to go in now while the corner water is still holding
        if (wm == Mat.WATER) {
            return inv.lava() ? lavaPlaceStep(l, c) : lavaScoopStep(l, t, inv, c);
        }
        return new Step(Kind.STUCK, null, null, 0, 0, 0, null, c, "the corner water went away");
    }
}
