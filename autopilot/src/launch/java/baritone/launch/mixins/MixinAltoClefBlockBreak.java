/*
 * This file is part of Soprano.
 *
 * Soprano is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Soprano is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Soprano.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.launch.mixins;

import baritone.altoclef.AltoClefBridge;
import adris.altoclef.eventbus.events.BlockBreakingCancelEvent;
import adris.altoclef.eventbus.events.BlockBreakingEvent;
import baritone.utils.accessor.IClientBlockBreak;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public abstract class MixinAltoClefBlockBreak implements IClientBlockBreak {

    // baritone pokes stopDestroyBlock every other frame even mid break, so a cancel only counts when it shows up 3 calls after the last continue
    @Unique
    private int altoclef$breakCancelFrames;

    @Accessor("destroyProgress")
    @Override
    public abstract float getCurrentBreakingProgress();

    @Inject(
            method = "continueDestroyBlock",
            at = @At("HEAD")
    )
    private void altoclef$onBreakUpdate(final BlockPos pos, final Direction direction, final CallbackInfoReturnable<Boolean> cir) {
        altoclef$breakCancelFrames = 2;
        try {
            AltoClefBridge.publish(new BlockBreakingEvent(pos.immutable(), getCurrentBreakingProgress()));
        } catch (Throwable t) {
            AltoClefBridge.onHookError(t);
        }
    }

    @Inject(
            method = "stopDestroyBlock",
            at = @At("HEAD")
    )
    private void altoclef$onBreakCancel(final CallbackInfo ci) {
        if (altoclef$breakCancelFrames-- == 0) {
            AltoClefBridge.publish(new BlockBreakingCancelEvent());
        }
    }

    @Inject(
            method = "useItemOn",
            at = @At("HEAD")
    )
    private void altoclef$onUseItemOn(final LocalPlayer player, final InteractionHand hand, final BlockHitResult hitResult, final CallbackInfoReturnable<InteractionResult> cir) {
        // soprano has its own BlockInteractEvent in the api, this one is altoclef's
        if (hitResult != null) {
            try {
                AltoClefBridge.publish(new adris.altoclef.eventbus.events.BlockInteractEvent(hitResult));
                baritone.pathing.movement.LavaWakeLog.click(player, hand, hitResult);
            } catch (Throwable t) {
                AltoClefBridge.onHookError(t);
            }
        }
    }
}
