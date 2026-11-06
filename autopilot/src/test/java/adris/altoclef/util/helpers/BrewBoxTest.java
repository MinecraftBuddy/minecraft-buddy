package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import baritone.pathing.movement.CliffAvoidance.Cell;
import baritone.pathing.movement.CliffAvoidance.Cells;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

// the brew box (BrewBox): two cells side by side, walls two high round both, a roof over both, nothing open; the site near the
// buddy, out of the spawner's sight where it can be, clear of lava; the door a wall with a walk out beyond it
public class BrewBoxTest {

    // a flat floor at y 63, open above, walled far out
    static final Cells FLOOR = (x, y, z) -> {
        if (Math.abs(x) > 30 || Math.abs(z) > 30) return Cell.SOLID;
        return y <= 63 ? Cell.SOLID : Cell.OPEN;
    };

    private static final Set<Long> NONE = Set.of();

    private static boolean same(int[] a, int x, int y, int z) {
        return a[0] == x && a[1] == y && a[2] == z;
    }

    // every face of the two cells, but the one between them, is solid once the blocks are in
    private static void assertShut(Cells after, BrewBox.Box box) {
        int[] b = box.back(), f = box.front();
        for (int[] c : new int[][]{b, f}) {
            for (int k = 0; k <= 1; k++) {
                int y = c[1] + k;
                for (int[] s : BrewBox.SIDES) {
                    int nx = c[0] + s[0], nz = c[2] + s[1];
                    if (same(b, nx, c[1], nz) || same(f, nx, c[1], nz)) continue;
                    assertEquals("side " + nx + "," + y + "," + nz, Cell.SOLID, after.at(nx, y, nz));
                }
            }
            assertEquals("roof", Cell.SOLID, after.at(c[0], c[1] + 2, c[2]));
            assertEquals("floor", Cell.SOLID, after.at(c[0], c[1] - 1, c[2]));
        }
        assertTrue(BrewBox.shut(after, box));
    }

    private static String describe(BrewBox.Box box) {
        StringBuilder sb = new StringBuilder("B " + Arrays.toString(box.back()) + " F " + Arrays.toString(box.front()) + " blocks " + box.blocks().size() + ":");
        for (int[] b : box.blocks()) sb.append(' ').append(Arrays.toString(b));
        return sb.toString();
    }

    // a bent pillbox standing on the floor: B (10, 64, 0), F east of it, the look out south of F and open, the other walls two
    // high and the roof over B and F, as BlazePillbox.planBentAt leaves it
    static final Cells PILLBOX = (x, y, z) -> {
        int[][] walls = {{9, 0}, {10, 1}, {10, -1}, {12, 0}, {11, -1}};
        for (int[] w : walls) if (x == w[0] && z == w[1] && (y == 64 || y == 65)) return Cell.SOLID;
        if (y == 66 && z == 0 && (x == 9 || x == 10 || x == 11)) return Cell.SOLID;
        return FLOOR.at(x, y, z);
    };

    @Test
    public void aStandingPillboxShutsWithItsLookOutAlone() {
        BrewBox.Box box = BrewBox.planAt(PILLBOX, 10, 64, 0, new int[]{1, 0}, Double.NaN, Double.NaN, null);
        assertNotNull(box);
        assertEquals(describe(box), 2, box.blocks().size());
        assertTrue(describe(box), same(box.blocks().get(0), 11, 64, 1) && same(box.blocks().get(1), 11, 65, 1));
        assertShut(BlazePillbox.after(PILLBOX, box.blocks()), box);
    }

    // a one-cell pillbox: B (10, 64, 0) walled west, north and south two high with a roof, the look out east of it open
    static final Cells ONE_CELL = (x, y, z) -> {
        int[][] walls = {{9, 0}, {10, 1}, {10, -1}};
        for (int[] w : walls) if (x == w[0] && z == w[1] && (y == 64 || y == 65)) return Cell.SOLID;
        if (y == 66 && z == 0 && (x == 9 || x == 10)) return Cell.SOLID;
        return FLOOR.at(x, y, z);
    };

    @Test
    public void aOneCellPillboxShutsWithItsLookOutWalledRound() {
        BrewBox.Box box = BrewBox.planAt(ONE_CELL, 10, 64, 0, new int[]{1, 0}, Double.NaN, Double.NaN, null);
        assertNotNull(box);
        // the look out's three open sides two high, and its roof
        assertEquals(describe(box), 7, box.blocks().size());
        assertShut(BlazePillbox.after(ONE_CELL, box.blocks()), box);
    }

    @Test
    public void anOpenFloorGetsTheWholeBox() {
        BrewBox.Box box = BrewBox.plan(FLOOR, new int[]{10, 64, 0}, List.of(), Double.NaN, Double.NaN, NONE);
        assertNotNull(box);
        System.out.println("open floor: " + describe(box));
        int[] b = box.back(), f = box.front();
        assertEquals(1, Math.abs(f[0] - b[0]) + Math.abs(f[2] - b[2]));
        assertEquals(b[1], f[1]);
        // (six walls of two, the roof's support on B's back wall and the roof over both)
        assertEquals(15, box.blocks().size());
        Cells after = BlazePillbox.after(FLOOR, box.blocks());
        assertShut(after, box);
        // (no block in the room itself)
        for (int[] p : box.blocks()) {
            for (int k = 0; k <= 1; k++) {
                assertFalse(same(p, b[0], b[1] + k, b[2]) || same(p, f[0], f[1] + k, f[2]));
            }
        }
        // (the low walls first, then the high ones, the roof row last: each is clicked against the one before)
        int lastLow = -1, firstHigh = Integer.MAX_VALUE, lastHigh = -1, firstRoof = Integer.MAX_VALUE;
        for (int i = 0; i < box.blocks().size(); i++) {
            int y = box.blocks().get(i)[1];
            if (y == b[1]) lastLow = i;
            if (y == b[1] + 1) {
                firstHigh = Math.min(firstHigh, i);
                lastHigh = i;
            }
            if (y == b[1] + 2) firstRoof = Math.min(firstRoof, i);
        }
        assertTrue(lastLow < firstHigh && lastHigh < firstRoof);
        // (F's roof last: it goes against B's)
        int[] last = box.blocks().get(box.blocks().size() - 1);
        assertTrue(same(last, f[0], f[1] + 2, f[2]));
        // (no fireball's way in from a ring of eyes all round, to either cell)
        assertTrue(BlazePillbox.ringHidden(after, b[0], b[1], b[2]));
        assertTrue(BlazePillbox.ringHidden(after, f[0], f[1], f[2]));
    }

    // a corridor one wide and two high: its sides and ceiling are the box's, only the two ends are placed
    @Test
    public void aCorridorTakesTwoEnds() {
        Cells corridor = (x, y, z) -> {
            if (z == 0 && (y == 64 || y == 65) && Math.abs(x) <= 20) return Cell.OPEN;
            return Cell.SOLID;
        };
        BrewBox.Box box = BrewBox.plan(corridor, new int[]{0, 64, 0}, List.of(), Double.NaN, Double.NaN, NONE);
        assertNotNull(box);
        System.out.println("corridor: " + describe(box));
        assertEquals(4, box.blocks().size());
        assertEquals(0, box.dir()[1]);
        assertShut(BlazePillbox.after(corridor, box.blocks()), box);
    }

    // lava within LAVA_CLEAR of the walls: no box there, and the one picked has none by it
    @Test
    public void noBoxBesideLava() {
        Cells pool = (x, y, z) -> {
            if (x >= 13 && x <= 15 && Math.abs(z) <= 1 && y == 63) return Cell.LAVA;
            return FLOOR.at(x, y, z);
        };
        // (F at 11: its far wall at 12, the lava one past it)
        assertNull(BrewBox.planAt(pool, 10, 64, 0, new int[]{1, 0}, Double.NaN, Double.NaN, NONE));
        BrewBox.Box box = BrewBox.plan(pool, new int[]{10, 64, 0}, List.of(), Double.NaN, Double.NaN, NONE);
        assertNotNull(box);
        assertFalse(BrewBox.lavaBy(pool, box.back()[0], box.back()[1], box.back()[2], box.front()[0], box.front()[2]));
    }

    // a wall between the buddy and the spawner with a door in it: the box goes up behind the wall, out of the spawn area's sight,
    // against the wall, which saves its blocks; never in the spawn area itself
    @Test
    public void theBoxGoesOutOfTheSpawnersSight() {
        Cells room = (x, y, z) -> {
            if (Math.abs(x) > 30 || Math.abs(z) > 30) return Cell.SOLID;
            if (x == 0 && z == 0 && y == 64) return Cell.SOLID;
            if (x == 8 && Math.abs(z) <= 6 && y >= 64 && y <= 67) return z == 0 && y <= 65 ? Cell.OPEN : Cell.SOLID;
            return y <= 63 ? Cell.SOLID : Cell.OPEN;
        };
        List<Vec3> eyes = BlazeCover.spawnEyes(0, 64, 0).stream()
                .filter(e -> room.at((int) Math.floor(e.x), (int) Math.floor(e.y), (int) Math.floor(e.z)) == Cell.OPEN).toList();
        BrewBox.Box box = BrewBox.plan(room, new int[]{6, 64, 3}, eyes, 0, 0, NONE);
        assertNotNull(box);
        System.out.println("behind the wall: " + describe(box) + " seen " + BlazePillbox.buildSeen(room, box.build(), eyes));
        assertTrue(box.back()[0] >= 9 && box.front()[0] >= 9);
        assertEquals(0.0, BlazePillbox.buildSeen(room, box.build(), eyes), 1e-9);
        // (the spawn area is no site: a blaze could spawn in the box)
        assertNull(BrewBox.planAt(room, 2, 64, 2, new int[]{1, 0}, 0, 0, NONE));
    }

    // no floor anywhere near: no box (the open brew)
    @Test
    public void noFloorNoBox() {
        Cells air = (x, y, z) -> Cell.OPEN;
        assertNull(BrewBox.plan(air, new int[]{0, 64, 0}, List.of(), Double.NaN, Double.NaN, NONE));
        // (a floor with a hole under one wall's cell: that wall cannot go in, so not there)
        Cells holed = (x, y, z) -> x == 12 && z == 0 && y == 63 ? Cell.OPEN : FLOOR.at(x, y, z);
        assertNull(BrewBox.planAt(holed, 10, 64, 0, new int[]{1, 0}, Double.NaN, Double.NaN, NONE));
    }

    // the cells of a pillbox under way are kept clear
    @Test
    public void thePillboxsCellsAreKeptClear() {
        BrewBox.Box first = BrewBox.plan(FLOOR, new int[]{10, 64, 0}, List.of(), Double.NaN, Double.NaN, NONE);
        assertNotNull(first);
        Set<Long> avoid = new HashSet<>();
        avoid.add(BlazeCover.key(first.back()[0], first.back()[1], first.back()[2]));
        BrewBox.Box other = BrewBox.plan(FLOOR, new int[]{10, 64, 0}, List.of(), Double.NaN, Double.NaN, avoid);
        assertNotNull(other);
        for (int[] c : new int[][]{other.back(), other.front()}) assertFalse(same(c, first.back()[0], first.back()[1], first.back()[2]));
        for (int[] s : other.shell()) assertFalse(same(s, first.back()[0], first.back()[1], first.back()[2]));
    }

    // the fight sits out only with all of it holding
    @Test
    public void holdsOnlyShutInAndWell() {
        assertTrue(BrewBox.holds(true, true, false, false, 20));
        assertTrue(BrewBox.holds(true, true, false, false, BrewBox.LOW_HP));
        assertFalse(BrewBox.holds(false, true, false, false, 20));
        assertFalse(BrewBox.holds(true, false, false, false, 20));
        assertFalse(BrewBox.holds(true, true, true, false, 20));
        assertFalse(BrewBox.holds(true, true, false, true, 20));
        assertFalse(BrewBox.holds(true, true, false, false, BrewBox.LOW_HP - 0.5f));
    }

    // the door: one of B's walls, two high, with a walk out beyond it, the one nearest the spawner; every wall of B listed after
    @Test
    public void theDoorIsBsWallTowardTheSpawner() {
        BrewBox.Box box = BrewBox.plan(FLOOR, new int[]{10, 64, 0}, List.of(), 0, 0, NONE);
        assertNotNull(box);
        Cells after = BlazePillbox.after(FLOOR, box.blocks());
        List<BrewBox.Door> doors = BrewBox.doors(after, after, box, placedOf(box), false, 0, 0);
        System.out.println("door: " + describe(box) + " door " + Arrays.toString(doors.get(0).cells().get(0)));
        assertEquals(3, doors.size());
        BrewBox.Door door = doors.get(0);
        assertTrue(door.out());
        assertEquals(2, door.cells().size());
        int[] low = door.cells().get(0), high = door.cells().get(1);
        int[] b = box.back(), f = box.front();
        assertEquals(b[1], low[1]);
        assertEquals(b[1] + 1, high[1]);
        assertEquals(1, Math.abs(low[0] - b[0]) + Math.abs(low[2] - b[2]));
        assertFalse(same(low, f[0], f[1], f[2]));
        // (of B's three walls, the one whose outside is nearest the spawner)
        double best = Double.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            int[] w = box.walls().get(i);
            best = Math.min(best, Math.hypot(w[0] + w[2] + 0.5 - 0.5, w[1] + w[3] + 0.5 - 0.5));
        }
        int dx = low[0] - b[0], dz = low[2] - b[2];
        assertEquals(best, Math.hypot(low[0] + dx, low[2] + dz), 1e-9);
        assertTrue(same(door.beyond(), low[0] + dx, b[1], low[2] + dz));
    }

    // B at (10, 64, 0), F at (11, 64, 0) on the open floor: B's back wall at x 9 faces the spawner at the origin
    private static BrewBox.Box eastBox() {
        BrewBox.Box box = BrewBox.planAt(FLOOR, 10, 64, 0, new int[]{1, 0}, Double.NaN, Double.NaN, NONE);
        assertNotNull(box);
        return box;
    }

    private static Set<Long> placedOf(BrewBox.Box box) {
        Set<Long> placed = new HashSet<>();
        for (int[] b : box.blocks()) placed.add(BlazeCover.key(b[0], b[1], b[2]));
        return placed;
    }

    private static boolean door(BrewBox.Door d, int x, int z) {
        return d.cells().get(0)[0] == x && d.cells().get(0)[2] == z;
    }

    // a drop beyond the wall toward the spawner: that wall is no door (the walk out would go over the edge), a side wall is
    @Test
    public void aWallWithADropIsNotTheDoor() {
        BrewBox.Box box = eastBox();
        Cells pit = (x, y, z) -> x >= 5 && x <= 8 && Math.abs(z) <= 3 && y >= 55 && y <= 63 ? Cell.OPEN : FLOOR.at(x, y, z);
        Cells after = BlazePillbox.after(pit, box.blocks());
        List<BrewBox.Door> doors = BrewBox.doors(after, after, box, placedOf(box), false, 0, 0);
        assertTrue(doors.get(0).out());
        assertFalse(door(doors.get(0), 9, 0));
        assertTrue(door(doors.get(0), 10, 1) || door(doors.get(0), 10, -1));
        // (the wall over the drop last, with no walk out)
        BrewBox.Door last = doors.get(doors.size() - 1);
        assertTrue(door(last, 9, 0));
        assertFalse(last.out());
        assertNull(last.beyond());
    }

    // a fortress wall beyond the wall toward the spawner, or a nook one cell deep: neither is the door
    @Test
    public void aWallOrANookBeyondIsNotTheDoor() {
        BrewBox.Box box = eastBox();
        Cells wall = (x, y, z) -> x <= 8 && y >= 64 && y <= 67 ? Cell.SOLID : FLOOR.at(x, y, z);
        Cells after = BlazePillbox.after(wall, box.blocks());
        List<BrewBox.Door> doors = BrewBox.doors(after, after, box, placedOf(box), false, 0, 0);
        assertTrue(doors.get(0).out());
        assertFalse(door(doors.get(0), 9, 0));
        // (the nook: the cell beyond is floor to stand on, but walled round, so it leads nowhere)
        Cells nook = (x, y, z) -> (x == 7 && z == 0 || x == 8 && Math.abs(z) == 1) && y >= 64 && y <= 67 ? Cell.SOLID : FLOOR.at(x, y, z);
        Cells nookAfter = BlazePillbox.after(nook, box.blocks());
        List<BrewBox.Door> nookDoors = BrewBox.doors(nookAfter, nookAfter, box, placedOf(box), false, 0, 0);
        assertFalse(door(nookDoors.get(0), 9, 0));
        BrewBox.Door back = nookDoors.stream().filter(d -> door(d, 9, 0)).findFirst().orElseThrow();
        assertNotNull(back.beyond());
        assertFalse(back.out());
    }

    // B walled in all round but F's front open to a hall: with a stand in F no wall of B has a way out (the first is still a
    // door to try), with F empty its front wall is the door
    @Test
    public void withFEmptyItsWallsCount() {
        BrewBox.Box box = eastBox();
        Cells closed = (x, y, z) -> {
            if (y <= 63) return Cell.SOLID;
            if (y >= 67 || x >= 13) return Cell.OPEN;
            if (z == 0 && (x == 10 || x == 11)) return Cell.OPEN;
            return Cell.SOLID;
        };
        Cells after = BlazePillbox.after(closed, box.blocks());
        List<BrewBox.Door> withStand = BrewBox.doors(after, after, box, placedOf(box), false, 0, 0);
        assertEquals(3, withStand.size());
        for (BrewBox.Door d : withStand) assertFalse(d.out());
        List<BrewBox.Door> empty = BrewBox.doors(after, after, box, placedOf(box), true, 0, 0);
        assertEquals(6, empty.size());
        assertTrue(empty.get(0).out());
        assertTrue(door(empty.get(0), 12, 0));
        assertTrue(same(empty.get(0).beyond(), 13, 64, 0));
        for (int i = 1; i < empty.size(); i++) assertFalse(empty.get(i).out());
    }

    @Test
    public void offByDefault() {
        assertFalse(BrewBox.on(0));
        assertFalse(BrewBox.on(0.5));
        assertTrue(BrewBox.on(1));
    }
}
