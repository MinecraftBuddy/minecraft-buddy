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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class PathKeepTest {
    private static final Object WORLD = new Object();
    private static final Object PLAYER = new Object();
    private static final String PATH = "path to 10 64 10";

    private static PathKeep<String> parked(long tick) {
        PathKeep<String> keep = new PathKeep<>();
        keep.handover(keep.peek(), PATH, tick, WORLD, PLAYER);
        return keep;
    }

    private static String take(PathKeep<String> keep, long now, boolean sameGoal, boolean feetOn) {
        return keep.take(true, now, WORLD, PLAYER, p -> sameGoal, p -> feetOn);
    }

    @Test
    public void theSameGoalRightAwayWithUsOnThePathGetsItBack() {
        assertSame(PATH, take(parked(100), 101, true, true));
        assertTrue(PathKeep.restore(true, true, PathKeep.MAX_AGE_TICKS, true, true, true));
    }

    @Test
    public void itIsUsedUpEitherWay() {
        PathKeep<String> keep = parked(100);
        assertSame(PATH, take(keep, 101, true, true));
        assertNull(take(keep, 101, true, true));
        PathKeep<String> wrong = parked(100);
        assertNull(take(wrong, 101, false, true));
        assertNull(take(wrong, 101, true, true));
    }

    @Test
    public void halfASecondIsTheMost() {
        assertEquals(10, PathKeep.MAX_AGE_TICKS);
        assertSame(PATH, take(parked(100), 100 + PathKeep.MAX_AGE_TICKS, true, true));
        assertNull(take(parked(100), 100 + PathKeep.MAX_AGE_TICKS + 1, true, true));
        // a park from the future is a broken clock, not a fresh path
        assertNull(take(parked(100), 99, true, true));
    }

    @Test
    public void aDifferentGoalNeverGetsAnOldPath() {
        assertNull(take(parked(100), 101, false, true));
    }

    @Test
    public void offThePathMeansKnockbackOrATeleport() {
        assertNull(take(parked(100), 101, true, false));
    }

    @Test
    public void anotherDimensionOrAnotherLifeIsAnotherPath() {
        assertNull(parked(100).take(true, 101, new Object(), PLAYER, p -> true, p -> true));
        assertNull(parked(100).take(true, 101, WORLD, new Object(), p -> true, p -> true));
    }

    @Test
    public void theUserCancellingDropsIt() {
        PathKeep<String> keep = parked(100);
        keep.drop();
        assertNull(take(keep, 101, true, true));
    }

    @Test
    public void theNextTasksStartKeepsTheLastOnesPark() {
        PathKeep<String> keep = parked(100);
        // the stop parked it, the cancel inside the start dropped it, and the start has no path of its own to park
        PathKeep.Park<String> before = keep.peek();
        keep.drop();
        keep.handover(before, null, 100, WORLD, PLAYER);
        assertSame(PATH, take(keep, 101, true, true));
    }

    @Test
    public void theSettingTurnsItOff() {
        assertNull(parked(100).take(false, 101, WORLD, PLAYER, p -> true, p -> true));
        assertFalse(PathKeep.restore(false, true, 0, true, true, true));
    }
}
