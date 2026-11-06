package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.misc.EquipArmorTask;
import adris.altoclef.util.ItemTarget;
import baritone.api.IBaritone;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public class EquipCommand extends AltoClefCommand {

    public EquipCommand(IBaritone baritone) {
        super(baritone, "equip");
    }

    // the one word shortcuts for a full set, null for anything else
    private static ItemTarget[] armorSet(String word) {
        Item[] pieces = switch (word.toLowerCase()) {
            case "leather" -> new Item[]{Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS};
            case "iron" -> new Item[]{Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS};
            case "gold" -> new Item[]{Items.GOLDEN_HELMET, Items.GOLDEN_CHESTPLATE, Items.GOLDEN_LEGGINGS, Items.GOLDEN_BOOTS};
            case "diamond" -> new Item[]{Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS};
            case "netherite" -> new Item[]{Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS};
            default -> null;
        };
        if (pieces == null) {
            return null;
        }
        return Arrays.stream(pieces).map(ItemTarget::new).toArray(ItemTarget[]::new);
    }

    @Override
    protected void run(AltoClef mod, String label, IArgConsumer args) throws CommandException {
        args.requireMin(1);
        // a lone "iron" is the whole set, anything longer is a list of pieces
        ItemTarget[] items = args.hasExactlyOne() ? armorSet(args.peekString()) : null;
        if (items != null) {
            args.get();
        } else {
            items = parseItems(args, AltoItem.ARMOR);
        }
        startTask(mod, new EquipArmorTask(items).billed());
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        return args.tabCompleteDatatype(AltoItem.ARMOR);
    }

    @Override
    public String getShortDesc() {
        return "Equip armor";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The equip command has altoclef put on armor, getting any pieces it does not have yet.",
                "",
                "A single set name (leather, iron, gold, diamond, netherite) means all four pieces of that set. Otherwise give it the pieces, with an optional count each. Only armor is accepted.",
                "",
                "Usage:",
                "> equip <set> - Equip a whole armor set.",
                "> equip <piece> [count] <piece> [count] ... - Equip these pieces.",
                "",
                "Examples:",
                "> equip diamond",
                "> equip iron_helmet iron_boots"
        );
    }
}
