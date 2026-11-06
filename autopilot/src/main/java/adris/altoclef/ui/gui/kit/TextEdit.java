package adris.altoclef.ui.gui.kit;

import java.util.function.IntPredicate;

// the single line editing model behind every text box: text, a caret, and an anchor that makes a selection when it
// is somewhere else. no drawing, no keys, so it is all unit tested. mutators return whether the text changed
public final class TextEdit {

    private String text = "";
    private int caret;
    private int anchor;
    private int maxLength = Integer.MAX_VALUE;
    private IntPredicate charFilter;

    public String text() {
        return text;
    }

    public int caret() {
        return caret;
    }

    public int anchor() {
        return anchor;
    }

    public int selStart() {
        return Math.min(caret, anchor);
    }

    public int selEnd() {
        return Math.max(caret, anchor);
    }

    public boolean hasSelection() {
        return caret != anchor;
    }

    public String selectedText() {
        return text.substring(selStart(), selEnd());
    }

    public void setMaxLength(int max) {
        maxLength = Math.max(0, max);
    }

    // chars the filter rejects are dropped on typing and pasting. setText is for code, it trusts the caller
    public void setCharFilter(IntPredicate filter) {
        charFilter = filter;
    }

    public void setText(String s) {
        String t = s == null ? "" : s;
        if (t.length() > maxLength) {
            t = t.substring(0, maxLength);
        }
        text = t;
        caret = t.length();
        anchor = caret;
    }

    public void setCaret(int pos, boolean extend) {
        caret = clamp(pos);
        if (!extend) {
            anchor = caret;
        }
    }

    public void selectAll() {
        anchor = 0;
        caret = text.length();
    }

    // double click: the run of same kind characters under pos
    public void selectWordAt(int pos) {
        int n = text.length();
        if (n == 0) {
            return;
        }
        int p = Math.min(clamp(pos), n - 1);
        char c = text.charAt(p);
        int a = p;
        int b = p + 1;
        if (Character.isWhitespace(c)) {
            while (a > 0 && Character.isWhitespace(text.charAt(a - 1))) {
                a--;
            }
            while (b < n && Character.isWhitespace(text.charAt(b))) {
                b++;
            }
        } else {
            boolean word = isWord(c);
            while (a > 0 && !Character.isWhitespace(text.charAt(a - 1)) && isWord(text.charAt(a - 1)) == word) {
                a--;
            }
            while (b < n && !Character.isWhitespace(text.charAt(b)) && isWord(text.charAt(b)) == word) {
                b++;
            }
        }
        anchor = a;
        caret = b;
    }

    public boolean insert(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        StringBuilder clean = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 32 || c == 127) {
                continue;
            }
            if (charFilter != null && !charFilter.test(c)) {
                continue;
            }
            clean.append(c);
        }
        if (clean.length() == 0) {
            return false;
        }
        int room = maxLength - (text.length() - (selEnd() - selStart()));
        if (room <= 0) {
            return false;
        }
        if (clean.length() > room) {
            clean.setLength(room);
        }
        int a = selStart();
        text = text.substring(0, a) + clean + text.substring(selEnd());
        caret = a + clean.length();
        anchor = caret;
        return true;
    }

    public boolean backspace(boolean word) {
        if (hasSelection()) {
            return deleteRange(selStart(), selEnd());
        }
        if (caret == 0) {
            return false;
        }
        int from = word ? wordLeft(text, caret) : caret - 1;
        return deleteRange(from, caret);
    }

    public boolean delete(boolean word) {
        if (hasSelection()) {
            return deleteRange(selStart(), selEnd());
        }
        if (caret >= text.length()) {
            return false;
        }
        int to = word ? wordRight(text, caret) : caret + 1;
        return deleteRange(caret, to);
    }

    public void left(boolean word, boolean extend) {
        if (!extend && hasSelection()) {
            caret = selStart();
            anchor = caret;
            return;
        }
        caret = word ? wordLeft(text, caret) : Math.max(0, caret - 1);
        if (!extend) {
            anchor = caret;
        }
    }

    public void right(boolean word, boolean extend) {
        if (!extend && hasSelection()) {
            caret = selEnd();
            anchor = caret;
            return;
        }
        caret = word ? wordRight(text, caret) : Math.min(text.length(), caret + 1);
        if (!extend) {
            anchor = caret;
        }
    }

    public void home(boolean extend) {
        caret = 0;
        if (!extend) {
            anchor = 0;
        }
    }

    public void end(boolean extend) {
        caret = text.length();
        if (!extend) {
            anchor = caret;
        }
    }

    public boolean copy(Clipboard clip) {
        if (!hasSelection()) {
            return false;
        }
        clip.set(selectedText());
        return true;
    }

    public boolean cut(Clipboard clip) {
        if (!copy(clip)) {
            return false;
        }
        return deleteRange(selStart(), selEnd());
    }

    // a pasted newline is just a space, this is a one line box
    public boolean paste(Clipboard clip) {
        String s = clip.get();
        if (s == null || s.isEmpty()) {
            return false;
        }
        return insert(s.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ').replace('\t', ' '));
    }

    private boolean deleteRange(int from, int to) {
        if (from >= to) {
            return false;
        }
        text = text.substring(0, from) + text.substring(to);
        caret = from;
        anchor = from;
        return true;
    }

    private int clamp(int pos) {
        return Math.max(0, Math.min(text.length(), pos));
    }

    private static boolean isWord(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    // skip spaces going left, then one run of the same kind. so "foo.bar" takes three hops, like every editor
    public static int wordLeft(String s, int pos) {
        int i = pos;
        while (i > 0 && Character.isWhitespace(s.charAt(i - 1))) {
            i--;
        }
        if (i > 0) {
            boolean word = isWord(s.charAt(i - 1));
            while (i > 0 && !Character.isWhitespace(s.charAt(i - 1)) && isWord(s.charAt(i - 1)) == word) {
                i--;
            }
        }
        return i;
    }

    // one run of the same kind, then the spaces after it, so you land on the start of the next thing
    public static int wordRight(String s, int pos) {
        int n = s.length();
        int i = pos;
        if (i < n && !Character.isWhitespace(s.charAt(i))) {
            boolean word = isWord(s.charAt(i));
            while (i < n && !Character.isWhitespace(s.charAt(i)) && isWord(s.charAt(i)) == word) {
                i++;
            }
        }
        while (i < n && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }
}
