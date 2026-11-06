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
import adris.altoclef.eventbus.events.GameOverlayEvent;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// the hud half of Gui.render became its own class (Hud) in 26.3, and rendering is extract-then-draw now
@Mixin(Hud.class)
public class MixinAltoClefGui {

    // RETURN so the hud lands on top of everything vanilla drew
    @Inject(
            method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
            at = @At("RETURN")
    )
    private void altoclef$onRenderHud(final GuiGraphicsExtractor graphics, final DeltaTracker deltaTracker, final CallbackInfo ci) {
        AltoClefBridge.renderHud(graphics);
    }

    @Inject(
            method = "setOverlayMessage",
            at = @At("HEAD")
    )
    private void altoclef$onSetOverlayMessage(final Component message, final boolean animate, final CallbackInfo ci) {
        if (!AltoClefBridge.isRunning()) {
            return;
        }
        try {
            AltoClefBridge.publish(new GameOverlayEvent(message.getString()));
        } catch (Throwable t) {
            AltoClefBridge.onHookError(t);
        }
    }
}
