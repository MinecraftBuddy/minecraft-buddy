package adris.altoclef.util.helpers;

import baritone.pathing.movement.CliffAvoidance.Cell;
import baritone.pathing.movement.CliffAvoidance.Cells;

// the buddy's fork, Tuning route_cube_way (on by default; route jar 8, 9 Nov 2026): a magma cube hunt begins only with a walk
// to the cube's stand. realfort-route-5 began 60 cube hunts and let 30 go "out of reach (no way to it)": cubes 25 to 48
// off and 10 to 38 higher, the approach banned only after it had walked about for its progress check. a walk here is
// what the feet do with no block placed or broken: a step to a side on the level, up one with room to jump, or down at
// most DROP; the stand is a cell from which the sword reaches the cube (within STAND_FLAT of its column, within
// STAND_UP of the ground it stands on). the search is bounded (MAX_CELLS, MAX_FLAT from the start), so a cube with no
// walk found in it counts as none
public final class CubeWay {
    private CubeWay() {
    }

    public static final int DROP = 3;
    public static final double STAND_FLAT = 2.5;
    public static final int STAND_UP = 1;
    public static final int MAX_CELLS = 6000;
    public static final int MAX_FLAT = 64;
    // a jumping cube is judged by the ground under it: at most this far down
    public static final int GROUND_DOWN = 6;

    public static boolean on() {
        return Tuning.get("route_cube_way", 0) > 0;
    }

    // feet in (x, y, z) stand: a solid floor under them, open feet and head
    public static boolean walkable(Cells cells, int x, int y, int z) {
        return cells.at(x, y - 1, z) == Cell.SOLID && cells.at(x, y, z) == Cell.OPEN && cells.at(x, y + 1, z) == Cell.OPEN;
    }

    // the y of the ground cell (feet) under (x, y, z): down through open cells, at most GROUND_DOWN
    public static int ground(Cells cells, int x, int y, int z) {
        int gy = y;
        for (int i = 0; i < GROUND_DOWN && cells.at(x, gy - 1, z) == Cell.OPEN; i++) gy--;
        return gy;
    }

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    // whether a walk from the feet (fx, fy, fz) reaches a stand of the cube standing at (cx, cy, cz) (its block cell; a cube
    // in the air by the ground under it, ground)
    public static boolean way(Cells cells, int fx, int fy, int fz, int cx, int cy, int cz) {
        int gy = ground(cells, cx, cy, cz);
        int sy = ground(cells, fx, fy, fz);
        java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        queue.add(new int[]{fx, sy, fz});
        seen.add(BlazeCover.key(fx, sy, fz));
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            if (stand(c[0], c[1], c[2], cx, gy, cz)) return true;
            if (seen.size() > MAX_CELLS) return false;
            boolean jump = cells.at(c[0], c[1] + 2, c[2]) == Cell.OPEN;
            for (int[] side : SIDES) {
                int nx = c[0] + side[0], nz = c[2] + side[1];
                if (Math.abs(nx - fx) > MAX_FLAT || Math.abs(nz - fz) > MAX_FLAT) continue;
                for (int dy = 1; dy >= -DROP; dy--) {
                    int ny = c[1] + dy;
                    if (dy == 1 && !jump) continue;
                    // the column between: open from the higher of the two heads down to the lower feet
                    boolean clear = true;
                    for (int k = ny + 2; k <= c[1] + 1 && clear; k++) clear = cells.at(nx, k, nz) == Cell.OPEN;
                    if (!clear) break;
                    if (!walkable(cells, nx, ny, nz)) continue;
                    if (seen.add(BlazeCover.key(nx, ny, nz))) queue.add(new int[]{nx, ny, nz});
                    break;
                }
            }
        }
        return false;
    }

    // a stand of a cube on the ground at (cx, gy, cz): feet within STAND_FLAT of its column and STAND_UP of its ground
    public static boolean stand(int x, int y, int z, int cx, int gy, int cz) {
        return Math.abs(y - gy) <= STAND_UP && Math.hypot(x - cx, z - cz) <= STAND_FLAT;
    }
}
