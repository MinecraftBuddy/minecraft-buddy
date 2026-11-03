package net.milenco.buddy.mixin.client;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.milenco.buddy.client.AfkConfig;
import net.milenco.buddy.client.AfkHelperClient;
import net.milenco.buddy.client.Containers;
import net.milenco.buddy.client.Exchange;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Eating is "using". If you happen to look at a sign or chest, vanilla opens that instead of eating.
 * With food in hand we skip the block interaction (PASS), so vanilla goes on to using the item = eating.
 * When AFK for every block; outside AFK only for signs (opening a chest with food in your hand must just work).
 * Not for a click the mod makes itself while it holds food that grows (carrots, potatoes): see AfkHelperClient.useOn.
 */
@Mixin(MultiPlayerGameMode.class)
public class GameModeMixin {
	/** Every click on a block, his own, the panel's or a job's: a container menu that opens next is that block's (Containers). */
	@Inject(method = "useItemOn", at = @At("HEAD"))
	private void buddy$noteUsedBlock(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
		Containers.used(hit.getBlockPos());
	}

	@Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
	private void buddy$eatInsteadOfBlockUse(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
		if (AfkHelperClient.ownUse) return;   // the mod plants a carrot or opens a chest with one in hand: that click is meant for the block
		ItemStack st = player.getItemInHand(hand);
		if (st.isEmpty() || (st.get(DataComponents.FOOD) == null && st.getItem() != net.minecraft.world.item.Items.OMINOUS_BOTTLE)) return;
		boolean sign = player.level().getBlockState(hit.getBlockPos()).getBlock() instanceof SignBlock;
		if (AfkConfig.get().afk || sign) {
			Exchange.emit("blocked_use", "block", player.level().getBlockState(hit.getBlockPos()).getBlock().getDescriptionId(), "afk", AfkConfig.get().afk);
			cir.setReturnValue(InteractionResult.PASS);
		}
	}
}
