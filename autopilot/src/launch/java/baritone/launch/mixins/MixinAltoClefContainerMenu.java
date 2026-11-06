/*
 * This file is part of Soprano.
 *
 * Soprano is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Soprano is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Soprano.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.launch.mixins;

import baritone.altoclef.AltoClefBridge;
import adris.altoclef.eventbus.events.SlotClickChangedEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Mixin(AbstractContainerMenu.class)
public class MixinAltoClefContainerMenu {

    @Unique
    private List<ItemStack> altoclef$beforeClick;

    // the old version wrapped the recursive doClick inside doClick, so it only ever saw one rare quickcraft case. clicked is the real front door
    @Inject(
            method = "clicked",
            at = @At("HEAD")
    )
    private void altoclef$snapshotSlots(final int slotIndex, final int button, final ContainerInput clickType, final Player player, final CallbackInfo ci) {
        // a click that throws never reaches RETURN, so never trust a leftover snapshot
        altoclef$beforeClick = null;
        // the integrated server clicks on its own thread too
        // the integrated server clicks on its own thread too. and nobody is listening unless altoclef has the bot
        if (!(player instanceof LocalPlayer) || !AltoClefBridge.isRunning()) {
            return;
        }
        try {
            List<Slot> slots = ((AbstractContainerMenu) (Object) this).slots;
            List<ItemStack> before = new ArrayList<>(slots.size());
            for (Slot slot : slots) {
                before.add(slot.getItem().copy());
            }
            altoclef$beforeClick = before;
        } catch (Throwable t) {
            AltoClefBridge.onHookError(t);
        }
    }

    @Inject(
            method = "clicked",
            at = @At("RETURN")
    )
    private void altoclef$publishChanges(final int slotIndex, final int button, final ContainerInput clickType, final Player player, final CallbackInfo ci) {
        List<ItemStack> before = altoclef$beforeClick;
        altoclef$beforeClick = null;
        if (before == null) {
            return;
        }
        // this is the end of every inventory click, nothing of altoclef's gets to throw out of it
        try {
            List<Slot> slots = ((AbstractContainerMenu) (Object) this).slots;
            int count = Math.min(before.size(), slots.size());
            for (int i = 0; i < count; i++) {
                ItemStack was = before.get(i);
                ItemStack now = slots.get(i).getItem();
                if (!ItemStack.matches(was, now)) {
                    // copy it, the live stack keeps mutating after we hand it out
                    AltoClefBridge.publish(new SlotClickChangedEvent(adris.altoclef.util.slots.Slot.getFromCurrentScreen(i), was, now.copy()));
                }
            }
        } catch (Throwable t) {
            AltoClefBridge.onHookError(t);
        }
    }
}
