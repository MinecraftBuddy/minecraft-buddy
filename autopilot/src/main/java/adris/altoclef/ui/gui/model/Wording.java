package adris.altoclef.ui.gui.model;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

// the gui is soprano's, all of it. command descriptions and setting javadoc still say altoclef in places,
// so every string that reaches the screen goes through here first. setting names (altoFoo) are not words,
// they stay as they are
public final class Wording {

    private static final Pattern ALTOCLEF = Pattern.compile("altoclef('s)?", Pattern.CASE_INSENSITIVE);

    private Wording() {
    }

    public static String soprano(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        Matcher m = ALTOCLEF.matcher(s);
        if (!m.find()) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        do {
            String hit = m.group();
            // a folder or file name (baritone/altoclef/configs, altoclef_butler_whitelist.txt) is a real path
            // on the player's disk, renaming it in a description would send them looking for a file that is not
            // there. only the word gets replaced
            if (glued(s, m.start() - 1, -1) || glued(s, m.end(), 1)) {
                m.appendReplacement(out, Matcher.quoteReplacement(hit));
                continue;
            }
            boolean upper = Character.isUpperCase(hit.charAt(0));
            String word = upper ? "Soprano" : "soprano";
            if (m.group(1) != null) {
                word += "'s";
            }
            m.appendReplacement(out, Matcher.quoteReplacement(word));
        } while (m.find());
        m.appendTail(out);
        return out.toString();
    }

    // is the character at i part of a path or identifier the match sits in? a dot only counts when a name
    // continues past it (butler.json), a sentence ending in "altoclef." is still the word
    private static boolean glued(String s, int i, int dir) {
        if (i < 0 || i >= s.length()) {
            return false;
        }
        char c = s.charAt(i);
        if (c == '.') {
            int j = i + dir;
            return j >= 0 && j < s.length() && Character.isLetterOrDigit(s.charAt(j));
        }
        return c == '/' || c == '\\' || c == '_' || c == '-' || Character.isLetterOrDigit(c);
    }
}
