package baritone.launch.mixins;

import adris.altoclef.ui.gui.mc.move.MoveSession;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// a crosshair in the middle of the free camera's view points at nothing the player is looking at
@Mixin(Hud.class)
public class MixinMoveHud {

    @Inject(
            method = "extractCrosshair",
            at = @At("HEAD"),
            cancellable = true
    )
    private void soprano$hideCrosshair(final GuiGraphicsExtractor graphics, final DeltaTracker deltaTracker, final CallbackInfo ci) {
        if (MoveSession.cameraOn()) {
            ci.cancel();
        }
    }
}
