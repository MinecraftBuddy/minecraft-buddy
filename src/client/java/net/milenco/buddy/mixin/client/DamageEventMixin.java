package net.milenco.buddy.mixin.client;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.milenco.buddy.client.AfkHelperClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A mob took a blow: the server's damage event, with who dealt it and how (0.4.80). The pen's gesture is read from it
 * (Pen): the player he follows punches a monster in a mob farm with his bare hand. Only plain values are copied there,
 * for the next tick; the event itself is the game's as ever.
 */
@Mixin(LivingEntity.class)
public class DamageEventMixin {
	@Inject(method = "handleDamageEvent", at = @At("HEAD"))
	private void buddy$damaged(DamageSource source, CallbackInfo ci) {
		if (!((Object) this instanceof Player)) AfkHelperClient.damageEvent((LivingEntity) (Object) this, source);
	}
}
