package adris.altoclef.tasks.speedrun.gamer;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

// pure: piglins only leave us alone while ONE piece of gold armor is on, so the kit swaps one iron piece for a gold one
// when that is cheap. a gold helmet is 2 armor like the iron one (and saves 5 iron), gold boots lose 1 armor and save 4.
// the gold itself: the overworld detour digs raw gold with the iron (ResourceDetour.gold), the portal gate crafts the piece, and
// NetherGoldTask is the last chance in the nether (nugget ore, when piglins are not around to be angered by it)
public final class PiglinGold {
    // the order we would rather give a slot up, by armor lost vs iron: helmet 0, chestplate 1, boots 1, leggings 2
    private static final String[] SLOTS = {"helmet", "chestplate", "boots", "leggings"};
    private static final Item[] GOLD = {Items.GOLDEN_HELMET, Items.GOLDEN_CHESTPLATE, Items.GOLDEN_BOOTS, Items.GOLDEN_LEGGINGS};
    public static final int HELMET_INGOTS = 5;
    public static final int BOOTS_INGOTS = 4;

    private PiglinGold() {
    }

    public static boolean worn(GamerFacts f) {
        for (Item piece : GOLD) {
            if (f.armorEquipped(piece)) {
                return true;
            }
        }
        return false;
    }

    // nothing gold on or in the bag and not the gold for a piece either: the nether fallback will dig nuggets, and turning them into
    // a piece takes a crafting table, so one rides along from the overworld (four planks here, nothing but luck there). a table the planner only
    // remembers at a far station does not count, the portal stops waiting for it past 48 blocks
    public static List<KitNeed> tableForNether(GamerFacts f) {
        if (worn(f) || holdsPiece(f) || available(f) >= BOOTS_INGOTS || f.has(Items.CRAFTING_TABLE) || f.tablePlacedNearby()) {
            return List.of();
        }
        return List.of(new KitNeed("crafting_table", 1));
    }

    // a gold piece in the bag (not on us), the gate has nothing left to craft then and the equip step puts it on
    public static boolean holdsPiece(GamerFacts f) {
        return piecesHeld(f) > 0;
    }

    // gold ingots we could end up with without leaving the spot: ingots, blocks (9), nuggets (9 a piece) and raw gold
    // (smelted by CollectGoldIngotTask). what we are wearing or carrying as armor is counted separately
    public static int available(GamerFacts f) {
        return f.count(Items.GOLD_INGOT) + 9 * f.count(Items.GOLD_BLOCK) + f.count(Items.GOLD_NUGGET) / 9 + f.count(Items.RAW_GOLD);
    }

    // "iron_helmet" -> "golden_helmet" for the one slot we are going gold in, everything else as it was. recomputed from
    // the facts every call, the held/worn rule is what makes the swap stick once the piece exists
    public static List<String> swap(GamerFacts f, List<String> names) {
        String slot = slot(f, names);
        if (slot == null) {
            return names;
        }
        List<String> out = new ArrayList<>(names.size());
        for (String name : names) {
            out.add(name.equals("iron_" + slot) ? "golden_" + slot : name);
        }
        return out;
    }

    // the slot of the plan that goes gold, or null. a gold piece we wear or hold in a plan slot wins (worn first, then by
    // armor lost), otherwise the cheapest slot we can pay for. any gold piece held outside the plan is just equipped,
    // no point paying for a second one
    static String slot(GamerFacts f, List<String> names) {
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < SLOTS.length; i++) {
                boolean has = pass == 0 ? f.armorEquipped(GOLD[i]) : f.has(GOLD[i]);
                if (GOLD[i] == Items.GOLDEN_BOOTS && diamondBootsWorn(f)) {
                    continue;
                }
                if (has && names.contains("iron_" + SLOTS[i])) {
                    return SLOTS[i];
                }
            }
        }
        if (piecesHeld(f) > 0) {
            return null;
        }
        int gold = available(f);
        if (gold >= HELMET_INGOTS && names.contains("iron_helmet") && !(bootsInstead(f) && names.contains("iron_boots"))) {
            return "helmet";
        }
        if (gold >= BOOTS_INGOTS && names.contains("iron_boots") && !diamondBootsWorn(f)) {
            return "boots";
        }
        return null;
    }

    // the slot the nether fallback digs for: the one slot() already settled on (gold held, or a piece in the bag), else the boots,
    // 4 ingots and the cheapest to dig. 36 nuggets is a lot of ore as it is
    public static String netherSlot(GamerFacts f, List<String> names) {
        String slot = slot(f, names);
        return slot != null ? slot : diamondBootsWorn(f) ? "helmet" : "boots";
    }

    // the gold piece for a slot
    public static Item piece(String slot) {
        for (int i = 0; i < SLOTS.length; i++) {
            if (SLOTS[i].equals(slot)) {
                return GOLD[i];
            }
        }
        return null;
    }

    // ingots of that slot's gold piece
    public static int ingotsFor(String slot) {
        return "helmet".equals(slot) ? HELMET_INGOTS : BOOTS_INGOTS;
    }

    // the helmet is already diamond and the boots are not: the gold goes on the boots (4 ingots, 5 spent diamonds stay on our head).
    // the diamond helmet is only ever made while the slot is still iron, so this never flips a slot that went gold first
    private static boolean bootsInstead(GamerFacts f) {
        return KitPlanner.have(f, "diamond_helmet") > 0 && KitPlanner.have(f, "diamond_boots") == 0 && available(f) >= BOOTS_INGOTS;
    }

    // a gold piece in the bag while none is on gets worn even when its slot is not in the plan (lighter plans, chestplate
    // or leggings out of a chest). appended to what toEquip already found if that is not the same piece
    public static void addToEquip(GamerFacts f, List<Item> equip) {
        if (worn(f)) {
            return;
        }
        for (Item piece : GOLD) {
            if (f.has(piece)) {
                if (piece == Items.GOLDEN_BOOTS && diamondBootsWorn(f)) {
                    continue;
                }
                if (!equip.contains(piece)) {
                    equip.add(piece);
                }
                return;
            }
        }
    }

    // last chance before the portal: nothing gold on, nothing gold in the bag, but the gold for a helmet (or boots) is
    // there. the catalogue crafts it, the equip step that follows puts it on. empty when there is nothing to do
    public static List<KitNeed> gate(GamerFacts f) {
        if (worn(f) || piecesHeld(f) > 0) {
            return List.of();
        }
        int gold = available(f);
        if (gold >= HELMET_INGOTS && !bootsInstead(f)) {
            return List.of(new KitNeed("golden_helmet", 1));
        }
        // boots over worn diamond boots would throw the diamonds away for a piglin pass
        return gold >= BOOTS_INGOTS && !diamondBootsWorn(f) ? List.of(new KitNeed("golden_boots", 1)) : List.of();
    }

    // gold pieces in the bag that are worth wearing. golden boots next to diamond ones are dead weight, they would never go on, so
    // they must not stop the helmet from being planned
    private static int piecesHeld(GamerFacts f) {
        int n = f.count(GOLD);
        return diamondBootsWorn(f) ? n - f.count(Items.GOLDEN_BOOTS) : n;
    }

    // diamond or netherite boots on our feet, the one slot gold must never take over
    static boolean diamondBootsWorn(GamerFacts f) {
        return f.armorEquipped(Items.DIAMOND_BOOTS) || f.armorEquipped(Items.NETHERITE_BOOTS);
    }
}
