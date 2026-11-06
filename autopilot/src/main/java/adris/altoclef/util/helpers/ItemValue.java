package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.speedrun.gamer.GamerTask;
import java.util.Set;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

// how much a stack is worth to the run, in four buckets. the death pile picks by it (valuables first, nearest within a
// bucket) and the full bag uses it to say who may push whom out (DropRoom). one classifier so the two can't disagree
public final class ItemValue {

    // a pickaxe when we hold none: everything else at the pile is a dig away
    public static final int PICK = 0;
    // the stuff the end needs and nothing else gives back: pearls, eyes, rods, powder
    public static final int CORE = 1;
    // kit we would be sad to walk away from
    public static final int KIT = 2;
    public static final int REST = 3;

    private static final Set<Item> CORE_ITEMS = Set.of(Items.ENDER_PEARL, Items.ENDER_EYE, Items.BLAZE_ROD, Items.BLAZE_POWDER);

    private static final Set<Item> KIT_ITEMS = Set.of(Items.SHIELD, Items.DIAMOND, Items.OBSIDIAN, Items.FLINT_AND_STEEL,
            Items.BUCKET, Items.WATER_BUCKET, Items.LAVA_BUCKET, Items.GOLD_INGOT, Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE,
            Items.BOW, Items.CROSSBOW, Items.ARROW, Items.POTION, Items.SPLASH_POTION, Items.LINGERING_POTION,
            Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE, Items.IRON_SWORD, Items.DIAMOND_SWORD,
            Items.NETHERITE_SWORD, Items.IRON_AXE, Items.DIAMOND_AXE, Items.NETHERITE_AXE);

    private ItemValue() {
    }

    public static int tier(boolean pickWanted, boolean isPick, boolean core, boolean kit) {
        if (pickWanted && isPick) return PICK;
        if (core) return CORE;
        return kit ? KIT : REST;
    }

    public static boolean isCore(Item item) {
        return CORE_ITEMS.contains(item);
    }

    // the part of KIT the item alone answers: the list, beds, food we would eat
    public static boolean isKitItem(Item item) {
        if (KIT_ITEMS.contains(item)) {
            return true;
        }
        for (Item bed : ItemHelper.BED) {
            if (bed == item) return true;
        }
        FoodHelper.Kind food = FoodHelper.kindOf(item);
        return food == FoodHelper.Kind.NORMAL || food == FoodHelper.Kind.RESERVED;
    }

    // the world half: armor we would put on and anything somebody protects (the gamer's list, the important list,
    // enchanted or named stacks, the build reserve) count as kit too
    public static int tierOf(AltoClef mod, ItemStack stack, boolean pickWanted) {
        Item item = stack.getItem();
        boolean kit = isKitItem(item) || GamerTask.guards(item) || StorageHelper.wouldWear(mod, stack) || ItemHelper.isStackProtected(mod, stack);
        return tier(pickWanted, stack.is(ItemTags.PICKAXES), isCore(item), kit);
    }
}
