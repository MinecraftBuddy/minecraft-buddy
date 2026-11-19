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

import adris.altoclef.util.helpers.ClockStep;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// the buddy's fork (Tuning clock_no_step_back, ClockStep): the server's time packet never sets the client's game time back;
// the client's own count holds still for as many ticks instead. the packet comes on the packet processor's thread, the
// count ticks on the game's
@Mixin(ClientLevel.class)
public class MixinClientLevelClock {

    @Unique
    private volatile long altoclef$hold;

    @Inject(method = "setTimeFromServer", at = @At("HEAD"), cancellable = true)
    private void altoclef$noStepBack(long gameTime, CallbackInfo ci) {
        if (!ClockStep.on()) {
            altoclef$hold = 0;
            return;
        }
        long hold = ClockStep.hold(((ClientLevel) (Object) this).getGameTime(), gameTime);
        if (hold > 0) {
            altoclef$hold = hold;
            ci.cancel();
        } else {
            altoclef$hold = 0;
        }
    }

    @Inject(method = "tickTime", at = @At("HEAD"), cancellable = true)
    private void altoclef$holdStill(CallbackInfo ci) {
        long hold = altoclef$hold;
        if (hold > 0) {
            altoclef$hold = hold - 1;
            ci.cancel();
        }
    }
}
