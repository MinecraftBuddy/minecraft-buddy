package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import baritone.api.IBaritone;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import java.util.Arrays;
import java.util.List;

public class StatusCommand extends AltoClefCommand {

    public StatusCommand(IBaritone baritone) {
        super(baritone, "status");
    }

    @Override
    protected void run(AltoClef mod, String label, IArgConsumer args) throws CommandException {
        args.requireMax(0);
        List<Task> tasks = mod.getUserTaskChain().getTasks();
        if (tasks.isEmpty()) {
            mod.log("No tasks currently running.");
        } else {
            mod.log("CURRENT TASK: " + tasks.get(0).toString());
        }
        done();
    }

    @Override
    public String getShortDesc() {
        return "Get the status of the running altoclef task";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The status command tells you what altoclef is working on right now.",
                "",
                "Usage:",
                "> status"
        );
    }
}
