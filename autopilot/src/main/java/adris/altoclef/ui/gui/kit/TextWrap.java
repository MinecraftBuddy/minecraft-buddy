package adris.altoclef.ui.gui.kit;

import java.util.ArrayList;
import java.util.List;

// word wrap for the few places the gui shows a paragraph (the update card, release notes). the kit otherwise
// ellipsizes, which is right for a row and wrong for a sentence you're meant to read to the end
public final class TextWrap {

    private TextWrap() {
    }

    // breaks on spaces, keeps the newlines that were already there (an empty line stays an empty line), and chops a
    // word that is wider than the box on its own so a url can't push the card open
    public static List<String> wrap(TextMeasurer m, String text, TextStyle style, float maxWidth) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String para : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            wrapParagraph(m, para, style, maxWidth, out);
        }
        return out;
    }

    private static void wrapParagraph(TextMeasurer m, String para, TextStyle style, float maxWidth, List<String> out) {
        String trimmed = para.strip();
        if (trimmed.isEmpty()) {
            out.add("");
            return;
        }
        StringBuilder line = new StringBuilder();
        for (String word : trimmed.split("\\s+")) {
            if (m.measure(word, style) > maxWidth) {
                // flush what we have, then the long word goes down in pieces
                if (!line.isEmpty()) {
                    out.add(line.toString());
                    line.setLength(0);
                }
                chop(m, word, style, maxWidth, out, line);
                continue;
            }
            if (line.isEmpty()) {
                line.append(word);
            } else if (m.measure(line + " " + word, style) <= maxWidth) {
                line.append(' ').append(word);
            } else {
                out.add(line.toString());
                line.setLength(0);
                line.append(word);
            }
        }
        if (!line.isEmpty()) {
            out.add(line.toString());
        }
    }

    // the tail piece lands in line so the next word can follow it
    private static void chop(TextMeasurer m, String word, TextStyle style, float maxWidth, List<String> out, StringBuilder line) {
        int start = 0;
        while (start < word.length()) {
            int end = start + 1;
            while (end < word.length() && m.measure(word.substring(start, end + 1), style) <= maxWidth) {
                end++;
            }
            String piece = word.substring(start, end);
            if (end >= word.length()) {
                line.append(piece);
            } else {
                out.add(piece);
            }
            start = end;
        }
    }
}
