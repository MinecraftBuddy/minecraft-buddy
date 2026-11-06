package adris.altoclef.util.helpers;

import net.minecraft.core.BlockPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// what a search that came back empty says about the block we were walking to. a search that ran out of nodes proved the
// block can't be reached, so its vein (same ore, touching) goes with it for a little while. one that ran out of time proved
// nothing about anything but that spot, so only that spot is dropped. pure, the callers hand in what they know
public final class NoPathScope {
    // a vein is a dozen blocks, a hundred of the same block is a hillside of stone
    public static final int VEIN_CAP = 24;

    public enum Verdict {
        NONE,
        TIMED_OUT,
        EXHAUSTED
    }

    // a block this far off that the search timed out on is not coming back in two minutes
    public static final double FAR_SQ = 64.0 * 64.0;

    private NoPathScope() {
    }

    // strikes a no path block may have before the long ban. a timed out search to something far off gets it at once: walking
    // toward it resets the strike count (the ban book counts a closer approach as a new try), so the same far block came back
    // every couple of minutes
    public static int strikesAllowed(Verdict verdict, double distSq) {
        return verdict == Verdict.TIMED_OUT && distSq > FAR_SQ ? 0 : 2;
    }

    // baritone only counts empty searches (and, separately, the ones that ran out of nodes). this turns the two counters into
    // "did one come back since I handed over my goal", once per failure
    public static final class Watch {
        private int failures = -1;
        private int exhausted;

        // call when we hand baritone a goal, or when we have been away from it (another chain had the wheel)
        public void arm(int failuresNow, int exhaustedNow) {
            failures = failuresNow;
            exhausted = exhaustedNow;
        }

        public void disarm() {
            failures = -1;
        }

        public boolean armed() {
            return failures >= 0;
        }

        // `pathing` = baritone has a path it is walking right now: whatever failed before that was an earlier ask, the one we
        // have now found its way
        public Verdict poll(int failuresNow, int exhaustedNow, boolean pathing) {
            if (failures < 0 || failuresNow <= failures) {
                return Verdict.NONE;
            }
            boolean ran = exhaustedNow > exhausted;
            failures = failuresNow;
            exhausted = exhaustedNow;
            if (pathing) {
                return Verdict.NONE;
            }
            return ran ? Verdict.EXHAUSTED : Verdict.TIMED_OUT;
        }
    }

    // the cells to ban for a block we can't get to: the block itself first, then (only when the search was exhausted and the
    // caller says veins are a thing for this block) everything of `known` that touches it, edges and corners, walking out
    // from the block until the cap
    public static List<BlockPos> cells(BlockPos target, Collection<BlockPos> known, Verdict verdict, boolean veins) {
        List<BlockPos> out = new ArrayList<>();
        out.add(target.immutable());
        if (verdict != Verdict.EXHAUSTED || !veins) {
            return out;
        }
        Set<BlockPos> pool = new HashSet<>();
        for (BlockPos pos : known) {
            pool.add(pos.immutable());
        }
        pool.remove(target);
        Set<BlockPos> seen = new HashSet<>();
        seen.add(target.immutable());
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(target.immutable());
        while (!queue.isEmpty() && out.size() < VEIN_CAP) {
            BlockPos at = queue.poll();
            for (int dx = -1; dx <= 1 && out.size() < VEIN_CAP; dx++) {
                for (int dy = -1; dy <= 1 && out.size() < VEIN_CAP; dy++) {
                    for (int dz = -1; dz <= 1 && out.size() < VEIN_CAP; dz++) {
                        BlockPos next = at.offset(dx, dy, dz);
                        if (pool.contains(next) && seen.add(next)) {
                            out.add(next);
                            queue.add(next);
                        }
                    }
                }
            }
        }
        return out;
    }
}
