package baritone.pathing.movement.movements;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.ItemFixture;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntFunction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.BeforeClass;
import org.junit.Test;

// a missed vine grab falls down a column: lava or fire at the bottom, or no bottom, is a no, water or a short drop is fine
public class ClimbMissTest {

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    private static IntFunction<BlockState> column(Object... ysAndBlocks) {
        Map<Integer, BlockState> blocks = new HashMap<>();
        for (int i = 0; i < ysAndBlocks.length; i += 2) {
            blocks.put((Integer) ysAndBlocks[i], ((net.minecraft.world.level.block.Block) ysAndBlocks[i + 1]).defaultBlockState());
        }
        return y -> blocks.getOrDefault(y, Blocks.AIR.defaultBlockState());
    }

    @Test
    public void lavaAtTheBottomOfTheShaftIsRejected() {
        assertFalse(ClimbMiss.safeFloor(column(60, Blocks.LAVA), 75));
    }

    @Test
    public void fireOnTheFloorIsRejected() {
        assertFalse(ClimbMiss.safeFloor(column(70, Blocks.FIRE, 69, Blocks.NETHERRACK), 75));
    }

    @Test
    public void waterBelowKeepsTheGrab() {
        assertTrue(ClimbMiss.safeFloor(column(60, Blocks.WATER), 75));
    }

    @Test
    public void aTwoDeepFloorKeepsTheGrab() {
        assertTrue(ClimbMiss.safeFloor(column(73, Blocks.STONE), 75));
    }

    @Test
    public void noFloorWithinTheDepthIsRejected() {
        assertFalse(ClimbMiss.safeFloor(column(40, Blocks.STONE), 75));
    }

    @Test
    public void aVineOverLavaIsNotAFloor() {
        assertFalse(ClimbMiss.safeFloor(column(74, Blocks.VINE, 60, Blocks.LAVA), 75));
        assertTrue(ClimbMiss.safeFloor(column(74, Blocks.VINE, 60, Blocks.STONE), 75));
    }

    @Test
    public void lavaPastTheDepthIsNotSeenButTheMissingFloorIsRejectedFirst() {
        assertFalse(ClimbMiss.safeFloor(column(30, Blocks.LAVA), 75));
    }
}
