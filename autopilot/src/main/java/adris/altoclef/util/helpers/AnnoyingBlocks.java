package adris.altoclef.util.helpers;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.FlowerBlock;

import java.util.Set;

// blocks that make the player "stuck" without being solid: vines, ladders, fences and friends.
// this used to be copy pasted into six tasks, five of which had the check returning inside their for loop
public final class AnnoyingBlocks {
    private static final Set<Block> ANNOYING = Set.of(
            Blocks.VINE,
            Blocks.NETHER_SPROUTS,
            Blocks.CAVE_VINES,
            Blocks.CAVE_VINES_PLANT,
            Blocks.TWISTING_VINES,
            Blocks.TWISTING_VINES_PLANT,
            Blocks.WEEPING_VINES_PLANT,
            Blocks.LADDER,
            Blocks.BIG_DRIPLEAF,
            Blocks.BIG_DRIPLEAF_STEM,
            Blocks.SMALL_DRIPLEAF,
            Blocks.TALL_GRASS,
            // the port turned the old Blocks.GRASS (the plant) into GRASS_BLOCK, which is the dirt on every hill
            Blocks.SHORT_GRASS,
            Blocks.SWEET_BERRY_BUSH
    );

    private AnnoyingBlocks() {
    }

    public static boolean isAnnoying(Block block) {
        return ANNOYING.contains(block)
                || block instanceof DoorBlock
                || block instanceof FenceBlock
                || block instanceof FenceGateBlock
                || block instanceof FlowerBlock;
    }
}
