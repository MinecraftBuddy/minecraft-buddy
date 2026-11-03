package net.milenco.buddy.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Keeps the crosshair away from blocks where "use" does something (sign = open the editor, chest, door, button...).
 * Eating is "use" as well, so AutoEat and the eating safety net otherwise go wrong when you happen to look at such a block.
 */
public class SafeLook {
	/** True if right-clicking the block you look at does something other than using the item. */
	public static boolean lookingAtInteractable(LocalPlayer p) {
		HitResult hr = Minecraft.getInstance().hitResult;
		if (!(hr instanceof BlockHitResult bhr) || hr.getType() != HitResult.Type.BLOCK) return false;
		return interactable(p.level().getBlockState(bhr.getBlockPos()));
	}

	static boolean interactable(BlockState st) {
		Block b = st.getBlock();
		return b instanceof BaseEntityBlock            // signs, chests, furnaces, anvil-likes with contents, beds, bells, lecterns...
			|| b instanceof DoorBlock || b instanceof TrapDoorBlock || b instanceof FenceGateBlock
			|| b instanceof ButtonBlock || b instanceof LeverBlock || b instanceof NoteBlock
			|| b instanceof RepeaterBlock || b instanceof ComparatorBlock || b instanceof DaylightDetectorBlock
			|| b instanceof CraftingTableBlock || b instanceof AnvilBlock || b instanceof GrindstoneBlock || b instanceof StonecutterBlock
			|| b instanceof LoomBlock || b instanceof CartographyTableBlock || b instanceof SmithingTableBlock
			|| b instanceof CakeBlock || b instanceof CandleCakeBlock || b instanceof CandleBlock || b instanceof FlowerPotBlock
			|| b instanceof ComposterBlock || b instanceof AbstractCauldronBlock || b instanceof RespawnAnchorBlock
			|| b instanceof TntBlock || b instanceof DragonEggBlock || b instanceof SweetBerryBushBlock || b instanceof CaveVinesBlock
			|| st.is(Blocks.REDSTONE_ORE) || st.is(Blocks.DEEPSLATE_REDSTONE_ORE) || b instanceof PumpkinBlock || b instanceof BeehiveBlock || b instanceof CopperBulbBlock;
	}

	/**
	 * Finds a view direction close to the current one where the crosshair hits nothing interactive (air or a plain block).
	 * Returns a target point for Look, or null if nothing was found.
	 */
	public static Vec3 findSafeTarget(LocalPlayer p) {
		float yaw0 = p.getYRot(), pitch0 = p.getXRot();
		float[][] tries = {
			{0, 15}, {0, -15}, {20, 10}, {-20, 10}, {0, 30}, {35, 0}, {-35, 0}, {0, -35}, {60, 15}, {-60, 15},
			{0, 55}, {90, 10}, {-90, 10}, {120, 20}, {-120, 20}, {180, 15}, {0, 80}, {0, -80}
		};
		Vec3 found = null;
		for (float[] t : tries) {
			float yaw = yaw0 + t[0], pitch = Math.max(-89f, Math.min(89f, pitch0 + t[1]));
			p.setYRot(yaw); p.setXRot(pitch);
			HitResult hr = p.pick(4.5, 0f, false);
			boolean ok = hr.getType() != HitResult.Type.BLOCK
				|| (hr instanceof BlockHitResult bhr && !interactable(p.level().getBlockState(bhr.getBlockPos())));
			if (ok) {
				Vec3 dir = Vec3.directionFromRotation(pitch, yaw);
				found = p.getEyePosition().add(dir.scale(hr.getType() == HitResult.Type.BLOCK ? Math.max(1.5, hr.getLocation().distanceTo(p.getEyePosition()) - 0.2) : 4.0));
				break;
			}
		}
		p.setYRot(yaw0); p.setXRot(pitch0);   // always restore; the real turn is done by Look
		return found;
	}
}
