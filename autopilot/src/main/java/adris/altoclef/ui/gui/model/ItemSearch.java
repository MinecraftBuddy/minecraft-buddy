package adris.altoclef.ui.gui.model;

import adris.altoclef.ui.gui.facade.ItemsAccess.ItemEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// ranking for the item grid: exact name, then prefix, then the start of a word after an underscore, then anywhere.
// inside a tier the facade's order stays, so a search never reshuffles things for no reason
public final class ItemSearch {

    public static final int NONE = -1;
    public static final int EXACT = 0;
    public static final int PREFIX = 1;
    public static final int WORD = 2;
    public static final int CONTAINS = 3;

    private ItemSearch() {
    }

    // players type "iron ingot" and mean iron_ingot
    public static String normalize(String query) {
        if (query == null) {
            return "";
        }
        return query.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "_");
    }

    // query must already be normalized
    public static int rank(String name, String query) {
        if (query.isEmpty()) {
            return PREFIX;
        }
        String n = name.toLowerCase(Locale.ROOT);
        if (n.equals(query)) {
            return EXACT;
        }
        if (n.startsWith(query)) {
            return PREFIX;
        }
        if (n.contains("_" + query)) {
            return WORD;
        }
        return n.contains(query) ? CONTAINS : NONE;
    }

    // an entry matches by its command name or by what the player reads, whichever ranks better ("Raw Iron" and raw_iron
    // are the same thing in english, in other languages they are not)
    public static int rank(ItemEntry e, String query) {
        int byName = rank(e.name(), query);
        String shown = e.displayName();
        int byShown = shown == null || shown.isBlank() ? NONE : rank(normalize(shown), query);
        if (byName == NONE) {
            return byShown;
        }
        return byShown == NONE ? byName : Math.min(byName, byShown);
    }

    public static List<ItemEntry> filter(List<ItemEntry> all, String query) {
        String q = normalize(query);
        if (q.isEmpty()) {
            return new ArrayList<>(all);
        }
        List<List<ItemEntry>> tiers = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        for (ItemEntry e : all) {
            int r = rank(e, q);
            if (r != NONE) {
                tiers.get(r).add(e);
            }
        }
        List<ItemEntry> out = new ArrayList<>();
        for (List<ItemEntry> t : tiers) {
            out.addAll(t);
        }
        return out;
    }

    // what enter in the search box picks, or null
    public static ItemEntry first(List<ItemEntry> all, String query) {
        List<ItemEntry> hits = filter(all, query);
        return hits.isEmpty() ? null : hits.get(0);
    }
}
