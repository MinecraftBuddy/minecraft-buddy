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
import adris.altoclef.eventbus.events.ChatMessageEvent;
import adris.altoclef.eventbus.events.SystemChatEvent;
import net.minecraft.network.chat.Component;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.multiplayer.chat.ChatListener;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.PlayerChatMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatListener.class)
public class MixinAltoClefChatListener {

    @Inject(
            method = "handlePlayerChatMessage",
            at = @At("HEAD")
    )
    private void altoclef$onChatMessage(final PlayerChatMessage message, final GameProfile sender, final ChatType.Bound bound, final CallbackInfo ci) {
        // the butler listens while altoclef is idle, so no running check here. a throw would end up in the chat handler
        try {
            AltoClefBridge.publish(new ChatMessageEvent(message, sender, bound));
        } catch (Throwable t) {
            AltoClefBridge.onHookError(t);
        }
    }

    // the server's own lines ("Respawn point set") never go through handlePlayerChatMessage. the "no home bed" line comes in
    // here too, the client turns that game event into a system message itself
    @Inject(
            method = "handleSystemMessage",
            at = @At("HEAD")
    )
    private void altoclef$onSystemMessage(final Component message, final boolean flag, final CallbackInfo ci) {
        try {
            AltoClefBridge.publish(new SystemChatEvent(message.getString()));
        } catch (Throwable t) {
            AltoClefBridge.onHookError(t);
        }
    }
}
