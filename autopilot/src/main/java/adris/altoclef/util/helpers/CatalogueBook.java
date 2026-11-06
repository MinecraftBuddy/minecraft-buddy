package adris.altoclef.util.helpers;

import adris.altoclef.TaskCatalogue;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.RecipeTarget;
import adris.altoclef.util.WoodType;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

// RecipeBill's view of the catalogue: shaped recipes, the edge book (TaskCatalogue.billEdge) and the up-front list
// (gatherRequirement). counting the bag is handed in so a test can count a pretend one
public final class CatalogueBook implements RecipeBill.Book {

    // item -> the catalogue name that knows how to make it, for slots and targets that come as items or as an alias
    private static Map<Item, String> _byItem;

    private final ToIntFunction<Item[]> _count;

    public CatalogueBook(ToIntFunction<Item[]> count) {
        _count = count;
    }

    @Override
    public RecipeBill.Way wayOf(String name) {
        RecipeTarget recipe = TaskCatalogue.recipeOf(name);
        if (recipe != null) {
            Map<String, Integer> inputs = new LinkedHashMap<>();
            for (ItemTarget slot : recipe.getRecipe().getSlots()) {
                if (slot == null || slot.isEmpty()) continue;
                String in = nameOf(slot);
                // a slot nobody can name is a slot nobody can plan for, the recipe task gets to deal with it
                if (in == null) return null;
                inputs.merge(in, 1, Integer::sum);
            }
            if (inputs.isEmpty()) return null;
            RecipeBill.Kind kind = recipe.getRecipe().isBig() ? RecipeBill.Kind.CRAFT_TABLE : RecipeBill.Kind.CRAFT;
            return new RecipeBill.Way(kind, inputs, recipe.getRecipe().outputCount());
        }
        TaskCatalogue.BillEdge edge = TaskCatalogue.billEdge(name);
        if (edge == null) {
            return null;
        }
        RecipeBill.Kind kind = switch (edge.step()) {
            case SMELT -> RecipeBill.Kind.SMELT;
            case FILL -> RecipeBill.Kind.FILL;
            case CRAFT -> RecipeBill.Kind.CRAFT;
        };
        return new RecipeBill.Way(kind, Map.of(edge.input(), edge.inPerRun()), edge.outPerRun());
    }

    @Override
    public int gatherTier(String name) {
        // anything needsNether flags stays off the up-front list. the schematic builder already asked about those on
        // its own, and a leaf fetch that wanders into a portal is not what anybody signed up for
        Item[] matches = TaskCatalogue.getItemMatches(name);
        if (matches.length == 1 && TaskCatalogue.needsNether(matches[0])) {
            return -1;
        }
        MiningRequirement req = TaskCatalogue.gatherRequirement(name);
        return req == null ? -1 : req.ordinal();
    }

    @Override
    public int held(String name) {
        return _count.applyAsInt(TaskCatalogue.getItemMatches(name));
    }

    @Override
    public List<String> woods() {
        List<String> out = new ArrayList<>();
        for (WoodType type : WoodType.values()) {
            ItemHelper.WoodItems wood = ItemHelper.getWoodItems(type);
            if (wood.planks != null && wood.log != null) out.add(wood.prefix);
        }
        return out;
    }

    // the name the bill should use for a target: "chain" and iron_chain are one entry, and the bill only knows the
    // recipe under the real name. null when the catalogue has no single entry for it
    public static String canonical(ItemTarget target) {
        if (target.isCatalogueItem()) {
            Item[] matches = TaskCatalogue.getItemMatches(target.getCatalogueName());
            if (matches.length == 1) {
                String byItem = byItem().get(matches[0]);
                if (byItem != null) return byItem;
            }
            return TaskCatalogue.taskExists(target.getCatalogueName()) ? target.getCatalogueName() : null;
        }
        Item[] matches = target.getMatches();
        return matches.length == 1 ? byItem().get(matches[0]) : null;
    }

    private static String nameOf(ItemTarget slot) {
        return slot.isCatalogueItem() ? slot.getCatalogueName() : (slot.getMatches().length == 1 ? byItem().get(slot.getMatches()[0]) : null);
    }

    // of the names that stand for one item, the one with a way to make it or fetch it wins. an alias shares the entry,
    // but recipeOf and the edge book only know the real name
    private static synchronized Map<Item, String> byItem() {
        if (_byItem == null) {
            Map<Item, String> map = new HashMap<>();
            for (String name : TaskCatalogue.resourceNames()) {
                Item[] matches = TaskCatalogue.getItemMatches(name);
                if (matches.length != 1) continue;
                String old = map.get(matches[0]);
                if (old == null || (!known(old) && known(name)) || (known(old) == known(name) && name.compareTo(old) < 0)) {
                    map.put(matches[0], name);
                }
            }
            _byItem = map;
        }
        return _byItem;
    }

    private static boolean known(String name) {
        return TaskCatalogue.recipeOf(name) != null || TaskCatalogue.billEdge(name) != null || TaskCatalogue.gatherRequirement(name) != null;
    }
}
