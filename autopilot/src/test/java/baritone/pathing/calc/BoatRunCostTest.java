package baritone.pathing.calc;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.pathing.movement.ActionCosts;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.path.BoatLaneTest;
import baritone.pathing.path.BoatTrip;
import net.minecraft.world.phys.Vec3;
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

import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.BetterBlockPos;
import baritone.utils.pathing.Favoring;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// a pond is not priced as a boat ride: BoatTrip wades anything under boatMinWaterLength. same fake world as GodBridgeCostTest,
// and the same skip when there's no shadow BaritoneAPI around
public class BoatRunCostTest {

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
            this(bsi, true);
        }

        Ctx(BlockStateInterface bsi, boolean hasBoat) {
            super(null, true, null, null, bsi, new ToolSet(null) {
                @Override
                public double getStrVsBlock(BlockState state) {
                    float hardness = state.getBlock().defaultDestroyTime();
                    return hardness < 0 ? -1 : hardness == 0 ? 1 : 8.0 / (hardness * 30);
                }
            }, false, false, true, 0, 1.0f, 20.0, hasBoat);
        }
    }

    private Settings settings;
    private boolean potions;
    private boolean boats;
    private int minLength;
    private double boardCost;
    private double exitCost;

    @Before
    public void setUp() {
        ItemFixture.boot();
        try {
            settings = BaritoneAPI.getSettings();
        } catch (Throwable t) {
            Assume.assumeNoException("needs the shadow BaritoneAPI from the bench harness", t);
        }
        potions = settings.considerPotionEffects.value;
        boats = settings.allowBoats.value;
        minLength = settings.boatMinWaterLength.value;
        boardCost = settings.boatBoardCost.value;
        exitCost = settings.boatExitCost.value;
        settings.considerPotionEffects.value = false;
        settings.allowBoats.value = true;
        settings.boatMinWaterLength.value = 8;
    }

    @After
    public void tearDown() {
        if (settings != null) {
            settings.considerPotionEffects.value = potions;
            settings.allowBoats.value = boats;
            settings.boatMinWaterLength.value = minLength;
            settings.boatBoardCost.value = boardCost;
            settings.boatExitCost.value = exitCost;
        }
    }

    // a basin of water with a stone bank all round, floor at 62, water at 63. the cells a boat fits in are the ones with water on
    // all sides, so lx by lz of them inside water that is 2 bigger each way
    private static MapWorld basin(int lx, int lz) {
        MapWorld w = new MapWorld();
        for (int x = -3; x <= lx + 3; x++) {
            for (int z = -3; z <= lz + 3; z++) {
                w.set(x, 62, z, Blocks.STONE.defaultBlockState());
                boolean water = x >= -1 && x <= lx && z >= -1 && z <= lz;
                w.set(x, 63, z, water ? Blocks.WATER.defaultBlockState() : Blocks.STONE.defaultBlockState());
            }
        }
        return w;
    }

    @Test
    public void aShortPondIsWadedNotRowed() {
        Ctx probe = new Ctx(basin(7, 7));
        assertTrue("the boat has to be cheaper or this test says nothing", probe.boatWaterSpeed < probe.waterWalkSpeed);
        // 7 boatable cells in a row, one short of the 8 BoatTrip wants
        assertEquals(probe.waterWalkSpeed, probe.waterCost(3, 63, 3), 0);
    }

    @Test
    public void aLongEnoughPondIsRowed() {
        Ctx probe = new Ctx(basin(8, 8));
        assertEquals(probe.boatWaterSpeed, probe.waterCost(3, 63, 3), 0);
        // every cell of the row, the ends of it too
        assertEquals(probe.boatWaterSpeed, probe.waterCost(0, 63, 0), 0);
        assertEquals(probe.boatWaterSpeed, probe.waterCost(7, 63, 7), 0);
    }

    @Test
    public void aNarrowRiverIsWadedWhicheverWayYouLookAtIt() {
        // 5 wide and 12 long: a crossing is 5 blocks of water and BoatTrip would wade it, the long way alone is not enough
        Ctx river = new Ctx(basin(12, 5));
        assertEquals(river.waterWalkSpeed, river.waterCost(6, 63, 2), 0);
        assertEquals(river.waterWalkSpeed, river.waterCost(6, 63, 0), 0);
        // the same river turned on its side
        Ctx turned = new Ctx(basin(5, 12));
        assertEquals(turned.waterWalkSpeed, turned.waterCost(2, 63, 6), 0);
    }

    @Test
    public void theMinimumIsTheSettingNotAConstant() {
        settings.boatMinWaterLength.value = 4;
        Ctx probe = new Ctx(basin(4, 4));
        assertEquals(probe.boatWaterSpeed, probe.waterCost(2, 63, 2), 0);
        settings.boatMinWaterLength.value = 5;
        probe = new Ctx(basin(4, 4));
        assertEquals(probe.waterWalkSpeed, probe.waterCost(2, 63, 2), 0);
    }

    @Test
    public void aCellABoatDoesNotFitInIsNeverRowed() {
        // the water against the bank of a big lake: the stone beside it means no boat, long rows or not
        Ctx probe = new Ctx(basin(20, 20));
        assertEquals(probe.waterWalkSpeed, probe.waterCost(10, 63, -1), 0);
        assertEquals(probe.boatWaterSpeed, probe.waterCost(10, 63, 10), 0);
    }

    // open ground at feet level 63 with a lake in the middle: water at x 0..length, z -half..half over a stone floor at 62.
    // the shore is just air at water level, which a boat is fine next to
    private static MapWorld lake(int length, int half) {
        MapWorld w = new MapWorld();
        for (int x = -8; x <= length + 8; x++) {
            for (int z = -half - 3; z <= half + 3; z++) {
                w.set(x, 62, z, Blocks.STONE.defaultBlockState());
                if (x >= 0 && x <= length && z >= -half && z <= half) {
                    w.set(x, 63, z, Blocks.WATER.defaultBlockState());
                }
            }
        }
        return w;
    }

    // dry land standing a block proud of the water, with a full block of water all round it (the ring cells are what a boat
    // goes through). its top is feet level 64
    private static void island(MapWorld w, int x0, int x1, int half) {
        for (int x = x0; x <= x1; x++) {
            for (int z = -half; z <= half; z++) {
                w.set(x, 63, z, Blocks.STONE.defaultBlockState());
            }
        }
    }

    private static double traverse(Ctx ctx, int x, int z, int destX) {
        return baritone.pathing.movement.movements.MovementTraverse.cost(ctx, x, 63, z, destX, z);
    }

    private static double diagonal(Ctx ctx, int x, int z, int destX, int destZ) {
        baritone.utils.pathing.MutableMoveResult res = new baritone.utils.pathing.MutableMoveResult();
        baritone.pathing.movement.movements.MovementDiagonal.cost(ctx, x, 63, z, destX, destZ, res);
        return res.cost;
    }

    @Test
    public void steppingOntoARowPaysToGetTheBoatOut() {
        MapWorld w = lake(20, 10);
        Ctx boat = new Ctx(w, true);
        Ctx none = new Ctx(w, false);
        assertEquals(none.waterWalkSpeed, traverse(none, -1, 0, 0), 1e-9);
        assertEquals(boat.boatWaterSpeed + settings.boatBoardCost.value, traverse(boat, -1, 0, 0), 1e-9);
        // and it's a setting, not a constant
        settings.boatBoardCost.value = 7.0;
        assertEquals(boat.boatWaterSpeed + 7.0, traverse(new Ctx(w, true), -1, 0, 0), 1e-9);
    }

    @Test
    public void steppingOffARowPaysToGetTheBoatBack() {
        MapWorld w = lake(20, 10);
        Ctx boat = new Ctx(w, true);
        Ctx none = new Ctx(w, false);
        // the walk onto the bank is the same either way, the difference is the pickup
        assertEquals(settings.boatExitCost.value, traverse(boat, 0, 0, -1) - traverse(none, 0, 0, -1), 1e-9);
        settings.boatExitCost.value = 11.0;
        assertEquals(11.0, traverse(new Ctx(w, true), 0, 0, -1) - traverse(none, 0, 0, -1), 1e-9);
    }

    @Test
    public void stayingInARowIsFree() {
        MapWorld w = lake(20, 10);
        Ctx boat = new Ctx(w, true);
        assertEquals(boat.boatWaterSpeed, traverse(boat, 5, 0, 6), 1e-9);
        assertEquals(boat.boatWaterSpeed * Math.sqrt(2), diagonal(boat, 5, 0, 6, 1), 1e-9);
    }

    @Test
    public void diagonalsPayTheSameBoardAndExit() {
        MapWorld w = lake(20, 10);
        Ctx boat = new Ctx(w, true);
        Ctx none = new Ctx(w, false);
        double sqrt2 = Math.sqrt(2);
        assertEquals((boat.boatWaterSpeed - none.waterWalkSpeed) * sqrt2 + settings.boatBoardCost.value,
                diagonal(boat, -1, -1, 0, 0) - diagonal(none, -1, -1, 0, 0), 1e-9);
        assertEquals(settings.boatExitCost.value, diagonal(boat, 0, 0, -1, -1) - diagonal(none, 0, 0, -1, -1), 1e-9);
    }

    @Test
    public void aPondNobodyRowsHasNoEndsToPayFor() {
        // 7 wide, one short of boatMinWaterLength, so it's priced as a swim and the boat never comes out
        MapWorld w = lake(6, 3);
        Ctx boat = new Ctx(w, true);
        Ctx none = new Ctx(w, false);
        assertEquals(traverse(none, -1, 0, 0), traverse(boat, -1, 0, 0), 1e-9);
        assertEquals(traverse(none, 0, 0, -1), traverse(boat, 0, 0, -1), 1e-9);
    }

    @Test
    public void withNoBoatTheHandoffIsNothing() {
        MapWorld w = lake(20, 10);
        Ctx none = new Ctx(w, false);
        assertEquals(0, none.boatHandoff(-1, 63, 0, 0, 63, 0), 0);
        assertEquals(0, none.boatHandoff(0, 63, 0, -1, 63, 0), 0);
    }

    // the cells of a found path, or null
    private static List<BetterBlockPos> search(MapWorld w, BetterBlockPos start, BetterBlockPos goal, Ctx ctx) {
        AStarPathFinder pf = new AStarPathFinder(start, start.x, start.y, start.z, new GoalBlock(goal), new Favoring(null, ctx), ctx);
        Optional<IPath> p;
        try {
            ctx.claimSearchCaches();
            p = pf.calculate0(10000, 10000);
        } finally {
            ctx.releaseSearchCaches();
        }
        return p.isPresent() && p.get().getDest().equals(goal) ? p.get().positions() : null;
    }

    private static boolean onTheIsland(List<BetterBlockPos> path, int x0, int x1) {
        for (BetterBlockPos p : path) {
            if (p.y == 64 && p.x >= x0 && p.x <= x1) {
                return true;
            }
        }
        return false;
    }

    private static final BetterBlockPos START = new BetterBlockPos(-2, 63, 0);

    @Test
    public void aBoatGoesRoundAnIslandInsteadOfHoppingOutToWalkOverIt() {
        // 5 blocks of island across the way and 15 wide. before the ends cost anything a crossing was the shorter walk
        MapWorld w = lake(30, 20);
        island(w, 12, 16, 7);
        BetterBlockPos goal = new BetterBlockPos(32, 63, 0);

        settings.boatBoardCost.value = 0.0;
        settings.boatExitCost.value = 0.0;
        List<BetterBlockPos> free = search(w, START, goal, new Ctx(w, true));
        assertTrue(free != null);
        assertTrue("with free ends the island is the way", onTheIsland(free, 12, 16));

        settings.boatBoardCost.value = 20.0;
        settings.boatExitCost.value = 30.0;
        Ctx ctx = new Ctx(w, true);
        List<BetterBlockPos> around = search(w, START, goal, ctx);
        assertTrue(around != null);
        assertFalse("hopped out onto the island: " + around, onTheIsland(around, 12, 16));
        for (BetterBlockPos p : around) {
            if (p.x >= 0 && p.x <= 30) {
                assertTrue("left the boat water at " + p, ctx.boatCell(p.x, p.y, p.z));
            }
        }
    }

    // the crossover for a 5 long island sits between a half width of 14 and 16 with the default 20 and 30. these two straddle it
    private static boolean crossesIslandOfHalfWidth(int half) {
        MapWorld w = lake(30, half + 12);
        island(w, 12, 16, half);
        List<BetterBlockPos> path = search(w, START, new BetterBlockPos(32, 63, 0), new Ctx(w, true));
        assertTrue(path != null);
        return onTheIsland(path, 12, 16);
    }

    @Test
    public void aDetourJustUnderTheCostOfTheEndsIsRowed() {
        assertFalse(crossesIslandOfHalfWidth(10));
    }

    @Test
    public void aDetourPastTheCostOfTheEndsIsWalked() {
        assertTrue(crossesIslandOfHalfWidth(20));
        assertTrue(crossesIslandOfHalfWidth(45));
    }

    private static double offChord(Vec3 a, Vec3 b, double x, double z) {
        double dx = b.x - a.x;
        double dz = b.z - a.z;
        return Math.abs(dx * (z - a.z) - dz * (x - a.x)) / Math.sqrt(dx * dx + dz * dz);
    }

    // octile ties: straights and diagonals cost the same as long as the count of each is right, so which order A* lays
    // them in is arbitrary. whatever it picked, the rowed lane across open water is one straight line
    private void tiedPathAcross(int length, int half, BetterBlockPos start, BetterBlockPos goal) {
        MapWorld w = lake(length, half);
        List<BetterBlockPos> path = search(w, start, goal, new Ctx(w, true));
        assertTrue(path != null);
        int from = 0;
        while (path.get(from).x < 0) {
            from++;
        }
        int to = path.size() - 1;
        while (path.get(to).x > length) {
            to--;
        }
        List<Vec3> lane = BoatTrip.smoothLane(w, path, from, to);
        List<Vec3> old = BoatLaneTest.oldLane(path, from, to);
        Vec3 a = lane.get(0);
        Vec3 b = lane.get(lane.size() - 1);
        double nodes = 0;
        for (int i = from; i <= to; i++) {
            nodes = Math.max(nodes, offChord(a, b, path.get(i).x + 0.5, path.get(i).z + 0.5));
        }
        double pulled = 0;
        for (Vec3 p : lane) {
            pulled = Math.max(pulled, offChord(a, b, p.x, p.z));
        }
        double relaxed = 0;
        for (Vec3 p : old) {
            relaxed = Math.max(relaxed, offChord(a, b, p.x, p.z));
        }
        System.out.println("BOATLANE " + length + "x" + (half * 2) + " lake: A* nodes up to " + nodes + " off the straight line, old lane " + relaxed
                + ", pulled lane " + pulled);
        assertTrue("the test path should be crooked, was " + nodes, nodes > 1.0);
        assertTrue("pulled " + pulled, pulled < 1e-6);
        assertTrue("old " + relaxed + " pulled " + pulled, relaxed >= pulled);
    }

    @Test
    public void aTiedPathAcrossOpenWaterRowsAsOneStraightLane() {
        tiedPathAcross(70, 30, new BetterBlockPos(-2, 63, -20), new BetterBlockPos(72, 63, 18));
        tiedPathAcross(100, 40, new BetterBlockPos(-2, 63, -30), new BetterBlockPos(102, 63, 29));
        tiedPathAcross(60, 25, new BetterBlockPos(-2, 63, -10), new BetterBlockPos(62, 63, 12));
    }

    @Test
    public void diveOutFromUnderTheBoatPaysTheExitToo() {
        // two deep: the surface cell is a row, the one under it has water over it so it isn't, and a dive is a way off the row
        MapWorld w = lake(20, 10);
        for (int x = 0; x <= 20; x++) {
            for (int z = -10; z <= 10; z++) {
                w.set(x, 62, z, Blocks.WATER.defaultBlockState());
                w.set(x, 61, z, Blocks.STONE.defaultBlockState());
            }
        }
        Ctx boat = new Ctx(w, true);
        Ctx none = new Ctx(w, false);
        assertTrue(boat.boatCell(5, 63, 0));
        double dive = baritone.pathing.movement.movements.MovementDownward.cost(boat, 5, 63, 0);
        assertEquals(settings.boatExitCost.value, dive - baritone.pathing.movement.movements.MovementDownward.cost(none, 5, 63, 0), 1e-9);
    }
}
