package net.milenco.buddy.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.milenco.buddy.client.AfkHelperClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells the mod which server a connection attempt is for; a failed attempt never becomes the "current server". */
@Mixin(ConnectScreen.class)
public class ConnectScreenMixin {
	@Inject(method = "startConnecting", at = @At("HEAD"))
	private static void buddy$connecting(Screen parent, Minecraft minecraft, ServerAddress address, ServerData server, boolean quickPlay, TransferState transfer, CallbackInfo ci) {
		AfkHelperClient.connectingTo(server);
	}
}
