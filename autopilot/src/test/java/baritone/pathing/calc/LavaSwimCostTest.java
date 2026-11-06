package baritone.pathing.calc;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.altoclef.AltoClefSettings;
import baritone.api.BaritoneAPI;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.movement.ActionCosts;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.movements.MovementTraverse;
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

// swim through lava makes the planner read lava as water (isWater says yes), which priced a lake crossing like a
// swim and made it look free. lava costs what lava is, so the escape takes the shortest dip
public class LavaSwimCostTest {

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

    private boolean potions;

    @Before
    public void setUp() {
        ItemFixture.boot();
        try {
            potions = BaritoneAPI.getSettings().considerPotionEffects.value;
            BaritoneAPI.getSettings().considerPotionEffects.value = false;
        } catch (Throwable t) {
            Assume.assumeNoException("needs the shadow BaritoneAPI from the bench harness", t);
        }
        AltoClefSettings.getInstance().allowSwimThroughLava(true);
    }

    @After
    public void tearDown() {
        AltoClefSettings.getInstance().allowSwimThroughLava(false);
        BaritoneAPI.getSettings().considerPotionEffects.value = potions;
    }

    // a strip of lava over x 0..lava-1 at z 0, feet level 63 over a stone floor at 62. the banks are bare floor either side
    private static MapWorld strip(int lava) {
        MapWorld w = new MapWorld();
        for (int x = -6; x <= lava + 6; x++) {
            for (int z = -6; z <= 6; z++) {
                w.set(x, 62, z, Blocks.STONE.defaultBlockState());
            }
        }
        // five wide so there is no sidestepping onto dry ground beside the strip
        for (int x = 0; x < lava; x++) {
            for (int z = -2; z <= 2; z++) {
                w.set(x, 63, z, Blocks.LAVA.defaultBlockState());
            }
        }
        return w;
    }

    // dry anywhere off the strip
    private static final Goal DRY = new Goal() {
        @Override
        public boolean isInGoal(int x, int y, int z) {
            return x < 0 || x >= 7;
        }

        @Override
        public double heuristic(int x, int y, int z) {
            return 0;
        }
    };

    private static List<BetterBlockPos> search(MapWorld w, BetterBlockPos start, Ctx ctx) {
        AStarPathFinder pf = new AStarPathFinder(start, start.x, start.y, start.z, DRY, new Favoring(null, ctx), ctx);
        Optional<IPath> p;
        try {
            ctx.claimSearchCaches();
            p = pf.calculate0(10000, 10000);
        } finally {
            ctx.releaseSearchCaches();
        }
        return p.map(IPath::positions).orElse(null);
    }

    @Test
    public void aBlockOfLavaCostsLavaSpeedPlusTheHearts() {
        Ctx ctx = new Ctx(strip(7));
        double lava = MovementTraverse.cost(ctx, 3, 63, 0, 4, 0);
        // 25.5 ticks to cross it, and about 0.2 hp a tick at 20 ticks a heart on top
        assertEquals(1 / (2 * 0.02 * 0.98) * (1 + 0.2 * 20), lava, 1e-6);
        assertTrue("lava swam like water: " + lava, lava > 10 * ctx.waterWalkSpeed);
    }

    @Test
    public void steppingOutOfTheLakeStillPaysForTheLakeLikeTheDiagonalDoes() {
        Ctx ctx = new Ctx(strip(7));
        assertEquals(ctx.lavaSwimCost, MovementTraverse.cost(ctx, 0, 63, 0, -1, 0), 1e-9);
    }

    @Test
    public void withTheToggleOffLavaIsStillAWallAndLandIsUnchanged() {
        AltoClefSettings.getInstance().allowSwimThroughLava(false);
        Ctx ctx = new Ctx(strip(7));
        assertEquals(ActionCosts.COST_INF, MovementTraverse.cost(ctx, -1, 63, 0, 0, 0), 0);
        double land = MovementTraverse.cost(ctx, -3, 63, 0, -2, 0);
        AltoClefSettings.getInstance().allowSwimThroughLava(true);
        assertEquals(land, MovementTraverse.cost(new Ctx(strip(7)), -3, 63, 0, -2, 0), 1e-9);
    }

    @Test
    public void theEscapeTakesTheNearShoreNotTheFarOne() {
        // lava over x 0..6, the player at x 1: the west bank is one cell off, the east bank is six
        MapWorld w = strip(7);
        Ctx ctx = new Ctx(w);
        List<BetterBlockPos> path = search(w, new BetterBlockPos(1, 63, 0), ctx);
        assertTrue(path != null);
        BetterBlockPos end = path.get(path.size() - 1);
        assertTrue("went for the far bank: " + path, end.x < 0);
        int wet = 0;
        for (BetterBlockPos p : path.subList(1, path.size())) {
            if (Math.abs(p.z) <= 2 && p.x >= 0 && p.x < 7) {
                wet++;
            }
        }
        assertTrue("swam " + wet + " blocks of lava: " + path, wet <= 1);
    }

    @Test
    public void theEscapeFlipsToTheEastWhenThatIsNearer() {
        MapWorld w = strip(7);
        Ctx ctx = new Ctx(w);
        List<BetterBlockPos> path = search(w, new BetterBlockPos(5, 63, 0), ctx);
        assertTrue(path != null);
        assertTrue(path.get(path.size() - 1).x >= 7);
    }
}
