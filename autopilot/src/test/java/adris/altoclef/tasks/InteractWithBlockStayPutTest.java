package adris.altoclef.tasks;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// a click that was told to stay put never walks anywhere on its own. everybody else still does
public class InteractWithBlockStayPutTest {

    @Test
    public void stayingPutNeverWalksOrWanders() {
        assertFalse(InteractWithBlockTask.walksWhenOutOfReach(true));
        assertFalse(InteractWithBlockTask.wandersWhenStuck(true));
    }

    @Test
    public void theDefaultStillWalksAndWanders() {
        assertTrue(InteractWithBlockTask.walksWhenOutOfReach(false));
        assertTrue(InteractWithBlockTask.wandersWhenStuck(false));
    }
}
