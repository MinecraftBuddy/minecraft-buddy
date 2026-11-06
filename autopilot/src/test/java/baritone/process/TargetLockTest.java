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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class TargetLockTest {

    // a stand in for an entity: same name = same uuid, dist is how far from us
    private static final class P {
        final String name;
        final int id;
        double dist;

        P(String name, int id, double dist) {
            this.name = name;
            this.id = id;
            this.dist = dist;
        }
    }

    private static TargetLock<P> lock() {
        return new TargetLock<>(p -> p.name, p -> p.dist * p.dist, Comparator.comparingInt(p -> p.id));
    }

    private static List<P> list(P... ps) {
        return new ArrayList<>(List.of(ps));
    }

    @Test
    public void picksTheNearestWithNothingLocked() {
        P near = new P("near", 1, 5), far = new P("far", 2, 40);
        assertSame(near, lock().update(list(far, near), 0, false));
    }

    @Test
    public void keepsTheLockedOneEvenWhenSomeoneElseGetsCloser() {
        TargetLock<P> lock = lock();
        P a = new P("a", 1, 5), b = new P("b", 2, 10);
        assertSame(a, lock.update(list(a, b), 0, false));
        // the whole bug: b behind us gets cheaper and the bot turns around
        a.dist = 30;
        b.dist = 2;
        for (int t = 1; t < 100; t++) {
            assertSame(a, lock.update(list(b, a), t, false));
        }
    }

    @Test
    public void followsAFreshObjectWithTheSameKey() {
        TargetLock<P> lock = lock();
        P a = new P("a", 1, 5);
        lock.update(list(a), 0, false);
        // the client re-adds an entity as a new object after a chunk edge
        P again = new P("a", 7, 6);
        assertSame(again, lock.update(list(new P("b", 2, 1), again), 1, false));
    }

    @Test
    public void waitsOutTheGraceBeforeRepicking() {
        TargetLock<P> lock = lock();
        P a = new P("a", 1, 5), b = new P("b", 2, 3);
        lock.update(list(a), 100, false);
        for (int t = 101; t < 101 + TargetLock.GRACE_TICKS; t++) {
            assertSame("tick " + t, a, lock.update(list(b), t, false));
        }
        assertSame(b, lock.update(list(b), 101 + TargetLock.GRACE_TICKS, false));
    }

    @Test
    public void graceIsTicksNotCalls() {
        TargetLock<P> lock = lock();
        P a = new P("a", 1, 5), b = new P("b", 2, 3);
        lock.update(list(a), 0, false);
        // isActive and onTick both scan every tick, a hundred calls on one tick are still one tick
        for (int i = 0; i < 100; i++) {
            assertSame(a, lock.update(list(b), 1, false));
        }
    }

    @Test
    public void comingBackInsideTheGraceKeepsIt() {
        TargetLock<P> lock = lock();
        P a = new P("a", 1, 5), b = new P("b", 2, 3);
        lock.update(list(a), 0, false);
        lock.update(list(b), 1, false);
        assertSame(a, lock.update(list(a, b), 5, false));
        // and the grace starts over next time it goes missing
        assertSame(a, lock.update(list(b), 6, false));
        assertSame(a, lock.update(list(b), 6 + TargetLock.GRACE_TICKS - 1, false));
        assertSame(b, lock.update(list(b), 6 + TargetLock.GRACE_TICKS, false));
    }

    @Test
    public void aTickCountThatStartedOverEndsTheGrace() {
        TargetLock<P> lock = lock();
        P a = new P("a", 1, 5), b = new P("b", 2, 3);
        lock.update(list(a), 5000, false);
        assertSame(a, lock.update(list(b), 5001, false));
        // respawned, tickCount is back near zero. the old world's entity is not coming back
        assertSame(b, lock.update(list(b), 3, false));
    }

    @Test
    public void aDeadTargetIsDroppedRightAway() {
        TargetLock<P> lock = lock();
        P a = new P("a", 1, 5), b = new P("b", 2, 30);
        lock.update(list(a), 0, false);
        assertSame(b, lock.update(list(b), 1, true));
    }

    @Test
    public void nothingLeftAfterTheGraceIsNothing() {
        TargetLock<P> lock = lock();
        lock.update(list(new P("a", 1, 5)), 0, false);
        // the grace counts from the first tick it was missing, not from when it was locked
        assertTrue(lock.update(list(), 1, false) != null);
        assertTrue(lock.update(list(), TargetLock.GRACE_TICKS, false) != null);
        assertNull(lock.update(list(), 1 + TargetLock.GRACE_TICKS, false));
        assertNull(lock.locked());
    }

    @Test
    public void tiesGoToTheComparatorNotTheListOrder() {
        P one = new P("one", 1, 8), two = new P("two", 2, 8);
        assertSame(one, lock().update(list(two, one), 0, false));
        assertSame(one, lock().update(list(one, two), 0, false));
    }

    @Test
    public void clearForgetsTheLock() {
        TargetLock<P> lock = lock();
        P a = new P("a", 1, 5), b = new P("b", 2, 3);
        lock.update(list(a), 0, false);
        lock.clear();
        assertNull(lock.locked());
        // a fresh follow picks the nearest, no grace for the old one
        assertSame(b, lock.update(list(b), 1, false));
    }

    @Test
    public void followLocksOneButPickupGetsEveryItem() {
        TargetLock<P> lock = lock();
        P a = new P("a", 1, 5), b = new P("b", 2, 3);
        List<P> valid = list(a, b);
        assertEquals(List.of(b), FollowProcess.targets(false, lock, valid, 0, false));
        TargetLock<P> items = lock();
        assertSame(valid, FollowProcess.targets(true, items, valid, 0, false));
        assertNull(items.locked());
        assertEquals(List.of(), FollowProcess.targets(false, lock(), list(), 0, false));
    }
}
