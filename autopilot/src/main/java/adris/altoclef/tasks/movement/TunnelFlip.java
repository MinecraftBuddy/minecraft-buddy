package adris.altoclef.tasks.movement;

import adris.altoclef.util.helpers.Recent;
import net.minecraft.core.BlockPos;

// (Tuning tunnel_flip_guard, a fix of 14 Nov 2026, on) the tunnel's step going to and fro between two cells. a tunnel of its own
// at y 16 met water two cells ahead: from the cell by it the way on was water and no way was closed, so the step turned half
// round into the tunnel just dug; from there the only closed way was back, the water one cell past what the step looks at,
// and a closed way ranks before an open one, so the step went back. 1042 half turns in 322 s, about 0.3 s each; each step was
// reached at once, so no watch of a step not reached fired, and the place watch's remedy was the same tunnel again (test
// heel's tunnel flip). two guards, pure, BranchMineTask holds one per task:
// - a step that turns half round against water keeps the step it refused for a game minute, and a way whose step comes within
//   a block of it is taken only when every way is like that (BranchMineTask.besideWater, as for the water walked out of)
// - a step back into the cell just left when the step before was one too (A to B to A) is a fire of the place watch, so the
//   second fire gives the depth up. one step back alone is a dead end and no fire
final class TunnelFlip {
    // a game minute
    static final long KEEP = 1200;

    private int[] refused;
    private long refusedAt = Long.MIN_VALUE;
    private BlockPos from;
    private boolean back;

    // the half turn round from a way into water: rank and the pick as BranchMineTask.pickWay has them
    static boolean refusesWater(int[] rank, int pick) {
        return pick == 2 && rank[0] == BranchMineTask.WAY_WET;
    }

    // the step refused at now
    void refuse(BlockPos step, long now) {
        refused = new int[]{step.getX(), step.getY(), step.getZ()};
        refusedAt = now;
    }

    // the step refused ({x, y, z}) while it is kept, else null
    int[] refused(long now) {
        return refused != null && Recent.within(now, refusedAt, KEEP) ? refused : null;
    }

    // a new step of the tunnel from feet to next; reached: feet is the step before, reached (not a step made anew after a turn
    // or a fire). true when it goes back into the cell just left and the step before did the same
    boolean step(BlockPos feet, BlockPos next, boolean reached) {
        boolean wasBack = back;
        back = reached && from != null && next.getX() == from.getX() && next.getZ() == from.getZ();
        from = feet.immutable();
        return back && wasBack;
    }

    // either array's way
    static boolean[] either(boolean[] a, boolean[] b) {
        boolean[] out = new boolean[a.length];
        for (int i = 0; i < a.length; i++) out[i] = a[i] || b[i];
        return out;
    }
}
