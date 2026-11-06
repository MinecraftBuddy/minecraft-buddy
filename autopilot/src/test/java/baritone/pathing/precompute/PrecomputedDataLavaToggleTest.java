package baritone.pathing.precompute;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.altoclef.AltoClefSettings;
import baritone.utils.BlockStateInterface;
import baritone.utils.pathing.BetterWorldBorder;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertSame;

// the shared table is only allowed to remember answers for the settings it was built with. a search that
// holds the table while the lava toggle flips used to leave the flipped answer in it for good
public class PrecomputedDataLavaToggleTest {

    private static final class Air extends BlockStateInterface {
        Air() {
            super(new BetterWorldBorder(new WorldBorder()), -64, 384);
        }

        @Override
        protected BlockState getUncached(int x, int y, int z) {
            return Blocks.AIR.defaultBlockState();
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

    // flowing lava is the one the toggle changes the table's mind about: off says no outright, on says maybe
    private static final BlockState FLOWING_LAVA = Blocks.LAVA.defaultBlockState().setValue(LiquidBlock.LEVEL, 1);

    private BlockStateInterface bsi;

    private static void dropShared() throws Exception {
        Field f = PrecomputedData.class.getDeclaredField("shared");
        f.setAccessible(true);
        f.set(null, null);
    }

    @Before
    public void setUp() throws Exception {
        ItemFixture.boot();
        bsi = new Air();
        AltoClefSettings.getInstance().allowSwimThroughLava(false);
        dropShared();
    }

    @After
    public void tearDown() throws Exception {
        AltoClefSettings.getInstance().allowSwimThroughLava(false);
        dropShared();
    }

    private void lavaMatchesAFreshTable(PrecomputedData held) {
        BlockState lava = FLOWING_LAVA;
        PrecomputedData fresh = new PrecomputedData();
        assertEquals(fresh.canWalkThrough(bsi, 0, 63, 0, lava), held.canWalkThrough(bsi, 0, 63, 0, lava));
        assertEquals(fresh.fullyPassable(bsi, 0, 63, 0, lava), held.fullyPassable(bsi, 0, 63, 0, lava));
        assertEquals(fresh.canWalkOn(bsi, 0, 63, 0, lava), held.canWalkOn(bsi, 0, 63, 0, lava));
    }

    @Test
    public void lavaFilledWhileTheToggleWasOnDoesNotSurviveItBeingOff() {
        PrecomputedData held = PrecomputedData.forCurrentSettings();
        AltoClefSettings.getInstance().allowSwimThroughLava(true);
        // the in-flight search asks while the toggle is on
        BlockState lava = FLOWING_LAVA;
        held.canWalkThrough(bsi, 0, 63, 0, lava);
        held.fullyPassable(bsi, 0, 63, 0, lava);
        held.canWalkOn(bsi, 0, 63, 0, lava);
        AltoClefSettings.getInstance().allowSwimThroughLava(false);
        assertSame(held, PrecomputedData.forCurrentSettings());
        assertFalse(held.canWalkThrough(bsi, 0, 63, 0, lava));
        lavaMatchesAFreshTable(PrecomputedData.forCurrentSettings());
    }

    @Test
    public void lavaFilledWhileTheToggleWasOffDoesNotSurviveItBeingOn() {
        AltoClefSettings.getInstance().allowSwimThroughLava(true);
        PrecomputedData held = PrecomputedData.forCurrentSettings();
        AltoClefSettings.getInstance().allowSwimThroughLava(false);
        BlockState lava = FLOWING_LAVA;
        held.canWalkThrough(bsi, 0, 63, 0, lava);
        held.fullyPassable(bsi, 0, 63, 0, lava);
        held.canWalkOn(bsi, 0, 63, 0, lava);
        AltoClefSettings.getInstance().allowSwimThroughLava(true);
        assertSame(held, PrecomputedData.forCurrentSettings());
        // swimming out of a lava flow is the whole point of the toggle
        assertTrue(held.canWalkThrough(bsi, 0, 63, 0, lava));
        lavaMatchesAFreshTable(PrecomputedData.forCurrentSettings());
    }
}
