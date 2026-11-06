package adris.altoclef.util.helpers;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;

// the pure parts of "where can i actually bucket this". everything takes fluid states (or a lookup of them) so a test
// can drive it without a world
public final class FluidSources {

    private FluidSources() {
    }

    // a source with none of the same fluid on top of it. this is the only kind of water or lava that a scan budget
    // should be spent on: flowing and submerged ones are most of any lake, and none of them can be bucketed from the
    // top anyway (see WorldHelper.isSourceBlock, it throws the same ones out)
    public static boolean exposedSource(FluidState self, FluidState above) {
        return self.isSource() && !above.getType().isSame(self.getType());
    }

    // the first of a nearest-first list that passes, looking at no more than maxTests of them. the predicate is the
    // expensive part (reachability), the cap is what keeps a bad lake from eating a tick
    public static Optional<BlockPos> firstPassing(List<BlockPos> nearestFirst, Predicate<BlockPos> passes, int maxTests) {
        int n = Math.min(nearestFirst.size(), maxTests);
        for (int i = 0; i < n; i++) {
            if (passes.test(nearestFirst.get(i))) return Optional.of(nearestFirst.get(i));
        }
        return Optional.empty();
    }

    // follows a falling column or a flowing stream back to what feeds it. up while there is fluid on top, and at the
    // top, if it is not a source, sideways to the neighbour with the most fluid (flow only ever spreads from a taller
    // level to a shorter one, so that is where it came from). maxMoves is the cap on all of that together, a stream
    // that wanders off for 40 blocks is not worth chasing
    public static Optional<BlockPos> climbToSource(Function<BlockPos, FluidState> fluidAt, BlockPos start, int maxMoves) {
        FluidState first = fluidAt.apply(start);
        if (first.isEmpty()) return Optional.empty();
        Fluid kind = first.getType();
        Set<BlockPos> seen = new HashSet<>();
        BlockPos pos = start;
        for (int moves = 0; moves <= maxMoves; moves++) {
            FluidState here = fluidAt.apply(pos);
            if (!sameFluid(here, kind) || !seen.add(pos)) return Optional.empty();
            BlockPos up = pos.above();
            if (sameFluid(fluidAt.apply(up), kind)) {
                pos = up;
                continue;
            }
            if (here.isSource()) return Optional.of(pos);
            BlockPos feeder = null;
            int tallest = here.getAmount();
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos next = pos.relative(side);
                FluidState there = fluidAt.apply(next);
                if (sameFluid(there, kind) && there.getAmount() > tallest) {
                    tallest = there.getAmount();
                    feeder = next;
                }
            }
            if (feeder == null) return Optional.empty();
            pos = feeder;
        }
        return Optional.empty();
    }

    // how much of a pool flood looks at. a lake is a few hundred cells, an ocean is not something to ban one cell at a time
    public static final int FLOOD_CAP = 1024;

    // every cell 6-way connected to start that inPool says yes to, start included, at most cap of them. same idea as
    // ConstructNetherPortalBucketTask.lakeSize, except it hands the cells back and the caller says what counts as the pool
    // (flowing cells between two patches of sources make them one pool, baritone sees it that way too). breadth first so a
    // capped flood is the part nearest start, not one long tendril
    public static Set<BlockPos> flood(Predicate<BlockPos> inPool, BlockPos start, int cap) {
        Set<BlockPos> pool = new LinkedHashSet<>();
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> todo = new ArrayDeque<>();
        todo.add(start.immutable());
        while (!todo.isEmpty() && pool.size() < cap) {
            BlockPos pos = todo.poll();
            if (!seen.add(pos) || !inPool.test(pos)) continue;
            pool.add(pos);
            for (Direction side : Direction.values()) {
                BlockPos next = pos.relative(side);
                if (!seen.contains(next)) todo.add(next);
            }
        }
        return pool;
    }

    private static boolean sameFluid(FluidState state, Fluid kind) {
        return !state.isEmpty() && state.getType().isSame(kind);
    }
}
