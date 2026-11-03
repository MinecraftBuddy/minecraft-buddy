package net.milenco.buddy.mixin.client;

import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.milenco.buddy.client.RaidState;
import net.milenco.buddy.client.Recorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.UUID;

/** Reads along with the boss bars: that is where "Overval - overwinning" or the number of raiders is shown. */
@Mixin(BossHealthOverlay.class)
public class BossBarMixin {
	@Shadow @org.spongepowered.asm.mixin.Final private Map<UUID, LerpingBossEvent> events;

	@Inject(method = "update", at = @At("TAIL"))
	private void buddy$afterUpdate(ClientboundBossEventPacket packet, CallbackInfo ci) {
		RaidState.update(events.values());
		Recorder.bossBars(events.values());
	}

	@Inject(method = "reset", at = @At("TAIL"))
	private void buddy$afterReset(CallbackInfo ci) {
		RaidState.update(java.util.List.of());
	}
}
