package adris.altoclef.ui.gui.kit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

// StubCanvas measures 0.6 em per char; use that advance so a font size change does not move our test clicks
public class TextFieldTest {

    private static final long MS = 1_000_000L;
    private final StubCanvas canvas = new StubCanvas();
    private final Theme theme = new Theme(1f, canvas);
    private final List<String> committed = new ArrayList<>();
    private final List<String> changes = new ArrayList<>();
    private final Clipboard clip = Clipboard.inMemory();

    private TextField field(String text) {
        TextField f = new TextField(theme, "Type here", committed::add).withClipboard(clip);
        f.setOnChange(changes::add);
        f.setBounds(0, 0, 200, 30);
        f.setText(text);
        f.setFocused(true);
        draw(f, 0);
        return f;
    }

    private void draw(TextField f, long ms) {
        canvas.calls.clear();
        f.draw(canvas, new Frame(ms * MS, 0.016f, -1, -1));
    }

    private float boundary(int chars) {
        return 10f + 0.6f * theme.body.sizePx() * chars;
    }

    private long clock = 5000;

    // each click is a second apart so none of them is a double click unless the test says so
    private void click(TextField f, float x) {
        clock += 1000;
        draw(f, clock);
        f.mouseDown(x, 15, 0, 0);
        f.mouseUp(x, 15, 0, 0);
    }

    @Test
    public void clickPlacesTheCaretAtTheNearestGlyphBoundary() {
        TextField f = field("hello");
        click(f, boundary(2) + 1f);
        assertEquals(2, f.edit().caret());
        click(f, boundary(3) - 1f);
        assertEquals(3, f.edit().caret());
        click(f, boundary(2) + 0.3f * theme.body.sizePx() - 0.1f);
        assertEquals(2, f.edit().caret());
    }

    @Test
    public void clickingBeforeOrAfterTheTextHitsTheEnds() {
        TextField f = field("hello");
        click(f, 4);
        assertEquals(0, f.edit().caret());
        click(f, 190);
        assertEquals(5, f.edit().caret());
    }

    @Test
    public void dragSelects() {
        TextField f = field("hello world");
        f.mouseDown(boundary(1), 15, 0, 0);
        assertTrue(f.mouseDrag(boundary(4), 15, 0, 0, 0));
        assertEquals("ell", f.edit().selectedText());
        f.mouseUp(boundary(4), 15, 0, 0);
        assertFalse(f.mouseDrag(boundary(6), 15, 0, 0, 0));
    }

    @Test
    public void shiftClickExtends() {
        TextField f = field("hello world");
        click(f, boundary(2));
        clock += 1000;
        draw(f, clock);
        f.mouseDown(boundary(7), 15, 0, Keys.MOD_SHIFT);
        assertEquals("llo w", f.edit().selectedText());
    }

    @Test
    public void doubleClickSelectsTheWord() {
        TextField f = field("foo bar");
        draw(f, 1000);
        f.mouseDown(boundary(5), 15, 0, 0);
        f.mouseUp(0, 0, 0, 0);
        draw(f, 1100);
        f.mouseDown(boundary(5), 15, 0, 0);
        assertEquals("bar", f.edit().selectedText());
    }

    @Test
    public void twoSlowClicksAreNotADoubleClick() {
        TextField f = field("foo bar");
        draw(f, 1000);
        f.mouseDown(boundary(5), 15, 0, 0);
        f.mouseUp(0, 0, 0, 0);
        draw(f, 2000);
        f.mouseDown(boundary(5), 15, 0, 0);
        assertFalse(f.edit().hasSelection());
    }

    @Test
    public void typingInsertsAndReportsChanges() {
        TextField f = field("");
        assertTrue(f.charTyped('a'));
        assertTrue(f.charTyped('b'));
        assertEquals("ab", f.text());
        assertEquals(List.of("a", "ab"), changes);
    }

    @Test
    public void unfocusedFieldIgnoresKeys() {
        TextField f = field("");
        f.setFocused(false);
        assertFalse(f.charTyped('a'));
        assertFalse(f.keyDown(Keys.BACKSPACE, 0));
    }

    @Test
    public void enterCommitsAndKeepsTheText() {
        TextField f = field("64");
        assertTrue(f.keyDown(Keys.ENTER, 0));
        assertEquals(List.of("64"), committed);
        assertEquals("64", f.text());
        assertTrue(f.isFocused());
    }

    @Test
    public void escapeAndTabAreLeftForTheShell() {
        TextField f = field("abc");
        assertFalse(f.keyDown(Keys.ESCAPE, 0));
        assertFalse(f.keyDown(Keys.TAB, 0));
        assertEquals("abc", f.text());
    }

    @Test
    public void clipboardShortcuts() {
        TextField f = field("hello");
        f.keyDown(Keys.A, Keys.MOD_CTRL);
        f.keyDown(Keys.C, Keys.MOD_CTRL);
        assertEquals("hello", clip.get());
        f.keyDown(Keys.X, Keys.MOD_CTRL);
        assertEquals("", f.text());
        f.keyDown(Keys.V, Keys.MOD_CTRL);
        assertEquals("hello", f.text());
        assertEquals(List.of("", "hello"), changes);
    }

    @Test
    public void ctrlBackspaceAndWordJumps() {
        TextField f = field("one two");
        f.keyDown(Keys.BACKSPACE, Keys.MOD_CTRL);
        assertEquals("one ", f.text());
        f.keyDown(Keys.LEFT, Keys.MOD_CTRL);
        assertEquals(0, f.edit().caret());
        f.keyDown(Keys.RIGHT, Keys.MOD_SHIFT);
        assertEquals("o", f.edit().selectedText());
    }

    @Test
    public void placeholderShowsOnlyWhenEmpty() {
        TextField f = field("");
        assertTrue(canvas.texts().contains("Type here"));
        f.setText("x");
        draw(f, 10);
        assertFalse(canvas.texts().contains("Type here"));
        assertTrue(canvas.texts().contains("x"));
    }

    private long carets() {
        return canvas.calls.stream().filter(c -> c.kind().equals("fillRect") && c.w() == 1f).count();
    }

    @Test
    public void caretIsSteadyThenBlinks() {
        TextField f = field("abc");
        draw(f, 400);
        assertEquals(1, carets());
        draw(f, 600);
        assertEquals(1, carets());
        draw(f, 1100);
        assertEquals(0, carets());
        draw(f, 1700);
        assertEquals(1, carets());
    }

    @Test
    public void typingMakesTheCaretSteadyAgain() {
        TextField f = field("abc");
        draw(f, 1100);
        assertEquals(0, carets());
        f.charTyped('d');
        draw(f, 1200);
        assertEquals(1, carets());
        draw(f, 1600);
        assertEquals(1, carets());
    }

    @Test
    public void noCaretWithoutFocus() {
        TextField f = field("abc");
        f.setFocused(false);
        draw(f, 10);
        assertEquals(0, carets());
    }

    @Test
    public void longTextScrollsToKeepTheCaretInView() {
        TextField f = field("x".repeat(50));
        draw(f, 10);
        float drawnX = canvas.textCall("x".repeat(50)).x();
        assertTrue("text should have scrolled left, was " + drawnX, drawnX < 10f - 100f);
        f.keyDown(Keys.HOME, 0);
        draw(f, 20);
        assertEquals(10f, canvas.textCall("x".repeat(50)).x(), 0.01f);
    }

    @Test
    public void clickMathStillWorksWhenScrolled() {
        TextField f = field("x".repeat(50));
        draw(f, 10);
        f.mouseDown(190, 15, 0, 0);
        assertEquals(50, f.edit().caret());
        f.mouseUp(0, 0, 0, 0);
        f.mouseDown(11, 15, 0, 0);
        int caret = f.edit().caret();
        assertTrue("caret " + caret, caret > 20);
    }

    @Test
    public void invalidDrawsADangerBorder() {
        TextField f = field("abc");
        f.setInvalid(true);
        draw(f, 10);
        boolean danger = canvas.calls.stream()
                .anyMatch(c -> c.kind().equals("strokeRoundRect") && c.argb() == Theme.DANGER);
        assertTrue(danger);
    }

    @Test
    public void searchFieldClearsOnEscThenLetsGo() {
        List<String> seen = new ArrayList<>();
        SearchField s = new SearchField(theme, "Search", seen::add);
        s.setBounds(0, 0, 260, 30);
        s.setFocused(true);
        s.charTyped('a');
        s.charTyped('b');
        assertTrue(s.keyDown(Keys.ESCAPE, 0));
        assertEquals("", s.text());
        assertEquals("", seen.get(seen.size() - 1));
        assertFalse(s.keyDown(Keys.ESCAPE, 0));
    }

    @Test
    public void searchFieldClearButtonWorks() {
        List<String> seen = new ArrayList<>();
        SearchField s = new SearchField(theme, "Search", seen::add);
        s.setBounds(0, 0, 260, 30);
        s.setFocused(true);
        s.charTyped('a');
        s.draw(canvas, new Frame(0, 0.016f, -1, -1));
        float cx = 260 - 3 - 12;
        assertTrue(s.mouseDown(cx, 15, 0, 0));
        assertTrue(s.mouseUp(cx, 15, 0, 0));
        assertEquals("", s.text());
        assertEquals("", seen.get(seen.size() - 1));
        assertTrue(s.children().isEmpty());
    }

    @Test
    public void searchFieldLeavesRoomForItsIcon() {
        SearchField s = new SearchField(theme, "Search", x -> { });
        s.setBounds(0, 0, 260, 30);
        s.setText("abc");
        s.setFocused(true);
        s.draw(canvas, new Frame(0, 0.016f, -1, -1));
        assertTrue(canvas.calls.stream().anyMatch(c -> c.kind().equals("icon") && c.text().equals("SEARCH")));
        assertTrue(canvas.textCall("abc").x() >= 10 + 16 + 8);
    }
}
