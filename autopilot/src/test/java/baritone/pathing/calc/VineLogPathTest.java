package baritone.pathing.calc;

import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.util.baritone.GoalReachBlock;
import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Moves;
import baritone.utils.BlockStateInterface;
import baritone.utils.ToolSet;
import baritone.utils.pathing.BetterWorldBorder;
import baritone.utils.pathing.Favoring;
import baritone.utils.pathing.MutableMoveResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// a log in a jungle trunk with vines on it, and what A* does to get to where AltoClef's DestroyBlockTask wants to stand.
// the planner needs a Baritone.settings() with no game behind it, which only the bench harness's shadow BaritoneAPI gives
// (the real one starts a Minecraft client in its static init), so this skips itself when that isn't on the test classpath
public class VineLogPathTest {

    static final class MapWorld extends BlockStateInterface {
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

    static final class Ctx extends CalculationContext {
        Ctx(BlockStateInterface bsi) {
            this(bsi, false);
        }

        // throwaway false: bridging and pillaring aren't answers. a pickaxe-ish tool for everything
        Ctx(BlockStateInterface bsi, boolean throwaway) {
            super(null, true, null, null, bsi, new ToolSet(null) {
                @Override
                public double getStrVsBlock(BlockState state) {
                    float hardness = state.getBlock().defaultDestroyTime();
                    return hardness < 0 ? -1 : hardness == 0 ? 1 : 8.0 / (hardness * 30);
                }
            }, throwaway, false, true, 0, 1.0f, 20.0, false);
        }
    }

    static final class Result {
        final List<BetterBlockPos> positions = new ArrayList<>();
        final List<String> moves = new ArrayList<>();
        boolean found, inGoal;
    }

    private Settings settings;
    private boolean parkour, climb, neos, experimental, sprintJumping, headHitters, potions, chat;

    @Before
    public void setUp() {
        // Settings builds its options out of registry things, so the game's registries have to be up first
        ItemFixture.boot();
        try {
            settings = BaritoneAPI.getSettings();
        } catch (Throwable t) {
            Assume.assumeNoException("needs the shadow BaritoneAPI from the bench harness", t);
        }
        parkour = settings.allowParkour.value;
        climb = settings.allowClimbJumps.value;
        neos = settings.allowNeos.value;
        experimental = settings.fastMode.value;
        sprintJumping = settings.sprintJumping.value;
        headHitters = settings.headHitters.value;
        potions = settings.considerPotionEffects.value;
        chat = settings.chatDebug.value;
        // what the user plays with
        settings.allowParkour.value = true;
        settings.allowClimbJumps.value = true;
        settings.allowNeos.value = true;
        settings.fastMode.value = true;
        settings.sprintJumping.value = true;
        settings.headHitters.value = true;
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
        settings.fastMode.value = experimental;
        settings.sprintJumping.value = sprintJumping;
        settings.headHitters.value = headHitters;
        settings.considerPotionEffects.value = potions;
        settings.chatDebug.value = chat;
    }

    // DestroyBlockTask asks the client's world, this is the same question put to the map
    private static BlockGetter view(MapWorld w) {
        return new BlockGetter() {
            @Override
            public BlockEntity getBlockEntity(BlockPos pos) {
                return null;
            }

            @Override
            public BlockState getBlockState(BlockPos pos) {
                return w.get0(pos.getX(), pos.getY(), pos.getZ());
            }

            @Override
            public FluidState getFluidState(BlockPos pos) {
                return Fluids.EMPTY.defaultFluidState();
            }

            @Override
            public int getHeight() {
                return 384;
            }

            @Override
            public int getMinY() {
                return -64;
            }
        };
    }

    private static void vine(MapWorld w, int x, int y, int z, Direction attached) {
        w.set(x, y, z, Blocks.VINE.defaultBlockState().setValue(VineBlock.PROPERTY_BY_DIRECTION.get(attached), true));
    }

    // flat ground at y 63, a one block trunk at 0,0 with vines on all four faces from 64 to 72
    private static MapWorld jungle() {
        MapWorld w = new MapWorld();
        for (int x = -12; x <= 12; x++) {
            for (int z = -12; z <= 12; z++) {
                w.set(x, 63, z, Blocks.GRASS_BLOCK.defaultBlockState());
            }
        }
        for (int y = 64; y <= 72; y++) {
            w.set(0, y, 0, Blocks.JUNGLE_LOG.defaultBlockState());
            vine(w, 1, y, 0, Direction.WEST);
            vine(w, -1, y, 0, Direction.EAST);
            vine(w, 0, y, 1, Direction.NORTH);
            vine(w, 0, y, -1, Direction.SOUTH);
        }
        return w;
    }

    // a trunk standing in a pit: ground only at x >= ledge, vines on the face toward it
    private static MapWorld pit(int ledge) {
        MapWorld w = new MapWorld();
        for (int x = ledge; x <= 12; x++) {
            for (int z = -12; z <= 12; z++) {
                w.set(x, 63, z, Blocks.GRASS_BLOCK.defaultBlockState());
            }
        }
        // a bottom to the pit: a missed grab into nothing is rejected now (ClimbMiss), and a pit is not a void
        for (int x = -12; x < ledge; x++) {
            for (int z = -12; z <= 12; z++) {
                w.set(x, 49, z, Blocks.STONE.defaultBlockState());
            }
        }
        for (int y = 50; y <= 72; y++) {
            w.set(0, y, 0, Blocks.JUNGLE_LOG.defaultBlockState());
        }
        for (int y = 55; y <= 72; y++) {
            vine(w, 1, y, 0, Direction.WEST);
        }
        return w;
    }

    private static Result path(MapWorld w, BetterBlockPos start, Goal goal) {
        return path(w, start, goal, false);
    }

    static Result path(MapWorld w, BetterBlockPos start, Goal goal, boolean throwaway) {
        CalculationContext ctx = new Ctx(w, throwaway);
        AStarPathFinder pf = new AStarPathFinder(start, start.x, start.y, start.z, goal, new Favoring(null, ctx), ctx);
        Optional<IPath> p;
        try {
            ctx.claimSearchCaches();
            p = pf.calculate0(3000, 3000);
        } finally {
            ctx.releaseSearchCaches();
        }
        Result out = new Result();
        if (p.isEmpty()) {
            return out;
        }
        out.found = true;
        out.positions.addAll(p.get().positions());
        out.inGoal = goal.isInGoal(p.get().getDest().x, p.get().getDest().y, p.get().getDest().z);
        MutableMoveResult res = new MutableMoveResult();
        for (int i = 0; i < out.positions.size() - 1; i++) {
            BetterBlockPos a = out.positions.get(i), b = out.positions.get(i + 1);
            // the cheapest way between the two is the one A* took (Path builds the Movement the same way, minus needing a game)
            Moves best = null;
            double cost = Double.MAX_VALUE;
            for (Moves m : Moves.values()) {
                res.reset();
                m.apply(ctx, a.x, a.y, a.z, res);
                if (res.cost < 1e6 && res.x == b.x && res.y == b.y && res.z == b.z && res.cost < cost) {
                    cost = res.cost;
                    best = m;
                }
            }
            out.moves.add(String.valueOf(best));
        }
        return out;
    }

    private static boolean touchesVine(MapWorld w, Result r) {
        for (BetterBlockPos p : r.positions) {
            if (w.get0(p.x, p.y, p.z).getBlock() == Blocks.VINE || w.get0(p.x, p.y - 1, p.z).getBlock() == Blocks.VINE) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void aLogTwoUpIsNotAnExcuseToClimbTheVines() {
        MapWorld w = jungle();
        BlockPos log = new BlockPos(0, 66, 0);
        BetterBlockPos start = new BetterBlockPos(6, 64, 0);

        // the old goal: be in one of the six cells touching it. all of those are vines, one block up and then two, so the
        // cheapest way there is to jump onto the vines (Ascend counts a vine as something to stand on)
        Result old = path(w, start, new GoalNear(log, 1));
        assertTrue(old.found && old.inGoal);
        assertTrue("expected the old goal to climb, got " + old.moves, touchesVine(w, old));

        // what DestroyBlockTask asks for now
        Goal goal = DestroyBlockTask.pickGoal(view(w), log, false);
        assertTrue(goal instanceof GoalReachBlock);
        Result now = path(w, start, goal);
        assertTrue(now.found && now.inGoal);
        assertFalse("walked into the vines: " + now.moves, touchesVine(w, now));
        for (BetterBlockPos p : now.positions) {
            assertEquals("left the ground: " + now.moves, 64, p.y);
        }
        for (String m : now.moves) {
            assertTrue(m, m.startsWith("TRAVERSE") || m.startsWith("DIAGONAL"));
        }
    }

    @Test
    public void groundLevelLogsAreUntouched() {
        // a log at your feet was never a climb, make sure the new goal doesn't make it one
        MapWorld w = jungle();
        Result r = path(w, new BetterBlockPos(6, 64, 0), DestroyBlockTask.pickGoal(view(w), new BlockPos(0, 64, 0), false));
        assertTrue(r.found && r.inGoal);
        for (String m : r.moves) {
            assertTrue(m, m.startsWith("TRAVERSE") || m.startsWith("DIAGONAL"));
        }
    }

    @Test
    public void aClimbJumpIsStillThereWhenItIsTheOnlyWay() {
        // the trunk is across a three block gap from the ledge, the vines hang down the side facing us. the log is out of
        // arm's reach from the ledge, so getting onto the vines is the way there. nothing else crosses
        MapWorld w = pit(5);
        BlockPos log = new BlockPos(0, 64, 0);
        Goal goal = DestroyBlockTask.pickGoal(view(w), log, false);
        assertTrue(goal instanceof GoalReachBlock);
        assertFalse(goal.isInGoal(5, 64, 0));
        Result r = null;
        // the climb sim table fills in on the common pool the first time a search sees a shape, the next search gets it.
        // counting sleeps for that was a coin flip on a busy machine (every shape sat waiting for a worker and then took a
        // few seconds to solve), so wait for the pool to actually go idle instead. a round per layer of shapes: the ones
        // past the first grab only get asked about once the search can get that far
        for (int round = 0; round < 6; round++) {
            r = path(w, new BetterBlockPos(8, 64, 0), goal);
            if (r.found && r.inGoal) {
                break;
            }
            assertTrue("the climb sim fills never finished", ForkJoinPool.commonPool().awaitQuiescence(120, TimeUnit.SECONDS));
        }
        assertTrue(r.found && r.inGoal);
        boolean grabbed = false;
        for (String m : r.moves) {
            grabbed |= m.startsWith("CLIMB_GRAB");
        }
        assertTrue("expected a climb grab, got " + r.moves, grabbed);
    }

    @Test
    public void heuristicIsFineInsideAndNeverNegative() {
        GoalReachBlock goal = new GoalReachBlock(new BlockPos(100, 64, 100));
        for (int x = -9; x <= 9; x++) {
            for (int y = -9; y <= 9; y++) {
                for (int z = -9; z <= 9; z++) {
                    double h = goal.heuristic(100 + x, 64 + y, 100 + z);
                    assertTrue(h >= 0);
                    if (goal.isInGoal(100 + x, 64 + y, 100 + z)) {
                        // a heuristic that charges you for standing where you're done walks you away from the goal
                        assertEquals(0, h, 0);
                    }
                }
            }
        }
        // closer is never more expensive than further
        assertTrue(goal.heuristic(112, 64, 100) > goal.heuristic(111, 64, 100));
    }
}
