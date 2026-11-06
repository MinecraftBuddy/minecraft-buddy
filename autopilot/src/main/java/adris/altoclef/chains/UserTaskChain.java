package adris.altoclef.chains;

import baritone.Baritone;
import baritone.altoclef.AltoClefBridge;
import adris.altoclef.AltoSettings;
import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.commands.AltoClefCommands;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.TaskFinishedEvent;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.time.Stopwatch;

// A task chain that runs a user defined task at the same priority.
// This basically replaces our old Task Runner.
@SuppressWarnings("ALL")
public class UserTaskChain extends SingleTaskChain {

    private final Stopwatch _taskStopwatch = new Stopwatch();
    private Runnable _currentOnFinish = null;

    private boolean _runningIdleTask;
    private boolean _nextTaskIdleFlag;

    public UserTaskChain(TaskRunner runner) {
        super(runner);
    }

    private static String prettyPrintTimeDuration(double seconds) {
        int minutes = (int) (seconds / 60);
        int hours = minutes / 60;
        int days = hours / 24;

        String result = "";
        if (days != 0) {
            result += days + " days ";
        }
        if (hours != 0) {
            result += (hours % 24) + " hours ";
        }
        if (minutes != 0) {
            result += (minutes % 60) + " minutes ";
        }
        if (!result.equals("")) {
            result += "and ";
        }
        result += String.format("%.3f", (seconds % 60));
        return result;
    }

    @Override
    protected void onTick(AltoClef mod) {

        // Pause if we're not loaded into a world.
        if (!mod.inGame()) return;

        super.onTick(mod);
    }

    public void cancel(AltoClef mod) {
        // installed but not ticked yet is not active, and that one has to be cancellable too
        if (_mainTask != null) {
            stop(mod);
            onTaskFinish(mod);
        }
    }

    @Override
    public float getPriority(AltoClef mod) {
        return 50;
    }

    @Override
    public String getName() {
        return "User Tasks";
    }

    // what you asked for is the title, "User Tasks" says nothing to the person who typed the command
    @Override
    public String getHudName() {
        return null;
    }

    public void runTask(AltoClef mod, Task task, Runnable onFinish) {
        _runningIdleTask = _nextTaskIdleFlag;
        _nextTaskIdleFlag = false;

        _currentOnFinish = onFinish;

        if (!_runningIdleTask) {
            Debug.logMessage("User Task Set: " + task.toString());
        }
        mod.getTaskRunner().enable();
        _taskStopwatch.begin();
        setTask(task);
    }

    @Override
    protected void onTaskFinish(AltoClef mod) {
        boolean shouldIdle = AltoSettings.shouldRunIdleCommandWhenNotActive();
        if (!shouldIdle) {
            // Stop. (with the idle gate on the runner stays up so the survival chains keep going)
            if (!Baritone.settings().altoRunsWhenIdle.value) {
                try {
                    mod.getTaskRunner().disable();
                } catch (Throwable t) {
                    // disable puts everything back before it throws, so the rest of the finish (clearing the task, telling
                    // whoever waits for it) still has to happen or the next command would find a half dead task
                    AltoClefBridge.onHookError(t);
                }
            }
            // Extra reset. Sometimes baritone is laggy and doesn't properly reset our press
            mod.getClientBaritone().getInputOverrideHandler().clearAllKeys();
        }
        double seconds = _taskStopwatch.time();
        Task oldTask = _mainTask;
        // same as the finished branch in SingleTaskChain. cancel and the runner shutdown stop the task first, so there
        // it has nothing left to do, it is cheap insurance for the paths that drop the root without a stop
        if (oldTask != null) {
            oldTask.letGoOfKeys(mod);
        }
        _mainTask = null;
        if (_currentOnFinish != null) {
            //noinspection unchecked
            _currentOnFinish.run();
        }
        // our `onFinish` might have triggered more tasks.
        boolean actuallyDone = _mainTask == null;
        if (actuallyDone) {
            if (!_runningIdleTask) {
                Debug.logMessage("User task FINISHED. Took %s seconds.", prettyPrintTimeDuration(seconds));
                EventBus.publish(new TaskFinishedEvent(seconds, oldTask));
            }
            if (shouldIdle) {
                AltoClefCommands.executeTrusted(Baritone.settings().altoIdleCommand.value);
                signalNextTaskToBeIdleTask();
                _runningIdleTask = true;
            }
        }
    }

    public boolean isRunningIdleTask() {
        return isActive() && _runningIdleTask;
    }

    // The next task will be an idle task.
    public void signalNextTaskToBeIdleTask() {
        _nextTaskIdleFlag = true;
    }
}
