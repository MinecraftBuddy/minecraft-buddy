package adris.altoclef.util.helpers;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import baritone.pathing.movement.CliffAvoidance.Cell;
import baritone.pathing.movement.CliffAvoidance.Cells;
import org.junit.Test;

// the cover from a ghast without a world (GhastCover.pick): the nearest cell to stand in that the ghast does not see, with no
// drop within MARGIN of it (the sight is the world's, a lambda here)
public class GhastCoverTest {

    // a floor at y 63 out to edge each way, open above it and open down to lava past it
    private static Cells yard(int edge) {
        return (x, y, z) -> {
            boolean in = Math.abs(x) <= edge && Math.abs(z) <= edge;
            if (y == 63 && in) return Cell.SOLID;
            if (y <= 40) return Cell.LAVA;
            return Cell.OPEN;
        };
    }

    @Test
    public void theNearestHiddenCellWins() {
        Cells c = yard(30);
        assertArrayEquals(new int[]{-3, 64, 0}, GhastCover.pick(c, c, 0, 64, 0, (x, y, z) -> x <= -3));
    }

    @Test
    public void noHiddenCellIsNoCover() {
        Cells c = yard(30);
        assertNull(GhastCover.pick(c, c, 0, 64, 0, (x, y, z) -> false));
    }

    @Test
    public void aCellByTheEdgeIsNoCover() {
        // the yard ends at x -6: the hidden cells from -3 out are within MARGIN of the drop beyond it, those nearer still in
        Cells c = yard(6);
        assertNull(GhastCover.pick(c, c, 0, 64, 0, (x, y, z) -> x <= -3));
        assertArrayEquals(new int[]{-2, 64, 0}, GhastCover.pick(c, c, 0, 64, 0, (x, y, z) -> x <= -2));
    }

    @Test
    public void theBodyNeedsAFloorAndRoom() {
        // a pillar from the head's cell at (-3, 65, 0) up, and no floor under (-3, 64, z) beside it by the body's reading: the next
        // nearest
        Cells walk = yard(30);
        Cells body = (x, y, z) -> {
            if (x == -3 && y >= 65 && y <= 70 && z == 0) return Cell.SOLID;
            if (x == -3 && y == 63 && z != 0) return Cell.OPEN;
            return walk.at(x, y, z);
        };
        assertArrayEquals(new int[]{-4, 64, 0}, GhastCover.pick(walk, body, 0, 64, 0, (x, y, z) -> x <= -3));
    }

    // a yard (x -30 to 30, z -30 to 6) at y 63 and a one-wide bridge of its own from it along +z at x 0 out to z 40, the
    // ground far below at y 20 (route jar 5: knocked off such a bridge by a ghast, a fall of 36)
    private static Cells yardAndBridge() {
        return (x, y, z) -> {
            boolean yard = Math.abs(x) <= 30 && z >= -30 && z <= 6;
            boolean bridge = x == 0 && z > 6 && z <= 40;
            if (y == 63 && (yard || bridge)) return Cell.SOLID;
            if (y <= 20) return Cell.SOLID;
            return Cell.OPEN;
        };
    }

    @Test
    public void groundWithRoomIsFourFromAnyDrop() {
        Cells c = yardAndBridge();
        assertTrue(GhastCover.safeGround(c, c, 0, 64, 2));
        assertFalse(GhastCover.safeGround(c, c, 0, 64, 3));
        assertFalse(GhastCover.safeGround(c, c, 0, 64, 25));
    }

    @Test
    public void backAlongTheBridgeToTheLastGroundStoodOn() {
        Cells c = yardAndBridge();
        // 23 back along the bridge, within 32
        assertArrayEquals(new int[]{0, 64, 2}, GhastCover.ground(c, c, 0, 64, 25, new int[]{0, 64, 2}, 32, 16));
        // none known: the nearest within 16 of the bridge's tip is none
        assertNull(GhastCover.ground(c, c, 0, 64, 25, null, 32, 16));
        // the last one 38 back, past 32: the nearest within 16 again
        assertNull(GhastCover.ground(c, c, 0, 64, 40, new int[]{0, 64, 2}, 32, 16));
        // a last one that is no ground with room any more is passed over for the nearest
        assertArrayEquals(new int[]{0, 64, 2}, GhastCover.ground(c, c, 0, 64, 12, new int[]{0, 64, 5}, 32, 16));
    }

    // route jar 8: the same ghast again within 30 s of a release doubles the wait (t1 run 11: 10 to 12 shelters in 3 minutes)
    @Test
    public void theSameGhastAgainDoublesTheWait() {
        assertTrue(GhastCover.streak(true, 100, 0) == 1);
        assertTrue(GhastCover.streak(true, 100, 1) == 2);
        // at most two doublings: 10, 20, 40 s
        assertTrue(GhastCover.streak(true, 100, 2) == 2);
        assertTrue(GhastCover.holdTicks(200, 0) == 200);
        assertTrue(GhastCover.holdTicks(200, 1) == 400);
        assertTrue(GhastCover.holdTicks(200, 2) == 800);
        assertTrue(GhastCover.holdTicks(200, 5) == 800);
        // another ghast, or the same one more than 30 s after the release: the plain wait
        assertTrue(GhastCover.streak(false, 100, 1) == 0);
        assertTrue(GhastCover.streak(true, GhastCover.AGAIN_TICKS + 1, 1) == 0);
        assertTrue(GhastCover.streak(true, -5, 1) == 0);
        // the wait ends once the ghast is dead or 64 or more off
        assertTrue(GhastCover.ghastGone(false, 10));
        assertTrue(GhastCover.ghastGone(true, 64));
        assertFalse(GhastCover.ghastGone(true, 40));
    }
}
