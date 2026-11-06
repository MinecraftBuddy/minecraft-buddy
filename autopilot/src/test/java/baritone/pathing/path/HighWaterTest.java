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

package baritone.pathing.path;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HighWaterTest {

    @Test
    public void bouncingBetweenTwoMovementsRunsOut() {
        HighWater water = new HighWater();
        // 0 and 1 trading places forever, each costing 10 with a 100 tick timeout
        int ticks = 0;
        boolean stuck = false;
        while (!stuck && ticks < 1000) {
            stuck = water.tick(ticks % 2, 10, 100);
            ticks++;
        }
        assertTrue(stuck);
        assertEquals(1, water.high());
        // the high was set on the second tick and every lap is a full tick and a half one, so the 110 tick budget
        // runs out 146 ticks after it
        assertEquals(148, ticks);
    }

    @Test
    public void knockbackDoesntCancelAProgressingPath() {
        HighWater water = new HighWater();
        for (int i = 0; i < 30; i++) {
            assertFalse(water.tick(5, 10, 100));
        }
        // knocked back a movement three times for a second each, walking back up to the high in between. 120 ticks
        // all told, which full rate called stuck
        for (int bounce = 0; bounce < 3; bounce++) {
            for (int i = 0; i < 20; i++) {
                assertFalse(water.tick(4, 10, 100));
            }
            for (int i = 0; i < 10; i++) {
                assertFalse(water.tick(5, 10, 100));
            }
        }
        // and then it got past
        for (int i = 0; i < 50; i++) {
            assertFalse(water.tick(6, 10, 100));
        }
    }

    @Test
    public void mostlyBehindStillRunsOut() {
        HighWater water = new HighWater();
        water.tick(4, 10, 100);
        // a tick on the high and ten behind it, over and over
        int ticks = 0;
        boolean stuck = false;
        while (!stuck && ticks < 1000) {
            stuck = water.tick(ticks % 11 == 0 ? 5 : 4, 10, 100);
            ticks++;
        }
        assertTrue(stuck);
        assertEquals(5, water.high());
        // within twice the 110 tick budget
        assertTrue(ticks <= 220);
    }

    @Test
    public void gettingFurtherStartsOver() {
        HighWater water = new HighWater();
        for (int i = 0; i < 100; i++) {
            assertFalse(water.tick(0, 10, 100));
        }
        // a new high, the clock starts again and takes the new movement's cost
        for (int i = 0; i < 150; i++) {
            assertFalse(water.tick(1, 50, 100));
        }
        assertTrue(water.tick(1, 50, 100));
    }

    @Test
    public void goingBackDoesntStartOver() {
        HighWater water = new HighWater();
        for (int i = 0; i < 100; i++) {
            water.tick(3, 10, 100);
        }
        // a resync back to 2 is not progress, the cost there doesn't matter either. 100 ticks on the high spent 200 of
        // the 220 half ticks, and a tick behind is one of them
        for (int i = 0; i < 20; i++) {
            assertFalse(water.tick(2, 1000, 100));
        }
        assertTrue(water.tick(2, 1000, 100));
    }

    @Test
    public void shiftAndForget() {
        HighWater water = new HighWater();
        water.tick(5, 10, 100);
        water.shift(3);
        assertEquals(2, water.high());
        water.forget();
        assertEquals(-1, water.high());
        assertFalse(water.tick(0, 10, 100));
        assertEquals(0, water.high());
    }
}
