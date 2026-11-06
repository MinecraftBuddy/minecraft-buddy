package adris.altoclef.tasks.movement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.tasksystem.TaskRunner;
import org.junit.Before;
import org.junit.Test;

// the wander's clock and anchor have to outlive an interrupt, or a wander that is going nowhere restarts its budget
public class WanderFailingTest {
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

    // a real wander with the world taken out: the real onStart and onTick want a player. the clock, the excursion and
    // the decision are all the real ones
    private static class Driven extends TimeoutWanderTask {
        double x;

        @Override
        protected void onStart(AltoClef mod) {
        }

        @Override
        protected Task onTick(AltoClef mod) {
            noteAt(x, 64, 0);
            return null;
        }

        @Override
        protected void onStop(AltoClef mod, Task interruptTask) {
        }
    }

    // a parent that hands out whatever child it was given, like most of the tasks that wander
    private static class Holder extends Task {
        final Task child;

        Holder(Task child) {
            this.child = child;
        }

        @Override
        protected void onStart(AltoClef mod) {
        }

        @Override
        protected Task onTick(AltoClef mod) {
            return child;
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
            return "holder";
        }
    }

    private void ticks(Task t, int n) {
        for (int i = 0; i < n; i++) {
            t.tick(null, chain);
        }
    }

    @Test
    public void numbersOverloadFollowsTheLine() {
        assertFalse(TimeoutWanderTask.failing(45, 0));
        assertTrue(TimeoutWanderTask.failing(45.05, 2.9));
        assertFalse(TimeoutWanderTask.failing(500, 3));
    }

    @Test
    public void excursionIsAThreeDimensionalRunningMax() {
        WanderGiveUp.Excursion e = new WanderGiveUp.Excursion();
        e.note(10, 64, 10);
        assertEquals(0, e.max(), 0);
        e.note(10, 64, 14);
        e.note(10, 64, 11);
        assertEquals(4, e.max(), 1e-9);
        e.note(10, 60, 10);
        assertEquals(4, e.max(), 1e-9);
        e.note(10, 69, 10);
        assertEquals(5, e.max(), 1e-9);
    }

    @Test
    public void interruptsDoNotBuyAFreshBudget() {
        Driven w = new Driven();
        // 30 s, interrupted, 30 s more. every restart used to look like a new wander
        for (int attempt = 0; attempt < 2; attempt++) {
            ticks(w, 30 * 20);
            w.interrupt(null, null);
            w.reset();
        }
        assertTrue(w.failing());
    }

    @Test
    public void youngWanderStaysOkAcrossARestart() {
        Driven w = new Driven();
        ticks(w, 20 * 20);
        w.interrupt(null, null);
        w.reset();
        ticks(w, 20 * 20);
        assertFalse(w.failing());
    }

    @Test
    public void theAnchorSurvivesARestartSoAFarTripStillCounts() {
        Driven w = new Driven();
        ticks(w, 10);
        w.interrupt(null, null);
        w.reset();
        // came back from the restart four blocks from where the first run began
        w.x = 4;
        ticks(w, 50 * 20);
        assertFalse(w.failing());
    }

    @Test
    public void anExcursionIsKeptEvenAfterWalkingBack() {
        Driven w = new Driven();
        ticks(w, 1);
        w.x = 3.5;
        ticks(w, 1);
        w.x = 0;
        ticks(w, 50 * 20);
        assertFalse(w.failing());
    }

    @Test
    public void pacingInPlaceFails() {
        Driven w = new Driven();
        for (int i = 0; i < 50 * 20; i++) {
            w.x = (i % 2) * 1.5;
            w.tick(null, chain);
        }
        assertTrue(w.failing());
    }

    @Test
    public void aFailingWanderTwoLevelsDownShowsInTheRoot() {
        Driven w = new Driven();
        Holder root = new Holder(new Holder(w));
        ticks(root, 46 * 20);
        assertTrue(root.thisOrChildWanderFailing());
        // the old question stays true the whole time, failing or not
        assertTrue(root.thisOrChildAreTimedOut());
    }

    @Test
    public void aYoungWanderIsNotFailingInTheTree() {
        Holder root = new Holder(new Holder(new Driven()));
        ticks(root, 20 * 20);
        assertFalse(root.thisOrChildWanderFailing());
        assertTrue(root.thisOrChildAreTimedOut());
    }

    @Test
    public void aWanderThatGotSomewhereIsNotFailingInTheTree() {
        Driven w = new Driven();
        Holder root = new Holder(w);
        ticks(root, 1);
        w.x = 5;
        ticks(root, 60 * 20);
        assertFalse(root.thisOrChildWanderFailing());
    }

    @Test
    public void aTreeWithNoWanderIsNeverFailing() {
        Holder root = new Holder(new Holder(new Holder(null)));
        ticks(root, 100 * 20);
        assertFalse(root.thisOrChildWanderFailing());
    }

    @Test
    public void theRootCanBeTheWander() {
        Driven w = new Driven();
        ticks(w, 46 * 20);
        assertTrue(w.thisOrChildWanderFailing());
    }
}
