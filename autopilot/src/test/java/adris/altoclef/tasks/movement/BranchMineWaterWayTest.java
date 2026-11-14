package adris.altoclef.tasks.movement;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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

// (Tuning mine_water_streak) the staircase's and the tunnel's step: a way into nothing, then into the open, then into water,
// and never beside the water cell walked out of while another way is left
public class BranchMineWaterWayTest {

    private static BlockState stone, air, water;
    private static final BlockPos FEET = new BlockPos(0, 23, 0);

    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        stone = Blocks.STONE.defaultBlockState();
        air = Blocks.AIR.defaultBlockState();
        water = Blocks.WATER.defaultBlockState();
    }

    // stone everywhere but the cells given
    private static Function<BlockPos, BlockState> world(Map<BlockPos, BlockState> cells) {
        return p -> cells.getOrDefault(p, stone);
    }

    private static Map<BlockPos, BlockState> cells(Object... posAndState) {
        Map<BlockPos, BlockState> m = new HashMap<>();
        for (int i = 0; i < posAndState.length; i += 2) m.put((BlockPos) posAndState[i], (BlockState) posAndState[i + 1]);
        return m;
    }

    @Test
    public void onByDefault() {
        assertTrue(BranchMineTask.waterStreakOn());
    }

    @Test
    public void howAStepDownEastOpens() {
        // solid rock: closed
        assertEquals(BranchMineTask.WAY_CLOSED, BranchMineTask.wayOpening(world(cells()), FEET, 1, 0, -1));
        // a cave beside the step's head: open
        assertEquals(BranchMineTask.WAY_OPEN, BranchMineTask.wayOpening(world(cells(new BlockPos(2, 23, 0), air)), FEET, 1, 0, -1));
        // water beside the step: wet
        assertEquals(BranchMineTask.WAY_WET, BranchMineTask.wayOpening(world(cells(new BlockPos(2, 22, 0), water)), FEET, 1, 0, -1));
        // water under the step, or on top of the cells dug: wet
        assertEquals(BranchMineTask.WAY_WET, BranchMineTask.wayOpening(world(cells(new BlockPos(1, 21, 0), water)), FEET, 1, 0, -1));
        assertEquals(BranchMineTask.WAY_WET, BranchMineTask.wayOpening(world(cells(new BlockPos(1, 25, 0), water)), FEET, 1, 0, -1));
        // water and a cave: wet
        assertEquals(BranchMineTask.WAY_WET, BranchMineTask.wayOpening(world(cells(new BlockPos(2, 23, 0), air,
                new BlockPos(1, 22, 1), water)), FEET, 1, 0, -1));
    }

    @Test
    public void aStepInTheWaterIsWet() {
        // the step's own cell is the pool's, rock round it: the old test saw nothing there
        assertEquals(BranchMineTask.WAY_WET, BranchMineTask.wayOpening(world(cells(new BlockPos(1, 22, 0), water)), FEET, 1, 0, -1));
        assertEquals(BranchMineTask.WAY_WET, BranchMineTask.wayOpening(world(cells(new BlockPos(1, 23, 0), water)), FEET, 1, 0, -1));
        // the level step and the step up too
        assertEquals(BranchMineTask.WAY_WET, BranchMineTask.wayOpening(world(cells(new BlockPos(1, 23, 0), water)), FEET, 1, 0, 0));
        assertEquals(BranchMineTask.WAY_WET, BranchMineTask.wayOpening(world(cells(new BlockPos(1, 25, 0), water)), FEET, 1, 0, 1));
    }

    // the live look's place: a cave round the dry cell (x -3 to 3, feet to two over), a floor of stone, and a pool east of the
    // feet, x 1 to 3 and z -1 to 1, at the feet's height and the one under
    private static Function<BlockPos, BlockState> poolInACave() {
        Map<BlockPos, BlockState> m = new HashMap<>();
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                for (int y = 23; y <= 25; y++) m.put(new BlockPos(x, y, z), air);
            }
        }
        for (int x = 1; x <= 3; x++) {
            for (int z = -1; z <= 1; z++) {
                m.put(new BlockPos(x, 22, z), water);
                m.put(new BlockPos(x, 23, z), water);
            }
        }
        return world(m);
    }

    @Test
    public void theOpenWayBeforeTheWaterOne() {
        // heading east, into the pool: east, south and north open into its water, west only into the cave
        int[] rank = BranchMineTask.wayRanks(poolInACave(), FEET, 1, 0, -1);
        assertArrayEquals(new int[]{BranchMineTask.WAY_WET, BranchMineTask.WAY_WET, BranchMineTask.WAY_OPEN, BranchMineTask.WAY_WET}, rank);
        // two quarters: west. the old rule kept east, every way opening into something
        assertEquals(2, BranchMineTask.pickWay(rank, BranchMineTask.wayNever(FEET, 1, 0, -1, null)));
    }

    @Test
    public void neverBesideTheWaterWalkedOutOf() {
        int[] east = {1, 23, 0};
        // the water cell walked out of just east of the feet: only the step west is not within a block of it
        boolean[] never = BranchMineTask.wayNever(FEET, 1, 0, -1, east);
        assertArrayEquals(new boolean[]{true, true, false, true}, never);
        assertEquals(2, BranchMineTask.pickWay(new int[]{1, 1, 1, 1}, never));
        // a closed way beside it loses to an open one away from it
        assertEquals(1, BranchMineTask.pickWay(new int[]{0, 1, 1, 1}, new boolean[]{true, false, false, false}));
        // all four beside it: the lowest rank of all, the first of equals
        assertEquals(1, BranchMineTask.pickWay(new int[]{2, 1, 2, 1}, new boolean[]{true, true, true, true}));
        // the water cell two over: the steps north and south are clear of it
        assertArrayEquals(new boolean[]{true, false, false, false}, BranchMineTask.wayNever(FEET, 1, 0, -1, new int[]{2, 22, 0}));
        // no water cell walked out of: none is never
        assertArrayEquals(new boolean[4], BranchMineTask.wayNever(FEET, 1, 0, -1, null));
    }

    @Test
    public void withoutWaterTheOldRuleStands() {
        // the first way that opens into nothing, from the one it goes
        assertEquals(0, BranchMineTask.pickWay(new int[]{0, 1, 0, 1}, new boolean[4]));
        assertEquals(1, BranchMineTask.pickWay(new int[]{1, 0, 1, 0}, new boolean[4]));
        // all open into a cave: it keeps its way
        assertEquals(0, BranchMineTask.pickWay(new int[]{1, 1, 1, 1}, new boolean[4]));
    }
}
