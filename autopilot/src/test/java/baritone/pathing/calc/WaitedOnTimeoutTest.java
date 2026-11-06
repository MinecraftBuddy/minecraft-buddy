package baritone.pathing.calc;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WaitedOnTimeoutTest {

    // plan ahead is 5000, normal is 2000, search started at t=1000
    @Test
    public void planAheadFailureTimeoutShrinksToTheNormalOne() {
        assertEquals(1000 + 2000, AbstractNodeCostSearch.capIfWaitedOn(1000 + 5000, 1000, 2000));
    }

    @Test
    public void planAheadPrimaryTimeoutShrinksToTheNormalOne() {
        assertEquals(1000 + 500, AbstractNodeCostSearch.capIfWaitedOn(1000 + 4000, 1000, 500));
    }

    // somebody set plan ahead shorter than normal on purpose, waiting should not make it longer
    @Test
    public void neverLengthens() {
        assertEquals(1000 + 300, AbstractNodeCostSearch.capIfWaitedOn(1000 + 300, 1000, 2000));
    }

    // the log: 5.4s into a search with a 2s cap, the deadline is long behind us so the next time check ends it
    @Test
    public void aSearchAlreadyPastTheCapIsDueImmediately() {
        long start = 1000;
        long deadline = AbstractNodeCostSearch.capIfWaitedOn(start + 5000, start, 2000);
        assertTrue(start + 5400 - deadline >= 0);
    }
}
