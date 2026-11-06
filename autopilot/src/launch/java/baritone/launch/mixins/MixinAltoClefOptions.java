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
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Options.class)
public class MixinAltoClefOptions {

    // altoclef turns pauseOnLostFocus off while a task runs so the game keeps going in the background. vanilla saves
    // options.txt from the options screens, F3+P, and on the way out, any of which can land mid task, and the user
    // never chose that value. their own goes in for the write and ours comes back after
    @Inject(
            method = "save",
            at = @At("HEAD")
    )
    private void altoclef$userValuesForSave(final CallbackInfo ci) {
        AltoClefBridge.beforeOptionsSave();
    }

    @Inject(
            method = "save",
            at = @At("RETURN")
    )
    private void altoclef$ourValuesAfterSave(final CallbackInfo ci) {
        AltoClefBridge.afterOptionsSave();
    }
}
