package baritone.pathing.calc;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.altoclef.AltoClefSettings;
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Moves;
import baritone.utils.BlockStateInterface;
import baritone.utils.ToolSet;
import baritone.utils.pathing.Favoring;
import baritone.utils.pathing.MutableMoveResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// the two search shortcuts: the moves that can't say yes get skipped (must give the same search, node for node) and the
// walk-through rules get remembered per block (must give the same path for a rule that doesn't change, and ask far less).
// same harness (and the same skip when the shadow BaritoneAPI isn't there) as VineLogPathTest
public class PathPerfTest {
    private static final int FLOOR = 63;

    private Settings settings;
    private boolean parkour, climb, neos, momentum, potions, chat;

    @Before
    public void setUp() {
        ItemFixture.boot();
        try {
            settings = BaritoneAPI.getSettings();
        } catch (Throwable t) {
            Assume.assumeNoException("needs the shadow BaritoneAPI from the bench harness", t);
        }
        parkour = settings.allowParkour.value;
        climb = settings.allowClimbJumps.value;
        neos = settings.allowNeos.value;
        momentum = settings.allowMomentumJumps.value;
        potions = settings.considerPotionEffects.value;
        chat = settings.chatDebug.value;
        settings.considerPotionEffects.value = false;
        settings.chatDebug.value = false;
    }

    @After
    public void tearDown() {
        if (settings == null) {
            return;
        }
        settings.allowParkour.value = parkour;
        settings.allowClimbJumps.value = climb;
        settings.allowNeos.value = neos;
        settings.allowMomentumJumps.value = momentum;
        settings.considerPotionEffects.value = potions;
        settings.chatDebug.value = chat;
        AltoClefSettings.getInstance().getForceAvoidWalkThroughPredicates().clear();
    }

    private static final class Ctx extends CalculationContext {
        Ctx(BlockStateInterface bsi, boolean canSprint) {
            super(null, true, null, null, bsi, new ToolSet(null) {
                @Override
                public double getStrVsBlock(BlockState state) {
                    float hardness = state.getBlock().defaultDestroyTime();
                    return hardness < 0 ? -1 : hardness == 0 ? 1 : 8.0 / (hardness * 30);
                }
            }, true, false, canSprint, 0, 1.0f, 20.0, false);
        }
    }

    // a stone floor with a two wide trench across it and a few walls, so there is something for every kind of move to chew on
    private static VineLogPathTest.MapWorld world() {
        VineLogPathTest.MapWorld w = new VineLogPathTest.MapWorld();
        for (int x = -16; x <= 16; x++) {
            for (int z = -16; z <= 16; z++) {
                if (z == 4 || z == 5) {
                    continue; // the trench, nothing under it
                }
                w.set(x, FLOOR, z, Blocks.STONE.defaultBlockState());
            }
        }
        for (int x = -6; x <= 6; x++) {
            w.set(x, FLOOR + 1, -3, Blocks.STONE.defaultBlockState());
            w.set(x, FLOOR + 2, -3, Blocks.STONE.defaultBlockState());
        }
        // something to climb
        for (int y = FLOOR + 1; y <= FLOOR + 4; y++) {
            w.set(8, y, 0, Blocks.STONE.defaultBlockState());
        }
        return w;
    }

    private static final class Run {
        final Optional<IPath> path;
        final int nodes, moves;

        Run(Optional<IPath> path, int nodes, int moves) {
            this.path = path;
            this.nodes = nodes;
            this.moves = moves;
        }

        List<BetterBlockPos> positions() {
            return path.map(IPath::positions).orElse(List.of());
        }
    }

    private static Run search(CalculationContext ctx, Goal goal, Moves[] override, boolean claim) {
        BetterBlockPos start = new BetterBlockPos(0, FLOOR + 1, -8);
        AStarPathFinder pf = new AStarPathFinder(start, start.x, start.y, start.z, goal, new Favoring(null, ctx), ctx);
        pf.movesOverride = override;
        Optional<IPath> p;
        try {
            if (claim) {
                ctx.claimSearchCaches();
            }
            p = pf.calculate0(5000, 5000);
        } finally {
            if (claim) {
                ctx.releaseSearchCaches();
            }
        }
        return new Run(p, pf.numNodesConsidered, pf.numMovementsConsidered);
    }

    private void flags(boolean parkourOn, boolean neosOn, boolean momentumOn, boolean climbOn) {
        settings.allowParkour.value = parkourOn;
        settings.allowNeos.value = neosOn;
        settings.allowMomentumJumps.value = momentumOn;
        settings.allowClimbJumps.value = climbOn;
    }

    private void sameSearch(boolean parkourOn, boolean neosOn, boolean momentumOn, boolean climbOn, boolean canSprint, int expectedMoves) {
        flags(parkourOn, neosOn, momentumOn, climbOn);
        VineLogPathTest.MapWorld w = world();
        // across the trench, over the walls
        Goal goal = new GoalBlock(2, FLOOR + 1, 12);
        CalculationContext ctx = new Ctx(w, canSprint);
        Moves[] live = Moves.live(ctx);
        assertEquals("live move count for parkour=" + parkourOn + " neo=" + neosOn + " momentum=" + momentumOn
                + " climb=" + climbOn + " sprint=" + canSprint, expectedMoves, live.length);
        Run full = search(new Ctx(w, canSprint), goal, Moves.values(), true);
        Run cut = search(new Ctx(w, canSprint), goal, null, true);
        assertTrue("the world has a way across", full.path.isPresent());
        assertEquals(full.positions(), cut.positions());
        assertEquals(full.nodes, cut.nodes);
        assertTrue(cut.moves <= full.moves);
        System.out.println("PERFCOUNT moves parkour=" + parkourOn + " neo=" + neosOn + " momentum=" + momentumOn + " climb=" + climbOn
                + " sprint=" + canSprint + " nodes=" + full.nodes + "/" + cut.nodes + " applies=" + full.moves + "->" + cut.moves);
        if (expectedMoves < Moves.values().length) {
            assertTrue("skipped moves are skipped", cut.moves < full.moves);
        }
    }

    @Test
    public void everythingOnSkipsNothing() {
        sameSearch(true, true, true, true, true, 42);
    }

    @Test
    public void parkourOffSkipsAllTheJumps() {
        sameSearch(false, true, true, true, true, 18);
    }

    @Test
    public void neosOffSkipsTheNeos() {
        sameSearch(true, false, true, true, true, 38);
    }

    @Test
    public void momentumOffSkipsTheMomentums() {
        sameSearch(true, true, false, true, true, 34);
    }

    @Test
    public void climbsOffSkipsTheClimbs() {
        sameSearch(true, true, true, false, true, 34);
    }

    @Test
    public void notSprintingSkipsNeosAndMomentums() {
        sameSearch(true, true, true, true, false, 30);
    }

    // every move that gets dropped has to say no everywhere, not just on the one world the search happens to look at
    @Test
    public void droppedMovesAlwaysSayNo() {
        flags(false, true, true, true);
        VineLogPathTest.MapWorld w = world();
        // ladders and vines hanging in the gap so the climbs have something to find if they were allowed to look
        w.set(0, FLOOR + 1, 4, Blocks.LADDER.defaultBlockState());
        CalculationContext ctx = new Ctx(w, true);
        java.util.Set<Moves> kept = new java.util.HashSet<>(java.util.Arrays.asList(Moves.live(ctx)));
        MutableMoveResult res = new MutableMoveResult();
        int dropped = 0;
        for (Moves m : Moves.values()) {
            if (kept.contains(m)) {
                continue;
            }
            dropped++;
            for (int x = -12; x <= 12; x++) {
                for (int z = -12; z <= 12; z++) {
                    for (int y = FLOOR; y <= FLOOR + 3; y++) {
                        res.reset();
                        m.apply(ctx, x, y, z, res);
                        assertTrue(m + " at " + x + " " + y + " " + z, res.cost >= 1e6);
                    }
                }
            }
        }
        assertEquals(24, dropped);
    }

    // a rule that doesn't change gets the same path with or without the memo, and the memo is what makes it cheap
    @Test
    public void walkThroughMemoKeepsThePathAndSavesTheCalls() {
        flags(false, true, true, true);
        AtomicInteger calls = new AtomicInteger();
        // a block of air on the way that we are not to walk through, so the path has to go around
        Predicate<BlockPos> zone = pos -> {
            calls.incrementAndGet();
            return pos.getX() >= -2 && pos.getX() <= 2 && pos.getZ() >= -2 && pos.getZ() <= 2;
        };
        AltoClefSettings.getInstance().getForceAvoidWalkThroughPredicates().add(zone);
        VineLogPathTest.MapWorld w = world();
        Goal goal = new GoalBlock(2, FLOOR + 1, 12);

        calls.set(0);
        Run plain = search(new Ctx(w, true), goal, null, false);
        int plainCalls = calls.get();
        calls.set(0);
        Run memo = search(new Ctx(w, true), goal, null, true);
        int memoCalls = calls.get();

        System.out.println("PERFCOUNT walkthrough nodes=" + plain.nodes + "/" + memo.nodes + " predicateCalls=" + plainCalls + "->" + memoCalls);
        assertTrue(plain.path.isPresent());
        assertEquals(plain.positions(), memo.positions());
        assertEquals(plain.nodes, memo.nodes);
        // the neighbours overlap a lot but not everywhere, about 30% of the asks go away on this map. a regression would
        // put it back near 100%
        assertTrue("memo asks less: " + memoCalls + " vs " + plainCalls, memoCalls * 10 < plainCalls * 8);
    }
}
