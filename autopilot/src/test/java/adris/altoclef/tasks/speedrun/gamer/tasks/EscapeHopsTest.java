package adris.altoclef.tasks.speedrun.gamer.tasks;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EscapeHopsTest {
    private static List<int[]> line(int n) {
        List<int[]> cells = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            cells.add(new int[]{i, 70, 0});
        }
        return cells;
    }

    @Test
    public void hopsGoEightCellsAtATimeAndEndOnTheLastCell() {
        assertEquals(8, EscapeHops.nextHop(30, -1));
        assertEquals(16, EscapeHops.nextHop(30, 8));
        assertEquals(29, EscapeHops.nextHop(30, 24));
        assertEquals(12, EscapeHops.nextHop(13, 8));
        assertEquals(0, EscapeHops.nextHop(1, -1));
    }

    @Test
    public void closestFindsTheCellWeAreOn() {
        assertEquals(5, EscapeHops.closest(line(13), 5.5, 0.4));
        assertEquals(12, EscapeHops.closest(line(13), 40, 3));
    }

    @Test
    public void aCloseFireballHoldsTheBridge() {
        assertTrue(EscapeHops.holdForFireball(3));
        assertTrue(EscapeHops.holdForFireball(8));
        assertFalse(EscapeHops.holdForFireball(8.5));
        assertFalse(EscapeHops.holdForFireball(Double.POSITIVE_INFINITY));
    }
}
