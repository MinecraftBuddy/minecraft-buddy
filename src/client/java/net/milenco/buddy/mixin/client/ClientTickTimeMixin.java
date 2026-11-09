package net.milenco.buddy.mixin.client;

import net.milenco.buddy.client.ClientTicks;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The client tick's own time (ClientTicks): from the head of Minecraft.tick to its return. */
@Mixin(Minecraft.class)
public class ClientTickTimeMixin {
	@Inject(method = "tick", at = @At("HEAD"))
	private void buddy$tickBegins(CallbackInfo ci) { ClientTicks.begin(); }

	@Inject(method = "tick", at = @At("RETURN"))
	private void buddy$tickEnds(CallbackInfo ci) { ClientTicks.end(); }
}
