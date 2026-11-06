package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.container.StoreInAnyContainerTask;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.PlayerSlot;
import baritone.api.IBaritone;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.apache.commons.lang3.ArrayUtils;

public class DepositCommand extends AltoClefCommand {

    public DepositCommand(IBaritone baritone) {
        super(baritone, "deposit");
    }

    // everything except worn armor and tools, which is what you want to keep when you empty out
    public static ItemTarget[] getAllNonEquippedOrToolItemsAsTarget(AltoClef mod) {
        return StorageHelper.getAllInventoryItemsAsTargets(slot -> {
            // Ignore armor
            if (ArrayUtils.contains(PlayerSlot.ARMOR_SLOTS, slot))
                return false;
            ItemStack stack = StorageHelper.getItemStackInSlot(slot);
            // Ignore tools
            if (!stack.isEmpty()) {
                Item item = stack.getItem();
                return !ItemHelper.isTool(item);
            }
            return false;
        });
    }

    @Override
    protected void run(AltoClef mod, String label, IArgConsumer args) throws CommandException {
        ItemTarget[] items = args.hasAny() ? parseItems(args, AltoItem.ANY_ITEM) : getAllNonEquippedOrToolItemsAsTarget(mod);
        startTask(mod, new StoreInAnyContainerTask(false, items));
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        return args.tabCompleteDatatype(AltoItem.ANY_ITEM);
    }

    @Override
    public String getShortDesc() {
        return "Deposit items into a container";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The deposit command finds a chest or other container and stores your items in it.",
                "",
                "With no items it stores everything except tools and armor you are wearing. Items can be catalogue names (see get) or any item id, each with an optional count.",
                "",
                "Usage:",
                "> deposit - Store everything that is not gear.",
                "> deposit <item> [count] <item> [count] ... - Store just these.",
                "",
                "Examples:",
                "> deposit cobblestone 64 dirt"
        );
    }
}
