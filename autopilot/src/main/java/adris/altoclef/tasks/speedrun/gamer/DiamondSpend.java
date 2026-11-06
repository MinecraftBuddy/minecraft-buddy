package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig.KitItem;
import adris.altoclef.util.helpers.ItemHelper;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

// what the diamonds in the bag buy, and the only place that asks. a diamond is worth more as an upgrade than as a trophy, so the
// kit plan crafts the list below in order, each one when it is not already ours at diamond tier and what is left still pays for it.
// the sword is the weapon (see WeaponPick), no axe, no shovel, no hoe. pure: facts in, catalogue names out.
// KitPlanner.iron reads it (and drops the iron it replaces), WoodBudget.owed reads it for the sticks
public final class DiamondSpend {
    // the pick comes first and costs 3: what the dig for diamonds aims at
    public static final int MIN = 3;
    // the sword at the end of the list costs 2. below that there is nothing to choose
    public static final int CHEAPEST = 2;

    // pick first: it mines faster than anything we make and the side job that finds more diamonds wants it. then the armor,
    // chestplate first for the same reason as the iron plan (biggest chunk of points). the sword is last and only replaces the
    // iron one (2 ingots less to smelt), we never upgrade an iron sword we already hold
    private static final String[] ORDER = {"diamond_pickaxe", "diamond_chestplate", "diamond_leggings", "diamond_boots",
            "diamond_helmet", "diamond_sword"};
    private static final int[] COST = {3, 8, 7, 4, 5, 2};

    private DiamondSpend() {
    }

    // which of the six are still up for grabs (not owned at diamond tier, and in the plan at all). same order as ORDER
    public record Open(boolean pickaxe, boolean chestplate, boolean leggings, boolean boots, boolean helmet, boolean sword) {
        boolean at(int i) {
            return switch (i) {
                case 0 -> pickaxe;
                case 1 -> chestplate;
                case 2 -> leggings;
                case 3 -> boots;
                case 4 -> helmet;
                default -> sword;
            };
        }
    }

    public static int cost(String name) {
        for (int i = 0; i < ORDER.length; i++) {
            if (ORDER[i].equals(name)) {
                return COST[i];
            }
        }
        return 0;
    }

    // the greedy walk: in order, take what is open and what the diamonds left can pay for, and go on. an item we already own
    // costs nothing and takes nothing, so crafting the first pick of the list changes nothing about the rest of the answer
    // (the diamonds it ate and the item it became cancel out). one more diamond can still swap an item for a dearer one
    // (9 buys the boots, 10 the leggings), that is the price of using all of them
    public static List<String> spend(int diamonds, Open open) {
        List<String> out = new ArrayList<>();
        int left = diamonds;
        for (int i = 0; i < ORDER.length; i++) {
            if (open.at(i) && COST[i] <= left) {
                out.add(ORDER[i]);
                left -= COST[i];
            }
        }
        return out;
    }

    // what the bag can pay for, [] with the setting off or too few diamonds. the count is the same one the iron ingots use, so
    // diamonds sitting in the crafting grid or on the cursor are still ours and the answer holds still through a craft
    public static List<String> choose(GamerFacts f, OverworldConfig cfg) {
        if (!cfg.diamondSpend) {
            return List.of();
        }
        int diamonds = f.count(Items.DIAMOND);
        Open open = open(f, cfg);
        // the pick is first in line: two diamonds with the pick still open are saved for it, not spent on the sword
        return diamonds < (open.pickaxe() ? MIN : CHEAPEST) ? List.of() : spend(diamonds, open);
    }

    // better tiers count as owned (KitPlanner.have), so a netherite piece closes its slot too. an armor slot the plan has gone gold
    // in (PiglinGold) is not iron, so it is not ours to hand to a diamond either
    static Open open(GamerFacts f, OverworldConfig cfg) {
        List<String> slots = PiglinGold.swap(f, KitPlanner.armorNames(cfg));
        return new Open(
                KitPlanner.have(f, "diamond_pickaxe") == 0,
                slot(f, slots, "chestplate"),
                slot(f, slots, "leggings"),
                slot(f, slots, "boots"),
                slot(f, slots, "helmet"),
                swordOpen(f, cfg));
    }

    private static boolean slot(GamerFacts f, List<String> slots, String piece) {
        return slots.contains("iron_" + piece) && KitPlanner.have(f, "diamond_" + piece) == 0;
    }

    // only as a stand in for the iron sword: the kit has to want one and nothing iron or better can be in the bag
    private static boolean swordOpen(GamerFacts f, OverworldConfig cfg) {
        for (KitItem k : cfg.ironKit) {
            if ("iron_sword".equals(k.item)) {
                return KitPlanner.have(f, "iron_sword") == 0;
            }
        }
        return false;
    }

    // two sticks, or the planks or log they come out of, are in the bag: a diamond pick or sword can be crafted right here
    static boolean sticksInHand(GamerFacts f) {
        return f.count(Items.STICK) >= 2 || f.count(ItemHelper.PLANKS) >= 2 || f.count(ItemHelper.LOG) >= 1;
    }

    // is this iron kit item the one a chosen diamond item stands in for (iron_pickaxe <-> diamond_pickaxe, and so on)
    static boolean replaces(List<String> chosen, String ironItem) {
        return !chosen.isEmpty() && ironItem.startsWith("iron_") && chosen.contains("diamond_" + ironItem.substring("iron_".length()));
    }

    // the diamond pick is chosen: the iron one is not worth three ingots, and not worth smelting an early batch for either
    static boolean takesThePick(GamerFacts f, OverworldConfig cfg) {
        return choose(f, cfg).contains("diamond_pickaxe");
    }
}
