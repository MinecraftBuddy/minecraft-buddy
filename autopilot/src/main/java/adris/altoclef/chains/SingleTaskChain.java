package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.time.Stopwatch;

public abstract class SingleTaskChain extends TaskChain {

    private final Stopwatch _taskStopwatch = new Stopwatch();
    protected Task _mainTask = null;
    private boolean _interrupted = false;

    private AltoClef _mod;

    public SingleTaskChain(TaskRunner runner) {
        super(runner);
        _mod = runner.getMod();
    }

    @Override
    protected void onTick(AltoClef mod) {
        if (!isActive()) return;

        if (_interrupted) {
            _interrupted = false;
            // what our paused tasks handed over goes back: they start again below and hold again on the next cut. a root
            // that finished while we were out is dropped below with no start or stop, and its blazes would be ignored forever
            if (mod != null && mod.getBehaviour() != null) {
                mod.getBehaviour().releasePausedFor(this);
            }
            if (_mainTask != null) {
                _mainTask.reset();
            }
        }

        if (_mainTask != null) {
            if ((_mainTask.isFinished(mod)) || _mainTask.stopped()) {
                // a finished root is dropped without stop (onStop is not part of the plan), so the keys it and its
                // children still hold have to be let go of here or they stay down for the next task
                _mainTask.letGoOfKeys(mod);
                onTaskFinish(mod);
            } else {
                _mainTask.tick(mod, this);
            }
        }
    }

    protected void onStop(AltoClef mod) {
        if (isActive() && _mainTask != null) {
            _mainTask.stop(mod);
            _mainTask = null;
        }
    }

    public void setTask(Task task) {
        if (shouldInstall(_mainTask, task, _mod)) {
            if (_mainTask != null) {
                _mainTask.stop(_mod, task);
            }
            _mainTask = task;
            if (task != null) task.reset();
        }
    }

    // an equal task used to win even when it was dead. a finished run never ticks again, so the fresh one that was asked
    // for got thrown away in its favour and the chain sat there for a minute holding the wheel over a corpse. pure so the
    // rule can be tested without a game
    static boolean shouldInstall(Task current, Task wanted, AltoClef mod) {
        if (current == null) return true;
        // the same object again is not news, whatever state it is in (a finished one just gets cleaned up by the tick)
        if (current == wanted) return false;
        return current.isFinished(mod) || current.stopped() || !current.equals(wanted);
    }


    @Override
    public boolean isActive() {
        return _mainTask != null;
    }

    protected abstract void onTaskFinish(AltoClef mod);

    @Override
    public void onInterrupt(AltoClef mod, TaskChain other) {
        if (other != null) {
            Debug.logInternal("Chain Interrupted: " + this + " by " + other);
        }
        // Stop our task. When we're started up again, let our task know we need to run.
        _interrupted = true;
        if (_mainTask != null && _mainTask.isActive()) {
            _mainTask.interrupt(mod, null);
        }
    }

    protected boolean isCurrentlyRunning(AltoClef mod) {
        return !_interrupted && _mainTask.isActive() && !_mainTask.isFinished(mod);
    }

    public Task getCurrentTask() {
        return _mainTask;
    }

    // for the "holds the wheel with nothing running" log
    @Override
    public String getHeldTaskDebug() {
        return _mainTask == null ? "no task" : _mainTask.toString();
    }
}
