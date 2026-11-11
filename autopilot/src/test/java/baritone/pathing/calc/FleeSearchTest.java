package baritone.pathing.calc;

import adris.altoclef.util.baritone.GoalFlee;
import adris.altoclef.util.helpers.ItemFixture;
import adris.altoclef.util.helpers.MobThreat;
import adris.altoclef.util.helpers.RiskField;
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.pathing.calc.IPath;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.CliffAvoidance;
import baritone.pathing.movement.RiskCost;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// S137 step 3: the flee search. from beside a zombie that wants us, the search toward GoalFlee ends at the nearest cell out of
// what the zombie can walk in the field's horizon, away from it and short of the floor's edge; the cell it starts from is
// never the goal, and fire on a shooter's line is no cover. the same fake world as MobRiskSearchTest, and the same skip when
// there is no shadow BaritoneAPI around
public class FleeSearchTest {

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
        RiskField.clear(0);
    }

    // a floor of stone at y 63, x -20 to 30 and z -20 to 20
    private static MapWorld floor() {
        MapWorld w = new MapWorld();
        for (int x = -20; x <= 30; x++) {
            for (int z = -20; z <= 20; z++) {
                w.set(x, 63, z, Blocks.STONE.defaultBlockState());
            }
        }
        return w;
    }

    private record Fled(List<BetterBlockPos> path, GoalFlee goal, RiskCost.Search risk) {}

    private static Fled flee(List<RiskField.Source> mobs) {
        return flee(floor(), mobs);
    }

    private static Fled flee(MapWorld world, List<RiskField.Source> mobs) {
        // the test's context has no world, so neither has the field
        RiskField.clear(0);
        if (!mobs.isEmpty()) RiskField.offer(1, null, false, mobs);
        Ctx ctx = new Ctx(world);
        GoalFlee goal = new GoalFlee(() -> CliffAvoidance.of(world), () -> null);
        BetterBlockPos start = new BetterBlockPos(2, 64, 0);
        AStarPathFinder pf = new AStarPathFinder(start, start.x, start.y, start.z, goal, new Favoring(null, ctx), ctx);
        Optional<IPath> p;
        try {
            ctx.claimSearchCaches();
            p = pf.calculate0(10000, 10000);
        } finally {
            ctx.releaseSearchCaches();
        }
        assertTrue(p.isPresent());
        return new Fled(p.get().positions(), goal, pf.risk());
    }

    @Test
    public void theFleeEndsJustOutOfTheZombiesReachAwayFromIt() {
        // the zombie a block and a half west of the start: 1.43 + 3 s x 2.3 = 8.3 blocks it can get to
        Fled fled = flee(List.of(new RiskField.Source(7, MobThreat.of("zombie", 1), 0.5, 64, 0.5, 1, 1, true)));
        BetterBlockPos end = fled.path().get(fled.path().size() - 1);
        double d = Math.hypot(end.x + 0.5 - 0.5, end.z + 0.5 - 0.5);
        assertTrue("ended inside the reach: " + end + " at " + d, d > 8.3);
        assertTrue("ran further than the nearest calm ground: " + end + " at " + d, d < 12);
        assertTrue(fled.goal().isInGoal(end.x, end.y, end.z));
        for (BetterBlockPos pos : fled.path()) {
            assertTrue("went past the zombie: " + fled.path(), pos.x >= 2);
        }
        // the way out was priced whatever risk_mobs says
        assertTrue(fled.risk().mobsPriced);
    }

    // the first smoke: 88% of the searches ended on the cell they started from, a run on the spot asked again every tick.
    // the start is never the goal: with no mob in the field the flee is one step, to the firm cell beside it
    @Test
    public void theStartIsNeverTheGoal() {
        Fled fled = flee(List.of());
        assertEquals(2, fled.path().size());
        BetterBlockPos end = fled.path().get(1);
        assertTrue(end.x != 2 || end.z != 0);
        assertTrue(fled.goal().isInGoal(end.x, end.y, end.z));
    }

    // the first smoke's cause: the search's cells read fire as solid (no cell to walk through), so the line of a blaze that
    // was hitting the buddy stopped at the burning fortress wall and the cell behind the fire was "calm ground". with a
    // sheet of fire between the blaze and the buddy, and a stone wall further on, the flee ends behind the stone
    @Test
    public void fireIsNoCoverTheFleeGoesOnToTheWall() {
        MapWorld world = floor();
        for (int y = 64; y <= 66; y++) {
            for (int z = -1; z <= 1; z++) world.set(4, y, z, Blocks.FIRE.defaultBlockState());
            for (int z = -2; z <= 2; z++) world.set(8, y, z, Blocks.STONE.defaultBlockState());
        }
        CliffAvoidance.Cells cells = CliffAvoidance.of(world);
        assertEquals(CliffAvoidance.Cell.SOLID, cells.at(4, 65, 0));
        assertTrue(!cells.blocksSight(4, 65, 0));
        assertTrue(cells.blocksSight(8, 65, 0));
        // a blaze that wants us two blocks west of the start, in range of every cell of the floor
        Fled fled = flee(world, List.of(new RiskField.Source(7, MobThreat.of("blaze", 1), 0.5, 64, 0.5, 1, 1, true)));
        BetterBlockPos end = fled.path().get(fled.path().size() - 1);
        assertTrue("ended behind the fire, in the blaze's sight: " + fled.path(), end.x >= 9);
        assertTrue("ended out of the wall's shadow: " + fled.path(), Math.abs(end.z) <= 2 && end.x <= 12);
        assertTrue(fled.goal().isInGoal(end.x, end.y, end.z));
    }

    // what counts as cover is the block's box: side to side and half the cell high. a fence or a door is none
    @Test
    public void coverIsABlockThatSpansItsCell() {
        assertTrue(CliffAvoidance.cover(Blocks.NETHER_BRICKS.defaultBlockState()));
        assertTrue(CliffAvoidance.cover(Blocks.SOUL_SAND.defaultBlockState()));
        assertTrue(CliffAvoidance.cover(Blocks.NETHER_BRICK_SLAB.defaultBlockState()));
        assertTrue(!CliffAvoidance.cover(Blocks.FIRE.defaultBlockState()));
        assertTrue(!CliffAvoidance.cover(Blocks.SOUL_FIRE.defaultBlockState()));
        assertTrue(!CliffAvoidance.cover(Blocks.AIR.defaultBlockState()));
        assertTrue(!CliffAvoidance.cover(Blocks.NETHER_BRICK_FENCE.defaultBlockState()));
        assertTrue(!CliffAvoidance.cover(Blocks.CRIMSON_ROOTS.defaultBlockState()));
        assertTrue(!CliffAvoidance.cover(Blocks.LAVA.defaultBlockState()));
    }
}
