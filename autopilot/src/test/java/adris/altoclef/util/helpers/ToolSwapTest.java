package adris.altoclef.util.helpers;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ToolSwapTest {
    // stand-ins for two items that are exactly as fast as each other on the block (every tool on a carrot)
    private static final String AXE = "axe";
    private static final String SWORD = "sword";
    private static final String HOE = "hoe";

    @Test
    public void aBlockThatBreaksInOneSwingNeverSwaps() {
        assertFalse(ToolSwap.worthSwapping(true, SWORD, false, 1, HOE, 50));
    }

    @Test
    public void whatWeHoldIsNotSwappedForItself() {
        assertFalse(ToolSwap.worthSwapping(false, AXE, true, 4, AXE, 4));
    }

    @Test
    public void twoEquallyFastToolsDoNotTradePlaces() {
        // this was the loop: the swap parked the old tool where it ranked first, so it was the "best" next tick
        assertFalse(ToolSwap.worthSwapping(false, AXE, true, 4, SWORD, 4));
        assertFalse(ToolSwap.worthSwapping(false, SWORD, true, 4, AXE, 4));
    }

    @Test
    public void aStrictlyFasterToolIsWorthTheSwapAndASlowerOneIsNot() {
        assertTrue(ToolSwap.worthSwapping(false, AXE, true, 4, HOE, 4.5));
        assertFalse(ToolSwap.worthSwapping(false, HOE, true, 4.5, AXE, 4));
    }

    @Test
    public void aHandThatCantDoTheJobSwapsEvenAtEqualSpeed() {
        // a wooden pickaxe on iron ore is "equally fast" at nothing
        assertTrue(ToolSwap.worthSwapping(false, AXE, false, 1, SWORD, 1));
        assertTrue(ToolSwap.worthSwapping(false, null, false, 1, SWORD, 1));
    }

    @Test
    public void noBestToolMeansNothingToSwap() {
        assertFalse(ToolSwap.worthSwapping(false, AXE, false, 1, null, 0));
    }

    @Test
    public void threeMissesAtTheSameItemAndWeSwingWithWhatWeHold() {
        ToolSwap swap = new ToolSwap();
        for (int i = 0; i < ToolSwap.GIVE_UP; i++) {
            assertTrue("try " + i, swap.mayTry(SWORD));
            swap.missed(SWORD);
        }
        assertFalse(swap.mayTry(SWORD));
        // and it stays given up, not a try every other tick
        assertFalse(swap.mayTry(SWORD));
    }

    @Test
    public void aDifferentItemGetsItsOwnTries() {
        ToolSwap swap = new ToolSwap();
        for (int i = 0; i < ToolSwap.GIVE_UP; i++) {
            swap.mayTry(SWORD);
            swap.missed(SWORD);
        }
        assertFalse(swap.mayTry(SWORD));
        assertTrue(swap.mayTry(AXE));
    }

    @Test
    public void swapsThatKeepLandingStillRunOut() {
        // something else re-selecting the slot behind our back: every click "lands", the miss count never moves
        ToolSwap swap = new ToolSwap();
        for (int i = 0; i < ToolSwap.MAX_SWAPS; i++) {
            assertTrue("swap " + i, swap.mayTry(SWORD));
            swap.landed();
        }
        assertFalse(swap.mayTry(SWORD));
        assertFalse(swap.mayTry(AXE));
    }

    @Test
    public void aSwapThatLandsWipesTheMisses() {
        ToolSwap swap = new ToolSwap();
        swap.mayTry(SWORD);
        swap.missed(SWORD);
        swap.mayTry(SWORD);
        swap.missed(SWORD);
        swap.landed();
        for (int i = 0; i < ToolSwap.GIVE_UP; i++) {
            assertTrue("try " + i, swap.mayTry(SWORD));
            swap.missed(SWORD);
        }
        assertFalse(swap.mayTry(SWORD));
    }
}
