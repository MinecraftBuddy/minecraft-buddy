package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.ui.MessagePriority;
import adris.altoclef.util.helpers.ItemHelper;
import baritone.api.IBaritone;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public class InventoryCommand extends AltoClefCommand {

    public InventoryCommand(IBaritone baritone) {
        super(baritone, "inventory");
    }

    @Override
    protected void run(AltoClef mod, String label, IArgConsumer args) throws CommandException {
        args.requireMax(1);
        if (!args.hasAny()) {
            // item counts by name, sorted so the list does not shuffle around between runs
            Map<String, Integer> counts = new TreeMap<>();
            for (int i = 0; i < mod.getPlayer().getInventory().getContainerSize(); ++i) {
                ItemStack stack = mod.getPlayer().getInventory().getItem(i);
                if (!stack.isEmpty()) {
                    counts.merge(ItemHelper.stripItemName(stack.getItem()), stack.getCount(), Integer::sum);
                }
            }
            mod.log("INVENTORY: ", MessagePriority.OPTIONAL);
            counts.forEach((name, count) -> mod.log(name + " : " + count, MessagePriority.OPTIONAL));
            mod.log("(inventory list sent) ", MessagePriority.OPTIONAL);
        } else {
            String item = args.getDatatypeFor(AltoItem.CATALOGUE);
            Item[] matches = TaskCatalogue.getItemMatches(item);
            int count = mod.getItemStorage().getItemCount(matches);
            mod.log(item + " COUNT: " + (count == 0 ? "(none)" : count));
        }
        done();
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            return args.tabCompleteDatatype(AltoItem.CATALOGUE);
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Print the inventory or count an item";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The inventory command lists everything you are carrying, or tells you how many of one item you have.",
                "",
                "Counting takes altoclef's catalogue names (see get), so a group like log adds up every kind of log. A butler gets the answer in a whisper.",
                "",
                "Usage:",
                "> inventory - List your inventory.",
                "> inventory <item> - Count one item or group.",
                "",
                "Examples:",
                "> inventory log"
        );
    }
}
