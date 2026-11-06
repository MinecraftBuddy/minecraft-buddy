package baritone.pathing.movement;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// may a block go that has liquid beside it? lava never, water by stock baritone's guess that a falling stream keeps falling
public class LiquidBesideBreakTest {

    @Test
    public void flowingLavaOverMoreLavaStopsTheDig() {
        // the cell beside a lava fall in practice run 116: flowing, lava under it. stock baritone called it static
        assertTrue(MovementHelper.liquidBesideBlocks(false, false, true, 3, true));
    }

    @Test
    public void anyLavaBesideStopsTheDig() {
        assertTrue(MovementHelper.liquidBesideBlocks(false, false, true, 0, false));
        assertTrue(MovementHelper.liquidBesideBlocks(false, false, true, 5, false));
    }

    @Test
    public void flowingWaterOverMoreWaterStillLetsItGo() {
        assertFalse(MovementHelper.liquidBesideBlocks(false, false, false, 3, true));
    }

    @Test
    public void waterKeepsTheRestOfTheOldRule() {
        assertTrue(MovementHelper.liquidBesideBlocks(false, false, false, 0, true));
        assertTrue(MovementHelper.liquidBesideBlocks(false, false, false, 3, false));
        assertTrue(MovementHelper.liquidBesideBlocks(true, false, false, 3, true));
        assertTrue(MovementHelper.liquidBesideBlocks(false, true, false, 3, true));
    }
}
