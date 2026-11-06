package adris.altoclef.tasksystem;

import adris.altoclef.AltoClef;

import java.util.ArrayList;
import java.util.List;

public abstract class TaskChain {

    private final List<Task> _cachedTaskChain = new ArrayList<>();

    public TaskChain(TaskRunner runner) {
        runner.addTaskChain(this);
    }

    public void tick(AltoClef mod) {
        _cachedTaskChain.clear();
        onTick(mod);
    }

    public void stop(AltoClef mod) {
        _cachedTaskChain.clear();
        onStop(mod);
    }

    protected abstract void onStop(AltoClef mod);

    public abstract void onInterrupt(AltoClef mod, TaskChain other);

    protected abstract void onTick(AltoClef mod);

    public abstract float getPriority(AltoClef mod);

    public abstract boolean isActive();

    public abstract String getName();

    // the header line of the task hud. plain words: "Defending", not "Mob Defense". null means the chain has no
    // name of its own and its root task is the headline (the user chain: what you asked for is the title)
    public String getHudName() {
        return getName();
    }

    public List<Task> getTasks() {
        return _cachedTaskChain;
    }

    void addTaskToChain(Task task) {
        _cachedTaskChain.add(task);
    }

    // what the chain thinks it is holding, for the log when it wins the wheel and runs nothing
    public String getHeldTaskDebug() {
        return "unknown";
    }

    public String toString() {
        return getName();
    }

}
