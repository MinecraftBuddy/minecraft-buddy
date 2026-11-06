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

package baritone.altoclef;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.resources.TripGatherTask;
import adris.altoclef.tasksystem.Task;
import baritone.api.command.exception.CommandException;
import baritone.behavior.trip.GatherSources;
import baritone.behavior.trip.TripGatherer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.util.List;

// a trip's gather, run as an altoclef user task: the same chain #get uses, so food and mob defense can still cut in
// and #stop stops it like any other task. the trip only ever sees the TripGatherer side. client thread only
final class TripGatherBridge implements TripGatherer {

    private long tripId = -1;
    private Task task;
    private Status status = Status.NONE;

    @Override
    public boolean start(long tripId, List<Item> items, int blocks, boolean pickaxe) {
        AltoClef mod;
        try {
            mod = AltoClefBridge.require();
        } catch (CommandException e) {
            return false;
        }
        // something of the user's own is running. the trip doesn't get to replace it (and runUserTask would drop its callback)
        if (mod.getUserTaskChain().isActive() && !mod.getUserTaskChain().isRunningIdleTask()) {
            return false;
        }
        Item[] wanted = items.toArray(new Item[0]);
        if (blocks > 0 && wanted.length == 0) {
            return false;
        }
        List<Block> all = GatherSources.sources(items);
        Block[] sources = all.toArray(new Block[0]);
        Block[] hand = GatherSources.byHand(all).toArray(new Block[0]);
        // the deficit is on top of what's held, GetBuildingMaterialsTask style "hold this many" from here on
        int target = blocks > 0 ? mod.getItemStorage().getItemCount(wanted) + blocks : 0;
        TripGatherTask t = new TripGatherTask(wanted, sources, hand, target, pickaxe);
        this.tripId = tripId;
        this.task = t;
        this.status = Status.RUNNING;
        mod.getButler().onOwnerTask();
        // the callback also runs on a cancel, so ask the task whether it really got there
        mod.runUserTask(t, () -> {
            if (task == t) {
                status = t.isFinished(mod) ? Status.DONE : Status.FAILED;
            }
        });
        return true;
    }

    @Override
    public Status status(long tripId) {
        if (tripId != this.tripId) {
            return Status.NONE;
        }
        if (status == Status.RUNNING && !AltoClefBridge.isAlive()) {
            status = Status.FAILED;
        }
        if (status == Status.RUNNING && AltoClefBridge.instanceOrNull() != null && AltoClefBridge.instanceOrNull().getUserTaskChain().getCurrentTask() != task) {
            // somebody ran another user task over ours, runUserTask never calls the old callback
            status = Status.FAILED;
        }
        return status;
    }

    @Override
    public void cancel(long tripId) {
        if (tripId != this.tripId || status != Status.RUNNING) {
            return;
        }
        Task t = task;
        task = null;
        status = Status.NONE;
        AltoClef mod = AltoClefBridge.instanceOrNull();
        if (mod != null && mod.getUserTaskChain().getCurrentTask() == t) {
            mod.cancelUserTask();
        }
    }
}
