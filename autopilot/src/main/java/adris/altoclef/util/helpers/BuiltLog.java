package adris.altoclef.util.helpers;

import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;

// the buddy's fork (Tuning built_log_skip, a fix of 21 Nov 2026, on): a log with a block no tree has in the 26 cells round it
// (planks, wooden stairs, slabs or fences, a banner) is part of something built, not a tree: a pillager outpost's tower (dark
// oak beside birch planks, banners on top), a village house, a shipwreck. the wood step climbed an outpost's tower for its
// logs and its pillagers shot the buddy dead at 4.6 game minutes (heel-120-mc3 r2)
public final class BuiltLog {
    private BuiltLog() {
    }

    public static boolean on() {
        return Tuning.get("built_log_skip", 0) > 0;
    }

    // whether any of the 26 cells round the log holds a foreign block
    public static boolean built(BlockPos log, Predicate<BlockPos> foreign) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if ((dx | dy | dz) == 0) continue;
                    if (foreign.test(log.offset(dx, dy, dz))) return true;
                }
            }
        }
        return false;
    }

    // a block no tree grows beside: what people build with
    public static boolean foreign(BlockState state) {
        return state.is(BlockTags.PLANKS) || state.is(BlockTags.WOODEN_STAIRS) || state.is(BlockTags.WOODEN_SLABS)
                || state.is(BlockTags.WOODEN_FENCES) || state.is(BlockTags.BANNERS);
    }
}
