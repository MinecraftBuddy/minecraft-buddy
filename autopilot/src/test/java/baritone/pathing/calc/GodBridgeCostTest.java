package baritone.pathing.calc;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.pathing.movement.ActionCosts;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.movements.MovementTraverse;
import baritone.utils.BlockStateInterface;
import baritone.utils.ToolSet;
import baritone.utils.pathing.BetterWorldBorder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.border.WorldBorder;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// what a bridge costs with and without allowGodBridge. same fake world as FallingCostTest, and the same skip when there's
// no shadow BaritoneAPI around. the only thing under the traverse is the block we start on, so it is a back place unless a
// test puts something next to the gap
public class GodBridgeCostTest {

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
            // hasThrowaway is the one that matters, a bridge with nothing to place is COST_INF
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
    private boolean god;
    private boolean magma;

    @Before
    public void setUp() {
        ItemFixture.boot();
        try {
            settings = BaritoneAPI.getSettings();
        } catch (Throwable t) {
            Assume.assumeNoException("needs the shadow BaritoneAPI from the bench harness", t);
        }
        potions = settings.considerPotionEffects.value;
        god = settings.allowGodBridge.value;
        magma = settings.allowWalkOnMagmaBlocks.value;
        settings.considerPotionEffects.value = false;
    }

    @After
    public void tearDown() {
        if (settings != null) {
            settings.considerPotionEffects.value = potions;
            settings.allowGodBridge.value = god;
            settings.allowWalkOnMagmaBlocks.value = magma;
        }
    }

    // a block to stand on at (0,63,0) and nothing else, traverse east from (0,64,0) to (1,64,0)
    private static MapWorld gap(BlockState underfoot) {
        MapWorld w = new MapWorld();
        w.set(0, 63, 0, underfoot);
        return w;
    }

    private double cost(MapWorld w, boolean godBridge) {
        settings.allowGodBridge.value = godBridge;
        return MovementTraverse.cost(new Ctx(w), 0, 64, 0, 1, 0);
    }

    private static double place() {
        return new Ctx(gap(Blocks.STONE.defaultBlockState())).costOfPlacingAt(1, 63, 0, Blocks.AIR.defaultBlockState());
    }

    @Test
    public void offIsTheSneakPriceOnIsTheWalkPrice() {
        MapWorld w = gap(Blocks.STONE.defaultBlockState());
        double off = cost(w, false);
        double on = cost(w, true);
        assertTrue("back place should be possible, got " + off, off < ActionCosts.COST_INF);
        assertEquals("flag off keeps the sneak multiplier on the walk", ActionCosts.SNEAK_ONE_BLOCK_COST + place(), off, 1e-9);
        assertEquals("flag on charges the walk and the block", ActionCosts.WALK_ONE_BLOCK_COST + place(), on, 1e-9);
        assertTrue(on < off);
    }

    @Test
    public void aSidePlaceCostsTheSameEitherWay() {
        MapWorld w = gap(Blocks.STONE.defaultBlockState());
        w.set(1, 63, 1, Blocks.STONE.defaultBlockState());
        double off = cost(w, false);
        double on = cost(w, true);
        assertEquals(ActionCosts.WALK_ONE_BLOCK_COST + place(), off, 1e-9);
        assertEquals(off, on, 0);
    }

    @Test
    public void theGatesThatMakeABackPlaceImpossibleStayImpossible() {
        MapWorld soulSand = gap(Blocks.SOUL_SAND.defaultBlockState());
        assertEquals(ActionCosts.COST_INF, cost(soulSand, false), 0);
        assertEquals(ActionCosts.COST_INF, cost(soulSand, true), 0);
        BlockState slab = Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
        MapWorld halfSlab = gap(slab);
        assertEquals(ActionCosts.COST_INF, cost(halfSlab, false), 0);
        assertEquals(ActionCosts.COST_INF, cost(halfSlab, true), 0);
        // nothing to place against on a ladder
        MapWorld ladder = gap(Blocks.LADDER.defaultBlockState());
        assertEquals(ActionCosts.COST_INF, cost(ladder, false), 0);
        assertEquals(ActionCosts.COST_INF, cost(ladder, true), 0);
    }

    @Test
    public void footingTheSpeedModelDoesNotCoverKeepsTheSneakPrice() {
        // ice is a different friction, the executor would not walk it backwards so the planner must not count on it
        MapWorld ice = gap(Blocks.ICE.defaultBlockState());
        assertEquals(cost(ice, false), cost(ice, true), 0);
        // a crafting table opens when clicked, only the sneaking back place can place against it
        MapWorld table = gap(Blocks.CRAFTING_TABLE.defaultBlockState());
        assertEquals(cost(table, false), cost(table, true), 0);
        MapWorld furnace = gap(Blocks.FURNACE.defaultBlockState());
        assertEquals(cost(furnace, false), cost(furnace, true), 0);
        // and magma, which wants the sneak held anyway
        settings.allowWalkOnMagmaBlocks.value = true;
        MapWorld magmaWorld = gap(Blocks.MAGMA_BLOCK.defaultBlockState());
        assertEquals(cost(magmaWorld, false), cost(magmaWorld, true), 0);
        // with it off, magma is just a block
        settings.allowWalkOnMagmaBlocks.value = false;
        assertTrue(cost(magmaWorld, true) < cost(magmaWorld, false));
    }

    @Test
    public void startingFromAClimbableKeepsTheSneakPrice() {
        // the executor turns a god bridge down when the player is on a ladder, so the planner can't price one
        for (BlockState climbable : new BlockState[]{Blocks.LADDER.defaultBlockState(), Blocks.VINE.defaultBlockState(), Blocks.SCAFFOLDING.defaultBlockState()}) {
            MapWorld w = gap(Blocks.STONE.defaultBlockState());
            w.set(0, 64, 0, climbable);
            double off = cost(w, false);
            assertTrue(climbable + " should still be a bridge, got " + off, off < ActionCosts.COST_INF);
            assertEquals(climbable.toString(), off, cost(w, true), 0);
        }
    }

    @Test
    public void aChainOfBridgesIsWalkSpeedAllTheWayAlong() {
        // the second traverse starts over the block the first one places, which is still air when the planner asks
        MapWorld w = gap(Blocks.STONE.defaultBlockState());
        settings.allowGodBridge.value = false;
        double offSecond = MovementTraverse.cost(new Ctx(w), 1, 64, 0, 2, 0);
        settings.allowGodBridge.value = true;
        double onSecond = MovementTraverse.cost(new Ctx(w), 1, 64, 0, 2, 0);
        assertEquals(ActionCosts.SNEAK_ONE_BLOCK_COST + place(), offSecond, 1e-9);
        assertEquals(ActionCosts.WALK_ONE_BLOCK_COST + place(), onSecond, 1e-9);
    }

    @Test
    public void aBridgeThroughWaterIsNotWalkedBackwards() {
        MapWorld w = gap(Blocks.STONE.defaultBlockState());
        w.set(1, 64, 0, Blocks.WATER.defaultBlockState());
        double off = cost(w, false);
        assertTrue("through water should still be possible, got " + off, off < ActionCosts.COST_INF);
        assertEquals(off, cost(w, true), 0);
    }
}
