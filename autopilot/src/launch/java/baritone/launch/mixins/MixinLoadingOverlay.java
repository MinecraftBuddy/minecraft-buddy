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

import adris.altoclef.ui.gui.mc.BootScreen;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.IntSupplier;

// the boot screen. vanilla's overlay keeps running exactly as it does (reload, progress smoothing, the fade clock,
// removing itself), we only take its paint: the brand colour becomes black, the mojang logo and the bar go, and
// BootScreen draws ours on top at the tail. every hook asks BootScreen.drawing() so the setting and a crash both
// fall back to vanilla without a restart. ours only goes up on frames where the logo redirect really ran
@Mixin(LoadingOverlay.class)
public abstract class MixinLoadingOverlay {

    @Shadow
    @Final
    private boolean fadeIn;

    @Shadow
    private long fadeOutStart;

    @Shadow
    private long fadeInStart;

    @Shadow
    private float currentProgress;

    // all three reads of the brand colour: the first boot clear colour and the two fade fills. no ordinal on purpose.
    // require 0 on both redirects: a loading screen mod redirecting the same call would otherwise kill the boot. this
    // way ours just skips, and BootScreen stays out of the way when the logo one didn't land
    @Redirect(
            method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/function/IntSupplier;getAsInt()I"
            ),
            require = 0
    )
    private int soprano$background(IntSupplier brand) {
        return BootScreen.drawing() ? 0xFF000000 : brand.getAsInt();
    }

    // both halves of the mojang studios logo
    @Redirect(
            method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blit(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIIIIII)V"
            ),
            require = 0
    )
    private void soprano$logo(GuiGraphicsExtractor g, RenderPipeline pipeline, Identifier texture, int x, int y,
                              float u, float v, int w, int h, int uw, int vh, int tw, int th, int color) {
        if (!BootScreen.drawing()) {
            g.blit(pipeline, texture, x, y, u, v, w, h, uw, vh, tw, th, color);
        } else {
            BootScreen.skippedLogo();
        }
    }

    @Inject(
            method = "extractProgressBar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIIIF)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void soprano$bar(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, float fade, CallbackInfo ci) {
        // no logo of ours this frame means no bar of ours either, so vanilla keeps its own
        if (BootScreen.ownsFrame()) {
            ci.cancel();
        }
    }

    // after vanilla's frame, so currentProgress has already moved and its own fill is under ours
    @Inject(
            method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
            at = @At("TAIL")
    )
    private void soprano$draw(GuiGraphicsExtractor g, int mouseX, int mouseY, float a, CallbackInfo ci) {
        BootScreen.draw(g, fadeIn, fadeInStart, fadeOutStart, currentProgress);
    }
}
