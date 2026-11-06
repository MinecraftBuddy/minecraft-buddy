package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.baritone.GoalColumnDown;
import baritone.api.pathing.goals.Goal;

// dig down a fixed xz column to floorY. GetToYTask has no column, so it digs down wherever baritone fancies
public class GetDownColumnTask extends CustomBaritoneGoalTask {
    private final int x;
    private final int z;
    private final int floorY;

    private final boolean wander;

    // what makes two of these the same task, split out so the test does not need a running game to build one
    record Shaft(int x, int z, int floorY, boolean wander) {
    }

    public GetDownColumnTask(int x, int z, int floorY) {
        this(x, z, floorY, true);
    }

    // wander = false for a shaft we must not leave: a failed progress check walks off into the open and the column is gone
    public GetDownColumnTask(int x, int z, int floorY, boolean wander) {
        super(wander);
        this.x = x;
        this.z = z;
        this.floorY = floorY;
        this.wander = wander;
    }

    @Override
    protected Goal newGoal(AltoClef mod) {
        return new GoalColumnDown(x, z, floorY);
    }

    Shaft shaft() {
        return new Shaft(x, z, floorY, wander);
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof GetDownColumnTask t && t.shaft().equals(shaft());
    }

    @Override
    protected String toDebugString() {
        return "Digging down at " + x + ", " + z + " to y=" + floorY;
    }

    @Override
    protected String toHudString() {
        return "Digging down to y " + floorY;
    }
}
