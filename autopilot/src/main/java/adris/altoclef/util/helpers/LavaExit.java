package adris.altoclef.util.helpers;

// where to swim when we are in lava and baritone has no path for us yet: the closest cell a body could stand in, dry and
// on something solid, with magma losing the close calls. pure, it only asks the same Terrain the pickup rules ask
public final class LavaExit {

    // a lake bigger than this around us is not something a blind swim gets out of anyway
    public static final int RADIUS = 8;
    // measured from the lava surface above us, not from our feet: jumping in lava floats us up to the surface, and from
    // there the most we climb out onto is one block. a cell on top of a 2 high wall is not a way out, however close it is
    private static final int ABOVE_SURFACE = 1;
    private static final int BELOW_SURFACE = 2;
    // how far up we follow the lava column to find its surface
    private static final int MAX_DEPTH = 6;

    public record Cell(int x, int y, int z) {
    }

    private LavaExit() {
    }

    // an exit this close is a hop, not a path: one or two cells over and at most one up from the lava cell we float in.
    // anything farther is baritone's, it can plan around walls and bridge a gap where a blind hop would just bonk
    static final int HOP_REACH = 2;

    public static boolean withinHop(Cell exit, int px, int py, int pz) {
        return exit != null
                && Math.abs(exit.x() - px) <= HOP_REACH
                && Math.abs(exit.z() - pz) <= HOP_REACH
                && exit.y() - py <= ABOVE_SURFACE;
    }

    // a rim two above the surface is a wall, and a hop can't climb a wall. a block set into the lava cell beside us at the
    // surface is a one high step to it: stand on its top and the rim is the usual one up. T is a horizontal neighbour of the
    // surface cell we float in (never our own column, pillaring in lava loses to the bobbing, see isSafeCell), lava or air,
    // with room above. the rim N beside it is solid 1..MAX_STEP up from T (the wall) and standable above that, and something
    // next to T or under it is there to click against. a taller rim works too: once we stand on T we're out of the lava, and
    // the pillar up to the rim is a dry one, plain baritone pillaring with nothing lava about it.
    // the lowest rim wins, then yaw, we'd rather place where we are already looking. null when there is no such cell
    public static Cell stairCell(int px, int py, int pz, ItemPickupRules.Terrain t, ItemPickupRules.Lava lava, float yawDegrees) {
        int surface = py;
        while (surface - py < MAX_DEPTH && lava.at(px, surface + 1, pz)) {
            surface++;
        }
        double lookX = -Math.sin(Math.toRadians(yawDegrees));
        double lookZ = Math.cos(Math.toRadians(yawDegrees));
        Cell best = null;
        int bestStep = Integer.MAX_VALUE;
        double bestScore = -Double.MAX_VALUE;
        for (int[] d : SIDES) {
            int x = px + d[0];
            int z = pz + d[1];
            if (!(lava.at(x, surface, z) || t.open(x, surface, z))) continue;
            int step = rimHeight(x, surface, z, t);
            if (step == 0 || step > bestStep || !hasSupport(x, surface, z, t)) continue;
            double score = d[0] * lookX + d[1] * lookZ;
            if (step < bestStep || score > bestScore) {
                best = new Cell(x, surface, z);
                bestStep = step;
                bestScore = score;
            }
        }
        return best;
    }

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    // a rim 4 up is 3 blocks of pillar on T, and by then a 4 dmg lava tick has had plenty of goes at us anyway
    static final int MAX_STEP = 3;

    // the lowest k (1..MAX_STEP) with a neighbour of T solid k up and a body's room on top of it, 0 for none. a rim k up is
    // k - 1 blocks of pillar on T and then a step, and the step's jump needs the cell over our head, so T's column has to be
    // open to k + 2. k = 1 keeps the two cells it always had: standing on T is already out of the lava, a lid over that one
    // step is baritone's to mine
    private static int rimHeight(int x, int y, int z, ItemPickupRules.Terrain t) {
        if (!t.open(x, y + 1, z) || !t.open(x, y + 2, z)) return 0;
        for (int k = 1; k <= MAX_STEP; k++) {
            if (k > 1 && (!t.open(x, y + k + 1, z) || !t.open(x, y + k + 2, z))) return 0;
            for (int[] d : SIDES) {
                int nx = x + d[0];
                int nz = z + d[1];
                if (t.solid(nx, y + k, nz) && t.open(nx, y + k + 1, nz) && t.open(nx, y + k + 2, nz)) return k;
            }
        }
        return 0;
    }

    // something to click against: a wall at T's level on any side, or a floor under it
    private static boolean hasSupport(int x, int y, int z, ItemPickupRules.Terrain t) {
        if (t.solid(x, y - 1, z)) return true;
        for (int[] d : SIDES) {
            if (t.solid(x + d[0], y, z + d[1])) return true;
        }
        return false;
    }

    // squared distance a hop will go out of its way to not land on magma. a tie break, nothing more: magma behind everything
    // sounds nice until it's magma a block away and netherrack six away, then there's no hop and we crawl through lava for it
    static final int MAGMA_SLACK = 2;

    // closest(), except a magma exit gives way to a non magma one that's about as close and that we can hop to.
    // null when nothing is standable in range
    public static Cell nearest(int px, int py, int pz, ItemPickupRules.Terrain t, ItemPickupRules.Lava lava) {
        Cell best = closest(px, py, pz, t, lava);
        if (best == null || !t.magma(best.x(), best.y() - 1, best.z())) {
            return best;
        }
        Cell cool = coolerBeside(best, px, py, pz, t, lava);
        return cool == null ? best : cool;
    }

    private static Cell coolerBeside(Cell magma, int px, int py, int pz, ItemPickupRules.Terrain t, ItemPickupRules.Lava lava) {
        int surface = surfaceOver(px, py, pz, lava);
        long limit = dist2(magma.x() - px, magma.y() - py, magma.z() - pz) + MAGMA_SLACK;
        Cell best = null;
        long bestDist = Long.MAX_VALUE;
        for (int dx = -HOP_REACH; dx <= HOP_REACH; dx++) {
            for (int dz = -HOP_REACH; dz <= HOP_REACH; dz++) {
                for (int y = surface - BELOW_SURFACE; y <= surface + ABOVE_SURFACE; y++) {
                    long dist = dist2(dx, y - py, dz);
                    Cell c = new Cell(px + dx, y, pz + dz);
                    if (dist > limit || dist >= bestDist || !withinHop(c, px, py, pz)) continue;
                    if (standable(c.x(), y, c.z(), t) && !t.magma(c.x(), y - 1, c.z())) {
                        best = c;
                        bestDist = dist;
                    }
                }
            }
        }
        return best;
    }

    // half of a player's width. the box is square, so it hangs over a block when it's this close to it on both axes
    private static final double BODY_HALF_WIDTH = 0.3;

    // the standable cell our box is actually over, for a body on a lip with its middle over the lava. takes the exact position
    // because the whole question is which side of the column we're on. y is the feet block. null when the box is over nothing
    public static Cell under(double x, int y, double z, ItemPickupRules.Terrain t) {
        int cx = (int) Math.floor(x);
        int cz = (int) Math.floor(z);
        Cell best = null;
        double bestGap = BODY_HALF_WIDTH;
        for (int nx = cx - 1; nx <= cx + 1; nx++) {
            for (int nz = cz - 1; nz <= cz + 1; nz++) {
                double gap = Math.max(gap(x, nx), gap(z, nz));
                if (gap < bestGap && standable(nx, y, nz, t)) {
                    best = new Cell(nx, y, nz);
                    bestGap = gap;
                }
            }
        }
        return best;
    }

    private static double gap(double p, int cell) {
        return Math.max(0, Math.max(cell - p, p - (cell + 1)));
    }

    private static long dist2(int dx, int dy, int dz) {
        return (long) dx * dx + (long) dy * dy + (long) dz * dz;
    }

    private static boolean standable(int x, int y, int z, ItemPickupRules.Terrain t) {
        return t.solid(x, y - 1, z) && t.open(x, y, z) && t.open(x, y + 1, z);
    }

    private static int surfaceOver(int px, int py, int pz, ItemPickupRules.Lava lava) {
        int surface = py;
        while (surface - py < MAX_DEPTH && lava.at(px, surface + 1, pz)) {
            surface++;
        }
        return surface;
    }

    // the plain closest standable cell, magma or not. feet position, so for a player in lava that is the lava cell itself
    private static Cell closest(int px, int py, int pz, ItemPickupRules.Terrain t, ItemPickupRules.Lava lava) {
        int surface = surfaceOver(px, py, pz, lava);
        Cell best = null;
        long bestDist = Long.MAX_VALUE;
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                for (int y = surface - BELOW_SURFACE; y <= surface + ABOVE_SURFACE; y++) {
                    long dist = dist2(dx, y - py, dz);
                    if (dist >= bestDist) continue;
                    int x = px + dx;
                    int z = pz + dz;
                    if (standable(x, y, z, t)) {
                        best = new Cell(x, y, z);
                        bestDist = dist;
                    }
                }
            }
        }
        return best;
    }
}
