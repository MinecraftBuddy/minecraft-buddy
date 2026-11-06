package adris.altoclef.ui.gui.model;

import java.util.List;
import java.util.Locale;

// one ranking for every tab's search box. higher is better, 0 means "does not match". a name beats an alias beats the
// description, and inside a group an exact hit beats a prefix beats a contains. callers that have two description
// tiers (help has short and long) call this with the short one and fall back to the long one themselves
public final class Search {

    public static final int NAME_EXACT = 100;
    public static final int NAME_PREFIX = 90;
    public static final int NAME_CONTAINS = 80;
    public static final int ALIAS_EXACT = 70;
    public static final int ALIAS_PREFIX = 60;
    public static final int ALIAS_CONTAINS = 50;
    public static final int TEXT_CONTAINS = 40;

    private Search() {
    }

    public static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    // aliases and text may be null
    public static int score(String query, String name, List<String> aliases, String text) {
        String q = normalize(query);
        if (q.isEmpty()) {
            return 0;
        }
        String n = normalize(name);
        if (n.equals(q)) {
            return NAME_EXACT;
        }
        if (n.startsWith(q)) {
            return NAME_PREFIX;
        }
        if (n.contains(q)) {
            return NAME_CONTAINS;
        }
        int best = 0;
        if (aliases != null) {
            for (String a : aliases) {
                String al = normalize(a);
                if (al.equals(q)) {
                    best = Math.max(best, ALIAS_EXACT);
                } else if (al.startsWith(q)) {
                    best = Math.max(best, ALIAS_PREFIX);
                } else if (al.contains(q)) {
                    best = Math.max(best, ALIAS_CONTAINS);
                }
            }
        }
        if (best > 0) {
            return best;
        }
        return containsAllWords(text, q) ? TEXT_CONTAINS : 0;
    }

    public static boolean matches(String query, String name, List<String> aliases, String text) {
        return score(query, name, aliases, text) > 0;
    }

    // "mine ore" finds a description that says "ore" and "mine" in any order: people type words, not phrases
    public static boolean containsAllWords(String text, String normalizedQuery) {
        if (text == null || text.isEmpty() || normalizedQuery.isEmpty()) {
            return false;
        }
        String t = text.toLowerCase(Locale.ROOT);
        for (String word : normalizedQuery.split("\\s+")) {
            if (!word.isEmpty() && !t.contains(word)) {
                return false;
            }
        }
        return true;
    }
}
