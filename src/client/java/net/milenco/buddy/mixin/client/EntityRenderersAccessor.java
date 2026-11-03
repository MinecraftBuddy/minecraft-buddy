package net.milenco.buddy.mixin.client;

import java.util.Map;

import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.PlayerModelType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The game's renderer per kind of entity and per player model, for the panel's 3D view (Models). */
@Mixin(EntityRenderDispatcher.class)
public interface EntityRenderersAccessor {
	@Accessor("renderers") Map<EntityType<?>, EntityRenderer<?, ?>> buddy$renderers();
	@Accessor("playerRenderers") Map<PlayerModelType, AvatarRenderer<AbstractClientPlayer>> buddy$playerRenderers();
}
