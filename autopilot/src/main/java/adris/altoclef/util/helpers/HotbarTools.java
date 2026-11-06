package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.util.slots.PlayerSlot;
import baritone.Baritone;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

// baritone's ToolSet only looks at the hotbar and InventoryBehavior only ever brings a pickaxe down, so the axe or shovel
// GatherBillTask made for the job sat in the bag and every log got punched. one swap into a hotbar slot nobody else
// shuffles: 0 is the pickaxe's, 1 is forceEquipItem's, 7 and 8 are the throwaways. 2-6 only see temp swaps, and those
// take empty slots first
public final class HotbarTools {

    private HotbarTools() {
    }

    // the axe for logs and planks-y stuff, the shovel for dirt and sand. pickaxes are InventoryBehavior's job already
    public static void forBlocks(AltoClef mod, Block... blocks) {
        boolean axe = false;
        boolean shovel = false;
        for (Block block : blocks) {
            axe |= block.defaultBlockState().is(BlockTags.MINEABLE_WITH_AXE);
            shovel |= block.defaultBlockState().is(BlockTags.MINEABLE_WITH_SHOVEL);
        }
        if (axe) bringDown(mod, "axe");
        if (shovel) bringDown(mod, "shovel");
        if (Baritone.settings().toolWearChain.value) {
            // slot 0 keeps the fastest pick (InventoryBehavior), this is the cheap one the wear chain wants next to it
            for (Block block : blocks) {
                if (block.defaultBlockState().is(BlockTags.MINEABLE_WITH_PICKAXE) && bringDownChainPick(mod, block.defaultBlockState())) {
                    return;
                }
            }
        }
    }

    // the pick the wear chain wants for this block, from the bag into 2-6, unless the hotbar already has one that is as cheap.
    // true when a swap went out
    private static boolean bringDownChainPick(AltoClef mod, BlockState state) {
        Inventory inv = usableInventory(mod);
        if (inv == null) return false;
        List<ItemStack> all = new ArrayList<>(36);
        for (int i = 0; i < 36; i++) {
            all.add(inv.getItem(i));
        }
        int want = ToolPick.chainIndex(all, state);
        // nothing that can break it, or it's on the bar already
        if (want < 9) return false;
        int have = ToolPick.chainIndex(all.subList(0, 9), state);
        if (have >= 0 && ToolPick.chainOrder(ToolPick.optionOf(all.get(have), state), ToolPick.optionOf(all.get(want), state)) <= 0) {
            return false;
        }
        int to = slotFor(inv, "pickaxe");
        if (to < 0) return false;
        swapIn(mod, want, to);
        return true;
    }

    // a swap click goes to whatever menu is open, with a chest up the slot numbers would hit the chest
    private static Inventory usableInventory(AltoClef mod) {
        LocalPlayer player = mod.getPlayer();
        if (player == null || player.containerMenu != player.inventoryMenu || mod.getExtraBaritoneSettings().isInteractionPaused()) {
            return null;
        }
        return player.getInventory();
    }

    // SWAP with the hotbar index as the button, the selected slot stays where it is. through baritone's gate so
    // allowInventory, the move delay and standing still hold here too (2b2t does not love a swap mid sprint)
    private static void swapIn(AltoClef mod, int from, int to) {
        mod.getClientBaritone().getInventoryBehavior().inventoryMoveIfAllowed(
                () -> mod.getSlotHandler().clickSlot(new PlayerSlot(from), to, ContainerInput.SWAP));
    }

    // the best one of this ItemHelper.toolKind from the bag into the hotbar, unless the hotbar has one already
    public static void bringDown(AltoClef mod, String kind) {
        Inventory inv = usableInventory(mod);
        if (inv == null) {
            return;
        }
        int best = -1;
        int bestUses = -1;
        boolean bestWorn = false;
        boolean hotbarWorn = false;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || !kind.equals(ItemHelper.toolKind(stack.getItem()))) continue;
            // one in the hotbar already is fine, unless it's worn out. then the fresh one GatherBillTask made comes down too
            boolean worn = ToolWear.wornOut(stack.getDamageValue(), stack.getMaxDamage());
            if (i < 9) {
                if (!worn) return;
                hotbarWorn = true;
                continue;
            }
            // what's left on it, not what it had new. a stone axe on its last legs isn't better than a fresh wooden one
            int uses = stack.getMaxDamage() - stack.getDamageValue();
            if (uses > bestUses) {
                best = i;
                bestUses = uses;
                bestWorn = worn;
            }
        }
        // worn for worn is a swap every tick for nothing
        if (best < 0 || (hotbarWorn && bestWorn)) return;
        int to = slotFor(inv, kind);
        if (to < 0) return;
        swapIn(mod, best, to);
    }

    // an empty one of 2-6 that isn't in our hand, else one of those that isn't holding a tool (so the axe and the shovel
    // don't take turns kicking each other out), else a worn one of the same kind, the swap sends it up to the bag.
    // -1 when 2-6 are all good tools
    private static int slotFor(Inventory inv, String kind) {
        int selected = inv.getSelectedSlot();
        for (int h = 2; h <= 6; h++) {
            if (h != selected && inv.getItem(h).isEmpty()) return h;
        }
        for (int h = 6; h >= 2; h--) {
            if (h != selected && !ItemHelper.isTool(inv.getItem(h).getItem())) return h;
        }
        for (int h = 6; h >= 2; h--) {
            ItemStack stack = inv.getItem(h);
            if (h != selected && kind.equals(ItemHelper.toolKind(stack.getItem()))
                    && ToolWear.wornOut(stack.getDamageValue(), stack.getMaxDamage())) return h;
        }
        return -1;
    }
}
