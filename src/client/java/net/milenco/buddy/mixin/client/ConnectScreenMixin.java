package net.milenco.buddy.mixin.client;

import io.netty.channel.ChannelFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.Connection;
import net.milenco.buddy.client.AfkHelperClient;
import net.milenco.buddy.client.ConnectAttempt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells the mod which server a connection attempt is for (a failed attempt never becomes the "current server"), and
 * lets the mod see and stop an attempt that hangs.
 */
@Mixin(ConnectScreen.class)
public class ConnectScreenMixin implements ConnectAttempt {
	@Shadow private volatile Connection connection;
	@Shadow private ChannelFuture channelFuture;
	@Shadow private volatile boolean aborted;

	@Inject(method = "startConnecting", at = @At("HEAD"))
	private static void buddy$connecting(Screen parent, Minecraft minecraft, ServerAddress address, ServerData server, boolean quickPlay, TransferState transfer, CallbackInfo ci) {
		AfkHelperClient.connectingTo(server);
	}

	@Override
	public boolean buddy$connected() {
		return connection != null;
	}

	/** The Cancel button's own steps (ConnectScreen.init). */
	@Override
	public void buddy$abort() {
		synchronized (this) {
			aborted = true;
			if (channelFuture != null) {
				channelFuture.cancel(true);
				channelFuture = null;
			}
			if (connection != null) connection.disconnect(ConnectScreen.ABORT_CONNECTION);
		}
	}
}
