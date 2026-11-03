package net.milenco.buddy.mixin.client;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The state a block item would place, exactly as vanilla decides it: a torch becomes a wall torch against a side,
 * a slab goes top or bottom by the half you click. The builder asks this before it clicks, so it only right-clicks
 * where the result matches the schematic.
 */
@Mixin(BlockItem.class)
public interface BlockItemAccessor {
	@Invoker("getPlacementState")
	BlockState buddy$placementState(BlockPlaceContext context);
}
