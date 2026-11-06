package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.entity.GiveItemToPlayerTask;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemHelper;
import baritone.api.IBaritone;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.command.helpers.TabCompleteHelper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

public class GiveCommand extends AltoClefCommand {

    public GiveCommand(IBaritone baritone) {
        super(baritone, "give");
    }

    // what is in the inventory right now, by the same stripped names the catalogue uses. an item altoclef can't get is
    // still one it can give
    private static List<String> inventoryNames() {
        List<String> out = new ArrayList<>();
        if (Minecraft.getInstance().player == null) {
            return out;
        }
        var inv = Minecraft.getInstance().player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); ++i) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                String name = ItemHelper.stripItemName(stack.getItem());
                if (!out.contains(name)) {
                    out.add(name);
                }
            }
        }
        return out;
    }

    private static boolean isItem(String word) {
        String n = ItemArgs.normalize(word);
        return TaskCatalogue.taskExists(n) || inventoryNames().contains(n);
    }

    private static boolean isCount(String word) {
        try {
            Integer.parseInt(word);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static int parseCount(String word) throws CommandException {
        try {
            int count = Integer.parseInt(word);
            if (count > 0) {
                return count;
            }
        } catch (NumberFormatException ignored) {
            // same complaint as a zero
        }
        throw new CommandInvalidStateException("\"" + word + "\" is not a count of at least 1");
    }

    @Override
    protected void run(AltoClef mod, String label, IArgConsumer args) throws CommandException {
        args.requireMin(1);
        args.requireMax(3);
        List<String> words = new ArrayList<>();
        while (args.hasAny()) {
            words.add(args.getString());
        }
        // give <player> <item> [count], or give <item> [count] for a butler, who already knows who is asking. two words
        // is the only ambiguous one: a number second means item+count, anything else means player+item
        boolean hasPlayer = words.size() == 3 || (words.size() == 2 && !isCount(words.get(1)));
        String username;
        String item;
        int count = 1;
        if (hasPlayer) {
            username = words.get(0);
            item = words.get(1);
            if (words.size() == 3) {
                count = parseCount(words.get(2));
            }
        } else {
            username = mod.getButler().hasCurrentUser() ? mod.getButler().getCurrentUser() : null;
            item = words.get(0);
            if (words.size() == 2) {
                count = parseCount(words.get(1));
            }
            if (username == null) {
                throw new CommandInvalidStateException("there is no butler user to give to, so say who: give <player> <item> [count]");
            }
        }
        item = ItemArgs.normalize(item);
        ItemTarget target = null;
        if (TaskCatalogue.taskExists(item)) {
            target = TaskCatalogue.getItemTarget(item, count);
        } else {
            // not catalogued, but it may well be sitting in the inventory
            for (int i = 0; i < mod.getPlayer().getInventory().getContainerSize(); ++i) {
                ItemStack stack = mod.getPlayer().getInventory().getItem(i);
                if (!stack.isEmpty() && ItemHelper.stripItemName(stack.getItem()).equals(item)) {
                    target = new ItemTarget(stack.getItem(), count);
                    break;
                }
            }
        }
        if (target == null) {
            throw new CommandInvalidStateException("item not found, and altoclef has no task for it: " + item);
        }
        Debug.logMessage("USER: " + username + " : ITEM: " + item + " x " + count);
        startTask(mod, new GiveItemToPlayerTask(username, target));
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        // same shapes as run: the first word is a player or an item, a second word that follows a player is the item,
        // and whatever comes after an item is a count, which has nothing to complete
        int n = args.getArgs().size();
        String first = args.peekString(0);
        if (n == 1) {
            return new TabCompleteHelper()
                    .append(TabListPlayer.online().stream())
                    .append(AltoItem.CATALOGUE.names().stream())
                    .append(inventoryNames().stream())
                    .filterPrefix(ItemArgs.normalize(first))
                    .sortAlphabetically()
                    .stream();
        }
        if (n == 2 && !isItem(first)) {
            return new TabCompleteHelper()
                    .append(AltoItem.CATALOGUE.names().stream())
                    .append(inventoryNames().stream())
                    .filterPrefix(ItemArgs.normalize(args.peekString(1)))
                    .sortAlphabetically()
                    .stream();
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Collect an item and give it to a player";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The give command gets an item (see get) and then brings it to a player and drops it at their feet.",
                "",
                "The player does not have to be close, altoclef tracks where they were last seen. Without a player name it gives to whoever is using you as a butler, which only works over a whisper.",
                "",
                "Usage:",
                "> give <player> <item> [count] - Give an item to a player.",
                "> give <item> [count] - Give an item to the butler user.",
                "",
                "Examples:",
                "> give Notch diamond 3"
        );
    }
}
