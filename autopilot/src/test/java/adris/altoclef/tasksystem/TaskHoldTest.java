package adris.altoclef.tasksystem;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.AltoClef;
import adris.altoclef.chains.SingleTaskChain;
import adris.altoclef.control.FakeKeys;
import adris.altoclef.control.InputControls;
import baritone.api.utils.input.Input;
import org.junit.Before;
import org.junit.Test;

// a task that holds a key lets go of it when it stops, is interrupted or is finished, and only then
public class TaskHoldTest {
    private FakeKeys keys;
    private InputControls controls;
    private AltoClef mod;
    private TaskChain chain;

    // the real thing needs a game to start, this one only has to hand out its input controls
    private static class FakeMod extends AltoClef {
        private final InputControls controls;

        FakeMod(InputControls controls) {
            this.controls = controls;
        }

        @Override
        public InputControls getInputControls() {
            return controls;
        }
    }

    @Before
    public void setUp() {
        keys = new FakeKeys();
        controls = new InputControls(keys);
        mod = new FakeMod(controls);
        chain = new TaskChain(new TaskRunner(mod)) {
            @Override
            protected void onStop(AltoClef m) {
            }

            @Override
            public void onInterrupt(AltoClef m, TaskChain other) {
            }

            @Override
            protected void onTick(AltoClef m) {
            }

            @Override
            public float getPriority(AltoClef m) {
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

    private static class Holder extends Task {
        final Input key;
        final String name;
        Task child;
        Input holdOnStop;

        Holder(String name, Input key) {
            this.name = name;
            this.key = key;
        }

        @Override
        protected void onStart(AltoClef m) {
        }

        @Override
        protected Task onTick(AltoClef m) {
            if (key != null) {
                m.getInputControls().hold(key);
            }
            return child;
        }

        @Override
        protected void onStop(AltoClef m, Task interruptTask) {
            if (holdOnStop != null) {
                m.getInputControls().hold(holdOnStop);
            }
        }

        @Override
        protected boolean isEqual(Task other) {
            return other == this;
        }

        @Override
        protected String toDebugString() {
            return name;
        }
    }

    @Test
    public void aHoldIsLetGoOfWhenTheTaskIsInterrupted() {
        Holder walker = new Holder("walker", Input.MOVE_FORWARD);
        walker.tick(mod, chain);
        assertTrue(keys.isDown(Input.MOVE_FORWARD));
        walker.interrupt(mod, null);
        assertFalse(keys.isDown(Input.MOVE_FORWARD));
        // and it takes the key again when it comes back
        walker.tick(mod, chain);
        assertTrue(keys.isDown(Input.MOVE_FORWARD));
    }

    @Test
    public void aHoldIsLetGoOfWhenTheTaskStops() {
        Holder sneaker = new Holder("sneaker", Input.SNEAK);
        sneaker.tick(mod, chain);
        sneaker.stop(mod);
        assertFalse(keys.isDown(Input.SNEAK));
    }

    @Test
    public void aChildsHoldGoesWhenTheParentStops() {
        Holder parent = new Holder("parent", null);
        Holder child = new Holder("child", Input.SNEAK);
        parent.child = child;
        parent.tick(mod, chain);
        assertTrue(keys.isDown(Input.SNEAK));
        parent.stop(mod);
        assertFalse(keys.isDown(Input.SNEAK));
    }

    @Test
    public void aParentsHoldStaysWhileItsChildRunsAndStops() {
        Holder parent = new Holder("parent", Input.SNEAK);
        Holder child = new Holder("child", Input.SNEAK);
        parent.child = child;
        parent.tick(mod, chain);
        // the child goes away, the parent still has its hand on the key
        parent.child = null;
        parent.tick(mod, chain);
        assertTrue(keys.isDown(Input.SNEAK));
        parent.stop(mod);
        assertFalse(keys.isDown(Input.SNEAK));
    }

    @Test
    public void aHoldMadeInOnStopStaysDown() {
        Holder t = new Holder("t", null);
        t.holdOnStop = Input.SNEAK;
        t.tick(mod, chain);
        t.stop(mod);
        // nobody owns it, exactly what happens today
        assertTrue(keys.isDown(Input.SNEAK));
    }

    @Test
    public void aHoldMadeInOnStopOfAChildDoesNotBelongToTheParent() {
        Holder parent = new Holder("parent", null);
        Holder child = new Holder("child", null);
        child.holdOnStop = Input.SNEAK;
        parent.child = child;
        parent.tick(mod, chain);
        parent.stop(mod);
        assertTrue(keys.isDown(Input.SNEAK));
    }

    @Test
    public void twoOwnersKeepTheKeyUntilTheLastOneStops() {
        Holder a = new Holder("a", Input.SNEAK);
        Holder b = new Holder("b", Input.SNEAK);
        a.tick(mod, chain);
        b.tick(mod, chain);
        a.stop(mod);
        assertTrue(keys.isDown(Input.SNEAK));
        b.stop(mod);
        assertFalse(keys.isDown(Input.SNEAK));
    }

    @Test
    public void aHoldWithNoTaskBehindItSurvivesATasksStop() {
        Holder t = new Holder("t", Input.SNEAK);
        // a chain holds it from getPriority, outside any task
        controls.hold(Input.SNEAK);
        t.tick(mod, chain);
        t.stop(mod);
        assertTrue(keys.isDown(Input.SNEAK));
    }

    @Test
    public void aPlainReleaseClearsItWhoeverHoldsIt() {
        Holder a = new Holder("a", Input.SNEAK);
        Holder b = new Holder("b", Input.SNEAK);
        a.tick(mod, chain);
        b.tick(mod, chain);
        controls.release(Input.SNEAK);
        assertFalse(keys.isDown(Input.SNEAK));
        // the claims went with it, so a later stop does not let go of a key somebody else took
        keys.setDown(Input.SNEAK, true);
        a.stop(mod);
        assertTrue(keys.isDown(Input.SNEAK));
    }

    @Test
    public void aStaleChainFlagDoesNotKeepATasksKeyDown() {
        // a chain held it, then the game let every key go (a screen opened) without anybody calling release
        controls.hold(Input.SNEAK);
        keys.setDown(Input.SNEAK, false);
        Holder t = new Holder("t", Input.SNEAK);
        t.tick(mod, chain);
        t.stop(mod);
        assertFalse(keys.isDown(Input.SNEAK));
    }

    @Test
    public void theOwnerComesBackAfterAnOnTickThatThrows() {
        Holder boom = new Holder("boom", null) {
            @Override
            protected Task onTick(AltoClef m) {
                throw new IllegalStateException("boom");
            }
        };
        try {
            boom.tick(mod, chain);
        } catch (IllegalStateException expected) {
            // that is the point
        }
        assertTrue(Task.currentOwner() == null);
    }

    @Test
    public void aChildStoppedFromInsideItsParentsOnTickHoldsNothingForTheParent() {
        Holder kid = new Holder("child", null);
        kid.holdOnStop = Input.SNEAK;
        boolean[] drop = {false};
        Holder parent = new Holder("parent", null) {
            @Override
            protected Task onTick(AltoClef m) {
                if (drop[0]) {
                    // dropChild is the one stop that runs inside an onTick stack
                    dropChild(m);
                    return null;
                }
                return kid;
            }
        };
        parent.tick(mod, chain);
        drop[0] = true;
        parent.tick(mod, chain);
        // the child's onStop held it while the parent's onTick was running, but it is nobody's claim
        assertTrue(keys.isDown(Input.SNEAK));
        parent.stop(mod);
        assertTrue(keys.isDown(Input.SNEAK));
    }

    @Test
    public void aStopWithoutAModIsHarmless() {
        Holder t = new Holder("t", null);
        t.tick(null, chain);
        t.interrupt(null, null);
        t.stop(null);
    }

    @Test
    public void aFinishedRootLetsGoOfItsWholeTree() {
        Holder root = new Holder("root", Input.MOVE_FORWARD);
        Holder child = new Holder("child", Input.SNEAK);
        root.child = child;
        root.tick(mod, chain);
        root.letGoOfKeys(mod);
        assertFalse(keys.isDown(Input.MOVE_FORWARD));
        assertFalse(keys.isDown(Input.SNEAK));
    }

    @Test
    public void aRootThatFinishesInTheChainLetsGoWithoutBeingStopped() {
        boolean[] finished = {false};
        int[] stops = {0};
        SingleTaskChain single = new SingleTaskChain(new TaskRunner(mod)) {
            @Override
            protected void onTaskFinish(AltoClef m) {
                _mainTask = null;
            }

            @Override
            public float getPriority(AltoClef m) {
                return 0;
            }

            @Override
            public String getName() {
                return "single";
            }
        };
        Holder root = new Holder("root", Input.SNEAK) {
            @Override
            public boolean isFinished(AltoClef m) {
                return finished[0];
            }

            @Override
            protected void onStop(AltoClef m, Task interruptTask) {
                stops[0]++;
            }
        };
        single.setTask(root);
        single.tick(mod);
        assertTrue(keys.isDown(Input.SNEAK));
        finished[0] = true;
        single.tick(mod);
        assertFalse(keys.isDown(Input.SNEAK));
        // letting go is not a stop
        assertTrue(stops[0] == 0);
    }
}
