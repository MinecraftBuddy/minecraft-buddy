package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.WitherGapRules.Cell;
import adris.altoclef.util.helpers.WitherGapRules.Facts;
import adris.altoclef.util.helpers.WitherGapRules.Kind;
import adris.altoclef.util.helpers.WitherGapRules.Plan;
import adris.altoclef.util.helpers.WitherGapRules.Search;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

// Tuning wither_gap: the cell a wither skeleton (2.4 tall, its reach its box widened 0.83 sideways) cannot hit the buddy in,
// and the plan that finds, digs or roofs one, over a made-up block grid; the start and the leave
public class WitherGapRulesTest {

    // netherrack everywhere (or whatever rest is), with what a test carves out
    private static final class World implements WitherGapRules.Grid {
        private final Map<Long, Cell> cells = new HashMap<>();
        private final Cell rest;

        World(Cell rest) {
            this.rest = rest;
        }

        @Override
        public Cell at(int x, int y, int z) {
            return cells.getOrDefault(WitherGapRules.key(x, y, z), rest);
        }

        World set(int x, int y, int z, Cell c) {
            cells.put(WitherGapRules.key(x, y, z), c);
            return this;
        }

        World box(int x0, int y0, int z0, int x1, int y1, int z1, Cell c) {
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) set(x, y, z, c);
                }
            }
            return this;
        }
    }

    // a room 3 high (feet at 64) from x -6 to -1 and z -3 to 3, its east wall at x 0
    private static World room() {
        return new World(Cell.DIG).box(-6, 64, -3, -1, 66, 3, Cell.OPEN);
    }

    // the room with a tunnel 1 wide and 2 high dug 2 deep east into its wall at z 0: mouth (0, 64, 0), back (1, 64, 0)
    private static World tunnel() {
        return room().box(0, 64, 0, 1, 65, 0, Cell.OPEN);
    }

    // ---- the cell

    @Test
    public void theBackOfATunnelTwoDeepIsACell() {
        assertNull(WitherGapRules.cellNo(tunnel(), 1, 64, 0));
    }

    @Test
    public void theMouthIsNot() {
        // one standing in the room against the mouth reaches 0.83 past its own box, into the mouth's column
        assertEquals("open beside", WitherGapRules.cellNo(tunnel(), 0, 64, 0));
    }

    @Test
    public void threeHighIsNoCell() {
        assertEquals("no ceiling at 2", WitherGapRules.cellNo(tunnel().set(1, 66, 0, Cell.OPEN), 1, 64, 0));
    }

    @Test
    public void aTwoHighSidePassageStaysShut() {
        // a 2.4 tall box does not fit in a column 2 high on a floor
        World w = tunnel().box(1, 64, 1, 1, 65, 1, Cell.OPEN);
        assertNull(WitherGapRules.cellNo(w, 1, 64, 0));
        // with a hole under it one stands in it a block down, its head over the buddy's feet
        assertEquals("open beside", WitherGapRules.cellNo(w.set(1, 63, 1, Cell.OPEN), 1, 64, 0));
    }

    @Test
    public void aSlabIsNoWallToAMob() {
        // (a partial block does not count as shutting a column: one may fit over or under it)
        assertEquals("open beside", WitherGapRules.cellNo(tunnel().set(1, 66, 1, Cell.OPEN).box(1, 64, 1, 1, 65, 1, Cell.OPEN)
                .set(1, 66, 1, Cell.PARTIAL), 1, 64, 0));
    }

    @Test
    public void lavaByItIsNoCell() {
        assertEquals("lava by it", WitherGapRules.cellNo(tunnel().set(2, 64, 1, Cell.LAVA), 1, 64, 0));
    }

    @Test
    public void noFloorNoCell() {
        assertEquals("no floor", WitherGapRules.cellNo(tunnel().set(1, 63, 0, Cell.PARTIAL), 1, 64, 0));
    }

    // ---- the tunnel to dig

    @Test
    public void aTunnelIsDugTwoDeepFromTheStand() {
        Object t = WitherGapRules.tunnel(room(), -1, 64, 0, 1, 0, 1);
        assertTrue(String.valueOf(t), t instanceof Plan);
        Plan p = (Plan) t;
        assertEquals(Kind.DUG, p.kind());
        assertEquals("1 64 0", p.at());
        assertEquals(4, p.blocks());
        assertEquals(-1, p.standX());
    }

    @Test
    public void noTunnelWithLavaBehindTheWall() {
        assertEquals("a fluid behind the wall", WitherGapRules.tunnel(room().set(2, 64, 0, Cell.LAVA), -1, 64, 0, 1, 0, 1));
        assertEquals("a fluid behind the wall", WitherGapRules.tunnel(room().set(0, 66, 1, Cell.OPEN).set(0, 65, 1, Cell.LAVA), -1, 64, 0, 1, 0, 1));
    }

    @Test
    public void noTunnelThroughAThinWall() {
        // a cave behind: the back's column is open to one standing there
        assertEquals("the wall too thin", WitherGapRules.tunnel(room().box(2, 64, -1, 4, 66, 1, Cell.OPEN), -1, 64, 0, 1, 0, 1));
    }

    @Test
    public void noTunnelUnderGravelOrOverADrop() {
        assertEquals("no lid", WitherGapRules.tunnel(room().set(0, 66, 0, Cell.FALLS), -1, 64, 0, 1, 0, 1));
        assertEquals("no floor", WitherGapRules.tunnel(room().set(0, 63, 0, Cell.OPEN), -1, 64, 0, 1, 0, 1));
        assertEquals("not a quick dig", WitherGapRules.tunnel(room().set(1, 64, 0, Cell.HARD), -1, 64, 0, 1, 0, 1));
    }

    // ---- the search

    @Test
    public void aCellThereIsIsTakenBeforeOneDug() {
        Search s = WitherGapRules.search(tunnel(), -1.5, 64, 0.5, List.of(), 8, 3, 3, true, 64);
        assertNotNull(s.no(), s.plan());
        assertEquals(Kind.FOUND, s.plan().kind());
        assertEquals("1 64 0", s.plan().at());
        assertEquals(0, s.plan().blocks());
    }

    @Test
    public void withNoneTheNearestWallIsDug() {
        Search s = WitherGapRules.search(room(), -1.5, 64, 0.5, List.of(), 8, 3, 3, true, 0);
        assertNotNull(s.no(), s.plan());
        assertEquals(Kind.DUG, s.plan().kind());
        assertEquals("1 64 0", s.plan().at());
        assertEquals(4, s.plan().dig().size());
    }

    @Test
    public void noDigWithoutAPickaxe() {
        Search s = WitherGapRules.search(room(), -1.5, 64, 0.5, List.of(), 8, 3, 3, false, 0);
        assertNull(s.plan());
        assertTrue(s.no(), s.no().contains("no pickaxe"));
    }

    @Test
    public void aWitherSkeletonOnTheWayBarsTheCell() {
        // the buddy at the room's west side, a wither skeleton between it and the tunnel
        Search s = WitherGapRules.search(tunnel(), -4.5, 64, 0.5, List.of(new double[]{-2.5, 0.5}), 8, 3, 3, true, 0);
        assertNotNull(s.no(), s.plan());
        assertNotEquals(Kind.FOUND, s.plan().kind());
    }

    @Test
    public void theDeadEndOfANarrowCorridorIsRoofedWithTwoBlocks() {
        // a corridor 1 wide and 3 high, the buddy at its dead end: two blocks over the cell and the next, against four to dig
        World w = new World(Cell.DIG).box(0, 64, 0, 6, 66, 0, Cell.OPEN);
        Search s = WitherGapRules.search(w, 0.5, 64, 0.5, List.of(), 8, 3, 3, true, 16);
        assertNotNull(s.no(), s.plan());
        assertEquals(Kind.ROOFED, s.plan().kind());
        assertEquals("0 64 0", s.plan().at());
        assertEquals(2, s.plan().fill().size());
        // the cell's own roof first, the next one leans on the corridor's side
        assertEquals(66, s.plan().fill().get(0)[1]);
        assertEquals(0, s.plan().fill().get(0)[0]);
        // no blocks in the bag: the dig
        assertEquals(Kind.DUG, WitherGapRules.search(w, 0.5, 64, 0.5, List.of(), 8, 3, 3, true, 0).plan().kind());
    }

    @Test
    public void inTheOpenThereIsNone() {
        // a floor at 63 and nothing else: no 2-high cell, no wall, a roof of nine blocks
        World w = new World(Cell.OPEN).box(-20, 63, -20, 20, 63, 20, Cell.DIG);
        Search s = WitherGapRules.search(w, 0.5, 64, 0.5, List.of(), 8, 3, 3, true, 64);
        assertNull(s.plan());
        assertTrue(s.no(), s.no().startsWith("no 2-high cell"));
    }

    // ---- the start and the leave

    @Test
    public void twoOrThreeAtGoodHealthWithASwordStart() {
        assertNull(WitherGapRules.startNo(2, 2, 0, 20, true));
        assertNull(WitherGapRules.startNo(3, 3, 0, 14, true));
        assertNotNull(WitherGapRules.startNo(1, 1, 0, 20, true));
        assertNotNull(WitherGapRules.startNo(2, 4, 0, 20, true));
        assertNotNull(WitherGapRules.startNo(4, 4, 0, 20, true));
        assertNotNull(WitherGapRules.startNo(2, 2, 1, 20, true));
        assertNotNull(WitherGapRules.startNo(2, 2, 0, 13.9, true));
        assertNotNull(WitherGapRules.startNo(2, 2, 0, 20, false));
    }

    @Test
    public void theLeaveRules() {
        Facts in = new Facts(true, true, null, 2);
        assertNull(WitherGapRules.leave(in, 20, 100, 0));
        assertEquals("hp 7.5 under 8", WitherGapRules.leave(in, 7.5, 100, 0));
        assertFalse(WitherGapRules.failedLeave(in, 7.5, 100));
        assertEquals("90 s in the gap", WitherGapRules.leave(in, 20, WitherGapRules.capTicks(), 0));
        assertTrue(WitherGapRules.failedLeave(in, 20, WitherGapRules.capTicks()));
        assertEquals("no wither skeleton left within 12", WitherGapRules.leave(new Facts(true, true, null, 0), 20, 100, WitherGapRules.QUIET_TICKS));
        assertFalse(WitherGapRules.failedLeave(new Facts(true, true, null, 0), 20, 100));
        Facts out = new Facts(false, false, null, 2);
        assertNull(WitherGapRules.leave(out, 20, WitherGapRules.buildTicks() - 1, 0));
        assertEquals("the cell not reached in 6 s", WitherGapRules.leave(out, 20, WitherGapRules.buildTicks(), 0));
        assertTrue(WitherGapRules.failedLeave(out, 20, WitherGapRules.buildTicks()));
        Facts gaveUp = new Facts(false, true, "no pickaxe to dig with", 2);
        assertEquals("no pickaxe to dig with", WitherGapRules.leave(gaveUp, 20, 5, 0));
        assertTrue(WitherGapRules.failedLeave(gaveUp, 20, 5));
    }
}
