package baritone.utils;

import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// the click time guard, the last word before a held left click lands on something it shouldn't
public class BlockBreakHelperNeverBreakTest {

    @Before
    public void setUp() {
        ItemFixture.boot();
    }

    @Test
    public void aListedBlockIsRefused() {
        List<Block> list = List.of(Blocks.BEE_NEST, Blocks.BEEHIVE);
        assertTrue(BlockBreakHelper.neverBreak(Blocks.BEE_NEST.defaultBlockState(), list));
        assertTrue(BlockBreakHelper.neverBreak(Blocks.BEEHIVE.defaultBlockState(), list));
    }

    @Test
    public void anyStateOfItCounts() {
        // a nest full of honey is still a nest full of bees
        List<Block> list = List.of(Blocks.BEE_NEST);
        Blocks.BEE_NEST.getStateDefinition().getPossibleStates()
                .forEach(state -> assertTrue(BlockBreakHelper.neverBreak(state, list)));
    }

    @Test
    public void everythingElseGoesThrough() {
        List<Block> list = List.of(Blocks.BEE_NEST, Blocks.BEEHIVE);
        assertFalse(BlockBreakHelper.neverBreak(Blocks.OAK_LOG.defaultBlockState(), list));
        assertFalse(BlockBreakHelper.neverBreak(Blocks.DIRT.defaultBlockState(), list));
    }

    @Test
    public void anEmptyListRefusesNothing() {
        assertFalse(BlockBreakHelper.neverBreak(Blocks.BEE_NEST.defaultBlockState(), List.of()));
    }
}
