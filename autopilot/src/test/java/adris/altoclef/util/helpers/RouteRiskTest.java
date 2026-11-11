package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// S137, a place goal by a safer route: go or flee where the danger meter says flee while the task walks to a place
public class RouteRiskTest {

    @Test
    public void theBuddyGoesWhileItOutlivesTheWayAndTheWork() {
        // 20 hp, 5 expected on the way: 15 left, over the margin
        assertTrue(RouteRisk.go(20, 5, 0, false, false));
        // the work at the goal counts with the way
        assertTrue(RouteRisk.go(20, 5, 8, true, false));
        // 12 hp, 8 expected: 4 left, at the margin
        assertFalse(RouteRisk.go(12, 8, 0, false, false));
        assertFalse(RouteRisk.go(12, 8, 0, false, true));
    }

    @Test
    public void theMarginHasItsBand() {
        // 4.5 left: not enough to start going (4.8), enough to go on (4)
        assertFalse(RouteRisk.go(12, 7.5, 0, false, false));
        assertTrue(RouteRisk.go(12, 7.5, 0, false, true));
    }

    @Test
    public void aDangerOnTheWayOnlyIsFledFromAtAnyHealth() {
        assertFalse(RouteRisk.go(20, 30, 0, false, false));
        assertFalse(RouteRisk.go(20, 30, 0, false, true));
    }

    @Test
    public void atAGoalInTheDangerTheBuddyGoesWholeAndGoesOn() {
        // more than it has, on the way and at the goal: whole, it goes all the same
        assertTrue(RouteRisk.go(20, 30, 10, true, false));
        assertTrue(RouteRisk.go(RecoverHold.DONE_AT, 30, 10, true, false));
        // hurt, it does not start
        assertFalse(RouteRisk.go(15, 30, 10, true, false));
        // going, it goes on (the last-ditch rule is the brain's)
        assertTrue(RouteRisk.go(9, 30, 10, true, true));
    }

    // decision 10: a fight begun on the task's way is the piece in hand. one wither skeleton 3.8 off with a sword (K 20 / 9.6
    // plus the step to it, about 2.3 s) at smoke5's reading (T 12.1 hp in 3 s): about 9 hp, outlived at 20. three of them
    // (the loss three times): not outlived, the meter's flee. a K the meter has not read is no piece to count on
    @Test
    public void aFightOnTheWayIsGoneOnWhileItIsOutlived() {
        double k = 20 / 9.6 + 0.8 / 4.3;
        double one = RouteRisk.piece(k, 12.1);
        assertEquals(9.15, one, 0.01);
        assertTrue(RouteRisk.go(20, one, 0, false, false));
        assertFalse(RouteRisk.go(20, RouteRisk.piece(k, 36.3), 0, false, false));
        // the rest of the route counts on top
        assertFalse(RouteRisk.go(20, one + 7, 0, false, false));
        assertEquals(Double.POSITIVE_INFINITY, RouteRisk.piece(Double.NaN, 12.1), 0);
        assertEquals("route: go, the fight in hand 9.2 hp (2.3 s to kill), 0.0 on the way and 0.0 at the goal, hp 20.0",
                RouteRisk.pieceLine(true, 20, one, k, 0, 0, false));
    }

    @Test
    public void theLine() {
        assertEquals("route: go, 3.2 hp expected on the way and 1.0 at the goal, hp 17.0, the goal lies in the danger",
                RouteRisk.line(true, 17, 3.2, 1, true));
        assertEquals("route: flee, 14.1 hp expected on the way and 0.0 at the goal, hp 12.0", RouteRisk.line(false, 12, 14.1, 0, false));
    }
}
