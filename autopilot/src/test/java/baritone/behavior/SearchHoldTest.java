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

public class SearchHoldTest {
    // PathingBehavior's side of it, as far as the decisions go: a cancel, then the ask, then the end of the tick
    private static final class Rig {
        final SearchHold hold = new SearchHold();
        int searchesStarted;

        // what a caller that cancels and asks again does in one tick. the cancel only happens with a search in the slot
        void tick(long tick, Object askedFor, boolean cancels) {
            if (cancels && hold.isLive()) {
                if (hold.cancel(tick) == SearchHold.Verdict.CANCEL) {
                    // the thread clears the slot a moment later, it is gone before the ask in this rig
                    assertFalse(hold.isLive());
                }
            }
            if (askedFor != null) {
                if (hold.isPending()) {
                    if (!hold.resume(askedFor)) {
                        hold.dropped();
                        start(askedFor, tick);
                    }
                } else if (!hold.isLive()) {
                    start(askedFor, tick);
                }
            }
            if (hold.expired(tick)) {
                hold.dropped();
            }
        }

        void start(Object key, long tick) {
            searchesStarted++;
            hold.started(key, tick);
        }
    }

    @Test
    public void aCancelAndTheSameAskEveryTickIsOneSearchAfterAFewTries() {
        Rig rig = new Rig();
        for (long t = 1; t <= 200; t++) {
            rig.tick(t, "goal block", true);
        }
        // the first few get cancelled the way they always did, then the search is held and stays the one
        assertEquals(SearchHold.STORM_STARTS + 1, rig.searchesStarted);
        assertTrue(rig.hold.isLive());
    }

    @Test
    public void theHeldSearchStillEndsOnItsOwn() {
        Rig rig = new Rig();
        for (long t = 1; t <= 50; t++) {
            rig.tick(t, "goal block", true);
        }
        int before = rig.searchesStarted;
        rig.hold.ended();
        assertFalse(rig.hold.isLive());
        assertFalse(rig.hold.isPending());
        // and the next ask is a new search, not a ghost of the old one
        rig.tick(51, "goal block", false);
        assertEquals(before + 1, rig.searchesStarted);
    }

    @Test
    public void aSearchLeftAloneIsNeverHeld() {
        Rig rig = new Rig();
        for (long t = 1; t <= 200; t++) {
            rig.tick(t, "goal block", false);
        }
        assertEquals(1, rig.searchesStarted);
        assertFalse(rig.hold.isPending());
    }

    @Test
    public void aGoalThatKeepsChangingIsNeverHeld() {
        Rig rig = new Rig();
        for (long t = 1; t <= 100; t++) {
            rig.tick(t, "goal " + t, true);
        }
        // every tick is a real change and every one of them gets its search
        assertEquals(100, rig.searchesStarted);
        assertFalse(rig.hold.isPending());
    }

    @Test
    public void cancelsOfSearchesThatGotSomewhereDoNotCount() {
        Rig rig = new Rig();
        long t = 1;
        for (int round = 0; round < 20; round++) {
            rig.tick(t, "goal block", false);
            t += SearchHold.YOUNG_TICKS + 5;
            // it ran a good while before it got cancelled
            rig.tick(t, "goal block", true);
            t++;
        }
        // the first one plus a restart for every cancel
        assertEquals(21, rig.searchesStarted);
        assertFalse(rig.hold.isPending());
    }

    @Test
    public void aStopOnAHeldSearchGoesThroughAtTheEndOfTheNextTick() {
        Rig rig = new Rig();
        long t = 1;
        for (; t <= 20; t++) {
            rig.tick(t, "goal block", true);
        }
        // the cancel is held, nobody asks again
        assertEquals(SearchHold.Verdict.HOLD, rig.hold.cancel(t));
        assertTrue(rig.hold.isPending());
        assertFalse(rig.hold.expired(t));
        assertTrue(rig.hold.expired(t + 1));
        rig.hold.dropped();
        assertFalse(rig.hold.isLive());
        // and the next stop is a plain cancel again, the streak is over
        rig.hold.started("goal block", t + 2);
        assertEquals(SearchHold.Verdict.CANCEL, rig.hold.cancel(t + 3));
    }

    @Test
    public void aDifferentAskWhileHeldDoesNotResume() {
        Rig rig = new Rig();
        for (long t = 1; t <= 20; t++) {
            rig.tick(t, "goal block", true);
        }
        assertEquals(SearchHold.Verdict.HOLD, rig.hold.cancel(21));
        assertFalse(rig.hold.resume("some other goal"));
        assertTrue(rig.hold.isPending());
        assertTrue(rig.hold.resume("goal block"));
        assertFalse(rig.hold.isPending());
    }

    @Test
    public void aCancelThatIsAlreadyHeldStaysOneCancel() {
        Rig rig = new Rig();
        for (long t = 1; t <= 20; t++) {
            rig.tick(t, "goal block", true);
        }
        // cancelEverything and forceCancel both cancel, in the same tick
        assertEquals(SearchHold.Verdict.HOLD, rig.hold.cancel(21));
        assertEquals(SearchHold.Verdict.HOLD, rig.hold.cancel(21));
        assertFalse(rig.hold.expired(21));
        assertTrue(rig.hold.resume("goal block"));
    }

    @Test
    public void aDroppedOrEndedHoldNeedsAFreshStormToComeBack() {
        for (boolean ended : new boolean[]{false, true}) {
            Rig rig = new Rig();
            for (long t = 1; t <= 20; t++) {
                rig.tick(t, "goal block", true);
            }
            if (ended) {
                rig.hold.ended();
            } else {
                rig.hold.dropped();
            }
            int before = rig.searchesStarted;
            // the same storm again: it takes its three plain cancels before it is held
            for (long t = 30; t <= 60; t++) {
                rig.tick(t, "goal block", true);
            }
            assertEquals(before + SearchHold.STORM_STARTS + 1, rig.searchesStarted);
        }
    }

    @Test
    public void theYoungLimitIsInclusive() {
        for (long age = SearchHold.YOUNG_TICKS; age <= SearchHold.YOUNG_TICKS + 1; age++) {
            SearchHold hold = new SearchHold();
            long t = 1;
            for (int i = 0; i < SearchHold.STORM_STARTS; i++) {
                hold.started("goal block", t);
                t += age;
                assertEquals(SearchHold.Verdict.CANCEL, hold.cancel(t));
                t++;
            }
            hold.started("goal block", t);
            SearchHold.Verdict next = hold.cancel(t + 1);
            // cancelled right at the limit counts as young, one tick later does not
            assertEquals(age == SearchHold.YOUNG_TICKS ? SearchHold.Verdict.HOLD : SearchHold.Verdict.CANCEL, next);
        }
    }

    @Test
    public void oneSearchThatGotSomewhereBreaksTheStreak() {
        SearchHold hold = new SearchHold();
        long t = 1;
        // young, young, old, young: the old one starts the count over, so there is no hold yet
        long[] ages = {1, 1, 10, 1};
        for (long age : ages) {
            hold.started("goal block", t);
            t += age;
            assertEquals(SearchHold.Verdict.CANCEL, hold.cancel(t));
            t++;
        }
        hold.started("goal block", t);
        assertEquals(SearchHold.Verdict.CANCEL, hold.cancel(t + 1));
    }

    @Test
    public void nothingInTheSlotNothingToHold() {
        SearchHold hold = new SearchHold();
        assertEquals(SearchHold.Verdict.CANCEL, hold.cancel(5));
        assertFalse(hold.expired(100));
        assertFalse(hold.resume("goal block"));
    }
}
