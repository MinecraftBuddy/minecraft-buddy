package adris.altoclef.tasks.speedrun.gamer.end;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.speedrun.gamer.GamerPhase;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.ItemFixture;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class SlayDragonTaskTest {
    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    // stands in for the phase's step, whatever it is (the beds, the sword, the walk into the exit portal)
    private static final Task STEP = new Task() {
        @Override
        protected void onStart(AltoClef mod) {
        }

        @Override
        protected Task onTick(AltoClef mod) {
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
            return "a step";
        }
    };

    // the phase's step while the dragon lives; nothing once it is dead, so the exit portal that ends a run is never walked into
    @Test
    public void neverTheExitPortalOnceDead() {
        assertSame(STEP, SlayDragonTask.afterTick(STEP, false));
        assertNull(SlayDragonTask.afterTick(STEP, true));
    }

    @Test
    public void doneWhenTheDragonIsDead() {
        SlayDragonTask t = new SlayDragonTask();
        assertEquals(GamerPhase.DRAGON, t.state().phase);
        assertFalse(t.isFinished(null));
        t.state().dragonDead = true;
        assertTrue(t.isFinished(null));
        assertTrue(t.dragonDead());
    }

    // the phase gives up (the dragon killed us too often): the task ends and says why
    @Test
    public void aFailEndsIt() {
        SlayDragonTask t = new SlayDragonTask();
        t.fail("the dragon killed us 3 times");
        assertTrue(t.isFinished(null));
        assertEquals("the dragon killed us 3 times", t.failed());
    }
}
