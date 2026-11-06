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
import adris.altoclef.eventbus.events.PlayerCollidedWithEntityEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
public class MixinAltoClefPlayerTouch {

    // touch is literally just entity.playerTouch(this), so HEAD is the same moment as the old redirect and nobody has to fight over the invoke
    @Inject(
            method = "touch",
            at = @At("HEAD")
    )
    private void altoclef$onTouch(final Entity entity, final CallbackInfo ci) {
        Player self = (Player) (Object) this;
        // server players touch things too, and the bus is client thread only
        // and only while altoclef has the bot: touch runs every tick for every entity in reach, and the tracker
        // that eats these only drains while altoclef runs, idle it leaked entities and whole client worlds
        if (self instanceof LocalPlayer && AltoClefBridge.isRunning()) {
            try {
                AltoClefBridge.publish(new PlayerCollidedWithEntityEvent(self, entity));
            } catch (Throwable t) {
                AltoClefBridge.onHookError(t);
            }
        }
    }
}
