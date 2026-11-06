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
import adris.altoclef.eventbus.events.BlockBrokenEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Block.class)
public class MixinAltoClefBlock {

    @Inject(
            method = "playerWillDestroy",
            at = @At("HEAD")
    )
    private void altoclef$onBlockBroken(final Level level, final BlockPos pos, final BlockState state, final Player player, final CallbackInfoReturnable<BlockState> cir) {
        // the integrated server runs this too, on its own thread, and the event bus is a plain hashmap. and nobody
        // is listening unless altoclef has the bot
        if (!level.isClientSide() || !AltoClefBridge.isRunning()) {
            return;
        }
        try {
            BlockBrokenEvent evt = new BlockBrokenEvent();
            // the pos can be somebody else's mutable one
            evt.blockPos = pos.immutable();
            evt.blockState = state;
            evt.player = player;
            AltoClefBridge.publish(evt);
        } catch (Throwable t) {
            AltoClefBridge.onHookError(t);
        }
    }
}
