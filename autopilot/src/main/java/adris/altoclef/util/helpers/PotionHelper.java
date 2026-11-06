package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;

// a drink is told by what is in the bottle: a water bottle, an awkward potion and one of fire resistance are all the one item
public final class PotionHelper {
    private PotionHelper() {
    }

    public static boolean is(ItemStack stack, Holder<Potion> potion) {
        if (!stack.is(Items.POTION)) return false;
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        return contents != null && contents.is(potion);
    }

    // the bag, the hotbar and the offhand (not the cursor, as ResourceTask's finish leaves it out too)
    public static int count(AltoClef mod, Holder<Potion> potion) {
        if (mod.getPlayer() == null) return 0;
        Inventory inventory = mod.getPlayer().getInventory();
        int n = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (is(stack, potion)) n += stack.getCount();
        }
        return n;
    }
}
