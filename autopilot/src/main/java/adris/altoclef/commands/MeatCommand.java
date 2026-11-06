package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.resources.CollectMeatTask;
import baritone.api.IBaritone;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import java.util.Arrays;
import java.util.List;

public class MeatCommand extends AltoClefCommand {

    public MeatCommand(IBaritone baritone) {
        super(baritone, "meat");
    }

    @Override
    protected void run(AltoClef mod, String label, IArgConsumer args) throws CommandException {
        args.requireExactly(1);
        int count = args.getAs(Integer.class);
        if (count <= 0) {
            throw new CommandInvalidStateException("the amount of meat has to be at least 1");
        }
        startTask(mod, new CollectMeatTask(count));
    }

    @Override
    public String getShortDesc() {
        return "Collect a certain amount of meat";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The meat command has altoclef hunt animals and cook the meat until it is carrying that much.",
                "",
                "The amount counts hunger points the meat restores, not items.",
                "",
                "Usage:",
                "> meat <amount> - Collect that much meat.",
                "",
                "Examples:",
                "> meat 20"
        );
    }
}
