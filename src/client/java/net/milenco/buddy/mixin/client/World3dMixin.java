package net.milenco.buddy.mixin.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.milenco.buddy.client.World3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * What changes in his world, for the panel's 3D view (World3d): a block that changed (from the server, or his own), a
 * chunk the server sent, and a block broken near him (the game's level event 2001: his own and other players').
 */
@Mixin(ClientLevel.class)
public class World3dMixin {
	@Inject(method = "sendBlockUpdated", at = @At("HEAD"))
	private void buddy$blockChanged(BlockPos pos, BlockState before, BlockState after, int flags, CallbackInfo ci) {
		World3d.changed(pos);
	}

	@Inject(method = "addDestroyBlockEffect", at = @At("HEAD"))
	private void buddy$blockBroken(BlockPos pos, BlockState state, CallbackInfo ci) {
		World3d.broke(pos, state);
	}

	@Inject(method = "onChunkLoaded", at = @At("HEAD"))
	private void buddy$chunkLoaded(ChunkPos pos, CallbackInfo ci) {
		World3d.chunkLoaded(pos);
	}
}
