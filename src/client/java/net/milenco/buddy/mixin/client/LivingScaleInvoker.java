package net.milenco.buddy.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The scale a renderer gives its model (a ghast, a giant), for the panel's 3D view (Models). */
@Mixin(LivingEntityRenderer.class)
public interface LivingScaleInvoker {
	@Invoker("scale") void buddy$scale(LivingEntityRenderState state, PoseStack poseStack);
}
