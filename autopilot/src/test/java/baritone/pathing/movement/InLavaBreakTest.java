package baritone.pathing.movement;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.utils.BlockStateInterface;
import baritone.utils.pathing.BetterWorldBorder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// in lava or not is the world view's own, taken when it is made: one view made in lava says nothing about the next one
public class InLavaBreakTest {

    private static final class MapWorld extends BlockStateInterface {
        final Map<Long, BlockState> blocks = new HashMap<>();

        MapWorld() {
            super(new BetterWorldBorder(new WorldBorder()), -64, 384);
        }

        @Override
        protected BlockState getUncached(int x, int y, int z) {
            // y comes in shifted by -minY
            return blocks.getOrDefault(BlockPos.asLong(x, y - 64, z), Blocks.AIR.defaultBlockState());
        }

        @Override
        public boolean isLoaded(int x, int z) {
            return true;
        }

        @Override
        public boolean worldContainsLoadedChunk(int x, int z) {
            return true;
        }
    }

    @Before
    public void setUp() {
        ItemFixture.boot();
    }

    // a block at 0,63,0 under an open cell, with lava beside that cell at 1,64,0
    private static MapWorld lavaBeside() {
        MapWorld w = new MapWorld();
        w.blocks.put(BlockPos.asLong(0, 63, 0), Blocks.NETHERRACK.defaultBlockState());
        w.blocks.put(BlockPos.asLong(1, 64, 0), Blocks.LAVA.defaultBlockState());
        return w;
    }

    @Test
    public void lavaBesideIsAvoidedUnlessThisViewWasMadeInLava() {
        MapWorld out = lavaBeside();
        assertTrue(MovementHelper.lavaPoursIn(out, 0, 64, 0));

        MapWorld in = lavaBeside();
        in.inLava = true;
        assertFalse(MovementHelper.lavaPoursIn(in, 0, 64, 0));
        assertFalse(MovementHelper.avoidAdjacentBreaking(in, 1, 64, 0, false));

        // the next view, made out of the lava, avoids it again
        assertTrue(MovementHelper.lavaPoursIn(lavaBeside(), 0, 64, 0));
    }
}
