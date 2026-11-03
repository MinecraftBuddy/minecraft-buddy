package net.milenco.buddy.mixin.client;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import net.milenco.buddy.client.Recorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * What the player does reaches the server as a packet, whichever key or mouse button made it: a click in a menu,
 * a block used, a sign typed. One place to read along, instead of a hook in every screen.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public class SendPacketMixin {
	@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
	private void buddy$recordSent(Packet<?> packet, CallbackInfo ci) {
		Recorder.sent(packet);
	}
}
