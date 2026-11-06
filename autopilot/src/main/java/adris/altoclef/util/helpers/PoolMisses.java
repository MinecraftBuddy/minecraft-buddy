package adris.altoclef.util.helpers;

import baritone.api.utils.Dimension;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

// bucket misses counted per pool instead of per source. a pool we can't path into fails once per source, and the next pick
// is always the source next door, so a per source count never gets anywhere. the second miss anywhere in one pool and the
// whole pool is the caller's to give up on. same shape as TreeStrikes, minus the clock: the ban has one of its own
public final class PoolMisses {

    private record Miss(Dimension dim, BlockPos pos) {
    }

    private final Set<Miss> misses = new HashSet<>();

    // one more miss at `at`, with `pool` the flood from it (FluidSources.flood). empty while it is the first miss in that
    // pool, which gets remembered. otherwise the pool's cells that isSource says yes to (flowing cells were never something
    // to pick, no point banning them) and the misses in the pool are forgotten, the ban is the memory now
    public List<BlockPos> miss(Dimension dim, BlockPos at, Set<BlockPos> pool, Predicate<BlockPos> isSource) {
        boolean again = misses.removeIf(m -> m.dim == dim && pool.contains(m.pos));
        if (!again) {
            misses.add(new Miss(dim, at.immutable()));
            return List.of();
        }
        List<BlockPos> sources = new ArrayList<>();
        for (BlockPos cell : pool) {
            if (isSource.test(cell)) {
                sources.add(cell);
            }
        }
        return sources;
    }

    // misses standing against pools, for the tests
    int open() {
        return misses.size();
    }
}
