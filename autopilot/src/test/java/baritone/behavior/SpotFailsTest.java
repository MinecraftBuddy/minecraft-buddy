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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

// (S137, the repair after smoke8) the failed searches by their spot, whatever the goal, and the way the buddy came. smoke8
// r3: 24 look points each failing one round from one patch of 4 blocks; the patch is the trouble, not the goals
public class SpotFailsTest {
    @Test
    public void theGoalsThatFailedFromAroundASpotAreCountedOnce() {
        SpotFails fails = new SpotFails(2.5, 1200);
        fails.note(10, 64, 10, "GoalXZRadius{a}", 100);
        fails.note(11, 65, 10, "GoalXZRadius{b}", 140);
        // the same goal again, built anew: one goal
        fails.note(10, 64, 11, "GoalXZRadius{b}", 160);
        assertEquals(2, fails.goals(10, 64, 10, 200));
        fails.note(12, 64, 9, "GoalComposite{c}", 180);
        assertEquals(3, fails.goals(10, 64, 10, 200));
        // a spot further off is another spot
        assertEquals(0, fails.goals(20, 64, 10, 200));
        // past the window they are gone; a clock that went back counts as long ago
        assertEquals(1, fails.goals(10, 64, 10, 160 + 1200 + 1));
        assertEquals(0, fails.goals(10, 64, 10, 50));
    }

    @Test
    public void theWayBackIsTheNewestBlockFarEnoughOff() {
        Trail trail = new Trail();
        // walked in along x, then about in a patch of 4 blocks
        for (int x = 0; x <= 20; x++) trail.note(x, 64, 0, x * 5L);
        trail.note(21, 64, 1, 200);
        trail.note(20, 66, 2, 260);
        trail.note(22, 64, 0, 300);
        Trail.Point back = trail.back(21, 64, 1, 8, 6000, 320);
        assertNotNull(back);
        // the newest at least 8 off: x 12 (9.1 off the feet; x 14 is 7.1, and only every second block was kept)
        assertEquals(12, back.x());
        // none far enough, or all too old
        assertNull(trail.back(21, 64, 1, 50, 6000, 320));
        assertNull(trail.back(21, 64, 1, 8, 100, 320));
    }

    @Test
    public void aPointIsKeptOnlyStepsApart() {
        Trail trail = new Trail();
        trail.note(0, 64, 0, 0);
        trail.note(1, 64, 0, 1);
        trail.note(2, 64, 0, 2);
        // 0 and 2 kept, 1 too close to 0: the newest 2 off from 4 is x 2
        assertEquals(2, trail.back(4, 64, 0, 2, 100, 3).x());
        assertEquals(0, trail.back(4, 64, 0, 3, 100, 3).x());
    }
    // the repair after smoke9 (risk_goal_rounds): the rounds one goal failed from around a spot, whatever their kind
    @Test
    public void theRoundsOfOneGoalFromOneSpot() {
        SpotFails fails = new SpotFails(2.5, 1200);
        fails.note(10, 64, 10, "GoalBlock{x=1,y=2,z=3}", 100);
        fails.note(11, 64, 10, "GoalFollowEntity{entity=7, near=3.0}", 120);
        assertEquals(1, fails.rounds(10, 64, 10, "GoalBlock{x=1,y=2,z=3}", 130));
        fails.note(12, 64, 11, "GoalBlock{x=1,y=2,z=3}", 140);
        assertEquals(2, fails.rounds(10, 64, 10, "GoalBlock{x=1,y=2,z=3}", 150));
        // from another spot, past the window, or another goal: not counted
        assertEquals(0, fails.rounds(20, 64, 10, "GoalBlock{x=1,y=2,z=3}", 150));
        assertEquals(1, fails.rounds(10, 64, 10, "GoalBlock{x=1,y=2,z=3}", 100 + 1200 + 1));
        assertEquals(1, fails.rounds(10, 64, 10, "GoalFollowEntity{entity=7, near=3.0}", 150));
        assertEquals(0, fails.rounds(10, 64, 10, null, 150));
    }
}
