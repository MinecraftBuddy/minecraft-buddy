package baritone.pathing.calc;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.pathing.movement.ActionCosts;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.movements.MovementDescend;
import baritone.pathing.movement.movements.MovementTraverse;
import baritone.utils.BlockStateInterface;
import baritone.utils.ToolSet;
import baritone.utils.pathing.BetterWorldBorder;
import baritone.utils.pathing.MutableMoveResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
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

// LavaWakeTest does the geometry, this is that it's plugged in where it should be: the place cost and the fall cost, with the
// doors they're meant to leave open. same fake world as GodBridgeCostTest
public class LavaWakeCostTest {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState LAVA = Blocks.LAVA.defaultBlockState();
    private static final BlockState WATER = Blocks.WATER.defaultBlockState();

    private static final class MapWorld extends BlockStateInterface {
        final Map<Long, BlockState> blocks = new HashMap<>();

        MapWorld() {
            super(new BetterWorldBorder(new WorldBorder()), -64, 384);
        }

        MapWorld set(int x, int y, int z, BlockState s) {
            blocks.put(BlockPos.asLong(x, y, z), s);
            return this;
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
            }, true, false, true, 0, 1.0f, 20.0, false);
        }
    }

    private Settings settings;
    private boolean potions;

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
    }

    @After
    public void tearDown() {
        if (settings != null) {
            settings.considerPotionEffects.value = potions;
        }
    }

    private static double placeOnTop(MapWorld w, boolean burning) {
        Ctx ctx = new Ctx(w);
        ctx.burning = burning;
        return ctx.costOfPlacingAt(7, 30, -2, AIR);
    }

    // a source right under the cell, air all around it
    private static MapWorld exposed() {
        return new MapWorld().set(7, 29, -2, LAVA);
    }

    private static MapWorld walledIn() {
        return exposed().set(8, 29, -2, STONE).set(6, 29, -2, STONE).set(7, 29, -1, STONE).set(7, 29, -3, STONE).set(7, 28, -2, STONE);
    }

    @Test
    public void placingOnAnExposedSourceIsOffTheMenu() {
        assertEquals(ActionCosts.COST_INF, placeOnTop(exposed(), false), 0);
    }

    @Test
    public void onFireWeTakeAnyBlockWeCanGet() {
        assertTrue(placeOnTop(exposed(), true) < ActionCosts.COST_INF);
    }

    @Test
    public void outsideTheNetherNobodyAsks() {
        Ctx ctx = new Ctx(exposed());
        ctx.lavaWakes = false;
        assertTrue(ctx.costOfPlacingAt(7, 30, -2, AIR) < ActionCosts.COST_INF);
    }

    @Test
    public void aWalledInSourcePricesLikeNothingIsThere() {
        assertEquals(placeOnTop(new MapWorld(), false), placeOnTop(walledIn(), false), 0);
    }

    @Test
    public void flowingLavaIsAlreadyAwake() {
        assertTrue(placeOnTop(new MapWorld().set(7, 29, -2, LAVA.setValue(LiquidBlock.LEVEL, 2)), false) < ActionCosts.COST_INF);
    }

    // through a real caller, traverse east from (0,64,0) bridging (1,63,0)
    @Test
    public void aBridgeOntoAnExposedSourceIsRefused() {
        MapWorld w = new MapWorld().set(0, 63, 0, STONE).set(1, 62, 0, LAVA);
        assertEquals(ActionCosts.COST_INF, MovementTraverse.cost(new Ctx(w), 0, 64, 0, 1, 0), 0);
        Ctx burning = new Ctx(w);
        burning.burning = true;
        assertTrue(MovementTraverse.cost(burning, 0, 64, 0, 1, 0) < ActionCosts.COST_INF);
    }

    // off the edge at (0,64,0), east, onto a floor at (1,61,0) so the feet land at y 62
    private static MapWorld floor() {
        return new MapWorld().set(1, 61, 0, STONE);
    }

    private static MutableMoveResult fall(MapWorld w, boolean planning) {
        Ctx ctx = new Ctx(w);
        MutableMoveResult res = new MutableMoveResult();
        MovementDescend.dynamicFallCost(ctx, 0, 64, 0, 1, 0, 0, ctx.get(1, 62, 0), res, true, planning);
        return res;
    }

    @Test
    public void aPlainFallIsStillThere() {
        assertTrue(fall(floor(), true).cost < ActionCosts.COST_INF);
    }

    @Test
    public void aLandingBesideLavaIsRefused() {
        assertEquals(ActionCosts.COST_INF, fall(floor().set(2, 62, 0, LAVA), true).cost, 0);
        assertEquals(ActionCosts.COST_INF, fall(floor().set(1, 63, 1, LAVA.setValue(LiquidBlock.LEVEL, 2)), true).cost, 0);
    }

    @Test
    public void lavaFurtherOffLeavesTheLandingAlone() {
        assertTrue(fall(floor().set(4, 62, 0, LAVA), true).cost < ActionCosts.COST_INF);
    }

    // already falling: this is fallMode and snapshotDamage asking, a no here costs us the save we're committed to
    @Test
    public void midAirTheAnswerDoesNotChange() {
        assertTrue(fall(floor().set(2, 62, 0, LAVA), false).cost < ActionCosts.COST_INF);
    }

    @Test
    public void aContextThatWantsTheLavaStillGetsIt() {
        Ctx ctx = new Ctx(floor().set(2, 62, 0, LAVA));
        ctx.allowFallIntoLava = true;
        MutableMoveResult res = new MutableMoveResult();
        MovementDescend.dynamicFallCost(ctx, 0, 64, 0, 1, 0, 0, ctx.get(1, 62, 0), res, true, true);
        assertTrue(res.cost < ActionCosts.COST_INF);
    }

    @Test
    public void aWaterLandingIsNotOurBusiness() {
        MapWorld w = new MapWorld().set(1, 60, 0, STONE).set(1, 61, 0, WATER).set(2, 61, 0, LAVA);
        assertTrue(fall(w, true).cost < ActionCosts.COST_INF);
    }
}
