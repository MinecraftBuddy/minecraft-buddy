package adris.altoclef.tasks.speedrun.gamer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

// the way off a portal that spawned over nothing: a forced nether portal can float on a 2x3 obsidian slab above a lava sea with
// the nearest ground a hundred blocks out, and the sweep's partial path never accepts a bridge that long. so this just answers
// "where is the cheapest real ground, counting blocks we have to place to get there" on the feet level plane. pure, the world
// comes in through Terrain so it runs in a test
public final class EscapeRoute {
    public enum Cell {
        // not loaded: unknown is not air, the route never crosses it
        UNLOADED,
        // walkable space (nether portal counts, we spawn inside one)
        AIR,
        LAVA,
        // fire and anything else that is not air and not solid
        HAZARD,
        // solid ground that was there before us
        NATURAL,
        // solid, but ours: obsidian, crying obsidian, an old bridge. never ground worth walking to, the slab is what we are trying to leave
        ARTIFICIAL
    }

    public interface Terrain {
        Cell at(int x, int y, int z);
    }

    public static final int RADIUS = 96;
    // the bag keeps this many for whatever comes after the walk
    public static final int SPARE = 8;
    // more than this many placements to reach natural floor = we are stranded, not just standing at an edge
    public static final int ISOLATED_PLACEMENTS = 3;
    // a step down this far is a walk off an edge, past it the fall is damage
    public static final int MAX_STEP_DOWN = 4;
    // how far down the lava lake test looks for the first real block (the real terrain ends it sooner at the world floor)
    private static final int LAKE_DEPTH = 320;

    public record Route(List<int[]> cells, int placements, boolean wall) {
        public int[] end() {
            return cells.get(cells.size() - 1);
        }
    }

    // route = null when nothing fits the budget. isolated = the nearest floor is further than ISOLATED_PLACEMENTS away
    public record Plan(Route route, boolean isolated) {
    }

    private EscapeRoute() {
    }

    public static int budget(int heldThrowaways) {
        return Math.max(0, heldThrowaways - SPARE);
    }

    public static Plan plan(Terrain t, int x, int y, int z, int heldThrowaways) {
        // searched at least ISOLATED_PLACEMENTS deep even on a thin bag, or a bag of 5 would call every arrival isolated
        Route r = find(t, x, y, z, Math.max(budget(heldThrowaways), ISOLATED_PLACEMENTS));
        boolean nearFloor = r != null && !r.wall() && r.placements() <= ISOLATED_PLACEMENTS;
        if (nearFloor) {
            return new Plan(r, false);
        }
        boolean fits = r != null && r.placements() <= budget(heldThrowaways);
        return new Plan(fits ? r : null, true);
    }

    // the arrival hangs over a lava lake: look down the feet column and the ones two out each way. our own slab and bridge cells
    // are skipped (the slab is what we stand on), the first thing under them decides the column. a netherrack pillar under the
    // feet with lava all around still counts, that is the lake with one post in it
    public static boolean overLavaLake(Terrain t, int x, int y, int z) {
        int lava = 0;
        int[][] cols = {{0, 0}, {2, 0}, {-2, 0}, {0, 2}, {0, -2}};
        for (int[] c : cols) {
            if (firstBelow(t, x + c[0], y - 1, z + c[1]) == Cell.LAVA) {
                lava++;
            }
        }
        return lava >= 3;
    }

    // the lake only ends the run when no wall within a few placements can be dug into: find() prefers a far floor over its saved
    // wall, so the plan's own route says nothing about whether a cheap wall exists
    public static boolean lavaLakeNoWay(Terrain t, int x, int y, int z) {
        if (!overLavaLake(t, x, y, z)) {
            return false;
        }
        Route r = find(t, x, y, z, ISOLATED_PLACEMENTS);
        return r == null || !r.wall() && r.placements() > ISOLATED_PLACEMENTS;
    }

    private static Cell firstBelow(Terrain t, int x, int fromY, int z) {
        // the terrain calls out of height hazard, but a test world is air forever, so the walk down has its own end
        for (int y = fromY; y > fromY - LAKE_DEPTH; y--) {
            Cell c = t.at(x, y, z);
            if (c != Cell.AIR && c != Cell.ARTIFICIAL) {
                return c;
            }
        }
        return Cell.AIR;
    }

    // cheapest floor within the budget, else the cheapest wall to dig into, else null. cost is placements first, then turns
    // (a straight bridge is a quick one), then length. the sweep target is not a factor, nobody is pathing back out of here
    public static Route find(Terrain t, int sx, int sy, int sz, int maxPlacements) {
        Grid g = new Grid(t, sx, sy, sz);
        int n = 2 * RADIUS + 1;
        long[][] best = new long[n * n][4];
        for (long[] b : best) {
            Arrays.fill(b, Long.MAX_VALUE);
        }
        PriorityQueue<Node> open = new PriorityQueue<>();
        // dir -1 is the start, it has not turned yet
        Node start = new Node(0, 0, -1, 0, 0, 0, null);
        open.add(start);
        Route wall = null;
        while (!open.isEmpty()) {
            Node cur = open.poll();
            if (cur.dir >= 0) {
                if (best[g.idx(cur.dx, cur.dz)][cur.dir] < cur.cost()) {
                    continue;
                }
            }
            Column c = g.col(cur.dx, cur.dz);
            if (c.floorDepth > 0) {
                return build(g, cur, c, false);
            }
            if (wall == null) {
                int[] w = g.wallAround(cur.dx, cur.dz);
                if (w != null) {
                    wall = build(g, cur, new Column(true, 0, 0, w), true);
                }
            }
            for (int d = 0; d < 4; d++) {
                int nx = cur.dx + DX[d];
                int nz = cur.dz + DZ[d];
                if (Math.abs(nx) > RADIUS || Math.abs(nz) > RADIUS) {
                    continue;
                }
                Column nc = g.col(nx, nz);
                if (!nc.passable || nc.place < 0) {
                    continue;
                }
                // a step down is a walk off the edge, nothing gets placed in the cell we leave in the air
                int place = cur.placements + (nc.floorDepth > 1 ? 0 : nc.place);
                if (place > maxPlacements) {
                    continue;
                }
                int turns = cur.dir >= 0 && cur.dir != d ? cur.turns + 1 : cur.turns;
                int len = cur.len + 1;
                long cost = place * 1_000_000L + turns * 1_000L + len;
                int bi = g.idx(nx, nz);
                if (cost >= best[bi][d]) {
                    continue;
                }
                best[bi][d] = cost;
                Node next = new Node(nx, nz, d, place, turns, len, cur);
                open.add(next);
            }
        }
        return wall;
    }

    private static final int[] DX = {1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 1, -1};

    private static Route build(Grid g, Node end, Column c, boolean wall) {
        List<int[]> cells = new ArrayList<>();
        for (Node n = end; n != null; n = n.prev) {
            cells.add(0, new int[]{g.sx + n.dx, g.sy, g.sz + n.dz});
        }
        if (wall) {
            cells.add(new int[]{c.wall[0], c.wall[1], c.wall[2]});
        } else if (c.floorDepth > 1) {
            // the plane cell is open air we only fall through, the stand cell is down on the floor
            cells.set(cells.size() - 1, new int[]{g.sx + end.dx, g.sy - c.floorDepth + 1, g.sz + end.dz});
        }
        return new Route(cells, end.placements, wall);
    }

    private record Node(int dx, int dz, int dir, int placements, int turns, int len, Node prev) implements Comparable<Node> {
        long cost() {
            return placements * 1_000_000L + turns * 1_000L + len;
        }

        @Override
        public int compareTo(Node o) {
            return Long.compare(cost(), o.cost());
        }
    }

    // what one column of the plane means for the walk: can we stand in it, what does it cost, is it ground
    private static final class Column {
        final boolean passable;
        // placements to stand here: 0 solid underfoot, 1 air or lava under us, -1 cannot (unloaded)
        final int place;
        // 0 not a floor, 1 natural right under the feet, 2..MAX_STEP_DOWN a step down to natural
        final int floorDepth;
        final int[] wall;

        Column(boolean passable, int place, int floorDepth, int[] wall) {
            this.passable = passable;
            this.place = place;
            this.floorDepth = floorDepth;
            this.wall = wall;
        }
    }

    private static final class Grid {
        final Terrain t;
        final int sx;
        final int sy;
        final int sz;
        final Map<Integer, Column> cols = new HashMap<>();

        Grid(Terrain t, int sx, int sy, int sz) {
            this.t = t;
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
        }

        int idx(int dx, int dz) {
            return (dx + RADIUS) * (2 * RADIUS + 1) + (dz + RADIUS);
        }

        Cell at(int dx, int y, int dz) {
            return t.at(sx + dx, y, sz + dz);
        }

        Column col(int dx, int dz) {
            Column c = cols.get(idx(dx, dz));
            if (c == null) {
                c = makeColumn(dx, dz);
                cols.put(idx(dx, dz), c);
            }
            return c;
        }

        private Column makeColumn(int dx, int dz) {
            boolean start = dx == 0 && dz == 0;
            // the start cell is wherever we stand, it never has to pass for air (we spawn inside the portal)
            boolean passable = start || (at(dx, sy, dz) == Cell.AIR && at(dx, sy + 1, dz) == Cell.AIR);
            Cell under = at(dx, sy - 1, dz);
            int place = switch (under) {
                case NATURAL, ARTIFICIAL -> 0;
                case AIR, LAVA, HAZARD -> 1;
                default -> -1;
            };
            int depth = 0;
            if (passable) {
                depth = floorDepth(dx, dz, under);
            }
            return new Column(passable, place, depth, null);
        }

        // natural ground under the feet, or a short drop to some. everything between has to be open air, a drop through lava is not
        // a floor
        private int floorDepth(int dx, int dz, Cell under) {
            if (under == Cell.NATURAL) {
                return 1;
            }
            if (under != Cell.AIR) {
                return 0;
            }
            for (int k = 2; k <= MAX_STEP_DOWN; k++) {
                Cell c = at(dx, sy - k, dz);
                if (c == Cell.NATURAL) {
                    return k;
                }
                if (c != Cell.AIR) {
                    return 0;
                }
            }
            return 0;
        }

        // a natural block next to the cell to dig into: ground under it to stand on, and head room that is rock or air (so digging
        // it out is all the work). lava on any side of what we dig out rules it out, it is either holding lava back or holding the
        // lava sea up
        int[] wallAround(int dx, int dz) {
            for (int d = 0; d < 4; d++) {
                int wx = dx + DX[d];
                int wz = dz + DZ[d];
                if (at(wx, sy, wz) != Cell.NATURAL || at(wx, sy - 1, wz) != Cell.NATURAL) {
                    continue;
                }
                Cell head = at(wx, sy + 1, wz);
                if (head != Cell.AIR && head != Cell.NATURAL) {
                    continue;
                }
                if (lavaBeside(wx, sy, wz) || (head == Cell.NATURAL && lavaBeside(wx, sy + 1, wz))) {
                    continue;
                }
                return new int[]{sx + wx, sy, sz + wz};
            }
            return null;
        }

        private boolean lavaBeside(int dx, int y, int dz) {
            return at(dx + 1, y, dz) == Cell.LAVA || at(dx - 1, y, dz) == Cell.LAVA
                    || at(dx, y, dz + 1) == Cell.LAVA || at(dx, y, dz - 1) == Cell.LAVA
                    || at(dx, y + 1, dz) == Cell.LAVA || at(dx, y - 1, dz) == Cell.LAVA;
        }
    }
}
