package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.squashed.GatherBillTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import baritone.api.IBaritone;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class GetCommand extends AltoClefCommand {

    public GetCommand(IBaritone baritone) {
        super(baritone, "get");
    }

    @Override
    protected void run(AltoClef mod, String label, IArgConsumer args) throws CommandException {
        args.requireMin(1);
        ItemTarget[] items = parseItems(args, AltoItem.CATALOGUE);
        // the plain catalogue task is still what GatherBillTask ends on, and the cheapest way to ask "do we know this at all"
        Task plain = items.length == 1 ? TaskCatalogue.getItemTask(items[0]) : TaskCatalogue.getSquashedItemTask(items);
        if (plain == null) {
            throw new CommandInvalidStateException("no task for that, " + AltoClefCommands.prefix() + "list shows what altoclef can get");
        }
        // the whole shopping list first, tools, fetch it all, then craft
        startTask(mod, new GatherBillTask(items));
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        return args.tabCompleteDatatype(AltoItem.CATALOGUE);
    }

    @Override
    public String getShortDesc() {
        return "Get an item or resource";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The get command has altoclef collect, mine, craft or smelt whatever it takes to end up holding the items.",
                "",
                "Items are altoclef's catalogue names. Most are plain item ids, some are groups like log, planks or food. Use list to see them all, or just tab complete.",
                "",
                "You can ask for several at once, each with an optional count (it is 1 if you leave it out). The old [a 1, b 2] spelling works too.",
                "",
                "Usage:",
                "> get <item> [count] - Get an item.",
                "> get <item> [count] <item> [count] ... - Get several items in one go.",
                "",
                "Examples:",
                "> get iron_ingot 3 diamond 2 - Three iron ingots and two diamonds.",
                "> get log 16 - Sixteen logs of whatever tree is closest.",
                "> get [iron_pickaxe, stone_sword 1]"
        );
    }
}
