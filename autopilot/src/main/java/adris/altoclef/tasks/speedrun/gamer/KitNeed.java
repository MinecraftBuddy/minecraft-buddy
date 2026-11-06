package adris.altoclef.tasks.speedrun.gamer;

// "hold this many of this catalogue item in total", already net of what we own so a phase can hand it straight to the
// catalogue. the three special names are not catalogue items, KitRunner turns them into their own tasks
public record KitNeed(String catalogueName, int count) {
    public static final String FOOD = "food";
    public static final String BUILD_BLOCKS = "build_blocks";
    public static final String EQUIP_ARMOR = "equip_armor";
    // cook the raw meat in the bag, in a smoker or in a furnace (CookGate picks which). not catalogue items either
    public static final String COOK_SMOKER = "cook_in_smoker";
    public static final String COOK_FURNACE = "cook_in_furnace";

    public boolean isSpecial() {
        return FOOD.equals(catalogueName) || BUILD_BLOCKS.equals(catalogueName) || EQUIP_ARMOR.equals(catalogueName) || isCookName(catalogueName);
    }

    public static boolean isCookName(String name) {
        return COOK_SMOKER.equals(name) || COOK_FURNACE.equals(name);
    }

    // true for needs that are about walking around and mining instead of standing at a crafting table
    public boolean isGathering() {
        return isGatheringName(catalogueName);
    }

    // a catalogue item we make at a table (stone pickaxe, bucket, shears...): the table has to stay put until it is done
    public boolean isCraft() {
        return isCraftName(catalogueName);
    }

    public static boolean isGatheringName(String name) {
        return switch (name == null ? "" : name) {
            // flint, logs, planks and coal are the filler while iron cooks (SmeltFiller), none of them is made at a table.
            // cobblestone is the one trip's worth of stone the gather mines before the stone crafts, it is mined not crafted
            // cooking stands at a smoker, not a table, so it never holds the table either
            // the diamond pick's dig is a mine like the iron, it never holds a table
            case "iron_ingot", "diamond", "raw_gold", "wool", FOOD, BUILD_BLOCKS, "flint", "log", "planks", "coal", "cobblestone", COOK_SMOKER, COOK_FURNACE -> true;
            default -> false;
        };
    }

    public static boolean isCraftName(String name) {
        return name != null && !isGatheringName(name) && !EQUIP_ARMOR.equals(name);
    }
}
