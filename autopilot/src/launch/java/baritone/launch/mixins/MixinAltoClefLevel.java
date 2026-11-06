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
import adris.altoclef.eventbus.events.BlockPlaceEvent;
import adris.altoclef.ui.gui.mc.ghost.SchematicGhostRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Level.class)
public class MixinAltoClefLevel {

    // ClientLevel.setBlock goes through Level.setBlock which calls this, so Level is the right place
    // (26.3 renamed it to updatePOIOnBlockStateChange, still the empty hook at the very end of setBlock)
    @Inject(
            method = "updatePOIOnBlockStateChange",
            at = @At("HEAD")
    )
    private void altoclef$onBlockStateChange(final BlockPos pos, final BlockState oldState, final BlockState newState, final CallbackInfo ci) {
        Level level = (Level) (Object) this;
        if (!level.isClientSide() || oldState == newState) {
            return;
        }
        // the schematic ghost wants every change, bot or no bot. BlockChangeEvent only sees the multi block packet,
        // a single placed block (the one you just put under a ghost) only shows up here
        SchematicGhostRenderer.blockChanged(pos.getX(), pos.getY(), pos.getZ());
        // integrated server levels land here on the server thread, and the event bus does not like that
        // and nobody is listening unless altoclef has the bot. idle, this fed the block tracker for nothing and the
        // cache grew across worlds
        if (!AltoClefBridge.isRunning()) {
            return;
        }
        try {
            if (!altoclef$hasBlock(level, oldState, pos) && altoclef$hasBlock(level, newState, pos)) {
                // immutable: a section update packet hands every block of the batch the same MutableBlockPos
                AltoClefBridge.publish(new BlockPlaceEvent(pos.immutable(), newState));
            }
        } catch (Throwable t) {
            // this is Level#setBlock, a throw here is the packet handler and a kick
            AltoClefBridge.onHookError(t);
        }
    }

    @Unique
    private static boolean altoclef$hasBlock(final Level level, final BlockState state, final BlockPos pos) {
        return !state.isAir() && state.isRedstoneConductor(level, pos);
    }
}
