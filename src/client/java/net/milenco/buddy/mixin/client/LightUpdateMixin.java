package net.milenco.buddy.mixin.client;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.milenco.buddy.client.World3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Light the server sends that changed, and a block's own data (a sign's text): those sections go to the panel's 3D view
 *  again (World3d). A sign's text changes no block, so the view kept the sign it had. */
@Mixin(ClientPacketListener.class)
public class LightUpdateMixin {
	@Inject(method = "handleLightUpdatePacket", at = @At("TAIL"))
	private void buddy$light(ClientboundLightUpdatePacket packet, CallbackInfo ci) {
		var d = packet.lightData();
		World3d.lightChanged(packet.x(), packet.z(), d.skyYMask(), d.blockYMask(), d.emptySkyYMask(), d.emptyBlockYMask());
	}

	@Inject(method = "handleBlockEntityData", at = @At("TAIL"))
	private void buddy$blockData(ClientboundBlockEntityDataPacket packet, CallbackInfo ci) {
		World3d.changed(packet.getPos());
	}
}
