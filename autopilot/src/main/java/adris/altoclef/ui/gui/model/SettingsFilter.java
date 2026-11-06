package adris.altoclef.ui.gui.model;

import adris.altoclef.ui.gui.facade.SettingsAccess.SettingView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

// the category column, "modified only" and the search box all narrow the same list, so they narrow it in one place.
// with a query the result is ranked: name prefix, then name contains, then description, then category. without one the
// list keeps the order it came in (sorted by name)
public final class SettingsFilter {

    public static final String ALL = "All";

    private SettingsFilter() {
    }

    public static List<SettingView> apply(List<SettingView> all, String category, boolean modifiedOnly, String query,
                                          Function<String, String> description) {
        return apply(all, category, modifiedOnly, true, query, description);
    }

    // showAdvanced false hides the tuning the everyday player does not reach for, so the list reads like a menu and
    // not a config file
    public static List<SettingView> apply(List<SettingView> all, String category, boolean modifiedOnly, boolean showAdvanced,
                                          String query, Function<String, String> description) {
        boolean anyCategory = category == null || category.isEmpty() || category.equals(ALL);
        String[] tokens = tokens(query);
        List<SettingView> out = new ArrayList<>();
        List<Integer> tiers = new ArrayList<>();
        for (SettingView v : all) {
            if (!showAdvanced && SettingCategories.isAdvanced(v.name())) {
                continue;
            }
            if (modifiedOnly && !v.modified()) {
                continue;
            }
            String cat = SettingCategories.categoryOf(v.name());
            if (!anyCategory && !cat.equals(category)) {
                continue;
            }
            int tier = tokens.length == 0 ? 0 : tierOf(v.name(), description.apply(v.name()), cat, tokens);
            if (tier < 0) {
                continue;
            }
            out.add(v);
            tiers.add(tier);
        }
        if (tokens.length == 0) {
            return out;
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < out.size(); i++) {
            order.add(i);
        }
        // stable, so equal tiers stay alphabetical
        order.sort(Comparator.comparingInt(tiers::get));
        List<SettingView> ranked = new ArrayList<>(out.size());
        for (int i : order) {
            ranked.add(out.get(i));
        }
        return ranked;
    }

    // how many settings each category has, in the column's order, only the ones that have any
    public static Map<String, Integer> counts(List<SettingView> all) {
        return counts(all, true);
    }

    public static Map<String, Integer> counts(List<SettingView> all, boolean showAdvanced) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String c : SettingCategories.names()) {
            out.put(c, 0);
        }
        for (SettingView v : all) {
            if (!showAdvanced && SettingCategories.isAdvanced(v.name())) {
                continue;
            }
            out.merge(SettingCategories.categoryOf(v.name()), 1, Integer::sum);
        }
        out.values().removeIf(n -> n == 0);
        return out;
    }

    public static int modifiedCount(List<SettingView> all) {
        int n = 0;
        for (SettingView v : all) {
            if (v.modified()) {
                n++;
            }
        }
        return n;
    }

    public static int advancedCount(List<SettingView> all) {
        int n = 0;
        for (SettingView v : all) {
            if (SettingCategories.isAdvanced(v.name())) {
                n++;
            }
        }
        return n;
    }

    private static String[] tokens(String query) {
        if (query == null || query.isBlank()) {
            return new String[0];
        }
        return query.trim().toLowerCase(Locale.ROOT).split("\\s+");
    }

    // every word has to land somewhere. an item ranks by its worst word, so "parkour place" puts allowParkourPlace
    // above something that only mentions parkour in its description. -1 when a word lands nowhere
    private static int tierOf(String name, String description, String category, String[] tokens) {
        String n = name.toLowerCase(Locale.ROOT);
        String d = description == null ? "" : description.toLowerCase(Locale.ROOT);
        String c = category.toLowerCase(Locale.ROOT);
        int worst = 0;
        for (String t : tokens) {
            int tier;
            if (n.startsWith(t)) {
                tier = 0;
            } else if (n.contains(t)) {
                tier = 1;
            } else if (d.contains(t)) {
                tier = 2;
            } else if (c.contains(t)) {
                tier = 3;
            } else {
                return -1;
            }
            worst = Math.max(worst, tier);
        }
        return worst;
    }
}
