package baritone.pathing.calc;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.pathing.movement.ActionCosts;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.movements.MovementAscend;
import baritone.utils.BlockStateInterface;
import baritone.utils.ToolSet;
import baritone.utils.pathing.BetterWorldBorder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// swinging a pickaxe with your head underwater is 5x slower, and 5x again with nothing under your feet.
// same fake world as VineLogPathTest, and the same skip when there's no shadow BaritoneAPI around
public class WaterBreakCostTest {

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
    private double waterBreak;
    private boolean potions;

    @Before
    public void setUp() {
        ItemFixture.boot();
        try {
            settings = BaritoneAPI.getSettings();
        } catch (Throwable t) {
            Assume.assumeNoException("needs the shadow BaritoneAPI from the bench harness", t);
        }
        waterBreak = settings.waterBreakCostMultiplier.value;
        potions = settings.considerPotionEffects.value;
        // the fake tool set would go ask a player for haste, and there is no player
        settings.considerPotionEffects.value = false;
        settings.waterBreakCostMultiplier.value = 5D;
    }

    @After
    public void tearDown() {
        if (settings != null) {
            settings.waterBreakCostMultiplier.value = waterBreak;
            settings.considerPotionEffects.value = potions;
        }
    }

    // sand floor at y 63 everywhere and a sand step to ascend onto at 1,64,0. the thing to break is a stone ceiling
    // over the src square, because baritone won't break anything sideways next to a water source (it would flow in).
    // feetWater/headWater fill the src square, floor says whether there is anything under it
    private static MapWorld world(boolean feetWater, boolean headWater, boolean floor, boolean ceiling) {
        MapWorld w = new MapWorld();
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                w.set(x, 63, z, Blocks.SAND.defaultBlockState());
            }
        }
        w.set(1, 64, 0, Blocks.SAND.defaultBlockState());
        if (ceiling) {
            w.set(0, 66, 0, Blocks.STONE.defaultBlockState());
        }
        if (!floor) {
            w.set(0, 63, 0, Blocks.WATER.defaultBlockState());
        }
        if (feetWater) {
            w.set(0, 64, 0, Blocks.WATER.defaultBlockState());
        }
        if (headWater) {
            w.set(0, 65, 0, Blocks.WATER.defaultBlockState());
        }
        return w;
    }

    // the part of the ascend that is breaking the ceiling: the same ascend with and without it
    private static double breakPart(boolean feetWater, boolean headWater, boolean floor) {
        double with = MovementAscend.cost(new Ctx(world(feetWater, headWater, floor, true)), 0, 64, 0, 1, 0);
        double without = MovementAscend.cost(new Ctx(world(feetWater, headWater, floor, false)), 0, 64, 0, 1, 0);
        assertTrue("ascend should be possible, got " + without, without < ActionCosts.COST_INF);
        assertTrue("ascend should be possible, got " + with, with < ActionCosts.COST_INF);
        return with - without;
    }

    // the swinging is what water slows down. the idle ticks the bot waits after a break are the same wherever it stands, and
    // so is the part of the last one that the jump covers (the ascend takes that off, see breakWaitCredit)
    private double slowedBy(double factor, double dry) {
        double idle = settings.blockBreakSpeed.value - 1;
        double credit = Math.min(idle, Math.max(ActionCosts.JUMP_ONE_BLOCK_COST, ActionCosts.WALK_ONE_BLOCK_COST));
        return (dry - idle + credit) * factor + idle - credit;
    }

    @Test
    public void headUnderwaterOnTheBottomIsFiveTimes() {
        double dry = breakPart(false, false, true);
        assertTrue("stone should cost something, got " + dry, dry > 1);
        assertEquals(slowedBy(5, dry), breakPart(true, true, true), 1e-6);
    }

    @Test
    public void eyesUnderWaterOverADryBlockStillCount() {
        double dry = breakPart(false, false, true);
        assertEquals(slowedBy(5, dry), breakPart(false, true, true), 1e-6);
    }

    @Test
    public void floatingOnTopOfThatIsTwentyFive() {
        double dry = breakPart(false, false, true);
        assertEquals(slowedBy(25, dry), breakPart(true, true, false), 1e-6);
    }

    @Test
    public void wadingWithADryHeadIsFree() {
        double dry = breakPart(false, false, true);
        assertEquals(dry, breakPart(true, false, true), 1e-6);
    }

    @Test
    public void settingOneTurnsItOff() {
        settings.waterBreakCostMultiplier.value = 1D;
        double dry = breakPart(false, false, true);
        assertEquals(dry, breakPart(true, true, true), 1e-6);
    }
}
