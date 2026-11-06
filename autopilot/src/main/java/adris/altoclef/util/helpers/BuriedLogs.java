package adris.altoclef.util.helpers;

import net.minecraft.core.BlockPos;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

// logs we caught being buried. the scan says "not buried" for a chunk it can't read, so a log right at render distance
// flips every time the chunk comes and goes, and the walk flips with it. once it was buried it stays buried. one per
// task, a new task starts clean, and a tree on the surface never gets in here
public final class BuriedLogs {
    private final Set<BlockPos> caught = new HashSet<>();

    public boolean buried(BlockPos log, Predicate<BlockPos> scan) {
        if (caught.contains(log)) {
            return true;
        }
        if (!scan.test(log)) {
            return false;
        }
        caught.add(log.immutable());
        return true;
    }
}
