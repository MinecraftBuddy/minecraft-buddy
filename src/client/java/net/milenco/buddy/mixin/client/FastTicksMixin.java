package net.milenco.buddy.mixin.client;

import net.milenco.buddy.client.AfkConfig;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * For a practice server only (practiceFastTicks, off): the client ticks as fast as the server's tick rate. The game
 * takes the longer of its own 50 ms and the server's tick, so with {@code /tick rate 60} the world ran three times as
 * fast as the player in it, mobs and all (measured: the client at 20 ticks a second, the world at 59).
 */
@Mixin(Minecraft.class)
public class FastTicksMixin {
	@Inject(method = "getTickTargetMillis", at = @At("HEAD"), cancellable = true)
	private void buddy$fastTicks(float own, CallbackInfoReturnable<Float> cir) {
		if (!AfkConfig.get().practiceFastTicks) return;
		Minecraft mc = (Minecraft) (Object) this;
		if (mc.level != null && mc.level.tickRateManager().runsNormally()) cir.setReturnValue(mc.level.tickRateManager().millisecondsPerTick());
	}
}
