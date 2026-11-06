package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.container.StoreInStashTask;
import baritone.api.utils.BlockRange;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.IBaritone;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.datatypes.RelativeBlockPos;
import baritone.api.command.datatypes.RelativeCoordinate;
import baritone.api.command.exception.CommandException;
import baritone.api.utils.BetterBlockPos;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class StashCommand extends AltoClefCommand {

    public StashCommand(IBaritone baritone) {
        super(baritone, "stash");
    }

    @Override
    protected void run(AltoClef mod, String label, IArgConsumer args) throws CommandException {
        args.requireMin(6);
        BetterBlockPos origin = ctx.playerFeet();
        BetterBlockPos start = args.getDatatypePost(RelativeBlockPos.INSTANCE, origin);
        BetterBlockPos end = args.getDatatypePost(RelativeBlockPos.INSTANCE, origin);
        // catalogue names only: the stash task goes and gets what it is short of, and it can only do that for those
        ItemTarget[] items = args.hasAny() ? parseItems(args, AltoItem.CATALOGUE) : DepositCommand.getAllNonEquippedOrToolItemsAsTarget(mod);
        startTask(mod, new StoreInStashTask(true, new BlockRange(start, end, WorldHelper.getCurrentDimension()), items));
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        // six coordinates, one at a time so each can offer a ~, then the items
        for (int i = 0; i < 6; i++) {
            if (!args.has(2)) {
                return args.tabCompleteDatatype(RelativeCoordinate.INSTANCE);
            }
            args.getDatatypePost(RelativeCoordinate.INSTANCE, 0.0);
        }
        return args.tabCompleteDatatype(AltoItem.CATALOGUE);
    }

    @Override
    public String getShortDesc() {
        return "Store items in a chest stash";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The stash command stores items in the chests, barrels and shulker boxes inside a box of the world. If it does not have what you asked it to store, it goes and gets it first.",
                "",
                "The box is two opposite corners. Coordinates can be relative with ~ just like in regular Minecraft commands.",
                "",
                "With no items it stores everything except tools and armor you are wearing. Items are catalogue names (see get), with an optional count each.",
                "",
                "Usage:",
                "> stash <x1> <y1> <z1> <x2> <y2> <z2> - Store everything that is not gear.",
                "> stash <x1> <y1> <z1> <x2> <y2> <z2> <item> [count] ... - Store just these items.",
                "",
                "Examples:",
                "> stash ~ ~ ~ ~5 ~3 ~5 cobblestone"
        );
    }
}
