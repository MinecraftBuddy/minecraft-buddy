package adris.altoclef.ui.gui.model;

import adris.altoclef.ui.gui.facade.CommandsAccess.CommandInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

// ranks the command list for the help tab and turns a command's long description into rows the tab can draw
public final class HelpIndex {

    // just under a short description hit: somebody who typed a word that only shows up in the fine print still
    // gets the command, but after the ones that say it up front
    public static final int LONG_CONTAINS = Search.TEXT_CONTAINS - 10;

    public enum Kind { TEXT, USAGE, HEADING, GAP }

    // note is the "- what it does" tail of a usage line, empty for everything else
    public record Line(Kind kind, String text, String note) {
        public Line(Kind kind, String text) {
            this(kind, text, "");
        }
    }

    private HelpIndex() {
    }

    // what the player typed, as the command registry wants it: no chat prefix, no stray spaces
    public static String cleanLine(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        while (s.startsWith("#")) {
            s = s.substring(1).trim();
        }
        return s;
    }

    public static int score(CommandInfo c, String query) {
        String q = cleanQuery(query);
        if (q.isEmpty()) {
            return 0;
        }
        List<String> names = c.names();
        List<String> aliases = names.size() > 1 ? names.subList(1, names.size()) : List.of();
        int s = Search.score(q, c.name(), aliases, c.shortDesc());
        if (s > 0) {
            return s;
        }
        List<String> longDesc = c.longDesc();
        if (longDesc != null && startsAllWords(String.join(" ", longDesc), q)) {
            return LONG_CONTAINS;
        }
        return 0;
    }

    // the fine print is long and full of examples, so a hit there has to start a word: "go" finds "go to" and
    // "goto" but not the "go" in "iron_ingot"
    private static boolean startsAllWords(String text, String normalizedQuery) {
        String t = text.toLowerCase(Locale.ROOT);
        for (String word : normalizedQuery.split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(word)).matcher(t).find()) {
                return false;
            }
        }
        return !normalizedQuery.isEmpty();
    }

    private static String cleanQuery(String query) {
        String q = Search.normalize(query);
        while (q.startsWith("#")) {
            q = q.substring(1).trim();
        }
        return q;
    }

    // best match first, ties alphabetical. an empty query is the whole list, alphabetical
    public static List<CommandInfo> rank(List<CommandInfo> all, String query) {
        List<CommandInfo> out = new ArrayList<>();
        if (all == null) {
            return out;
        }
        Comparator<CommandInfo> byName = Comparator.comparing((CommandInfo c) -> c.name().toLowerCase(Locale.ROOT));
        if (cleanQuery(query).isEmpty()) {
            out.addAll(all);
            out.sort(byName);
            return out;
        }
        List<int[]> scored = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            int s = score(all.get(i), query);
            if (s > 0) {
                scored.add(new int[] {s, i});
            }
        }
        scored.sort((a, b) -> {
            if (a[0] != b[0]) {
                return Integer.compare(b[0], a[0]);
            }
            return byName.compare(all.get(a[1]), all.get(b[1]));
        });
        for (int[] s : scored) {
            out.add(all.get(s[1]));
        }
        return out;
    }

    // one row per line, "> usage" lines marked as such with the marker stripped, runs of blanks squashed to one gap and
    // no gap at either end. "Usage:" style lines become headings
    public static List<Line> lines(List<String> longDesc) {
        List<Line> out = new ArrayList<>();
        if (longDesc == null) {
            return out;
        }
        for (String raw : longDesc) {
            String t = raw == null ? "" : raw.trim();
            if (t.isEmpty()) {
                if (!out.isEmpty() && out.get(out.size() - 1).kind() != Kind.GAP) {
                    out.add(new Line(Kind.GAP, ""));
                }
            } else if (t.startsWith(">")) {
                String u = t.substring(1).trim();
                // baritone writes "> goto <x> <z> - Go to an X,Z position": the part before the dash is what you run
                int dash = u.indexOf(" - ");
                String note = dash < 0 ? "" : u.substring(dash + 3).trim();
                String cmd = dash < 0 ? u : u.substring(0, dash).trim();
                if (!cmd.isEmpty()) {
                    out.add(new Line(Kind.USAGE, cmd, note));
                }
            } else if (isHeading(t)) {
                out.add(new Line(Kind.HEADING, t.substring(0, t.length() - 1)));
            } else {
                out.add(new Line(Kind.TEXT, t));
            }
        }
        while (!out.isEmpty() && out.get(out.size() - 1).kind() == Kind.GAP) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    private static boolean isHeading(String t) {
        if (!t.endsWith(":") || t.length() < 2) {
            return false;
        }
        int words = t.split("\\s+").length;
        return words <= 2;
    }
}
