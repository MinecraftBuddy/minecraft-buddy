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
import org.junit.Before;
import org.junit.Test;

import java.util.function.Supplier;

import static org.junit.Assert.*;

public class TaskInterruptTest {

    private boolean forceOn;
    private TaskChain chain;

    @Before
    public void setUp() {
        forceOn = false;
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

    // a task that is only its own id, so isEqual is "same id" and a different id means "swap me"
    private static class Node extends Task {
        final String id;
        Supplier<Task> child = () -> null;

        Node(String id) {
            this.id = id;
        }

        @Override
        protected void onStart(AltoClef mod) {
        }

        @Override
        protected Task onTick(AltoClef mod) {
            return child.get();
        }

        @Override
        protected void onStop(AltoClef mod, Task interruptTask) {
        }

        @Override
        protected boolean isEqual(Task other) {
            return other instanceof Node n && n.id.equals(id);
        }

        @Override
        protected String toDebugString() {
            return id;
        }
    }

    private class ForcingNode extends Node implements ITaskCanForce {
        ForcingNode(String id) {
            super(id);
        }

        @Override
        public boolean shouldForce(AltoClef mod, Task interruptingCandidate) {
            return forceOn;
        }
    }

    @Test
    public void directForcingChildHoldsTheSwap() {
        ForcingNode forcing = new ForcingNode("forcing");
        Node root = new Node("root");
        Supplier<Task>[] next = new Supplier[]{() -> forcing};
        root.child = () -> next[0].get();
        forceOn = true;
        root.tick(null, chain);
        next[0] = () -> new Node("other");
        root.tick(null, chain);
        assertFalse("direct force child must not be swapped out", forcing.stopped());
    }

    @Test
    public void forceNestedUnderAnOrdinaryTaskIsHonoured() {
        // this is the actual bug: it used to return true at the first node that wasn't forcing,
        // so a force two levels down was never seen
        ForcingNode leaf = new ForcingNode("leaf");
        Node middle = new Node("middle");
        middle.child = () -> leaf;
        Node root = new Node("root");
        Supplier<Task>[] next = new Supplier[]{() -> middle};
        root.child = () -> next[0].get();

        forceOn = true;
        root.tick(null, chain);
        assertTrue(leaf.isActive());

        next[0] = () -> new Node("replacement");
        root.tick(null, chain);
        assertFalse("nested force was ignored, middle got swapped out", middle.stopped());
        assertFalse(leaf.stopped());
    }

    @Test
    public void swapHappensOnceTheForceLetsGo() {
        ForcingNode leaf = new ForcingNode("leaf");
        Node middle = new Node("middle");
        middle.child = () -> leaf;
        Node root = new Node("root");
        Supplier<Task>[] next = new Supplier[]{() -> middle};
        root.child = () -> next[0].get();

        forceOn = true;
        root.tick(null, chain);
        next[0] = () -> new Node("replacement");
        root.tick(null, chain);
        assertFalse(middle.stopped());

        forceOn = false;
        root.tick(null, chain);
        assertTrue("force released but the swap still didn't happen", middle.stopped());
    }

    @Test
    public void ordinaryTasksSwapFreely() {
        Node middle = new Node("middle");
        Node root = new Node("root");
        Supplier<Task>[] next = new Supplier[]{() -> middle};
        root.child = () -> next[0].get();
        root.tick(null, chain);
        next[0] = () -> new Node("replacement");
        root.tick(null, chain);
        assertTrue(middle.stopped());
    }

    @Test
    public void forceAnywhereInTheChainBlocksNotJustAtTheTop() {
        // ordinary -> forcing -> ordinary, with the force in the middle of the walk
        Node bottom = new Node("bottom");
        ForcingNode mid = new ForcingNode("mid");
        mid.child = () -> bottom;
        Node top = new Node("top");
        top.child = () -> mid;
        Node root = new Node("root");
        Supplier<Task>[] next = new Supplier[]{() -> top};
        root.child = () -> next[0].get();

        forceOn = true;
        root.tick(null, chain);
        next[0] = () -> new Node("replacement");
        root.tick(null, chain);
        assertFalse(top.stopped());
    }
}
