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

package baritone.process;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// (inv_pause_cap) a live look at the test heel's gold mine: a swap loop asked for a pause four times a second for 18 minutes
public class PauseCapTest {
    @Test
    public void aShortPauseGoesThrough() {
        PauseCap c = new PauseCap();
        for (int i = 0; i < 10; i++) {
            assertTrue(c.tick(true));
        }
        assertFalse(c.tick(false));
        assertFalse(c.freeing());
    }

    @Test
    public void aLongRunOfPausesLetsThePathingRun() {
        PauseCap c = new PauseCap();
        int held = 0;
        // a request every fifth tick, as the swaps with their move delay came
        int t = 0;
        while (!c.freeing() && t < 1000) {
            boolean ok = c.tick(t % 5 == 0);
            if (ok) held++;
            t++;
        }
        assertTrue(c.freeing());
        assertTrue("run of " + t, t <= PauseCap.CAP_TICKS + 5);
        // then FREE_TICKS with no pause, whatever is asked
        for (int i = 0; i < PauseCap.FREE_TICKS; i++) {
            assertFalse(c.tick(true));
        }
        assertFalse(c.freeing());
        assertTrue(c.tick(true));
    }

    @Test
    public void aGapEndsTheRun() {
        PauseCap c = new PauseCap();
        for (int i = 0; i < PauseCap.CAP_TICKS - 5; i++) {
            assertTrue(c.tick(true));
        }
        for (int i = 0; i < PauseCap.GAP_TICKS; i++) {
            assertFalse(c.tick(false));
        }
        // a new run of its own
        for (int i = 0; i < PauseCap.CAP_TICKS; i++) {
            assertTrue("tick " + i, c.tick(true));
        }
        assertFalse(c.tick(true));
        assertTrue(c.freeing());
    }
}
