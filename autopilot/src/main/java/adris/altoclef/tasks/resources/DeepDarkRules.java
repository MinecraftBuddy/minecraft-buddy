package adris.altoclef.tasks.resources;

import adris.altoclef.trackers.BanPolicy;
import adris.altoclef.trackers.Bans;
import baritone.api.utils.Dimension;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

// wool in the deep dark is an ancient city's carpet. one swing there wakes a sensor, a few more and the shrieker brings the
// warden, and the warden does not care about our iron armour. so it goes in the ban book for the run (same idea as
// DangerFilter's outpost wool) and the tracker stops offering it. pure past the predicates, the wool task hands in the world
public final class DeepDarkRules {
    // wool has no honest source down here whatever the biome says. sheep don't spawn this low and nobody carpets a cave for fun
    private static final int LOWEST_WOOL_Y = 30;

    private DeepDarkRules() {
    }

    public static boolean bansWool(boolean deepDark, boolean wool) {
        return deepDark && wool;
    }

    // low down an unloaded chunk counts as wool too, that's the one that gets walked at from far away. higher up it has no biome
    // worth asking about (and no block either), so the next pass gets it once it loads
    static boolean bans(BlockPos pos, Predicate<BlockPos> loaded, Predicate<BlockPos> wool, Predicate<BlockPos> deepDark) {
        if (pos.getY() < LOWEST_WOOL_Y) {
            return !loaded.test(pos) || wool.test(pos);
        }
        return loaded.test(pos) && bansWool(deepDark.test(pos), wool.test(pos));
    }

    // one pass over the tracked wool. one line for the lot, the already banned ones are quiet (Bans.banAll)
    public static int banPass(Bans bans, Dimension dim, List<BlockPos> tracked, Predicate<BlockPos> loaded,
                              Predicate<BlockPos> wool, Predicate<BlockPos> deepDark) {
        List<Bans.Key> keys = new ArrayList<>();
        long sumX = 0;
        long sumZ = 0;
        for (BlockPos pos : tracked) {
            if (!bans(pos, loaded, wool, deepDark)) {
                continue;
            }
            keys.add(Bans.Key.block(dim, pos.getX(), pos.getY(), pos.getZ()));
            sumX += pos.getX();
            sumZ += pos.getZ();
        }
        if (keys.isEmpty()) {
            return 0;
        }
        // "near" the middle of the lot, an ancient city is one blob anyway
        return BanPolicy.deepDarkWool(bans, keys, Math.floorDiv(sumX, keys.size()), Math.floorDiv(sumZ, keys.size()));
    }
}
