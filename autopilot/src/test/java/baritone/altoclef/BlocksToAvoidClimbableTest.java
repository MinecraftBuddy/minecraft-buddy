package baritone.altoclef;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.precompute.Ternary;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

// the list altoclef drives with vs. the blocks we plan to climb. anything in both gets climbed in the cost and punched in
// the movement
public class BlocksToAvoidClimbableTest {

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    @Test
    public void nothingOnTheListIsClimbable() {
        for (Block block : BaritoneSettingsScope.blocksToAvoid()) {
            assertFalse(block.toString(), MovementHelper.isClimbable(block));
        }
    }

    @Test
    public void vinesStayWalkableWhileTheListIsOn() {
        Settings.Setting<List<Block>> avoid = BaritoneAPI.getSettings().blocksToAvoid;
        List<Block> was = avoid.value;
        try {
            avoid.value = BaritoneSettingsScope.blocksToAvoid();
            assertEquals(Ternary.YES, MovementHelper.canWalkThroughBlockState(Blocks.VINE.defaultBlockState()));
            assertEquals(Ternary.YES, MovementHelper.canWalkThroughBlockState(Blocks.TWISTING_VINES.defaultBlockState()));
            assertEquals(Ternary.YES, MovementHelper.canWalkThroughBlockState(Blocks.TWISTING_VINES_PLANT.defaultBlockState()));
            // the rest of the list still bites, or this test proves nothing
            assertEquals(Ternary.NO, MovementHelper.canWalkThroughBlockState(Blocks.FERN.defaultBlockState()));
        } finally {
            avoid.value = was;
        }
    }
}
