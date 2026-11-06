package adris.altoclef.util.helpers;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

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
}
