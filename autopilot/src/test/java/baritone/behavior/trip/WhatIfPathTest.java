package baritone.behavior.trip;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.movement.CalculationContext;
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// the what-if half of the trip ask, on a fake world with no game: a gap the real bag can't cross and the pretend one
// can, and the placements read off the path. same fake world idea as BoatRunCostTest
public class WhatIfPathTest {

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

    private static ToolSet hands() {
        return new ToolSet(null) {
            @Override
            public double getStrVsBlock(BlockState state) {
                float hardness = state.getBlock().defaultDestroyTime();
                return hardness < 0 ? -1 : hardness == 0 ? 1 : 1.0 / (hardness * 30);
            }
        };
    }

    // the real bag: no blocks
    private static final class Real extends CalculationContext {
        Real(BlockStateInterface bsi) {
            super(null, true, null, null, bsi, hands(), false, false, true, 0, 1.0f, 20.0, false);
        }
    }

    private Settings settings;
    private boolean potions;
    private boolean parkour;

    @Before
    public void setUp() {
        ItemFixture.boot();
        try {
            settings = BaritoneAPI.getSettings();
        } catch (Throwable t) {
            Assume.assumeNoException("needs the shadow BaritoneAPI", t);
        }
        potions = settings.considerPotionEffects.value;
        parkour = settings.allowParkour.value;
        settings.considerPotionEffects.value = false;
        settings.allowParkour.value = false;
    }

    @After
    public void tearDown() {
        if (settings != null) {
            settings.considerPotionEffects.value = potions;
            settings.allowParkour.value = parkour;
        }
    }

    // two stone platforms with `gap` columns of nothing between them, not even a floor far below. 3 wide so the
    // search has room to turn
    private static MapWorld gap(int gap) {
        MapWorld w = new MapWorld();
        for (int z = -1; z <= 1; z++) {
            for (int x = -6; x <= 0; x++) {
                w.set(x, 62, z, Blocks.STONE.defaultBlockState());
            }
            for (int x = gap + 1; x <= gap + 7; x++) {
                w.set(x, 62, z, Blocks.STONE.defaultBlockState());
            }
        }
        return w;
    }

    private static final BetterBlockPos START = new BetterBlockPos(-3, 63, 0);

    @Test
    public void aGapTheRealBagCantCrossTheWhatIfCan() {
        MapWorld w = gap(6);
        GoalBlock goal = new GoalBlock(10, 63, 0);
        WhatIfSearch.Result real = WhatIfSearch.run(new Real(w), START, goal, 5000);
        assertFalse("no blocks, no way over a 6 wide hole", real.found() && real.reachesGoal());
        WhatIfSearch.Result whatIf = WhatIfSearch.run(new WhatIfContext(w, hands()), START, goal, 5000);
        assertTrue(whatIf.found());
        assertTrue(whatIf.reachesGoal());
        // one block per column of the hole, no more
        assertEquals(6, whatIf.placements());
        assertFalse(whatIf.breaksStone());
        assertTrue(TripMath.madeProgress(whatIf.reachesGoal(), whatIf.startHeuristic(), whatIf.endHeuristic(), settings.costHeuristic.value));
        // and that's 6 + 2 to go and get with nothing in the bag
        assertEquals(8, TripMath.deficit(whatIf.placements(), 0));
        // the estimate is the real cost of a path that reaches the goal
        assertTrue(whatIf.estTicks() > 0 && Double.isFinite(whatIf.estTicks()));
    }

    @Test
    public void aLongBridgeCountsEveryBlockSoTheCapCanSayNo() {
        MapWorld w = gap(40);
        GoalBlock goal = new GoalBlock(44, 63, 0);
        WhatIfSearch.Result whatIf = WhatIfSearch.run(new WhatIfContext(w, hands()), START, goal, 20000);
        assertTrue(whatIf.found());
        assertTrue(whatIf.reachesGoal());
        assertEquals(40, whatIf.placements());
        // gatherMaxBlocks is the line TripBehavior draws, a lower one says no to this bridge
        assertTrue(whatIf.placements() > 32);
        assertTrue(whatIf.placements() <= settings.gatherMaxBlocks.value);
    }

    @Test
    public void walkingNeedsNothing() {
        MapWorld w = gap(0);
        GoalBlock goal = new GoalBlock(4, 63, 0);
        WhatIfSearch.Result whatIf = WhatIfSearch.run(new WhatIfContext(w, hands()), START, goal, 5000);
        assertTrue(whatIf.reachesGoal());
        assertEquals(0, whatIf.placements());
        assertTrue(new GatherPlan(Trip.Ask.STUCK, 0, TripMath.deficit(0, 0), false).isEmpty());
    }
}
