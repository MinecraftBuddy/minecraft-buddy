package baritone.launch.mixins;

import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.mc.move.MoveSession;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// the free camera of the schematic move mode. right after alignWithEntity, not at the tail: update goes on to work out
// the fov, the cull frustum and the projection from where the camera is, and they all have to agree with ours or
// chunks behind the player's back get culled out of a view that's looking right at them
@Mixin(Camera.class)
public abstract class MixinMoveCamera {

    @Shadow
    private boolean detached;

    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    // yaw first, the way Camera has it
    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    @Inject(
            method = "update",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/Camera;alignWithEntity(F)V",
                    shift = At.Shift.AFTER
            )
    )
    private void soprano$moveCamera(final DeltaTracker deltaTracker, final CallbackInfo ci) {
        try {
            double[] pose = MoveSession.pose();
            if (pose == null) {
                return;
            }
            setPosition(pose[0], pose[1], pose[2]);
            setRotation((float) pose[3], (float) pose[4]);
            // detached is what draws the player's own body, and they're standing right there in the shot
            detached = true;
        } catch (Throwable t) {
            // fail open: a camera that throws once is the player's camera from then on
            Safe.report("move camera", t);
            MoveSession.end();
        }
    }
}
