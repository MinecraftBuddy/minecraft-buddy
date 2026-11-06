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

import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.utils.BetterBlockPos;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GivenUpTest {

    private static final BetterBlockPos A = new BetterBlockPos(10, 64, 10);

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    @Test
    public void onlyTheOnesStillComingBackAreWaiting() {
        GivenUp g = new GivenUp();
        assertFalse(g.anyWaiting(0, (x, y, z) -> true));
        g.giveUp(A, 0);
        assertTrue(g.anyWaiting(1, (x, y, z) -> true));
        // the filter gets the last word, the fluid pass doesn't wait on its own cells
        assertFalse(g.anyWaiting(1, (x, y, z) -> false));
        // its break ran out
        assertFalse(g.anyWaiting(GivenUp.EXPIRY_TICKS, (x, y, z) -> true));
        // for good isn't waiting on anything
        g.giveUp(A, 1);
        g.giveUp(A, 2);
        assertFalse(g.anyWaiting(3, (x, y, z) -> true));
    }

    @Test
    public void aGiveUpRunsOut() {
        GivenUp g = new GivenUp();
        assertFalse(g.isGivenUp(A.x, A.y, A.z, 0));
        assertEquals(1, g.giveUp(A, 100));
        assertTrue(g.isGivenUp(A.x, A.y, A.z, 100 + GivenUp.EXPIRY_TICKS - 1));
        assertFalse(g.isGivenUp(A.x, A.y, A.z, 100 + GivenUp.EXPIRY_TICKS));
        // somebody else is fine
        assertFalse(g.isGivenUp(A.x + 1, A.y, A.z, 100));
    }

    @Test
    public void thirdTimeIsForGood() {
        GivenUp g = new GivenUp();
        long t = 0;
        for (int i = 1; i < GivenUp.MAX_ATTEMPTS; i++) {
            assertEquals(i, g.giveUp(A, t));
            t += GivenUp.EXPIRY_TICKS;
        }
        assertEquals(GivenUp.MAX_ATTEMPTS, g.giveUp(A, t));
        assertTrue(g.isGivenUp(A.x, A.y, A.z, t + 1_000_000));
    }

    @Test
    public void retryOnlyBringsBackTheTimedOnes() {
        GivenUp g = new GivenUp();
        BetterBlockPos b = new BetterBlockPos(50, 64, 50);
        for (int i = 0; i < GivenUp.MAX_ATTEMPTS; i++) {
            g.giveUp(b, 0);
        }
        g.giveUp(A, 0);
        assertTrue(g.retryNow(1));
        assertFalse(g.isGivenUp(A.x, A.y, A.z, 1));
        assertTrue(g.isGivenUp(b.x, b.y, b.z, 1));
        // nothing left to bring back, so a caller looping on this stops
        assertFalse(g.retryNow(2));
        // and the retried one still remembers its attempt
        assertEquals(2, g.giveUp(A, 2));
    }

    @Test
    public void aChangeNextToAForGoodOneLetsItBackIn() {
        GivenUp g = new GivenUp();
        for (int i = 0; i < GivenUp.MAX_ATTEMPTS; i++) {
            g.giveUp(A, 0);
        }
        g.nearbyChanged(A.x + GivenUp.NEARBY + 1, A.y, A.z, 10);
        assertTrue(g.isGivenUp(A.x, A.y, A.z, 10));
        g.nearbyChanged(A.x + GivenUp.NEARBY, A.y - 1, A.z + 1, 10);
        assertFalse(g.isGivenUp(A.x, A.y, A.z, 10));
        // one more go, not a fresh count: the next stall puts it straight back for good
        assertEquals(GivenUp.MAX_ATTEMPTS, g.giveUp(A, 10));
        assertTrue(g.isGivenUp(A.x, A.y, A.z, Long.MAX_VALUE - 1));
        // and the next change buys exactly one more again
        g.nearbyChanged(A.x, A.y + 1, A.z, 20);
        assertFalse(g.isGivenUp(A.x, A.y, A.z, 20));
        assertEquals(GivenUp.MAX_ATTEMPTS, g.giveUp(A, 20));
    }

    @Test
    public void aChangeDoesntCutATimedOneShort() {
        GivenUp g = new GivenUp();
        g.giveUp(A, 0);
        g.nearbyChanged(A.x, A.y + 1, A.z, 1);
        assertTrue(g.isGivenUp(A.x, A.y, A.z, 1));
    }

    @Test
    public void theSecondBreakIsLonger() {
        GivenUp g = new GivenUp();
        g.giveUp(A, 0);
        assertFalse(g.isGivenUp(A.x, A.y, A.z, GivenUp.EXPIRY_TICKS));
        assertEquals(2, g.giveUp(A, 1000));
        assertTrue(g.isGivenUp(A.x, A.y, A.z, 1000 + GivenUp.SECOND_EXPIRY_TICKS - 1));
        assertFalse(g.isGivenUp(A.x, A.y, A.z, 1000 + GivenUp.SECOND_EXPIRY_TICKS));
    }

    @Test
    public void comingBackAfterAWhileGivesTheForGoodOnesOneMoreGo() {
        GivenUp g = new GivenUp();
        BetterBlockPos b = new BetterBlockPos(50, 64, 50);
        for (int i = 0; i < GivenUp.MAX_ATTEMPTS; i++) {
            g.giveUp(A, 0);
        }
        g.giveUp(b, 0);
        g.unwatched(5);
        assertFalse(g.isGivenUp(A.x, A.y, A.z, 5));
        assertEquals(GivenUp.MAX_ATTEMPTS, g.giveUp(A, 5));
        // a timed one keeps its break and its count
        assertTrue(g.isGivenUp(b.x, b.y, b.z, 5));
        assertEquals(2, g.giveUp(b, 300));
    }

    @Test
    public void aPinnedOneStaysOutWhateverChangesNextToIt() {
        GivenUp g = new GivenUp();
        g.forGood(A);
        assertTrue(g.isGivenUp(A.x, A.y, A.z, 0));
        // the build going on around it, and a stop and start, both used to put it right back
        g.nearbyChanged(A.x + 1, A.y, A.z, 10);
        g.unwatched(20);
        assertFalse(g.retryNow(30));
        assertTrue(g.isGivenUp(A.x, A.y, A.z, 40));
    }
}
