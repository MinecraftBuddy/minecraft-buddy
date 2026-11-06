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
import adris.altoclef.eventbus.events.ScreenOpenEvent;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// setScreen moved off Minecraft onto Gui in 26.3
@Mixin(Gui.class)
public class MixinAltoClefScreen {

    // setScreen is the middle of everything (death, disconnect, chat), nothing of altoclef's gets to throw out of it
    @Unique
    private static void altoclef$publishScreen(final Screen screen, final boolean preOpen) {
        try {
            AltoClefBridge.publish(new ScreenOpenEvent(screen, preOpen));
        } catch (Throwable t) {
            AltoClefBridge.onHookError(t);
        }
    }

    @Inject(
            method = "setScreen",
            at = @At("HEAD")
    )
    private void altoclef$onScreenOpenBegin(final Screen screen, final CallbackInfo ci) {
        altoclef$publishScreen(screen, true);
    }

    // setScreen only has the one return, so TAIL really is the end
    @Inject(
            method = "setScreen",
            at = @At("TAIL")
    )
    private void altoclef$onScreenOpenEnd(final Screen screen, final CallbackInfo ci) {
        altoclef$publishScreen(screen, false);
    }
}
