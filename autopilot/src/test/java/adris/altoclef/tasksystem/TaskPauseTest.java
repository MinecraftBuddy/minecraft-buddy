package adris.altoclef.tasksystem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.AltoClef;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.Before;
import org.junit.Test;

// interrupt (a chain takes over for a bit) versus stop, and the child that gets dropped after it threw
public class TaskPauseTest {
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

    private static class Probe extends Task {
        final List<String> log = new ArrayList<>();
        Supplier<Task> child = () -> null;

        @Override
        protected void onStart(AltoClef mod) {
            log.add("start");
        }

        @Override
        protected Task onTick(AltoClef mod) {
            return child.get();
        }

        @Override
        protected void onStop(AltoClef mod, Task interruptTask) {
            log.add(isInterrupting() ? "stop(interrupt)" : "stop(real)");
        }

        @Override
        protected void onStopWhilePaused(AltoClef mod) {
            log.add("stopWhilePaused");
        }

        @Override
        protected boolean isEqual(Task other) {
            return other == this;
        }

        @Override
        protected String toDebugString() {
            return "probe";
        }

        void dropIt() {
            dropChild(null);
        }
    }

    // always the same class and equal to its own kind, like LocateStrongholdTask: isEqual would keep a broken one forever
    private static class Leaf extends Task {
        int starts;
        int stops;

        @Override
        protected void onStart(AltoClef mod) {
            starts++;
        }

        @Override
        protected Task onTick(AltoClef mod) {
            return null;
        }

        @Override
        protected void onStop(AltoClef mod, Task interruptTask) {
            stops++;
        }

        @Override
        protected boolean isEqual(Task other) {
            return other instanceof Leaf;
        }

        @Override
        protected String toDebugString() {
            return "leaf";
        }
    }

    @Test
    public void anInterruptIsToldApartFromARealStop() {
        Probe p = new Probe();
        p.tick(null, chain);
        p.interrupt(null, null);
        assertEquals(List.of("start", "stop(interrupt)"), p.log);
        // it comes back
        p.tick(null, chain);
        p.stop(null);
        assertEquals(List.of("start", "stop(interrupt)", "start", "stop(real)"), p.log);
    }

    @Test
    public void aRealStopOfAPausedTaskStillGetsACleanUp() {
        Probe p = new Probe();
        p.tick(null, chain);
        p.interrupt(null, null);
        // #stop in the middle of a fight: the task is not running, so Task.stop skips onStop. the pause hook replaces it
        p.stop(null);
        assertEquals(List.of("start", "stop(interrupt)", "stopWhilePaused"), p.log);
        assertFalse(p.isActive());
        // and only once
        p.stop(null);
        assertEquals(3, p.log.size());
    }

    @Test
    public void aTaskThatCameBackIsNotPausedAnymore() {
        Probe p = new Probe();
        p.tick(null, chain);
        p.interrupt(null, null);
        p.tick(null, chain);
        p.stop(null);
        assertFalse(p.log.contains("stopWhilePaused"));
    }

    @Test
    public void resetClearsThePause() {
        Probe p = new Probe();
        p.tick(null, chain);
        p.interrupt(null, null);
        p.reset();
        p.stop(null);
        assertFalse(p.log.contains("stopWhilePaused"));
    }

    @Test
    public void aTaskThatNeverRanHasNothingToCleanUp() {
        Probe p = new Probe();
        p.stop(null);
        p.interrupt(null, null);
        assertTrue(p.log.isEmpty());
    }

    @Test
    public void aDroppedChildIsStoppedAndReplacedEvenWhenTheNewOneIsEqual() {
        Probe parent = new Probe();
        Leaf first = new Leaf();
        Leaf second = new Leaf();
        Task[] hand = {first};
        parent.child = () -> hand[0];
        parent.tick(null, chain);
        assertEquals(1, first.starts);
        // without a drop the equal second leaf would never replace the first
        hand[0] = second;
        parent.tick(null, chain);
        assertEquals(0, second.starts);
        parent.dropIt();
        assertEquals(1, first.stops);
        parent.tick(null, chain);
        assertEquals(1, second.starts);
    }

    @Test
    public void droppingWithoutAChildIsHarmless() {
        new Probe().dropIt();
    }
}
