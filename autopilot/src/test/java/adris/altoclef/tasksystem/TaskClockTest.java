package adris.altoclef.tasksystem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.AltoClef;
import org.junit.Before;
import org.junit.Test;

// the time on task clock: only ticks the task ran are billed, and only an explicit reset gives them back
public class TaskClockTest {
    private TaskChain chain;

    @Before
    public void setUp() {
        chain = new TaskChain(new TaskRunner(null)) {
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
                return "test";
            }
        };
    }

    private static class Clocked extends Task {
        final double limit;
        boolean over;

        Clocked(double limit) {
            this.limit = limit;
        }

        @Override
        protected void onStart(AltoClef mod) {
        }

        @Override
        protected Task onTick(AltoClef mod) {
            over = overTimeOnTask(limit);
            return null;
        }

        @Override
        protected void onStop(AltoClef mod, Task interruptTask) {
        }

        @Override
        protected boolean isEqual(Task other) {
            return other == this;
        }

        @Override
        protected String toDebugString() {
            return "clocked";
        }

        double spent() {
            return timeOnTaskSeconds();
        }
    }

    private void ticks(Task t, int n) {
        for (int i = 0; i < n; i++) {
            t.tick(null, chain);
        }
    }

    @Test
    public void ticksSumToSeconds() {
        TaskClock clock = new TaskClock();
        for (int i = 0; i < 40; i++) {
            clock.tick();
        }
        assertEquals(2.0, clock.spentSeconds(), 1e-9);
    }

    @Test
    public void overIsStrict() {
        TaskClock clock = new TaskClock();
        for (int i = 0; i < 90 * 20; i++) {
            clock.tick();
        }
        assertFalse(clock.over(90));
        clock.tick();
        assertTrue(clock.over(90));
    }

    @Test
    public void resetIsExplicit() {
        TaskClock clock = new TaskClock();
        clock.tick();
        clock.reset();
        assertEquals(0, clock.spentSeconds(), 0);
    }

    @Test
    public void theTaskBillsEveryTickItRan() {
        Clocked t = new Clocked(90);
        ticks(t, 20 * 20);
        assertEquals(20, t.spent(), 1e-9);
    }

    @Test
    public void timeSpentInterruptedIsNotCharged() {
        Clocked t = new Clocked(90);
        ticks(t, 20 * 20);
        // mob defense had us for ten minutes: nobody ticks the task, so nothing is billed
        t.interrupt(null, null);
        t.tick(null, chain);
        assertEquals(20.05, t.spent(), 1e-9);
    }

    @Test
    public void aRestartKeepsWhatWasAlreadySpent() {
        Clocked t = new Clocked(90);
        ticks(t, 50 * 20);
        t.interrupt(null, null);
        t.reset();
        ticks(t, 30 * 20);
        assertEquals(80, t.spent(), 1e-9);
        assertFalse(t.over);
        t.stop(null);
        ticks(t, 11 * 20);
        assertTrue(t.spent() > 90);
        assertTrue(t.over);
    }

    @Test
    public void theFirstTickAfterARestartIsOneTick() {
        Clocked t = new Clocked(90);
        ticks(t, 10);
        t.interrupt(null, null);
        t.reset();
        t.tick(null, chain);
        assertEquals(11 * 0.05, t.spent(), 1e-9);
    }

    @Test
    public void manyShortRunsStillAddUpToAGiveUp() {
        Clocked t = new Clocked(90);
        boolean gaveUp = false;
        // 15 running seconds per go, a minute of being chased between them
        for (int attempt = 0; attempt < 7 && !gaveUp; attempt++) {
            for (int i = 0; i < 15 * 20 && !gaveUp; i++) {
                t.tick(null, chain);
                gaveUp |= t.over;
            }
            t.interrupt(null, null);
            t.reset();
        }
        assertTrue(gaveUp);
        assertEquals(90, t.spent(), 1.0);
    }

    @Test
    public void aTaskInterruptedAfterEveryTickStillRunsOutOfBudget() {
        Clocked t = new Clocked(1);
        boolean gaveUp = false;
        for (int i = 0; i < 100 && !gaveUp; i++) {
            t.tick(null, chain);
            gaveUp = t.over;
            t.interrupt(null, null);
            t.reset();
        }
        assertTrue(gaveUp);
    }

    @Test
    public void resetOnTheTaskDoesNotClearTheClock() {
        Clocked t = new Clocked(90);
        ticks(t, 100);
        t.reset();
        assertEquals(5, t.spent(), 1e-9);
    }
}
