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
import adris.altoclef.tasks.CraftGenericManuallyTask;
import adris.altoclef.tasks.slot.EnsureFreePlayerCraftingGridTask;
import adris.altoclef.tasks.slot.ReceiveCraftingOutputSlotTask;
import org.junit.Before;
import org.junit.Test;

import java.util.function.Supplier;

import static org.junit.Assert.*;

// the planks loop: ResourceTask swept the 2x2 grid right after CraftGenericManuallyTask filled it, forever,
// because only ReceiveCraftingOutputSlotTask carried the marker and that one only runs at the very end
public class CraftingGridGuardTest {

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

    private static class GridNode extends Node implements ITaskUsesCraftingGrid {
        GridNode(String id) {
            super(id);
        }
    }

    @Test
    public void everyTaskThatFillsTheGridCarriesTheMarker() {
        assertTrue(ITaskUsesCraftingGrid.class.isAssignableFrom(CraftGenericManuallyTask.class));
        assertTrue(ITaskUsesCraftingGrid.class.isAssignableFrom(ReceiveCraftingOutputSlotTask.class));
        // and the sweeper must not hide from itself
        assertFalse(ITaskUsesCraftingGrid.class.isAssignableFrom(EnsureFreePlayerCraftingGridTask.class));
    }

    @Test
    public void markerAnywhereInTheRunningChainCounts() {
        // craft2x2 -> crafting (marker) -> moving, the shape from the log, asked from the top
        Node moving = new Node("moving");
        GridNode crafting = new GridNode("crafting");
        crafting.child = () -> moving;
        Node craft2x2 = new Node("craft2x2");
        craft2x2.child = () -> crafting;
        Node top = new Node("top");
        top.child = () -> craft2x2;

        assertFalse("nothing is running yet", ITaskUsesCraftingGrid.isUsingGrid(top));
        top.tick(null, chain);
        assertTrue(ITaskUsesCraftingGrid.isUsingGrid(top));
        assertTrue(ITaskUsesCraftingGrid.isUsingGrid(craft2x2));
        assertTrue(ITaskUsesCraftingGrid.isUsingGrid(crafting));
        // it only looks down the chain, the child under the marker is not "using" anything
        assertFalse(ITaskUsesCraftingGrid.isUsingGrid(moving));
    }

    @Test
    public void noMarkerMeansSweepIsAllowed() {
        Node leaf = new Node("leaf");
        Node top = new Node("top");
        top.child = () -> leaf;
        top.tick(null, chain);
        assertFalse(ITaskUsesCraftingGrid.isUsingGrid(top));
        assertFalse(ITaskUsesCraftingGrid.isUsingGrid(null));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void markerGoesAwayWhenTheChainSwapsAway() {
        GridNode crafting = new GridNode("crafting");
        Node top = new Node("top");
        Supplier<Task>[] next = new Supplier[]{() -> crafting};
        top.child = () -> next[0].get();
        top.tick(null, chain);
        assertTrue(ITaskUsesCraftingGrid.isUsingGrid(top));
        next[0] = () -> new Node("collect");
        top.tick(null, chain);
        assertFalse(ITaskUsesCraftingGrid.isUsingGrid(top));
    }

    @Test
    public void shouldClearTruthTable() {
        // shouldClear(gridInUse, ensureActive, gridHasItems, cursorHasItems)
        // crafting right now: leave the grid alone, whatever is in it
        assertFalse(EnsureFreePlayerCraftingGridTask.shouldClear(true, false, true, false));
        assertFalse(EnsureFreePlayerCraftingGridTask.shouldClear(true, false, true, true));
        // nobody is crafting and there is junk in the grid: sweep
        assertTrue(EnsureFreePlayerCraftingGridTask.shouldClear(false, false, true, false));
        // clean grid, empty hand: nothing to do
        assertFalse(EnsureFreePlayerCraftingGridTask.shouldClear(false, false, false, false));
        // a plain item in the cursor is not ours to touch when we were not sweeping
        assertFalse(EnsureFreePlayerCraftingGridTask.shouldClear(false, false, false, true));
        // the ping pong: the sweep just picked the last item up, grid is empty but it is in our hand. keep going
        assertTrue(EnsureFreePlayerCraftingGridTask.shouldClear(false, true, false, true));
        // sweep finished: empty grid, empty hand, let go
        assertFalse(EnsureFreePlayerCraftingGridTask.shouldClear(false, true, false, false));
        // a sweep already running finishes the job even if items are still in the grid
        assertTrue(EnsureFreePlayerCraftingGridTask.shouldClear(false, true, true, false));
        assertTrue(EnsureFreePlayerCraftingGridTask.shouldClear(true, true, true, false));
    }
}
