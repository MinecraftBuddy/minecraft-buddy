package baritone.pathing.movement;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.pathing.movement.ActionCosts;
import baritone.utils.BlockStateInterface;
import baritone.utils.ToolSet;
import baritone.utils.pathing.BetterWorldBorder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// nests and hives are on the never break list out of the box, so the planner prices digging one at infinity. same fake
// world and the same skip as PortalAvoidanceTest
public class BeeNestBreakTest {

    private static final class MapWorld extends BlockStateInterface {
        final Map<Long, BlockState> blocks = new HashMap<>();

        MapWorld() {
            super(new BetterWorldBorder(new WorldBorder()), -64, 384);
        }

        void set(int x, int y, int z, BlockState s) {
            blocks.put(BlockPos.asLong(x, y, z), s);
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

    private static final class Ctx extends CalculationContext {
        Ctx(BlockStateInterface bsi) {
            super(null, true, null, null, bsi, new ToolSet(null) {
                @Override
                public double getStrVsBlock(BlockState state) {
                    float hardness = state.getBlock().defaultDestroyTime();
                    return hardness < 0 ? -1 : hardness == 0 ? 1 : 8.0 / (hardness * 30);
                }
            }, false, false, true, 0, 1.0f, 20.0, false);
        }
    }

    private Settings settings;
    private boolean potions;
    private List<Block> disallowed;

    @Before
    public void setUp() {
        ItemFixture.boot();
        try {
            settings = BaritoneAPI.getSettings();
        } catch (Throwable t) {
            Assume.assumeNoException("needs the shadow BaritoneAPI from the bench harness", t);
        }
        potions = settings.considerPotionEffects.value;
        settings.considerPotionEffects.value = false;
        // whatever another test left in there, this one is about the shipped default
        disallowed = settings.blocksToDisallowBreaking.value;
        settings.blocksToDisallowBreaking.value = settings.blocksToDisallowBreaking.defaultValue;
    }

    @After
    public void tearDown() {
        if (settings != null) {
            settings.considerPotionEffects.value = potions;
            settings.blocksToDisallowBreaking.value = disallowed;
        }
    }

    // one block on a stone floor, nothing around it that avoidAdjacentBreaking cares about
    private static Ctx worldWith(Block block) {
        MapWorld w = new MapWorld();
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                w.set(x, 63, z, Blocks.STONE.defaultBlockState());
            }
        }
        w.set(0, 64, 0, block.defaultBlockState());
        return new Ctx(w);
    }

    private static void assertNeverBroken(Block block) {
        Ctx ctx = worldWith(block);
        BlockState state = block.defaultBlockState();
        assertTrue(MovementHelper.avoidBreaking(ctx, 0, 64, 0, state));
        assertTrue(MovementHelper.avoidBreaking(ctx.bsi, 0, 64, 0, state));
        assertEquals(ActionCosts.COST_INF, MovementHelper.getMiningDurationTicks(ctx, 0, 64, 0, state, true), 0);
    }

    @Test
    public void theDefaultListHasTheBees() {
        assertTrue(settings.blocksToDisallowBreaking.defaultValue.contains(Blocks.BEE_NEST));
        assertTrue(settings.blocksToDisallowBreaking.defaultValue.contains(Blocks.BEEHIVE));
    }

    @Test
    public void aBeeNestIsNeverDug() {
        assertNeverBroken(Blocks.BEE_NEST);
    }

    @Test
    public void aBeehiveIsNeverDug() {
        assertNeverBroken(Blocks.BEEHIVE);
    }

    @Test
    public void theLogItHangsOffIsStillFair() {
        // the tree is still the job, only the nest is off limits
        Ctx ctx = worldWith(Blocks.OAK_LOG);
        BlockState state = Blocks.OAK_LOG.defaultBlockState();
        assertFalse(MovementHelper.avoidBreaking(ctx, 0, 64, 0, state));
        assertFalse(MovementHelper.avoidBreaking(ctx.bsi, 0, 64, 0, state));
        assertTrue(MovementHelper.getMiningDurationTicks(ctx, 0, 64, 0, state, true) < ActionCosts.COST_INF);
    }
}
