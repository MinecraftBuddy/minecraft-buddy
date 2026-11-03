package net.milenco.buddy.mixin.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.milenco.buddy.client.AfkHelperClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Another player starts or stops breaking a block: the server's destruction packet (the breaker, the block, a stage
 * or -1). The tap that steers the alt is read from it (Tap).
 */
@Mixin(ClientLevel.class)
public class BlockProgressMixin {
	@Inject(method = "destroyBlockProgress", at = @At("HEAD"))
	private void buddy$progress(int id, BlockPos pos, int progress, CallbackInfo ci) {
		AfkHelperClient.blockProgress(id, pos, progress);
		net.milenco.buddy.client.World3d.progress(id, pos, progress);
	}
}
