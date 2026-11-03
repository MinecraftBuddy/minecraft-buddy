package net.milenco.buddy.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.milenco.buddy.client.AfkHelperClient;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The alt answers a server's resource pack itself, by the server's profile: a client without a screen otherwise hung
 * on the question, which {@code acceptTextures} in its {@code servers.dat} only answered for one server. Also for a
 * pack sent halfway, after a switch behind a proxy.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public class ResourcePackMixin {
	@Shadow @Final protected ServerData serverData;
	@Shadow @Final protected Minecraft minecraft;

	// after the hand-over to the thread that handles packets: the call before it runs on the network's thread and
	// throws to go there (in 26.3 that is the packet processor, not the render thread)
	@Inject(method = "handleResourcePackPush", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V", shift = At.Shift.AFTER))
	private void buddy$answerPack(ClientboundResourcePackPushPacket packet, CallbackInfo ci) {
		if (serverData == null) return;
		Boolean take = AfkHelperClient.takePack(packet.required(), serverData.ip);
		if (take == null) return;
		serverData.setResourcePackStatus(take ? ServerData.ServerPackStatus.ENABLED : ServerData.ServerPackStatus.DISABLED);
		if (take) minecraft.getDownloadedPackSource().allowServerPacks();
		else minecraft.getDownloadedPackSource().rejectServerPacks();
	}
}
