/*
 * This file is part of Soprano.
 *
 * Soprano is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Soprano is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Soprano.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.pathing.movement;

import org.junit.Test;

import static baritone.pathing.movement.SwimStall.Verdict.DIVE;
import static baritone.pathing.movement.SwimStall.Verdict.GIVE_UP;
import static baritone.pathing.movement.SwimStall.Verdict.SWIM;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SwimStallTest {

    // n ticks parked at the same distance, returns the last verdict
    private static SwimStall.Verdict sit(SwimStall s, int n, double dist, boolean canDive) {
        SwimStall.Verdict v = null;
        for (int i = 0; i < n; i++) {
            v = s.tick(dist, canDive);
        }
        return v;
    }

    @Test
    public void swimmingAlongNeverTrips() {
        SwimStall s = new SwimStall();
        // even slow, a block every 20 ticks is plenty
        for (int i = 0; i < 400; i++) {
            assertEquals(SWIM, s.tick(20 - i * 0.05, true));
        }
        assertFalse(s.diving());
    }

    @Test
    public void aSecondOfStandingStillIsNotAStall() {
        // a door or a fumbled climb onto the bank, then off we go again. the window is a second and a half
        assertEquals(30, SwimStall.WINDOW);
        SwimStall s = new SwimStall();
        assertEquals(SWIM, sit(s, 25, 3, false));
        assertEquals(SWIM, s.tick(2.5, false));
        assertEquals(SWIM, sit(s, 25, 2.5, false));
        assertFalse(s.diving());
    }

    @Test
    public void pinnedWithSomethingOverheadDivesThenGivesUp() {
        SwimStall s = new SwimStall();
        // the first tick only sets the baseline, the window counts from there
        assertEquals(SWIM, sit(s, SwimStall.WINDOW, 3, true));
        assertEquals(DIVE, s.tick(3, true));
        assertTrue(s.diving());
        assertEquals(DIVE, sit(s, SwimStall.DIVE_TICKS - 1, 3, true));
        assertEquals(GIVE_UP, s.tick(3, true));
    }

    @Test
    public void pinnedWithNothingToDiveUnderGivesUpRightAway() {
        SwimStall s = new SwimStall();
        assertEquals(SWIM, sit(s, SwimStall.WINDOW, 3, false));
        assertEquals(GIVE_UP, s.tick(3, false));
        assertFalse(s.diving());
    }

    @Test
    public void lowAirEndsAFruitlessDiveAndGivesUp() {
        SwimStall s = new SwimStall();
        sit(s, SwimStall.WINDOW + 1, 3, true);
        assertTrue(s.diving());
        // breath runs low mid dive: no dive, and it never got anywhere, so replan (and the new movement surfaces)
        assertEquals(GIVE_UP, s.tick(3, false));
        assertFalse(s.diving());
    }

    @Test
    public void aDiveThatGetsUnderItGoesBackToSwimming() {
        SwimStall s = new SwimStall();
        sit(s, SwimStall.WINDOW + 1, 3, true);
        assertTrue(s.diving());
        double d = 3;
        for (int i = 0; i < 5; i++) {
            d -= 0.1;
            assertEquals(DIVE, s.tick(d, true));
        }
        // out from under it, nothing overhead anymore
        assertEquals(SWIM, s.tick(d - 0.1, false));
        assertFalse(s.diving());
        // and a fresh stall gets a fresh window, counted from that last bit of progress
        assertEquals(SWIM, sit(s, SwimStall.WINDOW - 1, d - 0.1, false));
        assertEquals(GIVE_UP, s.tick(d - 0.1, false));
    }

    @Test
    public void aDiveStillMovingUnderALongCeilingKeepsDiving() {
        SwimStall s = new SwimStall();
        sit(s, SwimStall.WINDOW + 1, 10, true);
        double d = 10;
        for (int i = 0; i < SwimStall.DIVE_TICKS * 3; i++) {
            d -= 0.05;
            assertEquals(DIVE, s.tick(d, true));
        }
    }

    @Test
    public void resetForgetsEverything() {
        SwimStall s = new SwimStall();
        sit(s, SwimStall.WINDOW + 1, 3, true);
        assertTrue(s.diving());
        s.reset();
        assertFalse(s.diving());
        assertEquals(SWIM, sit(s, SwimStall.WINDOW, 3, true));
    }
}
