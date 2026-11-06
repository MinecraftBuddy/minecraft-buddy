package adris.altoclef.tasks.speedrun.gamer.tasks;

import java.util.List;

// the numbers behind EscapeBridgeTask, pure so they can be tested without a world
public final class EscapeHops {
    // a goal this far is still "a few cells away" to baritone: it searches straight to it instead of picking a partial path,
    // which is the whole reason the escape hops at all
    public static final int HOP_CELLS = 8;
    // a hop that gets no closer for this long is stuck, the route gets recomputed around it
    public static final double STALL_SECONDS = 10;
    public static final int MAX_RECOMPUTES = 2;
    // a ghast ball this close and the escape stands still, so the punch gets its swing
    public static final double FIREBALL_HOLD_BLOCKS = 8;
    // all the holding one escape may do, a ghast that never leaves is not a reason to stand there forever
    public static final double MAX_HOLD_SECONDS = 30;

    private EscapeHops() {
    }

    // the cell the next hop goes to, given the index of the last one reached (-1 before the first)
    public static int nextHop(int cells, int reached) {
        return Math.min(cells - 1, Math.max(reached, 0) + HOP_CELLS);
    }

    public static int[] hopCell(List<int[]> route, int reached) {
        return route.get(nextHop(route.size(), reached));
    }

    // index of the route cell closest to where we stand, so a hop that drifted still counts what it got through
    public static int closest(List<int[]> route, double x, double z) {
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < route.size(); i++) {
            double dx = route.get(i)[0] + 0.5 - x;
            double dz = route.get(i)[2] + 0.5 - z;
            double d = dx * dx + dz * dz;
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    public static boolean holdForFireball(double nearestFireballBlocks) {
        return nearestFireballBlocks <= FIREBALL_HOLD_BLOCKS;
    }
}
