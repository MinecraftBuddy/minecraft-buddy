package net.milenco.buddy.mixin.client;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.milenco.buddy.client.MenuLog;
import net.milenco.buddy.client.Recorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The title and the kind of a server menu come in this packet and nowhere else: the menu object keeps neither,
 * and the alt has no screen to read the title from.
 */
@Mixin(ClientPacketListener.class)
public class OpenScreenMixin {
	// at the tail: the head also runs on the network thread, which hands the packet on to the game thread
	@Inject(method = "handleOpenScreen", at = @At("TAIL"))
	private void buddy$menuOpened(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
		MenuLog.opened(packet.getContainerId(), packet.getType(), packet.getTitle());
	}

	// the big texts in the middle of the screen and the line above the hotbar: a server says a lot there that never reaches chat
	@Inject(method = "setTitleText", at = @At("TAIL"))
	private void buddy$title(ClientboundSetTitleTextPacket packet, CallbackInfo ci) { Recorder.shown("title", packet.text()); }

	@Inject(method = "setSubtitleText", at = @At("TAIL"))
	private void buddy$subtitle(ClientboundSetSubtitleTextPacket packet, CallbackInfo ci) { Recorder.shown("subtitle", packet.text()); }

	@Inject(method = "setActionBarText", at = @At("TAIL"))
	private void buddy$actionBar(ClientboundSetActionBarTextPacket packet, CallbackInfo ci) { Recorder.shown("actionbar", packet.text()); }
}
