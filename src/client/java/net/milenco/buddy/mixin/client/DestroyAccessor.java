package net.milenco.buddy.mixin.client;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The block being broken, for the cracks on it in the panel's 3D view (World3d). */
@Mixin(MultiPlayerGameMode.class)
public interface DestroyAccessor {
	@Accessor("destroyBlockPos") BlockPos buddy$destroyBlockPos();
}
