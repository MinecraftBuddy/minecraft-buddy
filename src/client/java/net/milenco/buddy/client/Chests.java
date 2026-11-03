package net.milenco.buddy.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

/** What counts as a container, and which position stands for a double chest. */
final class Chests {
	private Chests() {}

	/** Double chest: both halves under the same position (the lowest of the two). */
	static BlockPos canonical(BlockPos p) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || p == null) return p;
		BlockState st = mc.level.getBlockState(p);
		if (st.getBlock() instanceof ChestBlock && st.hasProperty(ChestBlock.TYPE) && st.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
			BlockPos other = p.relative(ChestBlock.getConnectedDirection(st));
			return other.compareTo(p) < 0 ? other : p;
		}
		return p;
	}

	static boolean isContainer(BlockState st) {
		var b = st.getBlock();
		return b instanceof ChestBlock || b instanceof BarrelBlock || b instanceof ShulkerBoxBlock;
	}
}
