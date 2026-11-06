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

package adris.altoclef.tasksystem;

import adris.altoclef.AltoClef;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HeldWheelWatchTest {

    private static TaskChain chain(String name) {
        return new TaskChain(new TaskRunner(null)) {
            @Override
            protected void onStop(AltoClef mod) {
            }

            @Override
            public void onInterrupt(AltoClef mod, TaskChain other) {
            }

            @Override
            protected void onTick(AltoClef mod) {
            }

            @Override
            public float getPriority(AltoClef mod) {
                return 0;
            }

            @Override
            public boolean isActive() {
                return true;
            }

            @Override
            public String getName() {
                return name;
            }
        };
    }

    @Test
    public void quietForLongEnoughSpeaksUpOnceAndOnlyOnce() {
        HeldWheelWatch watch = new HeldWheelWatch();
        TaskChain mob = chain("mob");
        assertFalse(watch.shouldLog(mob, false, 0));
        assertFalse(watch.shouldLog(mob, false, HeldWheelWatch.EMPTY_MS));
        assertTrue(watch.shouldLog(mob, false, HeldWheelWatch.EMPTY_MS + 1));
        assertFalse("already said it", watch.shouldLog(mob, false, HeldWheelWatch.EMPTY_MS * 10));
    }

    @Test
    public void aTaskRunningRearmsIt() {
        HeldWheelWatch watch = new HeldWheelWatch();
        TaskChain mob = chain("mob");
        watch.shouldLog(mob, false, 0);
        assertTrue(watch.shouldLog(mob, false, 6_000));
        watch.shouldLog(mob, true, 7_000);
        assertFalse("clock starts over after something ran", watch.shouldLog(mob, false, 7_500));
        assertFalse(watch.shouldLog(mob, false, 12_000));
        assertTrue(watch.shouldLog(mob, false, 12_600));
    }

    @Test
    public void aDifferentChainStartsItsOwnClock() {
        HeldWheelWatch watch = new HeldWheelWatch();
        TaskChain mob = chain("mob");
        TaskChain food = chain("food");
        watch.shouldLog(mob, false, 0);
        assertFalse(watch.shouldLog(food, false, 6_000));
        assertTrue(watch.shouldLog(food, false, 11_100));
    }

    @Test
    public void nobodyWinningNeverLogs() {
        HeldWheelWatch watch = new HeldWheelWatch();
        assertFalse(watch.shouldLog(null, false, 0));
        assertFalse(watch.shouldLog(null, false, 60_000));
    }
}
