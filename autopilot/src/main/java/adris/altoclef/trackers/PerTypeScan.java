package adris.altoclef.trackers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

// baritone's world scan has one budget for the whole filter, nearest first. track coal, iron, copper and diamond at
// once and the surface coal eats the budget before the scan ever gets down to the deepslate diamond, so the scan
// reports diamond as not existing. this runs the scan until every type either has its own share or has been
// swept completely: types that filled up drop out and the starved ones go again without the hogs in the filter.
// each round retires at least one type (pigeonhole), so it is at most types.length scans, and in practice it is one
// scan, or two when something common is on the list
final class PerTypeScan {

    private PerTypeScan() {
    }

    interface Scan {
        // nearest first, at most max results across all of types, like MineProcess.searchWorld
        Scanned scan(Block[] types, int max);
    }

    // raw is how many hits the scan saw before anything got thrown out of hits (pruned, filtered). a scan that comes
    // back short of its budget has only run out of world if raw is short too
    record Scanned(List<BlockPos> hits, int raw) {
        static Scanned of(List<BlockPos> hits) {
            return new Scanned(hits, hits.size());
        }
    }

    // blockAt may return null for "can't tell", those positions are dropped
    static Map<Block, List<BlockPos>> run(Block[] types, int perType, Scan scan, Function<BlockPos, Block> blockAt) {
        Map<Block, List<BlockPos>> result = new LinkedHashMap<>();
        Set<Block> remaining = new HashSet<>();
        for (Block block : types) {
            remaining.add(block);
            result.put(block, new ArrayList<>());
        }
        for (int round = 0; round < types.length && !remaining.isEmpty(); round++) {
            Block[] asking = remaining.toArray(Block[]::new);
            int budget = perType * asking.length;
            Scanned scanned = scan.scan(asking, budget);
            for (Block block : asking) {
                result.get(block).clear();
            }
            for (BlockPos pos : scanned.hits()) {
                Block block = blockAt.apply(pos);
                if (block == null || !remaining.contains(block)) continue;
                List<BlockPos> list = result.get(block);
                if (list.size() < perType) {
                    list.add(pos);
                }
            }
            // under budget means the scan ran out of world, nothing more to find for anyone still asking. judged on what
            // the scan saw, not on what survived prune: counting survivors called every type with pruned hits (flowing
            // water, ores in bedrock) empty and covered after one scan, the lake was right there
            if (scanned.raw() < budget) {
                break;
            }
            boolean retired = remaining.removeIf(block -> result.get(block).size() >= perType);
            if (!retired) {
                // saturated by positions that aren't any of ours (changed since the scan?). going again would
                // just do the same thing
                break;
            }
        }
        return result;
    }
}
