package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.baritone.GoalXZRadius;
import baritone.api.utils.Dimension;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.ui.HudText;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalXZ;
import net.minecraft.core.BlockPos;

public class GetToXZTask extends CustomBaritoneGoalTask {

    private final int _x, _z;
    private final Dimension _dimension;
    private final double _radius;

    public GetToXZTask(int x, int z) {
        this(x, z, null);
    }

    public GetToXZTask(int x, int z, Dimension dimension) {
        _x = x;
        _z = z;
        _dimension = dimension;
        _radius = 0;
    }

    // done anywhere within radius blocks of the column, for callers that move on at that distance anyway
    public GetToXZTask(int x, int z, double radius) {
        _x = x;
        _z = z;
        _dimension = null;
        _radius = radius;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (_dimension != null && WorldHelper.getCurrentDimension() != _dimension) {
            return new DefaultGoToDimensionTask(_dimension);
        }
        return super.onTick(mod);
    }

    @Override
    protected Goal newGoal(AltoClef mod) {
        return _radius > 0 ? new GoalXZRadius(_x, _z, _radius) : new GoalXZ(_x, _z);
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof GetToXZTask task) {
            return task._x == _x && task._z == _z && task._dimension == _dimension && task._radius == _radius;
        }
        return false;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        BlockPos cur = mod.getPlayer().blockPosition();
        if (_radius > 0) {
            return GoalXZRadius.within(cur.getX() - _x, cur.getZ() - _z, _radius);
        }
        return (cur.getX() == _x && cur.getZ() == _z && (_dimension == null || _dimension == WorldHelper.getCurrentDimension()));
    }

    @Override
    protected String toDebugString() {
        return "Getting to (" + _x + "," + _z + ")" + (_dimension != null ? " in dimension " + _dimension : "");
    }

    @Override
    protected String toHudString() {
        return "Walking to " + _x + ", " + _z + (_dimension != null ? " in " + HudText.dimension(_dimension) : "");
    }
}
