package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.Items;

// the buddy's fork: a held use key alone did not raise the shield in the buddy's game. a drill with a lit creeper three
// blocks off had the key down, the game's own key down, no block being broken, the hands free and the creeper under the
// crosshair, and the player never once used an item: the shield's durability stayed at 336 through every attempt of the
// runner and the blast came through it (2 Nov 2026). so it goes up the way a right click raises it, as the buddy's own
// shield does: the off hand's use, with nothing in the main hand that has a use of its own. the callers hold the key
// besides, which keeps it up
public final class ShieldRaise {
    private ShieldRaise() {
    }

    public static void raise(AltoClef mod) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mod.getPlayer();
        if (p == null || mc.gameMode == null || p.isUsingItem()) return;
        if (!p.getOffhandItem().is(Items.SHIELD) || p.containerMenu != p.inventoryMenu) return;
        if (p.getCooldowns().isOnCooldown(p.getOffhandItem())) return;
        ItemStack main = p.getMainHandItem();
        if (!main.isEmpty() && main.getUseAnimation() != ItemUseAnimation.NONE) return;
        mc.gameMode.useItem(p, InteractionHand.OFF_HAND);
        _raises++;
        long now = System.currentTimeMillis();
        if (now - _told > 1000) {
            _told = now;
            // (a shield that drops every tick is raised every tick and never blocks: the count says so, 21 a second before the
            // buddy's own shield code let Soprano's be)
            adris.altoclef.Debug.logInternal("combat: shield up " + p.isUsingItem() + ", " + _raises + " raises in the last second");
            _raises = 0;
        }
    }

    private static long _told;
    private static int _raises;

    // the buddy's fork, Tuning rod_snowball, off by default: a snowball in the main hand is thrown by a held use key, so the
    // shield never goes up and the balls fly wherever the look is (zombified piglins hit by them killed the buddy, the strategy
    // trial's lane t3). before a shield's held key: a sword, else a pickaxe, else an empty hotbar slot in the hand, chosen as a
    // player picks a slot. false while the hand still holds a snowball: the caller holds no key this tick
    public static boolean handClear(AltoClef mod) {
        if (Tuning.get("rod_snowball", 0) <= 0) return true;
        LocalPlayer p = mod.getPlayer();
        if (p == null || !p.getMainHandItem().is(Items.SNOWBALL)) return true;
        var inventory = p.getInventory();
        int[] kinds = new int[9];
        for (int i = 0; i < 9; i++) {
            ItemStack s = inventory.getItem(i);
            kinds[i] = s.isEmpty() ? SnowballAim.KIND_EMPTY : s.is(net.minecraft.tags.ItemTags.SWORDS) ? SnowballAim.KIND_SWORD
                    : s.is(net.minecraft.tags.ItemTags.PICKAXES) ? SnowballAim.KIND_PICKAXE : SnowballAim.KIND_OTHER;
        }
        int slot = SnowballAim.shieldSlot(kinds);
        if (slot >= 0) inventory.setSelectedSlot(slot);
        boolean clear = !p.getMainHandItem().is(Items.SNOWBALL);
        long now = System.currentTimeMillis();
        if (now - _handTold > 1000) {
            _handTold = now;
            adris.altoclef.Debug.logInternal("strategy snowball: the snowball out of the hand for the shield, " + (clear ? "slot " + (slot + 1) : "no slot, no shield"));
        }
        return clear;
    }

    private static long _handTold;
}
