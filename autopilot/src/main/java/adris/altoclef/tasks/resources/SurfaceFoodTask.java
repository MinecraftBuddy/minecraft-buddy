package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.movement.GetToYTask;
import adris.altoclef.tasksystem.Task;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Food from the surface: up to the open sky first, then the food run there (CollectFoodTask). For a food run begun deep
 * under the ground in the overworld: the food run hunted wherever it stood, through caves at low health with no
 * regeneration, after an animal heard through the rock, and both deaths of a gold phase's smoke followed (12 Nov 2026).
 * The way up is a staircase to the surface's height above the spot where the climb began, picked once, so the goal does
 * not move with every column passed; once the sky is in view the food run takes over, wherever that is.
 */
public class SurfaceFoodTask extends Task {

    private final double _units;
    private final CollectFoodTask _food;
    private Integer _topY;
    private boolean _up;

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
