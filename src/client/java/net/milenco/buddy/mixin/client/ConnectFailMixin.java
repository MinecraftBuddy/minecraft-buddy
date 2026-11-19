package net.milenco.buddy.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The game's connector thread builds the "could not connect" text from the error's message. Some errors carry none
 * (NoRouteToHostException while the server's host is down): building the text then throws, the thread dies before the
 * failure screen comes up, the connect screen stays, and the game refuses every later attempt as "already connecting".
 * The error's name stands in for a missing message.
 */
@Mixin(targets = "net.minecraft.client.gui.screens.ConnectScreen$1")
public class ConnectFailMixin {
	@WrapOperation(method = "run", at = @At(value = "INVOKE", target = "Ljava/lang/Exception;getMessage()Ljava/lang/String;"))
	private String buddy$message(Exception e, Operation<String> original) {
		String message = original.call(e);
		return message != null ? message : e.getClass().getSimpleName();
	}
}
