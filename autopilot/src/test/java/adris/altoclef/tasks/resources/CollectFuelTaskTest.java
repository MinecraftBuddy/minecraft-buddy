package adris.altoclef.tasks.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// the fuel trip asks for smelts and counts smelts: the go-back test in the smelt task and the finish test here are one number
public class CollectFuelTaskTest {
    @Test
    public void eightItemsIsOneCoalNotTwo() {
        // 8 mutton, nothing in the bag: one coal. the old + 1 asked for 9 and a second coal nobody needed
        assertEquals(1, CollectFuelTask.coalWanted(8, 0, 0));
        assertEquals(2, CollectFuelTask.coalWanted(9, 0, 0));
        assertEquals(2, CollectFuelTask.coalWanted(16, 0, 0));
    }

    @Test
    public void whatWeHoldAlreadyCounts() {
        // a coal in the bag and 12 smelts to go: one more coal covers the 4 left over
        assertEquals(2, CollectFuelTask.coalWanted(12, 8, 1));
        // wood we may burn counts as fuel, coal makes up the rest
        assertEquals(1, CollectFuelTask.coalWanted(8, 3, 0));
        // enough already: no more coal than we hold
        assertEquals(1, CollectFuelTask.coalWanted(8, 8, 1));
        assertEquals(0, CollectFuelTask.coalWanted(0, 0, 0));
    }

    @Test
    public void finishedWhenTheBagHoldsWhatWasAsked() {
        // one coal (8 smelts) meets a target of 8: the smelt task walks back at the same moment, not at some other number
        assertTrue(CollectFuelTask.enough(8, 8));
        assertFalse(CollectFuelTask.enough(7.5, 8));
        assertTrue(CollectFuelTask.enough(24, 8.3));
    }
}
