package adris.altoclef.tasks.resources;

import adris.altoclef.tasks.resources.SmeltRouter.Held;
import adris.altoclef.tasks.resources.SmeltRouter.Pick;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SmeltRouterTest {
    private static final double RANGE = 48;
    private static final double NONE = Double.POSITIVE_INFINITY;

    private static Pick pick(boolean useNearby, double dist, Held held, boolean pickedBefore) {
        return SmeltRouter.pick(useNearby, dist, RANGE, held, pickedBefore);
    }

    @Test
    public void nearBlastFurnaceWins() {
        assertEquals(Pick.NEARBY_BLAST, pick(true, 10, Held.NONE, false));
        assertEquals(Pick.NEARBY_BLAST, pick(true, RANGE, Held.NONE, false));
    }

    @Test
    public void farOrMissingBlastFurnaceFallsBackToTheDefault() {
        assertEquals(Pick.DEFAULT, pick(true, RANGE + 1, Held.NONE, false));
        assertEquals(Pick.DEFAULT, pick(true, NONE, Held.NONE, false));
    }

    @Test
    public void settingOffMeansDefault() {
        assertEquals(Pick.DEFAULT, pick(false, 3, Held.NONE, false));
    }

    @Test
    public void pickedOneGetsSlackSoTheRangeEdgeDoesNotFlipFlop() {
        assertEquals(Pick.DEFAULT, pick(true, 60, Held.NONE, false));
        assertEquals(Pick.NEARBY_BLAST, pick(true, 60, Held.NONE, true));
        // slack has an end though
        assertEquals(Pick.DEFAULT, pick(true, RANGE * 1.5 + 1, Held.NONE, true));
    }

    @Test
    public void startedInAFurnaceStaysInTheFurnace() {
        assertEquals(Pick.DEFAULT, pick(true, 2, Held.FURNACE, false));
        assertEquals(Pick.DEFAULT, pick(true, 2, Held.FURNACE, true));
    }

    @Test
    public void startedInABlastFurnaceStaysNoMatterTheRangeOrTheSetting() {
        assertEquals(Pick.NEARBY_BLAST, pick(true, 500, Held.BLAST, true));
        assertEquals(Pick.NEARBY_BLAST, pick(false, 500, Held.BLAST, true));
    }
}
