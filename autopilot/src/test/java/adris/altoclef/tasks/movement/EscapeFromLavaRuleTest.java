package adris.altoclef.tasks.movement;

import adris.altoclef.util.helpers.HopLatch;
import adris.altoclef.util.helpers.LavaExit;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// lava pathing is for being in lava, not for being on fire next to it
public class EscapeFromLavaRuleTest {

    @Test
    public void onTheLipTheHopWalksOntoWhatItIsHangingOff() {
        LavaExit.Cell stone = new LavaExit.Cell(6, 64, 10);
        LavaExit.Cell farShore = new LavaExit.Cell(4, 64, 10);
        assertEquals(farShore, EscapeFromLavaTask.hopAim(HopLatch.Keys.WALK_ON, stone, farShore, () -> stone));
    }

    @Test
    public void aBlinkKeepsTheLastAnswerAndDoesNotAskAgain() {
        LavaExit.Cell stone = new LavaExit.Cell(6, 64, 10);
        LavaExit.Cell farShore = new LavaExit.Cell(4, 64, 10);
        assertEquals(stone, EscapeFromLavaTask.hopAim(HopLatch.Keys.WALK_ON, stone, null, () -> farShore));
    }

    @Test
    public void whileSwimmingTheHopKeepsLooking() {
        LavaExit.Cell stone = new LavaExit.Cell(6, 64, 10);
        LavaExit.Cell closer = new LavaExit.Cell(4, 64, 10);
        assertEquals(closer, EscapeFromLavaTask.hopAim(HopLatch.Keys.SWIM, stone, null, () -> closer));
    }

    @Test
    public void inLavaSwimsThroughIt() {
        assertTrue(EscapeFromLavaTask.lavaPathingAllowed(true, 0));
    }

    @Test
    public void onFireOnDryLandDoesNot() {
        // the task starts with the grace already spent when we're only burning
        assertFalse(EscapeFromLavaTask.lavaPathingAllowed(false, 10));
        assertFalse(EscapeFromLavaTask.lavaPathingAllowed(false, 1000));
    }

    @Test
    public void justOutOfItKeepsItForAMoment() {
        // the surface flickers in and out at the shore, a flip a tick would rebuild the tables every tick
        assertTrue(EscapeFromLavaTask.lavaPathingAllowed(false, 1));
        assertTrue(EscapeFromLavaTask.lavaPathingAllowed(false, 9));
    }
}
