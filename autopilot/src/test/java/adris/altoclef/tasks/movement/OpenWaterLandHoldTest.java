package adris.altoclef.tasks.movement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.MineLeave;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.BeforeClass;
import org.junit.Test;

// (Tuning open_water_land_hold) the test heel's flooded start: the leave from the flooded cave ended on a ledge at the sea's
// height, and at sea by a one-block islet the walk to it and the staircase's step back into the sea took turns every tick
public class OpenWaterLandHoldTest {
    private static BlockState stone, air, water;
    // the sea's top water at y 62, its floor at 55
    private static final int TOP = 62, FLOOR = 55;

    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        stone = Blocks.STONE.defaultBlockState();
        air = Blocks.AIR.defaultBlockState();
        water = Blocks.WATER.defaultBlockState();
    }

    // the sea: water from the floor to TOP, air over it, stone under it, and the cells given on top of that
    private static Function<BlockPos, BlockState> sea(Map<BlockPos, BlockState> cells) {
        return p -> {
            BlockState c = cells.get(p);
            if (c != null) return c;
            if (p.getY() > TOP) return air;
            return p.getY() > FLOOR ? water : stone;
        };
    }

    // a column of stone from the floor up to and with y
    private static void pillar(Map<BlockPos, BlockState> m, int x, int y, int z) {
        for (int h = FLOOR + 1; h <= y; h++) m.put(new BlockPos(x, h, z), stone);
    }

    @Test
    public void onByDefault() {
        assertTrue(BranchMineTask.landHoldOn());
    }

    @Test
    public void theLedgeAtTheSeasHeightIsNoDryGround() {
        // the leave's end: a stone at y 61, the feet at 62 beside the sea's top water, not in it, on the ground for a tick
        Map<BlockPos, BlockState> m = new HashMap<>();
        pillar(m, 0, 61, 0);
        m.put(new BlockPos(0, 62, 0), air);
        BlockPos feet = new BlockPos(0, 62, 0);
        Function<BlockPos, BlockState> at = sea(m);
        // a cell to stand in by the old rule (and the old leave's onGround and not in the water)
        assertTrue(BranchMineTask.standable(at.apply(feet.below()), at.apply(feet), at.apply(feet.above())));
        assertFalse(BranchMineTask.dryGround(at, feet));
    }

    @Test
    public void aOneBlockIsletIsNoDryGround() {
        // a stone at the water's top: the cells beside the feet are air, the sea beside the floor, where a step down goes
        Map<BlockPos, BlockState> m = new HashMap<>();
        pillar(m, 0, TOP, 0);
        BlockPos feet = new BlockPos(0, TOP + 1, 0);
        Function<BlockPos, BlockState> at = sea(m);
        assertTrue(BranchMineTask.standable(at.apply(feet.below()), at.apply(feet), at.apply(feet.above())));
        assertFalse(BranchMineTask.dryGround(at, feet));
    }

    @Test
    public void aBeachIsDryGroundButNotItsEdge() {
        // a shore of stone at the water's top, x -6 to 2, the sea east of it
        Map<BlockPos, BlockState> m = new HashMap<>();
        for (int x = -6; x <= 2; x++) {
            for (int z = -6; z <= 6; z++) pillar(m, x, TOP, z);
        }
        Function<BlockPos, BlockState> at = sea(m);
        assertTrue(BranchMineTask.dryGround(at, new BlockPos(0, TOP + 1, 0)));
        assertTrue(BranchMineTask.dryGround(at, new BlockPos(1, TOP + 1, 0)));
        // the edge: the sea beside its floor
        assertFalse(BranchMineTask.dryGround(at, new BlockPos(2, TOP + 1, 0)));
    }

    @Test
    public void noRoomOrWaterInTheCellIsNot() {
        Map<BlockPos, BlockState> m = new HashMap<>();
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) pillar(m, x, TOP, z);
        }
        m.put(new BlockPos(0, TOP + 2, 0), stone);
        m.put(new BlockPos(2, TOP + 1, 0), water);
        Function<BlockPos, BlockState> at = sea(m);
        // the head in rock
        assertFalse(BranchMineTask.dryGround(at, new BlockPos(0, TOP + 1, 0)));
        // water in the cell, and beside it
        assertFalse(BranchMineTask.dryGround(at, new BlockPos(2, TOP + 1, 0)));
        assertFalse(BranchMineTask.dryGround(at, new BlockPos(1, TOP + 1, 0)));
        // in the sea itself
        assertFalse(BranchMineTask.dryGround(at, new BlockPos(5, TOP, 0)));
    }

    @Test
    public void aTickOnTheIsletKeepsTheWalk() {
        // in open water: on to the land
        assertEquals(BranchMineTask.LAND_WALK, BranchMineTask.landStep(true, true, false, false));
        // out of the water on the islet, a tick later: no dry ground, the walk goes on and no staircase starts
        assertEquals(BranchMineTask.LAND_WALK, BranchMineTask.landStep(false, true, true, false));
        // still on the islet ten seconds on: the walk is held until dry ground
        assertEquals(BranchMineTask.LAND_WALK, BranchMineTask.landStep(false, false, true, false));
    }

    @Test
    public void onDryGroundTheStaircaseWaitsTenSeconds() {
        // on dry ground, out of the water less than LAND_HOLD ago: stand
        assertEquals(BranchMineTask.LAND_STAND, BranchMineTask.landStep(false, true, true, true));
        assertEquals(BranchMineTask.LAND_STAND, BranchMineTask.landStep(false, true, false, true));
        // and after it: the walk is over, the mine goes on
        assertEquals(BranchMineTask.LAND_OFF, BranchMineTask.landStep(false, false, true, true));
        // never in open water: nothing
        assertEquals(BranchMineTask.LAND_OFF, BranchMineTask.landStep(false, false, false, false));
        assertEquals(BranchMineTask.LAND_OFF, BranchMineTask.landStep(false, false, false, true));
    }

    @Test
    public void walkOutsOfTheSeaByTheIsletFire() {
        // the walk-outs of open water count as those under a roof: off the islet at 35959, 63, -8352 and back in, every 3 s
        MineLeave.WalkOuts w = new MineLeave.WalkOuts(3, 10, 2400);
        int[] islet = {35960, 63, -8352};
        assertFalse(w.start("OVERWORLD", 35959, 62, -8352, islet, 1000));
        assertFalse(w.start("OVERWORLD", 35960, 62, -8351, islet, 1060));
        assertTrue(w.start("OVERWORLD", 35959, 62, -8353, islet, 1120));
    }
}
