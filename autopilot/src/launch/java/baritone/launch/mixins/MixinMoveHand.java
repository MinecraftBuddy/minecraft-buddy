package baritone.launch.mixins;

import adris.altoclef.ui.gui.mc.move.MoveSession;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// the hand doesn't look at detached, so a free camera fifty blocks up would still be holding a pickaxe
@Mixin(GameRenderer.class)
public class MixinMoveHand {

    @Inject(
            method = "renderItemInHand",
            at = @At("HEAD"),
            cancellable = true
    )
    private void soprano$hideHand(final CameraRenderState camera, final PlayerRenderState player, final GpuTextureView target,
                                  final CallbackInfo ci) {
        if (MoveSession.cameraOn()) {
            ci.cancel();
        }
    }
}
