package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.baritone.GoalDirectionXZ;
import baritone.api.pathing.goals.Goal;
import net.minecraft.world.phys.Vec3;

public class GoInDirectionXZTask extends CustomBaritoneGoalTask {

    private final Vec3 _origin;
    private final Vec3 _delta;
    private final double _sidePenalty;

    public GoInDirectionXZTask(Vec3 origin, Vec3 delta, double sidePenalty) {
        _origin = origin;
        _delta = delta;
        _sidePenalty = sidePenalty;
    }

    private static boolean closeEnough(Vec3 a, Vec3 b) {
        return a.distanceToSqr(b) < 0.001;
    }

    @Override
    protected Goal newGoal(AltoClef mod) {
        try {
            return new GoalDirectionXZ(_origin, _delta, _sidePenalty);
        } catch (Exception e) {
            Debug.logMessage("Invalid goal direction XZ (probably zero distance)");
            return null;
        }
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof GoInDirectionXZTask) {
            GoInDirectionXZTask task = (GoInDirectionXZTask) other;
            return (closeEnough(task._origin, _origin) && closeEnough(task._delta, _delta));
        }
        return false;
    }

    @Override
    protected String toHudString() {
        if (_delta.horizontalDistanceSqr() < 0.0001) {
            return "Walking in a straight line";
        }
        // north is -z, so the angle runs clockwise from there in eight slices
        int slice = (int) Math.round(Math.toDegrees(Math.atan2(_delta.x, -_delta.z)) / 45.0);
        return "Walking " + switch (Math.floorMod(slice, 8)) {
            case 0 -> "north";
            case 1 -> "northeast";
            case 2 -> "east";
            case 3 -> "southeast";
            case 4 -> "south";
            case 5 -> "southwest";
            case 6 -> "west";
            default -> "northwest";
        };
    }

    @Override
    protected String toDebugString() {
        return "Going in direction: <" + _origin.x + "," + _origin.z + "> direction: <" + _delta.x + "," + _delta.z + ">";
    }
}
