package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertTrue;

public class StrongholdRoomSearchTaskTest {
    @BeforeClass
    public static void bootstrap() {
        ItemFixture.boot();
    }

    @Test
    public void everyStrongholdWallCountsAsBricks() {
        List<Block> set = List.of(StrongholdRoomSearchTask.BRICKS);
        for (Block b : List.of(Blocks.STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS,
                Blocks.CHISELED_STONE_BRICKS, Blocks.INFESTED_STONE_BRICKS, Blocks.INFESTED_MOSSY_STONE_BRICKS,
                Blocks.INFESTED_CRACKED_STONE_BRICKS, Blocks.INFESTED_CHISELED_STONE_BRICKS, Blocks.IRON_BARS,
                Blocks.END_PORTAL_FRAME)) {
            assertTrue(b + " missing", set.contains(b));
        }
    }
}
