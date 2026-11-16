package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.BeforeClass;
import org.junit.Test;

// (ice_berg_not_land) an iceberg's top is no land: under its snow cap the column is looked down to the sea's top block for ice
// of any kind. a frozen-ocean start took a snow-capped iceberg's top for land and walked to and fro under the sea ice
public class LandFinderBergTest {

    private static final int SEA = 63;

    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    // a column by height: what is set, stone under it all
    private static java.util.function.IntFunction<BlockState> column(Map<Integer, BlockState> cells) {
        return y -> cells.getOrDefault(y, Blocks.STONE.defaultBlockState());
    }

    // an iceberg: its top at y 75 (the cell over it 76), snow down to y 68, packed ice under it down to the sea's floor
    private static Map<Integer, BlockState> berg(BlockState ice) {
        Map<Integer, BlockState> m = new HashMap<>();
        for (int y = 68; y <= 75; y++) m.put(y, Blocks.SNOW_BLOCK.defaultBlockState());
        for (int y = 40; y < 68; y++) m.put(y, ice);
        return m;
    }

    @Test
    public void anIcebergsSnowCapIsNoLand() {
        for (BlockState ice : new BlockState[]{Blocks.PACKED_ICE.defaultBlockState(), Blocks.BLUE_ICE.defaultBlockState(), Blocks.ICE.defaultBlockState()}) {
            // the five blocks under the top are snow: as before it passed
            assertFalse(LandFinder.icyColumn(column(berg(ice)), 76, SEA, false));
            assertTrue(ice.toString(), LandFinder.icyColumn(column(berg(ice)), 76, SEA, true));
        }
    }

    // a hill by the sea is land: stone down to the sea and below, a pool of water in it under the top five not counted
    @Test
    public void aHillIsStillLand() {
        Map<Integer, BlockState> hill = new HashMap<>();
        hill.put(79, Blocks.GRASS_BLOCK.defaultBlockState());
        hill.put(70, Blocks.WATER.defaultBlockState());
        assertFalse(LandFinder.icyColumn(column(hill), 80, SEA, true));
        // water or ice in the top five is no land, as before, key or not
        hill.put(77, Blocks.WATER.defaultBlockState());
        assertTrue(LandFinder.icyColumn(column(hill), 80, SEA, false));
        assertTrue(LandFinder.icyColumn(column(hill), 80, SEA, true));
    }

    // ice deeper than the sea's top block is the sea floor's business, not the top's: low land over a frozen sea's edge is land
    @Test
    public void iceUnderTheSeasTopBlockIsNotLooked() {
        Map<Integer, BlockState> low = new HashMap<>();
        for (int y = 64; y <= 70; y++) low.put(y, Blocks.DIRT.defaultBlockState());
        low.put(61, Blocks.PACKED_ICE.defaultBlockState());
        assertFalse(LandFinder.icyColumn(column(low), 71, SEA, true));
        low.put(62, Blocks.PACKED_ICE.defaultBlockState());
        assertTrue(LandFinder.icyColumn(column(low), 71, SEA, true));
    }

    @Test
    public void theDepthIsToTheSeasTopBlockFiveAtLeastAndCapped() {
        // the cell over a top at the sea: five, as before
        assertEquals(LandFinder.ICE_DEPTH, LandFinder.bergDepth(64, SEA));
        // the top at 75 (the cell 76): down to y 62, the sea's top block
        assertEquals(14, LandFinder.bergDepth(76, SEA));
        assertEquals(LandFinder.BERG_DEPTH, LandFinder.bergDepth(300, SEA));
    }
}
