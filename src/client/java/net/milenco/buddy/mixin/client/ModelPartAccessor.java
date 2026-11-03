package net.milenco.buddy.mixin.client;

import java.util.List;
import java.util.Map;

import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A model's cubes and its named children, for the panel's 3D view (Models). */
@Mixin(ModelPart.class)
public interface ModelPartAccessor {
	@Accessor("cubes") List<ModelPart.Cube> buddy$cubes();
	@Accessor("children") Map<String, ModelPart> buddy$children();
}
