package adris.altoclef.util.helpers;

import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

// which crops are worth breaking. a young carrot gives the one carrot you planted back, so the farm was a field of
// "food" that cost a swing each and fed nobody. CollectFoodTask only asked wheat (the rest were "not wheat so do NOT
// reject") and CollectCropTask only asked when replanting was on
public final class CropRules {
    private CropRules() {
    }

    // fully grown. wheat, carrots and potatoes top out at age 7, beetroots at 3, isMaxAge knows which. not a crop is not ripe
    public static boolean ripe(BlockState state) {
        return state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state);
    }
}
