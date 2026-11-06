package adris.altoclef.ui.gui.kit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TextEditTest {

    private static TextEdit of(String s) {
        TextEdit e = new TextEdit();
        e.setText(s);
        return e;
    }

    @Test
    public void typingMovesTheCaret() {
        TextEdit e = new TextEdit();
        assertTrue(e.insert("ab"));
        assertEquals("ab", e.text());
        assertEquals(2, e.caret());
        e.left(false, false);
        e.insert("X");
        assertEquals("aXb", e.text());
        assertEquals(2, e.caret());
    }

    @Test
    public void controlCharsAreDropped() {
        TextEdit e = new TextEdit();
        assertFalse(e.insert("\n\t"));
        e.insert("a\u0001b");
        assertEquals("ab", e.text());
    }

    @Test
    public void typingOverASelectionReplacesIt() {
        TextEdit e = of("hello");
        e.selectAll();
        e.insert("x");
        assertEquals("x", e.text());
        assertFalse(e.hasSelection());
    }

    @Test
    public void backspaceAndDelete() {
        TextEdit e = of("abc");
        e.left(false, false);
        assertTrue(e.backspace(false));
        assertEquals("ac", e.text());
        assertEquals(1, e.caret());
        assertTrue(e.delete(false));
        assertEquals("a", e.text());
        assertFalse(e.delete(false));
        e.home(false);
        assertFalse(e.backspace(false));
    }

    @Test
    public void backspaceDeletesTheSelectionFirst() {
        TextEdit e = of("abcdef");
        e.setCaret(1, false);
        e.setCaret(4, true);
        assertEquals("bcd", e.selectedText());
        e.backspace(false);
        assertEquals("aef", e.text());
        assertEquals(1, e.caret());
    }

    @Test
    public void ctrlBackspaceDeletesAWord() {
        TextEdit e = of("foo bar");
        e.backspace(true);
        assertEquals("foo ", e.text());
        e.backspace(true);
        assertEquals("", e.text());
    }

    @Test
    public void ctrlDeleteTakesTheWordAndTheSpaceAfter() {
        TextEdit e = of("foo bar");
        e.home(false);
        e.delete(true);
        assertEquals("bar", e.text());
    }

    @Test
    public void wordJumpsSplitLettersFromPunctuation() {
        TextEdit e = of("foo.bar baz");
        e.home(false);
        e.right(true, false);
        assertEquals(3, e.caret());
        e.right(true, false);
        assertEquals(4, e.caret());
        e.right(true, false);
        assertEquals(8, e.caret());
        e.right(true, false);
        assertEquals(11, e.caret());
        e.left(true, false);
        assertEquals(8, e.caret());
        e.left(true, false);
        assertEquals(4, e.caret());
        e.left(true, false);
        assertEquals(3, e.caret());
    }

    @Test
    public void underscoreCountsAsPartOfAWord() {
        TextEdit e = of("oak_log x");
        e.home(false);
        e.right(true, false);
        assertEquals(8, e.caret());
    }

    @Test
    public void shiftArrowsExtendAndPlainArrowsCollapseToTheEdge() {
        TextEdit e = of("abcdef");
        e.home(false);
        e.right(false, true);
        e.right(false, true);
        assertEquals("ab", e.selectedText());
        e.right(false, false);
        assertFalse(e.hasSelection());
        assertEquals(2, e.caret());
        e.setCaret(4, false);
        e.left(false, true);
        e.left(false, true);
        assertEquals("cd", e.selectedText());
        e.left(false, false);
        assertEquals(2, e.caret());
        assertFalse(e.hasSelection());
    }

    @Test
    public void homeEndWithShiftSelect() {
        TextEdit e = of("hello");
        e.setCaret(2, false);
        e.end(true);
        assertEquals("llo", e.selectedText());
        e.home(true);
        assertEquals("he", e.selectedText());
    }

    @Test
    public void clipboardCopyCutPaste() {
        Clipboard clip = Clipboard.inMemory();
        TextEdit e = of("hello world");
        e.setCaret(0, false);
        e.setCaret(5, true);
        assertTrue(e.copy(clip));
        assertEquals("hello", clip.get());
        assertEquals("hello world", e.text());
        assertTrue(e.cut(clip));
        assertEquals(" world", e.text());
        e.end(false);
        assertTrue(e.paste(clip));
        assertEquals(" worldhello", e.text());
    }

    @Test
    public void copyWithNothingSelectedLeavesTheClipboardAlone() {
        Clipboard clip = Clipboard.inMemory();
        clip.set("keep");
        TextEdit e = of("abc");
        assertFalse(e.copy(clip));
        assertFalse(e.cut(clip));
        assertEquals("keep", clip.get());
    }

    @Test
    public void pasteTurnsNewlinesIntoSpaces() {
        Clipboard clip = Clipboard.inMemory();
        clip.set("a\r\nb\nc");
        TextEdit e = new TextEdit();
        e.paste(clip);
        assertEquals("a b c", e.text());
    }

    @Test
    public void filterAppliesToTypingAndPasting() {
        TextEdit e = new TextEdit();
        e.setCharFilter(ch -> Character.isDigit(ch));
        e.insert("1a2b3");
        assertEquals("123", e.text());
        Clipboard clip = Clipboard.inMemory();
        clip.set("x9y");
        e.paste(clip);
        assertEquals("1239", e.text());
        assertFalse(e.insert("zzz"));
    }

    @Test
    public void maxLengthTruncatesAndRefuses() {
        TextEdit e = new TextEdit();
        e.setMaxLength(4);
        e.insert("abcdefg");
        assertEquals("abcd", e.text());
        assertFalse(e.insert("x"));
        e.selectAll();
        assertTrue(e.insert("xy"));
        assertEquals("xy", e.text());
    }

    @Test
    public void setTextPutsTheCaretAtTheEnd() {
        TextEdit e = of("hello");
        assertEquals(5, e.caret());
        assertFalse(e.hasSelection());
        e.setText(null);
        assertEquals("", e.text());
    }

    @Test
    public void selectWordAt() {
        TextEdit e = of("foo bar.baz");
        e.selectWordAt(5);
        assertEquals("bar", e.selectedText());
        e.selectWordAt(7);
        assertEquals(".", e.selectedText());
        e.selectWordAt(1);
        assertEquals("foo", e.selectedText());
    }

    @Test
    public void setCaretClamps() {
        TextEdit e = of("abc");
        e.setCaret(99, false);
        assertEquals(3, e.caret());
        e.setCaret(-5, false);
        assertEquals(0, e.caret());
    }
}
