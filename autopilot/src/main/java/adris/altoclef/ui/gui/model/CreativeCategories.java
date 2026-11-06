package adris.altoclef.ui.gui.model;

import java.util.List;
import java.util.Locale;

// short browsing groups, shared by the creative inventory adapter and the get tab
public final class CreativeCategories {

    private CreativeCategories() {
    }

    public static final List<String> GROUPS = List.of("Blocks", "Tools", "Combat", "Food", "Materials", "Redstone", "Other");

    public static String group(String category) {
        String key = category == null ? "" : category.toLowerCase(Locale.ROOT).replace(' ', '_');
        return switch (key) {
            case "blocks", "building_blocks", "colored_blocks", "natural_blocks", "functional_blocks" -> "Blocks";
            case "tools", "tools_and_utilities", "tools_&_utilities" -> "Tools";
            case "combat" -> "Combat";
            case "food", "food_and_drinks", "food_&_drinks" -> "Food";
            case "materials", "ingredients" -> "Materials";
            case "redstone", "redstone_blocks" -> "Redstone";
            default -> "Other";
        };
    }

}
