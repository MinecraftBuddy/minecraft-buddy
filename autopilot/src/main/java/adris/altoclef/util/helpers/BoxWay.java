package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasksystem.Task;
import net.minecraft.core.BlockPos;

// the buddy's fork: the way into a blaze fight's corner. a standing pillbox is walled on every side but its look out's, and no
// walk may break a block within five of its cell (the fight's pocket wall): a run aimed at the cell from a walled side walked
// into the wall nearest the cell and stood there in the blazes' fire, "No path found" over and over. so round by the box's
// door first (the cell outside the look out's open side, BlazeShelter.box), then in to the cell, as the camp walk does. the
// door gets DOOR_TICKS, then the walk is at the cell; the whole walk RUN_TICKS, then null: the caller's plain run or cover.
// a corner with no box is the walk at the cell, as before. one per user, the task object kept while the goal is the same
public final class BoxWay {
    static final long DOOR_TICKS = 200;
    static final long RUN_TICKS = 400;
    // a walk not asked for in this long is over: the next ask is a new walk with its own clock
    static final long GAP_TICKS = 40;

    private BlockPos corner;
    private long since;
    private long lastAsk = Long.MIN_VALUE / 2;
    private boolean doorDone;
    private boolean overSaid;
    private GetToBlockTask walk;
    private BlockPos walkTo;

    // the walk into the corner `to` this tick, null once this walk had RUN_TICKS
    public Task step(AltoClef mod, BlockPos to) {
        long now = mod.getWorld().getGameTime();
        BlockPos door = BlazeShelter.door(to);
        BlockPos feet = mod.getPlayer().blockPosition();
        BlockPos goal = next(now, to, door, feet, door != null && BlazeShelter.inBox(to, feet));
        if (goal == null) {
            walk = null;
            walkTo = null;
            return null;
        }
        if (walk == null || !goal.equals(walkTo)) {
            walk = new GetToBlockTask(goal, false);
            walkTo = goal;
            if (goal.equals(door)) Debug.logInternal("rods: into the pillbox at " + to.toShortString() + " round by its door at " + door.toShortString());
        }
        return walk;
    }

    // in the corner: the next walk starts its clock afresh
    public void reset() {
        corner = null;
        walk = null;
        walkTo = null;
    }

    // where the walk goes this tick: the door until it is reached, the box is entered or DOOR_TICKS are spent, then the cell;
    // null once RUN_TICKS are spent. a new corner, a clock that went back or a pause of GAP_TICKS starts a new walk
    BlockPos next(long now, BlockPos to, BlockPos door, BlockPos feet, boolean inBox) {
        if (!to.equals(corner) || now < lastAsk || now - lastAsk > GAP_TICKS) {
            corner = to;
            since = now;
            doorDone = false;
            overSaid = false;
        }
        lastAsk = now;
        if (now - since > RUN_TICKS) {
            if (!overSaid) {
                overSaid = true;
                Debug.logInternal("rods: the corner at " + to.toShortString() + " not reached in " + RUN_TICKS / 20 + " s, the run as without one");
            }
            return null;
        }
        if (door != null && !doorDone) {
            if (feet.equals(door) || inBox) {
                doorDone = true;
            } else if (now - since > DOOR_TICKS) {
                doorDone = true;
                Debug.logInternal("rods: the pillbox's door at " + door.toShortString() + " not reached in " + DOOR_TICKS / 20 + " s, the walk at its cell");
            }
        }
        return door != null && !doorDone ? door : to;
    }
}
