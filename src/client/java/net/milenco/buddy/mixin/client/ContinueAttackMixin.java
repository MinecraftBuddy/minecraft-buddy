package net.milenco.buddy.mixin.client;

import net.minecraft.client.Minecraft;
import net.milenco.buddy.client.AfkHelperClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The game stops every block breaking each tick its own attack key is up (continueAttack(false) calls stopDestroyBlock),
 * and the panel breaks without that key: the block it breaks is the page's, not the game's crosshair. So while the panel
 * holds the left button on a block the game's own stop is left out; otherwise the server was told to abort every tick,
 * the cracks were gone each tick, and a press on the same block again counted on in the buddy's game alone (31 Oct 2026).
 */
@Mixin(Minecraft.class)
public class ContinueAttackMixin {
	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void buddy$panelBreaks(boolean down, CallbackInfo ci) {
		if (!down && AfkHelperClient.panelBreaking()) ci.cancel();
	}
}
