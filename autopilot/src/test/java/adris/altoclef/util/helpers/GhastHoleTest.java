package adris.altoclef.util.helpers;

import adris.altoclef.util.helpers.GhastHole.Cell;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GhastHoleTest {
    private static final Cell ROCK = new Cell(true, false, false, false, false);
    private static final Cell LAVA = new Cell(false, true, false, false, false);
    private static final Cell AIR = new Cell(false, false, false, false, false);
    private static final Cell ROCK_BY_LAVA = new Cell(true, false, false, false, true);
    private static final Cell GRAVEL = new Cell(true, false, true, false, false);
    private static final Cell MAGMA = new Cell(true, false, false, true, false);

    @Test
    public void plainNetherrackIsFine() {
        assertTrue(GhastHole.columnOk(List.of(ROCK, ROCK, ROCK), ROCK));
        // gravel to stand on is fine, it only matters in a cell we break
        assertTrue(GhastHole.columnOk(List.of(ROCK, ROCK, ROCK), GRAVEL));
    }

    @Test
    public void lavaNextToACellWeBreakIsNo() {
        assertFalse(GhastHole.columnOk(List.of(ROCK, ROCK_BY_LAVA, ROCK), ROCK));
        assertFalse(GhastHole.columnOk(List.of(ROCK, ROCK, ROCK_BY_LAVA), ROCK));
    }

    @Test
    public void lavaUnderTheFloorIsNo() {
        assertFalse(GhastHole.columnOk(List.of(ROCK, ROCK, ROCK), LAVA));
        // nor a cave we would just drop into
        assertFalse(GhastHole.columnOk(List.of(ROCK, ROCK, ROCK), AIR));
        assertFalse(GhastHole.columnOk(List.of(ROCK, AIR, ROCK), ROCK));
    }

    @Test
    public void magmaIsNo() {
        assertFalse(GhastHole.columnOk(List.of(ROCK, ROCK, ROCK), MAGMA));
        assertFalse(GhastHole.columnOk(List.of(ROCK, MAGMA, ROCK), ROCK));
    }

    @Test
    public void gravelInTheWayIsNo() {
        assertFalse(GhastHole.columnOk(List.of(GRAVEL, ROCK, ROCK), ROCK));
    }

    @Test
    public void aShortColumnIsNo() {
        assertFalse(GhastHole.columnOk(List.of(ROCK, ROCK), ROCK));
    }

    @Test
    public void hideOnlyWhenAGhastSeesUsOrJustHitUs() {
        assertTrue(GhastHole.hideWanted(true, Long.MAX_VALUE));
        assertTrue(GhastHole.hideWanted(false, 0));
        assertTrue(GhastHole.hideWanted(false, GhastHole.HIT_TICKS));
        assertFalse(GhastHole.hideWanted(false, GhastHole.HIT_TICKS + 1));
        assertFalse(GhastHole.hideWanted(false, Long.MAX_VALUE));
        // a clock that went backwards is not a hit
        assertFalse(GhastHole.hideWanted(false, -5));
    }
}
