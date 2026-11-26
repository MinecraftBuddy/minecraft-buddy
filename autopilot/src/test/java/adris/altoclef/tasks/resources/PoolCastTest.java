package adris.altoclef.tasks.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.pathing.movement.CliffAvoidance.Cell;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import org.junit.BeforeClass;
import org.junit.Test;

// the cast cell by a lava pool (pool_cast): PoolCast's cell test, its pick, and the pin's fallbacks. P is the cast's lava cell,
// the stand S one west and one up (x - 1, y + 1), the frame's closed side east
public class PoolCastTest {

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    // stone everywhere but the cells set here
    private static class World implements PoolCast.Grid {
        private final Map<String, Cell> cells = new HashMap<>();

        World set(int x, int y, int z, Cell c) {
            cells.put(x + "," + y + "," + z, c);
            return this;
        }

        World box(int x0, int y0, int z0, int x1, int y1, int z1, Cell c) {
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        set(x, y, z, c);
                    }
                }
            }
            return this;
        }

        @Override
        public Cell at(int x, int y, int z) {
            return cells.getOrDefault(x + "," + y + "," + z, Cell.SOLID);
        }
    }

    // a lava pool at y 0, x 10 to 14, z -3 to 3, in a cave four high; rock all round
    private static World rim() {
        return new World().box(10, 0, -3, 14, 0, 3, Cell.LAVA).box(10, 1, -3, 14, 4, 3, Cell.OPEN);
    }

    // the same pool at x -14 to -10
    private static World mirrored() {
        return new World().box(-14, 0, -3, -10, 0, 3, Cell.LAVA).box(-14, 1, -3, -10, 4, 3, Cell.OPEN);
    }

    @Test
    public void aRockRimWithThePoolEastPasses() {
        // four west of the pool, its floor one over the lava's top: the frame is the rock, the stand in rock
        assertNull(PoolCast.refuse(rim(), 6, 2, 0));
        // three off is enough: the pool one nearer still passes
        assertNull(PoolCast.refuse(rim().box(9, 0, -3, 9, 0, 3, Cell.LAVA), 6, 2, 0));
        assertEquals(0, PoolCast.frameMissing(rim(), 6, 2, 0));
    }

    @Test
    public void lavaWithinThreeSidewaysRefuses() {
        // two east of the cell, behind the frame's wall
        assertNotNull(PoolCast.refuse(rim().set(8, 2, 0, Cell.LAVA), 6, 2, 0));
        // two north of the stand, at its feet
        assertNotNull(PoolCast.refuse(rim().set(5, 3, 2, Cell.LAVA), 6, 2, 0));
        // under the floor two off
        String why = PoolCast.refuse(rim().set(7, 0, 2, Cell.LAVA), 6, 2, 0);
        assertNotNull(why);
        assertTrue(why, why.contains("within 3"));
    }

    @Test
    public void aFloorLevelWithTheLavaRefuses() {
        // the cell one over the lava: its floor at the lava's height
        String why = PoolCast.refuse(rim(), 6, 1, 0);
        assertNotNull(why);
        assertTrue(why, why.contains("level"));
        // two over passes, five over has no pool within reach under it
        assertNull(PoolCast.refuse(rim(), 6, 2, 0));
        assertNotNull(PoolCast.refuse(rim(), 6, 5, 0));
    }

    @Test
    public void thePoolOnTheWestSideRefuses() {
        // the mirror of the passing cell: the pool four west of the cell, on the stand's open side
        String why = PoolCast.refuse(mirrored(), -6, 2, 0);
        assertNotNull(why);
        assertTrue(why, why.contains("stand's side"));
        // whatever the pick takes there, its pool lies east of it
        PoolCast.Pick pick = PoolCast.pick(mirrored(), new BlockPos(-10, 0, 0), new BlockPos(-5, 3, 0), p -> false);
        if (pick != null) {
            assertTrue(pick.cell().toShortString(), pick.lava().getX() > pick.cell().getX());
            assertNull(PoolCast.refuse(mirrored(), pick.cell().getX(), pick.cell().getY(), pick.cell().getZ()));
        }
    }

    @Test
    public void aStandOverADeepDropRefuses() {
        // a shaft fourteen deep right west of the stand: a hit from the east throws the buddy down it
        String why = PoolCast.refuse(rim().box(3, -10, -1, 4, 4, 1, Cell.OPEN), 6, 2, 0);
        assertNotNull(why);
        assertTrue(why, why.contains("hit"));
    }

    @Test
    public void wetOrLooseOrBurningCellsRefuse() {
        assertNotNull(PoolCast.refuse(rim().set(6, 3, 0, Cell.WATER), 6, 2, 0));
        World wood = new World() {
            @Override
            public boolean flammable(int x, int y, int z) {
                return x == 7 && y == 2 && z == 0;
            }
        };
        wood.box(10, 0, -3, 14, 0, 3, Cell.LAVA).box(10, 1, -3, 14, 4, 3, Cell.OPEN);
        assertNotNull(PoolCast.refuse(wood, 6, 2, 0));
        World hard = new World() {
            @Override
            public boolean hard(int x, int y, int z) {
                return x == 5 && y == 3 && z == 0;
            }
        };
        hard.box(10, 0, -3, 14, 0, 3, Cell.LAVA).box(10, 1, -3, 14, 4, 3, Cell.OPEN);
        assertNotNull(PoolCast.refuse(hard, 6, 2, 0));
    }

    @Test
    public void thePickPrefersTheNearestPassingCell() {
        PoolCast.Pick at = PoolCast.pick(rim(), new BlockPos(10, 0, 0), new BlockPos(5, 3, 0), p -> false);
        assertNotNull(at);
        assertEquals(new BlockPos(6, 2, 0), at.cell());
        assertEquals(4.0, at.gap(), 1e-9);
        assertEquals(new BlockPos(6, 2, -2), PoolCast.pick(rim(), new BlockPos(10, 0, 0), new BlockPos(5, 3, -2), p -> false).cell());
        // a banned cell is passed over for the next
        PoolCast.Pick next = PoolCast.pick(rim(), new BlockPos(10, 0, 0), new BlockPos(5, 3, 0), p -> p.equals(new BlockPos(6, 2, 0)));
        assertNotNull(next);
        assertFalse(next.cell().equals(new BlockPos(6, 2, 0)));
        // from anywhere: the cheapest of every cell that passes
        BlockPos from = new BlockPos(1, 6, 5);
        PoolCast.Pick pick = PoolCast.pick(rim(), new BlockPos(10, 0, 0), from, p -> false);
        assertNotNull(pick);
        double best = Double.MAX_VALUE;
        for (int y = 2; y <= 4; y++) {
            for (int x = 4; x <= 16; x++) {
                for (int z = -6; z <= 6; z++) {
                    if (PoolCast.refuse(rim(), x, y, z) == null) best = Math.min(best, PoolCast.cost(rim(), x, y, z, from));
                }
            }
        }
        BlockPos c = pick.cell();
        assertEquals(best, PoolCast.cost(rim(), c.getX(), c.getY(), c.getZ(), from), 1e-9);
    }

    @Test
    public void aBuiltFrameCostsMoreThanRock() {
        // flat ground, open above y 0: five frame blocks to place (four walls and the water's wall)
        World flat = new World().box(-8, 1, -8, 8, 6, 8, Cell.OPEN);
        assertEquals(5, PoolCast.frameMissing(flat, 0, 1, 0));
        BlockPos from = new BlockPos(-1, 2, 0);
        assertEquals(5 * PoolCast.FRAME_COST, PoolCast.cost(flat, 0, 1, 0, from), 1e-9);
        assertEquals(0, PoolCast.cost(rim(), 0, 1, 0, from), 1e-9);
    }

    @Test
    public void twoRuinedCellsGiveThePoolUp() {
        PoolCast.Pin pin = new PoolCast.Pin();
        pin.pin(new BlockPos(6, 2, 0), new BlockPos(10, 0, 0), 0);
        pin.ruin(100);
        assertTrue(pin.on());
        assertNull(pin.cell);
        assertTrue(pin.banned.contains(new BlockPos(6, 2, 0)));
        pin.pin(new BlockPos(6, 2, -2), new BlockPos(10, 0, 0), 200);
        pin.ruin(300);
        assertFalse(pin.on());
        assertTrue(pin.gaveUp, pin.gaveUp.contains("2 cells ruined"));
    }

    @Test
    public void aMinuteWithNoCellGivesThePoolUp() {
        PoolCast.Pin pin = new PoolCast.Pin();
        assertTrue(pin.searching(1000));
        assertTrue(pin.searching(1000 + PoolCast.SEARCH_TICKS));
        // a fight of 30 s in between does not count
        pin.paused(600);
        assertTrue(pin.searching(1000 + PoolCast.SEARCH_TICKS + 600));
        assertFalse(pin.searching(1000 + PoolCast.SEARCH_TICKS + 601));
        assertTrue(pin.gaveUp, pin.gaveUp.contains("no cell"));
    }

    @Test
    public void aFightThatCarriesTheBuddyFarPicksANewCell() {
        assertEquals(PoolCast.Step.CAST, PoolCast.step(false, 3));
        assertEquals(PoolCast.Step.WALK, PoolCast.step(false, 40));
        assertEquals(PoolCast.Step.CAST, PoolCast.step(true, 5));
        assertEquals(PoolCast.Step.BACK, PoolCast.step(true, 12));
        assertEquals(PoolCast.Step.REPICK, PoolCast.step(true, 30));
    }

    @Test
    public void aWalkThatComesNoNearerIsRuined() {
        PoolCast.Pin pin = new PoolCast.Pin();
        pin.pin(new BlockPos(6, 2, 0), new BlockPos(10, 0, 0), 0);
        assertFalse(pin.walkStalled(40, 0));
        assertFalse(pin.walkStalled(30, 200));
        // stood at 30 since tick 200
        assertFalse(pin.walkStalled(29.5, 200 + PoolCast.WALK_STALL_TICKS));
        assertTrue(pin.walkStalled(29.5, 201 + PoolCast.WALK_STALL_TICKS));
    }

    @Test
    public void naturalObsidianOnlyNearAndOnAFloor() {
        assertTrue(PoolCast.naturalTaken(15 * 15, true));
        assertFalse(PoolCast.naturalTaken(17 * 17, true));
        assertFalse(PoolCast.naturalTaken(4, false));
    }
}
