package baritone.pathing.calc;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.CliffAvoidance;
import baritone.utils.BlockStateInterface;
import baritone.utils.ToolSet;
import baritone.utils.pathing.BetterWorldBorder;
import baritone.utils.pathing.Favoring;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

// a node with a drop within two blocks pays CliffAvoidance.NODE_COST, in the nether only. same fake world as
// PortalAvoidanceTest, and the same skip when there's no shadow BaritoneAPI around
public class CliffAvoidanceTest {

    private static final class MapWorld extends BlockStateInterface {
        final Map<Long, BlockState> blocks = new HashMap<>();
        final Set<Long> unloaded = new HashSet<>();

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
            return !unloaded.contains(BlockPos.asLong(x, 0, z));
        }

        @Override
        public boolean worldContainsLoadedChunk(int x, int z) {
            return isLoaded(x, z);
        }
    }

    private static final class Ctx extends CalculationContext {
        Ctx(BlockStateInterface bsi, boolean nether) {
            super(null, true, null, null, bsi, new ToolSet(null) {
                @Override
                public double getStrVsBlock(BlockState state) {
                    float hardness = state.getBlock().defaultDestroyTime();
                    return hardness < 0 ? -1 : hardness == 0 ? 1 : 8.0 / (hardness * 30);
                }
            }, false, false, true, 0, 1.0f, 20.0, false);
            // no world to ask which dimension this is, so the test says
            this.cliffEdges = nether;
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

    // a shelf of stone at y 63 from x -8 to 6, 15 wide in z, with nothing below it and nothing past x = 6
    private static MapWorld shelf() {
        MapWorld w = new MapWorld();
        for (int x = -8; x <= 6; x++) {
            for (int z = -7; z <= 7; z++) {
                w.set(x, 63, z, Blocks.STONE.defaultBlockState());
            }
        }
        return w;
    }

    private static double cost(MapWorld w, int x, int z) {
        return CliffAvoidance.penalty(new Ctx(w, true), new CliffAvoidance.Memo(), x, 64, z);
    }

    @Test
    public void theMiddleOfTheShelfIsFree() {
        assertEquals(0, cost(shelf(), 0, 0), 0);
        // two blocks from the edge: the columns two out are the last ones with a floor
        assertEquals(0, cost(shelf(), 4, 0), 0);
    }

    @Test
    public void aDropWithinTwoBlocksCosts() {
        // x = 7 is air all the way down
        assertEquals(CliffAvoidance.NODE_COST, cost(shelf(), 5, 0), 0);
        assertEquals(CliffAvoidance.NODE_COST, cost(shelf(), 6, 0), 0);
    }

    @Test
    public void aDropOnlyOnTheDiagonalCounts() {
        // one missing corner of the 5x5, everything else is floor
        MapWorld w = shelf();
        for (int x = 7; x <= 8; x++) {
            for (int z = -7; z <= 7; z++) {
                w.set(x, 63, z, Blocks.STONE.defaultBlockState());
            }
        }
        w.set(7, 63, 2, Blocks.AIR.defaultBlockState());
        assertEquals(CliffAvoidance.NODE_COST, cost(w, 5, 0), 0);
        assertEquals(0, cost(w, 5, -1), 0);
    }

    @Test
    public void theFloorHasToBeCloseEnough() {
        // a ledge at y 56 is the 8th block down from feet at 64, 55 is the 9th
        MapWorld near = shelf();
        near.set(7, 56, 0, Blocks.STONE.defaultBlockState());
        near.set(8, 56, 0, Blocks.STONE.defaultBlockState());
        assertEquals(CliffAvoidance.NODE_COST, cost(near, 5, 0), 0); // the other z rows are still sheer
        MapWorld nearEverywhere = shelf();
        MapWorld farEverywhere = shelf();
        for (int x = 7; x <= 8; x++) {
            for (int z = -7; z <= 7; z++) {
                nearEverywhere.set(x, 56, z, Blocks.STONE.defaultBlockState());
                farEverywhere.set(x, 55, z, Blocks.STONE.defaultBlockState());
            }
        }
        assertEquals(0, cost(nearEverywhere, 6, 0), 0);
        assertEquals(CliffAvoidance.NODE_COST, cost(farEverywhere, 6, 0), 0);
    }

    @Test
    public void lavaWithinTheScanIsACliffEvenWithAFloorBelowIt() {
        MapWorld lava = shelf();
        MapWorld ledge = shelf();
        for (int x = 7; x <= 8; x++) {
            for (int z = -7; z <= 7; z++) {
                lava.set(x, 60, z, Blocks.LAVA.defaultBlockState());
                lava.set(x, 50, z, Blocks.STONE.defaultBlockState());
                ledge.set(x, 60, z, Blocks.STONE.defaultBlockState());
            }
        }
        assertEquals(CliffAvoidance.NODE_COST, cost(lava, 6, 0), 0);
        assertEquals(0, cost(ledge, 6, 0), 0);
    }

    @Test
    public void waterIsSomethingToLandIn() {
        MapWorld w = shelf();
        for (int x = 7; x <= 8; x++) {
            for (int z = -7; z <= 7; z++) {
                w.set(x, 58, z, Blocks.WATER.defaultBlockState());
            }
        }
        assertEquals(0, cost(w, 6, 0), 0);
    }

    @Test
    public void aWallIsNotADrop() {
        MapWorld w = shelf();
        for (int z = -7; z <= 7; z++) {
            w.set(7, 64, z, Blocks.NETHERRACK.defaultBlockState());
            w.set(7, 65, z, Blocks.NETHERRACK.defaultBlockState());
        }
        // the wall column is not somewhere to stand, x = 8 is past the reach of anyone at 5
        assertEquals(0, cost(w, 5, 0), 0);
    }

    @Test
    public void aChunkWeDontHaveIsNotACliff() {
        MapWorld w = shelf();
        for (int z = -7; z <= 7; z++) {
            w.unloaded.add(BlockPos.asLong(7, 0, z));
            w.unloaded.add(BlockPos.asLong(8, 0, z));
        }
        assertEquals(0, cost(w, 6, 0), 0);
    }

    @Test
    public void bridgingOutOverTheVoidPaysLikeTheRidgeDoes() {
        // the block under (7, 63, 0) is a bridge step that hasn't been placed yet, so the world says air there
        MapWorld w = shelf();
        assertEquals(CliffAvoidance.NODE_COST, cost(w, 7, 0), 0);
        assertEquals(CliffAvoidance.NODE_COST, cost(w, 8, 0), 0);
        // a floor-less node in the middle of a shelf is nobody's problem
        w.set(0, 63, 0, Blocks.AIR.defaultBlockState());
        assertEquals(0, cost(w, 0, 0), 0);
    }

    @Test
    public void fortressBricksPayAThird() {
        MapWorld w = shelf();
        w.set(6, 63, 0, Blocks.NETHER_BRICKS.defaultBlockState());
        w.set(6, 63, 1, Blocks.NETHER_BRICK_SLAB.defaultBlockState());
        assertEquals(CliffAvoidance.NODE_COST / 3, cost(w, 6, 0), 0);
        assertEquals(CliffAvoidance.NODE_COST / 3, cost(w, 6, 1), 0);
        assertEquals(CliffAvoidance.NODE_COST, cost(w, 6, 2), 0);
    }

    @Test
    public void nothingOutsideTheNether() {
        MapWorld w = shelf();
        assertEquals(0, CliffAvoidance.penalty(new Ctx(w, false), new CliffAvoidance.Memo(), 6, 64, 0), 0);
    }

    @Test
    public void aMemoKeepsItsAnswerAndANewOneAsksAgain() {
        MapWorld w = shelf();
        Ctx ctx = new Ctx(w, true);
        CliffAvoidance.Memo memo = new CliffAvoidance.Memo();
        assertEquals(CliffAvoidance.NODE_COST, CliffAvoidance.penalty(ctx, memo, 6, 64, 0), 0);
        for (int x = 7; x <= 8; x++) {
            for (int z = -7; z <= 7; z++) {
                w.set(x, 63, z, Blocks.STONE.defaultBlockState());
            }
        }
        // the memo is one search's view of the world, and it doesn't look again
        assertEquals(CliffAvoidance.NODE_COST, CliffAvoidance.penalty(ctx, memo, 6, 64, 0), 0);
        assertEquals(0, CliffAvoidance.penalty(ctx, new CliffAvoidance.Memo(), 6, 64, 0), 0);
    }

    // the cells of a found path, or null
    private static List<BetterBlockPos> search(MapWorld w, boolean nether) {
        Ctx ctx = new Ctx(w, nether);
        BetterBlockPos start = new BetterBlockPos(0, 64, 0);
        AStarPathFinder pf = new AStarPathFinder(start, start.x, start.y, start.z, new GoalBlock(10, 64, 0), new Favoring(null, ctx), ctx);
        Optional<IPath> p;
        try {
            ctx.claimSearchCaches();
            p = pf.calculate0(10000, 10000);
        } finally {
            ctx.releaseSearchCaches();
        }
        return p.map(IPath::positions).orElse(null);
    }

    private static MapWorld deck(int widthInZ) {
        MapWorld w = new MapWorld();
        for (int x = -2; x <= 12; x++) {
            for (int z = 0; z < widthInZ; z++) {
                w.set(x, 63, z, Blocks.STONE.defaultBlockState());
            }
        }
        return w;
    }

    private static int deepestIn(List<BetterBlockPos> path) {
        int most = 0;
        for (BetterBlockPos pos : path) {
            most = Math.max(most, pos.z);
        }
        return most;
    }

    @Test
    public void theSearchTakesTheInlandRouteInTheNetherAndTheRidgeAnywhereElse() {
        // the straight line from start to goal runs along the edge of a deck with a void on one side
        List<BetterBlockPos> overworld = search(deck(9), false);
        List<BetterBlockPos> nether = search(deck(9), true);
        assertNotNull(overworld);
        assertNotNull(nether);
        assertEquals(0, deepestIn(overworld));
        assertTrue("hugged the edge: " + nether, deepestIn(nether) >= 2);
    }

    @Test
    public void aRidgeIsStillAPathWhenItIsAllThereIs() {
        // two wide and a void on both sides: every node pays, so the penalty can't change which way is cheapest
        List<BetterBlockPos> path = search(deck(2), true);
        assertNotNull(path);
        assertEquals(10, path.get(path.size() - 1).x);
    }
}
