package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import baritone.api.utils.input.Input;

// walks straight off whatever we stand on. for a pillar top with a water bucket: the clutch takes it from the air
public class StepOffTask extends Task {

    @Override
    protected void onStart(AltoClef mod) {
    }

    @Override
    protected Task onTick(AltoClef mod) {
        mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
        mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.SPRINT, true);
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
        mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof StepOffTask;
    }

    @Override
    protected String toHudString() {
        return "Stepping off the pillar";
    }

    @Override
    protected String toDebugString() {
        return "Step off";
    }
}
