package baritone.pathing.calc;

import adris.altoclef.util.helpers.ItemFixture;
import adris.altoclef.util.helpers.MobThreat;
import adris.altoclef.util.helpers.RiskField;
import adris.altoclef.util.helpers.Tuning;
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.movement.CalculationContext;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

// S137 step 2: a zombie that wants us beside the straight way. without risk_mobs the search walks past it; with it the path
// bends away, and the search line's hp from the mobs is lower on the bent path. the same fake world as CliffAvoidanceTest,
// and the same skip when there is no shadow BaritoneAPI around
public class MobRiskSearchTest {

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
    private String tuningWas;
    private Path tuning;

    @Before
    public void setUp() throws Exception {
        ItemFixture.boot();
        try {
            settings = BaritoneAPI.getSettings();
        } catch (Throwable t) {
            Assume.assumeNoException("needs the shadow BaritoneAPI from the bench harness", t);
        }
        potions = settings.considerPotionEffects.value;
        settings.considerPotionEffects.value = false;
        tuningWas = System.getProperty("buddy.tuning");
        tuning = Files.createTempFile("tuning", ".properties");
        System.setProperty("buddy.tuning", tuning.toString());
    }

    @After
    public void tearDown() throws Exception {
        if (settings != null) {
            settings.considerPotionEffects.value = potions;
        }
        if (tuning != null) {
            Files.writeString(tuning, "");
            Tuning.now("risk_mobs", 0);
            Files.deleteIfExists(tuning);
        }
        if (tuningWas == null) {
            System.clearProperty("buddy.tuning");
        } else {
            System.setProperty("buddy.tuning", tuningWas);
        }
        RiskField.clear(0);
    }

    // a floor of stone at y 63, x -2 to 14 and z -8 to 8
    private static MapWorld floor() {
        MapWorld w = new MapWorld();
        for (int x = -2; x <= 14; x++) {
            for (int z = -8; z <= 8; z++) {
                w.set(x, 63, z, Blocks.STONE.defaultBlockState());
            }
        }
        return w;
    }

    private record Found(List<BetterBlockPos> path, double mobHp, int[] shaped) {}

    private static Found search(boolean mobs) throws Exception {
        Files.writeString(Path.of(System.getProperty("buddy.tuning")), "risk_mobs=" + (mobs ? 1 : 0) + "\n");
        assertEquals(mobs ? 1 : 0, Tuning.now("risk_mobs", 0), 0);
        // a zombie a block to the side of the way from (0, 64, 0) to (12, 64, 0), halfway; the test's context has no world,
        // so neither has the field
        RiskField.clear(0);
        RiskField.offer(1, null, false, List.of(new RiskField.Source(7, MobThreat.of("zombie", 1), 6.5, 64, 1.5, 1, 1, true)));
        Ctx ctx = new Ctx(floor());
        BetterBlockPos start = new BetterBlockPos(0, 64, 0);
        AStarPathFinder pf = new AStarPathFinder(start, start.x, start.y, start.z, new GoalBlock(12, 64, 0), new Favoring(null, ctx), ctx);
        Optional<IPath> p;
        try {
            ctx.claimSearchCaches();
            p = pf.calculate0(10000, 10000);
        } finally {
            ctx.releaseSearchCaches();
        }
        assertTrue(p.isPresent());
        IPath path = p.get().postProcess();
        RiskCost.Search risk = pf.risk();
        assertNotNull(risk.mobs);
        assertEquals(mobs, risk.mobsPriced);
        double hp = 0, arrival = 0;
        for (int i = 0; i < path.movements().size(); i++) {
            double cost = path.movements().get(i).getCost();
            arrival += cost;
            BetterBlockPos at = path.positions().get(i + 1);
            hp += RiskCost.mobHp(risk, at.x, at.y, at.z, cost, arrival);
        }
        return new Found(path.positions(), hp, RiskCost.shaped(risk, 1));
    }

    private static int mostNegativeZ(List<BetterBlockPos> path) {
        int most = 0;
        for (BetterBlockPos pos : path) {
            most = Math.min(most, pos.z);
        }
        return most;
    }

    @Test
    public void thePathBendsAwayFromAZombieOnlyUnderRiskMobs() throws Exception {
        Found off = search(false);
        Found on = search(true);
        assertEquals("walked straight without the key: " + off.path(), 0, mostNegativeZ(off.path()));
        assertTrue("did not bend away: " + on.path(), mostNegativeZ(on.path()) <= -2);
        assertTrue("bent but no safer: " + on.mobHp() + " against " + off.mobHp(), on.mobHp() < off.mobHp());
        // off, the field still prices the path for the search line, and steered nothing; on, the zombie steered it
        assertTrue(off.mobHp() > 0);
        assertEquals(0, off.shaped().length);
        assertEquals(1, on.shaped().length);
        assertEquals(7, on.shaped()[0]);
    }
}
