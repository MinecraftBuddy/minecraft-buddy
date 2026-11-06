package adris.altoclef.tasks;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// a bucket on a liquid keeps its feet flat, and a click that never fills it is a ban. everything else clicks like it always did
public class InteractWithBlockScoopTest {

    @Test
    public void aScoopNeverSneaksAfterTheClick() {
        assertFalse(InteractWithBlockTask.sneaksAfterClick(true, true));
        assertFalse(InteractWithBlockTask.sneaksAfterClick(false, true));
    }

    @Test
    public void aPlainShiftClickStillSneaks() {
        assertTrue(InteractWithBlockTask.sneaksAfterClick(true, false));
        assertFalse(InteractWithBlockTask.sneaksAfterClick(false, false));
    }

    @Test
    public void onlyAScoopBansOnAClickTimeout() {
        assertTrue(InteractWithBlockTask.bansOnClickTimeout(true));
        assertFalse(InteractWithBlockTask.bansOnClickTimeout(false));
    }
}
