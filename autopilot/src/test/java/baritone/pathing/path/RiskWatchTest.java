package baritone.pathing.path;

import adris.altoclef.util.baritone.GoalFlee;
import adris.altoclef.util.helpers.ItemFixture;
import adris.altoclef.util.helpers.MobThreat;
import adris.altoclef.util.helpers.RiskField;
import adris.altoclef.util.helpers.Tuning;
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.calc.AStarPathFinder;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// S137 step 2's walk check, after the smoke of steering 153: a mob that steered the search and blinks out of the field right
// after a replan for more gives no replan for less within risk_replan_flip, only after; a flee (a path toward GoalFlee) is
// watched for more whatever risk_mobs says, and never for less. the same fake world as MobRiskSearchTest
public class RiskWatchTest {

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
    // the replan clock is shared by every walk: each test starts far past the last one's
    private static long clock = 1_000_000;

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
        clock += 1_000_000;
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
        RiskField.alert(false);
        RiskField.taskWalk(false);
    }

    private static MapWorld floor() {
        MapWorld w = new MapWorld();
        for (int x = -20; x <= 30; x++) {
            for (int z = -20; z <= 20; z++) {
                w.set(x, 63, z, Blocks.STONE.defaultBlockState());
            }
        }
        return w;
    }

    private static void mobs(boolean risk) throws Exception {
        Files.writeString(Path.of(System.getProperty("buddy.tuning")), "risk_mobs=" + (risk ? 1 : 0) + "\n");
        Tuning.now("risk_mobs", 0);
    }

    private static RiskField.Source zombie(int id, double x, double z) {
        return new RiskField.Source(id, MobThreat.of("zombie", 1), x, 64, z, 1, 1, true);
    }

    private static IPath path(Ctx ctx, Goal goal) {
        BetterBlockPos start = new BetterBlockPos(0, 64, 0);
        AStarPathFinder pf = new AStarPathFinder(start, start.x, start.y, start.z, goal, new Favoring(null, ctx), ctx);
        Optional<IPath> p = pf.calculate(10000, 10000).getPath();
        assertTrue(p.isPresent());
        return p.get();
    }

    private static final BetterBlockPos FEET = new BetterBlockPos(0, 64, 0);

    @Test
    public void aSteeringMobGoneJustAfterAMoreIsNoLessTillTheFlipWindowIsOver() throws Exception {
        mobs(true);
        RiskField.clear(0);
        Ctx ctx = new Ctx(floor());
        IPath way = path(ctx, new GoalBlock(12, 64, 0));
        // a walk on it: no mob, then a zombie on the way, a replan for more
        RiskWatch walk = new RiskWatch();
        assertNull(walk.check(way, 0, ctx, FEET, clock));
        RiskField.offer(1, null, false, List.of(zombie(7, 6.5, 0.5)));
        String more = walk.check(way, 0, ctx, FEET, clock + 1);
        assertNotNull(more);
        assertTrue(more, more.startsWith("more"));
        // the new walk, steered by the zombie, sees it once; then it blinks out of the field
        RiskWatch next = new RiskWatch();
        next.shapedBy(new int[]{7});
        assertNull(next.check(way, 0, ctx, FEET, clock + 2));
        RiskField.offer(2, null, false, List.of(zombie(8, 40, 0)));
        assertNull("less within the flip window", next.check(way, 0, ctx, FEET, clock + 40));
        // still gone after it: now the detour goes
        RiskField.offer(3, null, false, List.of(zombie(8, 40, 3)));
        String less = next.check(way, 0, ctx, FEET, clock + 1 + (long) RiskWatch.FLIP);
        assertNotNull(less);
        assertTrue(less, less.startsWith("less"));
    }

    @Test
    public void aFleeIsWatchedForMoreWithoutRiskMobsAndNeverForLess() throws Exception {
        mobs(false);
        RiskField.clear(0);
        RiskField.offer(1, null, false, List.of(zombie(7, -1.5, 0.5)));
        MapWorld world = floor();
        Ctx ctx = new Ctx(world);
        IPath away = path(ctx, new GoalFlee(() -> CliffAvoidance.of(world), () -> null));
        assertTrue(away.movements().size() > 3);
        RiskWatch walk = new RiskWatch();
        walk.shapedBy(new int[]{7});
        assertNull(walk.check(away, 0, ctx, FEET, clock));
        // the zombie that steered it gone, close: no less on a flee
        RiskField.offer(2, null, false, List.of(zombie(8, -40, 0)));
        assertNull(walk.check(away, 0, ctx, FEET, clock + 500));
        // three zombies on the rest of the way out: more
        BetterBlockPos mid = away.positions().get(away.positions().size() / 2);
        RiskField.offer(3, null, false, List.of(zombie(8, -40, 0), zombie(9, mid.x + 0.5, mid.z + 1.5),
                zombie(10, mid.x + 0.5, mid.z - 0.5), zombie(11, mid.x + 1.5, mid.z + 0.5)));
        String more = walk.check(away, 0, ctx, FEET, clock + 1000);
        assertNotNull(more);
        assertTrue(more, more.startsWith("more"));
    }

    private static void route() throws Exception {
        Files.writeString(Path.of(System.getProperty("buddy.tuning")), "risk_flee=1\nrisk_mobs=0\n");
        Tuning.now("risk_mobs", 0);
        RiskField.taskWalk(true);
    }

    // S137, a place goal by a safer route: under risk_flee a walk is watched while the meter is over calm. one planned in
    // calm planned for no risk, so the first check that finds a mob on the rest plans again; the rest's expected hp is
    // there for the go or flee
    @Test
    public void aWalkPlannedInCalmPlansAgainOnceTheMeterIsOverCalm() throws Exception {
        route();
        RiskField.clear(0);
        RiskField.alert(false);
        Ctx ctx = new Ctx(floor());
        IPath way = path(ctx, new GoalBlock(12, 64, 0));
        RiskWatch walk = new RiskWatch();
        walk.planned(false);
        // calm, a zombie on the way or not: not watched
        RiskField.offer(1, null, false, List.of(zombie(7, 6.5, 0.5)));
        assertNull(walk.check(way, 0, ctx, FEET, clock));
        assertTrue(Double.isNaN(walk.expected(0)));
        // the meter over calm: watched, and planned again at the first look
        RiskField.alert(true);
        String more = walk.check(way, 0, ctx, FEET, clock + 1);
        assertNotNull(more);
        assertTrue(more, more.startsWith("more"));
        assertTrue(walk.expected(0) > 1);
        // past the zombie the rest costs less
        assertTrue(walk.expected(way.movements().size() - 1) < walk.expected(0));
    }

    @Test
    public void aWalkThatPricedTheMobsKeepsItsOwnBaseline() throws Exception {
        route();
        RiskField.clear(0);
        RiskField.alert(true);
        RiskField.offer(1, null, false, List.of(zombie(7, 6.5, 0.5)));
        Ctx ctx = new Ctx(floor());
        IPath way = path(ctx, new GoalBlock(12, 64, 0));
        RiskWatch walk = new RiskWatch();
        walk.planned(true);
        assertNull(walk.check(way, 0, ctx, FEET, clock));
        assertFalse(Double.isNaN(walk.expected(0)));
        // calm again and nothing remembered: not watched, nothing to say
        RiskField.alert(false);
        assertNull(walk.check(way, 0, ctx, FEET, clock + 50));
        assertTrue(Double.isNaN(walk.expected(0)));
        // a fight has the wheel: not the task's walk, not watched
        RiskField.alert(true);
        RiskField.taskWalk(false);
        assertNull(walk.check(way, 0, ctx, FEET, clock + 55));
        assertTrue(Double.isNaN(walk.expected(0)));
        RiskField.taskWalk(true);
        // a remembered mob in the field keeps the watch on in calm
        RiskField.alert(true);
        assertNull(walk.check(way, 0, ctx, FEET, clock + 60));
        assertEquals(1, RiskField.remember(clock + 60));
        RiskField.alert(false);
        // (the field's clock is the memory's: a tick after the mob was remembered)
        RiskField.offer(clock + 61, null, false, List.of());
        assertTrue(RiskField.current().remembers());
        assertNull(walk.check(way, 0, ctx, FEET, clock + 70));
        assertFalse(Double.isNaN(walk.expected(0)));
    }
}
