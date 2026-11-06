package adris.altoclef.util;

import adris.altoclef.Debug;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

public enum MiningRequirement implements Comparable<MiningRequirement> {
    HAND(Items.AIR), WOOD(Items.WOODEN_PICKAXE), STONE(Items.STONE_PICKAXE), IRON(Items.IRON_PICKAXE), DIAMOND(Items.DIAMOND_PICKAXE), NETHERITE(Items.NETHERITE_PICKAXE);

    private final Item _minPickaxeItem;
    private ItemStack _minPickaxe;

    MiningRequirement(Item minPickaxe) {
        _minPickaxeItem = minPickaxe;
    }

    public static MiningRequirement getMinimumRequirementForBlock(Block block) {
        if (block.defaultBlockState().requiresCorrectToolForDrops()) {
            for (MiningRequirement req : MiningRequirement.values()) {
                if (req == MiningRequirement.HAND) continue;
                ItemStack pick = req.getMinimumPickaxe();
                if (pick.isCorrectToolForDrops(block.defaultBlockState())) {
                    return req;
                }
            }
            Debug.logWarning("Failed to find ANY effective tool against: " + block + ". I assume netherite is not required anywhere, so something else probably went wrong.");
            return MiningRequirement.DIAMOND;
        }
        return MiningRequirement.HAND;
    }

    public ItemStack getMinimumPickaxe() {
        // a stack needs the item's components, and on 26.3 those only get bound once a world's registries load. TaskCatalogue
        // touches this enum in its static init, which can run before that, so build the stack on first use instead
        if (_minPickaxe == null) {
            _minPickaxe = _minPickaxeItem.getDefaultInstance();
        }
        return _minPickaxe;
    }

}
