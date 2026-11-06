package adris.altoclef.ui.gui.mc;

import adris.altoclef.AltoClef;
import adris.altoclef.chains.UserTaskChain;
import adris.altoclef.tasks.speedrun.gamer.GamerHudState;
import adris.altoclef.tasks.speedrun.gamer.GamerTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.ui.gui.facade.Result;
import adris.altoclef.ui.gui.facade.StatusAccess;
import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.model.Wording;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.pathing.goals.GoalYLevel;
import baritone.api.process.IBaritoneProcess;

import java.util.List;
import java.util.Optional;

// what the status strip says. reads the same places the task hud reads, one level less detailed
final class McStatus implements StatusAccess {

    private static final Activity IDLE = new Activity(false, "Idle", "", null);

    @Override
    public Activity activity() {
        try {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            AltoClef mod = AltoClef.getInstance();
            String phase = gamerPhase(mod);
            String line = null;

            if (mod != null && mod.getTaskRunner().isActive()) {
                UserTaskChain user = mod.getUserTaskChain();
                Task userTask = user.isActive() && !user.isRunningIdleTask() ? user.getCurrentTask() : null;
                if (userTask != null) {
                    line = userTask.getHudName();
                } else {
                    // eating, mob defense and friends have the wheel, say so
                    TaskChain chain = mod.getTaskRunner().getCurrentTaskChain();
                    List<Task> tasks = chain == null || chain == user ? List.of() : chain.getTasks();
                    if (!tasks.isEmpty()) {
                        line = tasks.get(tasks.size() - 1).getHudName();
                    }
                }
            }

            if (line != null && line.isBlank()) {
                line = null;
            }
            boolean pathing = baritone.getPathingBehavior().isPathing();
            Optional<IBaritoneProcess> process = baritone.getPathingControlManager().mostRecentInControl();
            if (line == null && process.isPresent()) {
                line = process.get().displayName();
            }
            if (line == null && pathing) {
                line = "Walking";
            }
            if (line == null || line.isBlank()) {
                return phase == null ? IDLE : new Activity(true, "Running", "", phase);
            }
            return new Activity(true, Wording.soprano(line), goalText(baritone.getPathingBehavior().getGoal()), phase);
        } catch (Throwable t) {
            Safe.report("status", t);
            return IDLE;
        }
    }

    // only while a run is live, the card lingers after a win and that is not "running"
    private static String gamerPhase(AltoClef mod) {
        if (mod == null || !mod.getTaskRunner().isActive()) {
            return null;
        }
        if (mod.getUserTaskChain().getCurrentTask() instanceof GamerTask run) {
            GamerHudState state = run.hudSnapshot();
            return state == null ? null : Wording.soprano(state.phase().hud());
        }
        return null;
    }

    private static String goalText(Goal goal) {
        if (goal == null) {
            return "";
        }
        if (goal instanceof GoalBlock b) {
            return "goal " + b.x + " " + b.y + " " + b.z;
        }
        if (goal instanceof GoalXZ xz) {
            return "goal " + xz.getX() + " " + xz.getZ();
        }
        if (goal instanceof GoalYLevel y) {
            return "goal y " + y.level;
        }
        String s = String.valueOf(goal);
        return s.length() > 40 ? s.substring(0, 39) + "…" : s;
    }

    @Override
    public Result stop() {
        // the command itself: it also clears a pause, which is not something to copy out of it
        Result r = new McCommands().run("stop");
        return r.ok() ? Result.ok("Stopped", "stop") : r;
    }
}
