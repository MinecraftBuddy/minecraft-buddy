package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.movement.GetToYTask;
import adris.altoclef.tasks.movement.TrailBackTask;
import adris.altoclef.tasksystem.Task;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.List;

/**
 * Food from the surface: up to the open sky first, then the food run there (CollectFoodTask). For a food run begun deep
 * under the ground in the overworld: the food run hunted wherever it stood, through caves at low health with no
 * regeneration, after an animal heard through the rock, and both deaths of a gold phase's smoke followed (12 Nov 2026).
 * The way up is a staircase to the surface's height above the spot where the climb began, picked once, so the goal does
 * not move with every column passed; once the sky is in view the food run takes over, wherever that is.
 * (Tuning food_climb_trail) With no pickaxe above wood the way up is first the way the buddy came down (DownTrail,
 * TrailBackTask), as for the log climb of pickless_way_out; the staircase by hand only from where that walk ends short.
 */
public class SurfaceFoodTask extends Task {

    private final double _units;
    private final CollectFoodTask _food;
    private Integer _topY;
    private boolean _up;
    // (Tuning food_climb_trail) the walk back up the trail, when it began, and whether it is over (or was never on). kept through
    // onStart: a walk an interruption broke goes on where it was, on the same clock
    private TrailBackTask _trail;
    private long _trailSince;
    private boolean _trailDone;

    public SurfaceFoodTask(double unitsNeeded) {
        _units = unitsNeeded;
        _food = new CollectFoodTask(unitsNeeded);
    }

    /** The height to climb to: one above the highest block that stops motion over the column, at least the sea's level. */
    static int climbTo(int surfaceTop, int seaLevel) {
        return Math.max(surfaceTop, seaLevel) + 1;
    }

    @Override
    protected void onStart(AltoClef mod) {
        _topY = null;
        _up = false;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        var p = mod.getPlayer();
        BlockPos feet = p.blockPosition();
        // (the sky once seen counts for the rest of the run: the food run may lead under a tree or into a hollow)
        if (!_up && mod.getWorld().canSeeSky(feet.above())) _up = true;
        if (_up) {
            setDebugState("Food on the surface");
            return _food;
        }
        Task back = trailBack(mod, feet);
        if (back != null) {
            return back;
        }
        if (_topY == null) {
            _topY = climbTo(mod.getWorld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, feet.getX(), feet.getZ()),
                    mod.getWorld().getSeaLevel());
        }
        // (Tuning food_climb_again, a fix of 13 Nov 2026, on: the height to climb to was read once, at the column the run began
        // in; the climb's path moves on to other columns, and at the height reached with no sky in view the climb was done and
        // the run stood still, 20 minutes at a time under a cave's roof at y 69 and 77: about 78 game minutes over the goudslijm
        // smokes 3 and 4. reached with no sky, the height is read again here; when this column has nothing higher to climb (a
        // tree's leaves over it), the surface is reached and the food run begins)
        if (feet.getY() >= _topY && adris.altoclef.util.helpers.Tuning.get("food_climb_again", 0) > 0) {
            int top = climbTo(mod.getWorld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, feet.getX(), feet.getZ()),
                    mod.getWorld().getSeaLevel());
            if (top > feet.getY()) {
                adris.altoclef.Debug.logInternal("food: no sky at y " + feet.getY() + ", the surface here is higher: on up to y " + top);
                _topY = top;
            } else {
                adris.altoclef.Debug.logInternal("food: at the surface's height (y " + feet.getY() + ") with no sky in view, the food run begins here");
                _up = true;
                return _food;
            }
        }
        setDebugState("Up to the surface for food, to y " + _topY);
        return new GetToYTask(_topY);
    }

    // (Tuning food_climb_trail, a fix of 14 Nov 2026, on) the walk back up the way down, null once it is over or when there is
    // none: the key off (or pickless_way_out, which keeps the trail), a pickaxe above wood in the bag, no trail from the open. a
    // run with no pickaxe at all in a cave at y 13 to 46 climbed by hand to y 70 for food: rock at 153 a block to baritone,
    // 360 "No path found" in 32 minutes, raw food and rotten flesh eaten
    private Task trailBack(AltoClef mod, BlockPos feet) {
        long now = mod.getWorld().getGameTime();
        if (_trailDone) {
            return null;
        }
        if (_trail == null) {
            List<int[]> way = adris.altoclef.util.helpers.Tuning.get("food_climb_trail", 0) > 0
                    ? adris.altoclef.util.helpers.DownTrail.wayOut(mod) : null;
            if (way == null) {
                _trailDone = true;
                return null;
            }
            _trail = new TrailBackTask(way);
            _trailSince = now;
            int[] top = way.get(way.size() - 1);
            Debug.logInternal("food: no pickaxe above wood at y " + feet.getY() + ": back up the way down first, " + way.size()
                    + " cells to " + top[0] + " " + top[1] + " " + top[2] + " (food_climb_trail)");
        }
        if (!TrailBackTask.over(_trail.isFinished(mod), _trailSince, now)) {
            setDebugState("Back up the way down for food, no pickaxe above wood");
            return _trail;
        }
        Debug.logInternal("food: the way back up " + (_trail.failed() ? "got stuck" : _trail.isFinished(mod) ? "ended"
                : "took " + TrailBackTask.CAP / 1200 + " game minutes") + " at y " + feet.getY()
                + " with no sky in view: the climb by hand from here (food_climb_trail)");
        _trailDone = true;
        // the surface's height read again over the column the walk ended at
        _topY = null;
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return _up && _food.isFinished(mod);
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof SurfaceFoodTask t && t._units == _units;
    }

    @Override
    protected String toDebugString() {
        return "Food from the surface, " + Math.round(_units) + " points";
    }

    @Override
    protected String toHudString() {
        return "Up for food";
    }
}
