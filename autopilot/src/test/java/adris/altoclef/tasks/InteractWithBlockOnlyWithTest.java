package adris.altoclef.tasks;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// a click told to go out only with its item in hand (a pillbox's block) waits for the equip for ever; any other click still
// clicks with the hand after EQUIP_PATIENCE misses
public class InteractWithBlockOnlyWithTest {

    @Test
    public void onlyWithNeverClicksWithTheHand() {
        assertTrue(InteractWithBlockTask.waitsForEquip(true, 0));
        assertTrue(InteractWithBlockTask.waitsForEquip(true, InteractWithBlockTask.EQUIP_PATIENCE));
        assertTrue(InteractWithBlockTask.waitsForEquip(true, 1000));
    }

    @Test
    public void theDefaultStillFallsBackAfterItsPatience() {
        assertTrue(InteractWithBlockTask.waitsForEquip(false, 0));
        assertTrue(InteractWithBlockTask.waitsForEquip(false, InteractWithBlockTask.EQUIP_PATIENCE - 1));
        assertFalse(InteractWithBlockTask.waitsForEquip(false, InteractWithBlockTask.EQUIP_PATIENCE));
    }
}
