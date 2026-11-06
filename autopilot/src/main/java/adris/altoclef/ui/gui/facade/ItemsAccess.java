package adris.altoclef.ui.gui.facade;

import java.util.List;

// what the get tab can ask for. "get" is altoclef's TaskCatalogue (it crafts, smelts and mines its way there),
// "mine" is baritone's mine process (blocks only, no count means keep going)
public interface ItemsAccess {

    // name is what the command takes ("oak_log"), displayName what the player reads ("Oak Log", the item's own
    // name in the player's language, or the id title-cased when no single item owns the name), iconItemId is a
    // registry id for the icon ("minecraft:oak_log"), maxStack is the stack size shift-click steps by, category is
    // its browsing group ("Blocks", "Tools"...), a creative tab title, or "" when there is none
    record ItemEntry(String name, String displayName, String iconItemId, int maxStack, String category) {

        public ItemEntry(String name, String displayName, String iconItemId, int maxStack) {
            this(name, displayName, iconItemId, maxStack, "");
        }

        // "oak_log" -> "Oak Log", for names no item can speak for
        public static String titleCase(String id) {
            String path = id == null ? "" : id.substring(id.indexOf(':') + 1);
            StringBuilder sb = new StringBuilder(path.length());
            boolean up = true;
            for (char c : path.toCharArray()) {
                if (c == '_' || c == ' ') {
                    sb.append(' ');
                    up = true;
                } else {
                    sb.append(up ? Character.toUpperCase(c) : c);
                    up = false;
                }
            }
            return sb.toString();
        }
    }

    record BasketLine(String name, int count) {
    }

    List<ItemEntry> gettable();

    List<ItemEntry> mineable();

    Result get(List<BasketLine> lines);

    // count 0 means "no limit"
    Result mine(List<BasketLine> lines);
}
