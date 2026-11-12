/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.behavior;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// (S137, the repair after smoke7) a goal given up from a block: held for the window, from that block only, and kept apart from
// the other goals asked from the same block in between (smoke7 r2: the camp walk and the blaze chases took turns)
public class GivenUpGoalsTest {
    @Test
    public void aGoalGivenUpFromABlockHoldsForItsWindowThere() {
        GivenUpGoals<String, String> given = new GivenUpGoals<>(200);
        given.note("block", "camp", 100);
        assertTrue(given.holds("block", "camp", 100));
        assertTrue(given.holds("block", "camp", 300));
        assertFalse(given.holds("block", "camp", 301));
        given.note("block", "camp", 400);
        // another block, another goal: searched as ever
        assertFalse(given.holds("next block", "camp", 400));
        assertFalse(given.holds("block", "stand", 400));
        // a clock that went back is long ago
        assertFalse(given.holds("block", "camp", 50));
    }

    @Test
    public void goalsAskedInTurnsAreEachKept() {
        GivenUpGoals<String, String> given = new GivenUpGoals<>(200);
        given.note("block", "camp", 100);
        given.note("block", "blaze 1", 110);
        given.note("block", "blaze 2", 120);
        assertTrue(given.holds("block", "camp", 130));
        assertTrue(given.holds("block", "blaze 1", 130));
    }

    @Test
    public void itsLineIsSaidOnceAndTheOldestGoFirst() {
        GivenUpGoals<String, String> given = new GivenUpGoals<>(200);
        given.note("block", "camp", 100);
        assertEquals(40, given.sayOnce("block", "camp", 140));
        assertEquals(-1, given.sayOnce("block", "camp", 150));
        assertEquals(-1, given.sayOnce("block", "stand", 150));
        // given up again: a line again
        given.note("block", "camp", 160);
        assertEquals(0, given.sayOnce("block", "camp", 160));
        for (int i = 0; i < GivenUpGoals.MAX + 4; i++) given.note("block", "goal " + i, 200 + i);
        assertEquals(GivenUpGoals.MAX, given.size());
        assertFalse(given.holds("block", "camp", 230));
        assertTrue(given.holds("block", "goal " + (GivenUpGoals.MAX + 3), 230));
    }
}
