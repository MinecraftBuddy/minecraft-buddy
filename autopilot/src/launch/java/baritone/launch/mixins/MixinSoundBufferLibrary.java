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

import adris.altoclef.ui.gui.mc.MenuSounds;
import com.mojang.blaze3d.audio.SoundBuffer;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

// the title screen's sounds come off our classpath, not a resource pack: on fabric a mod's assets only become a pack
// with fabric api installed, and we don't need fabric api. soprano: paths get decoded by MenuSounds into vanilla's own
// cache (the blips) or streamed straight off the jar (the menu music), everything else goes the vanilla way. require
// 0 on both: if one ever stops applying the menu is just quiet
@Mixin(SoundBufferLibrary.class)
public abstract class MixinSoundBufferLibrary {

    @Shadow
    @Final
    private Map<Identifier, CompletableFuture<SoundBuffer>> cache;

    @Inject(
            method = "getCompleteBuffer(Lnet/minecraft/resources/Identifier;)Ljava/util/concurrent/CompletableFuture;",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void soprano$ourSounds(Identifier path, CallbackInfoReturnable<CompletableFuture<SoundBuffer>> cir) {
        if (MenuSounds.owns(path)) {
            cir.setReturnValue(cache.computeIfAbsent(path, MenuSounds::load));
        }
    }

    // looping is what the engine worked out (looping and no delay), MenuSounds.stream passes it on like vanilla does
    @Inject(
            method = "getStream(Lnet/minecraft/resources/Identifier;Z)Ljava/util/concurrent/CompletableFuture;",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void soprano$ourStreams(Identifier path, boolean looping,
                                    CallbackInfoReturnable<CompletableFuture<AudioStream>> cir) {
        if (MenuSounds.owns(path)) {
            cir.setReturnValue(MenuSounds.stream(path, looping));
        }
    }
}
