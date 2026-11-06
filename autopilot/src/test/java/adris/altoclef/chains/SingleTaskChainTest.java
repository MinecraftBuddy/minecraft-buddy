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

package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskRunner;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

// the "an equal task wins even when it is dead" rule. a finished run used to be kept over the fresh one that was asked for
public class SingleTaskChainTest {

    private SingleTaskChain chain;

    @Before
    public void setUp() {
        chain = new SingleTaskChain(new TaskRunner(null)) {
            @Override
            protected void onTaskFinish(AltoClef mod) {
            }

            @Override
            public float getPriority(AltoClef mod) {
                return 0;
            }

            @Override
            public String getName() {
                return "test";
            }
        };
    }

    // equal by id, finished when told to be
    private static class Fake extends Task {
        final String id;
        boolean finished;

        Fake(String id) {
            this.id = id;
        }

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
        public boolean isFinished(AltoClef mod) {
            return finished;
        }

        @Override
        protected boolean isEqual(Task other) {
            return other instanceof Fake f && f.id.equals(id);
        }

        @Override
        protected String toDebugString() {
            return id;
        }
    }

    @Test
    public void anEqualTaskThatIsStillRunningIsKept() {
        Fake first = new Fake("run");
        chain.setTask(first);
        chain.setTask(new Fake("run"));
        assertSame(first, chain.getCurrentTask());
    }

    @Test
    public void anEqualTaskThatIsFinishedGetsReplaced() {
        Fake dead = new Fake("run");
        chain.setTask(dead);
        dead.finished = true;
        Fake fresh = new Fake("run");
        chain.setTask(fresh);
        assertSame(fresh, chain.getCurrentTask());
    }

    @Test
    public void anEqualTaskThatWasStoppedGetsReplaced() {
        Fake first = new Fake("run");
        chain.setTask(first);
        first.tick(null, chain);
        first.stop(null);
        Fake fresh = new Fake("run");
        chain.setTask(fresh);
        assertSame(fresh, chain.getCurrentTask());
    }

    @Test
    public void aDifferentTaskReplacesAsBefore() {
        chain.setTask(new Fake("a"));
        Fake other = new Fake("b");
        chain.setTask(other);
        assertSame(other, chain.getCurrentTask());
    }

    @Test
    public void theSameObjectAgainIsLeftAloneEvenWhenFinished() {
        Fake only = new Fake("run");
        chain.setTask(only);
        only.finished = true;
        chain.setTask(only);
        assertSame(only, chain.getCurrentTask());
    }

    @Test
    public void nullClearsIt() {
        chain.setTask(new Fake("run"));
        chain.setTask(null);
        assertNull(chain.getCurrentTask());
    }
}
