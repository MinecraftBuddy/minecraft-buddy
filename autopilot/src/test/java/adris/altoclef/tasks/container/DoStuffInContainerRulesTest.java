package adris.altoclef.tasks.container;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// the table left the bag, the walk cut the placer off mid verify, and a second table got crafted. what to place or walk to is
// StationChoiceTest now (the force timer, PLACED_CLOSE and mayMakeNew these used to pin are gone)
public class DoStuffInContainerRulesTest {
    @Test
    public void thePlacerStaysThroughVerifyWithTheItemGone() {
        assertTrue(DoStuffInContainerTask.keepPlacing(true, true, false, false));
        // the click took the table out of the bag, the block has not shown up yet
        assertTrue(DoStuffInContainerTask.keepPlacing(false, true, false, true));
        // item gone and nothing to wait for: not our placer's business any more
        assertFalse(DoStuffInContainerTask.keepPlacing(false, true, false, false));
        // done is done
        assertFalse(DoStuffInContainerTask.keepPlacing(false, true, true, true));
        assertFalse(DoStuffInContainerTask.keepPlacing(true, false, false, false));
    }
}
