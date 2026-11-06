package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.resources.CollectFoodTask;
import baritone.api.IBaritone;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import java.util.Arrays;
import java.util.List;

public class FoodCommand extends AltoClefCommand {

    public FoodCommand(IBaritone baritone) {
        super(baritone, "food");
    }

    @Override
    protected void run(AltoClef mod, String label, IArgConsumer args) throws CommandException {
        args.requireExactly(1);
        int count = args.getAs(Integer.class);
        if (count <= 0) {
            throw new CommandInvalidStateException("the amount of food has to be at least 1");
        }
        startTask(mod, new CollectFoodTask(count));
    }

    @Override
    public String getShortDesc() {
        return "Collect a certain amount of food";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The food command has altoclef hunt, farm and cook until it is carrying that much food.",
                "",
                "The amount counts hunger points the food restores, not items. Use meat if you only want meat.",
                "",
                "Usage:",
                "> food <amount> - Collect that much food.",
                "",
                "Examples:",
                "> food 20"
        );
    }
}
