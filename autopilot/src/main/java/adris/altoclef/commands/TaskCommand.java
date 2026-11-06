package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import baritone.api.IBaritone;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

// the commands that are just "no arguments, start this task": hero, selfcare, idle and the two lava ones
public class TaskCommand extends AltoClefCommand {

    private final String shortDesc;
    private final List<String> longDesc;
    private final Supplier<? extends Task> task;

    public TaskCommand(IBaritone baritone, String[] names, String shortDesc, Supplier<? extends Task> task, String... description) {
        super(baritone, names);
        this.shortDesc = shortDesc;
        this.task = task;
        List<String> lines = new ArrayList<>(List.of(description));
        lines.add("");
        lines.add("Usage:");
        lines.add("> " + names[0] + " - " + shortDesc + ".");
        this.longDesc = lines;
    }

    @Override
    protected void run(AltoClef mod, String label, IArgConsumer args) throws CommandException {
        args.requireMax(0);
        startTask(mod, task.get());
    }

    @Override
    public String getShortDesc() {
        return shortDesc;
    }

    @Override
    public List<String> getLongDesc() {
        return longDesc;
    }
}
