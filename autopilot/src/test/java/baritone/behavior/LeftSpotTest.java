package baritone.behavior;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import baritone.api.utils.BetterBlockPos;
import java.util.List;
import org.junit.Test;

// S137, the repair after smoke9 (Tuning risk_trap_back): a way to the goal a walk back left a spot for that leads back into
// that spot is no way. smoke9 r3 walked back 8 blocks from a rim its searches timed out on, and the next search led straight
// back onto it
public class LeftSpotTest {

    private static final String WART = "GoalBlock{x=4542,y=63,z=-1133}";

    private static List<BetterBlockPos> along(int x0, int x1) {
        List<BetterBlockPos> way = new java.util.ArrayList<>();
        for (int x = x0; x <= x1; x++) way.add(new BetterBlockPos(x, 64, 0));
        return way;
    }

    @Test
    public void aWayBackIntoTheSpotIsRefused() {
        LeftSpot left = new LeftSpot(2.5, 1200);
        left.left(WART, 10, 64, 0, 100);
        // from 8 off, a way through x 10: back into it
        assertTrue(left.leadsBack(WART, 2, 64, 0, along(2, 12), 160));
        // a way that stays 3 or more off: fine
        assertFalse(left.leadsBack(WART, 2, 64, 0, along(2, 7), 160));
        assertEquals(60, left.ago(160));
        assertEquals("10,64,0", left.where());
    }

    @Test
    public void onlyThatGoalFromOutsideWithinTheWindow() {
        LeftSpot left = new LeftSpot(2.5, 1200);
        left.left(WART, 10, 64, 0, 100);
        // another goal
        assertFalse(left.leadsBack("GoalBlock{x=0,y=0,z=0}", 2, 64, 0, along(2, 12), 160));
        // a search from inside the spot is the spot's own
        assertFalse(left.leadsBack(WART, 9, 64, 0, along(9, 12), 160));
        // past the window it is forgotten; a clock that went back counts as long ago
        assertFalse(left.leadsBack(WART, 2, 64, 0, along(2, 12), 100 + 1200 + 1));
        assertEquals(-1, left.ago(2000));
        LeftSpot back = new LeftSpot(2.5, 1200);
        back.left(WART, 10, 64, 0, 100);
        assertFalse(back.leadsBack(WART, 2, 64, 0, along(2, 12), 50));
    }
}
