package net.milenco.buddy.mixin.client;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.milenco.buddy.client.AfkHelperClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A player near the alt died: the server's death event (3), sent right after the player's things fell. The health is no sign:
 * a player who respawns at once is gone from the world before it reads 0 (measured on the test server, 20 Oct 2026).
 * A mob's death is the same event: the pen counts a kill of one it hit (0.4.80).
 */
@Mixin(LivingEntity.class)
public class DeathEventMixin {
	@Inject(method = "handleEntityEvent", at = @At("HEAD"))
	private void buddy$died(byte id, CallbackInfo ci) {
		if (id != 3) return;
		if ((Object) this instanceof Player player) AfkHelperClient.playerDied(player);
		else AfkHelperClient.mobDied(((LivingEntity) (Object) this).getId());
	}
}
