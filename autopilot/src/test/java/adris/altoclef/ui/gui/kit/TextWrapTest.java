package adris.altoclef.ui.gui.kit;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class TextWrapTest {

    // every glyph is 10 wide, so widths are character counts times ten
    private static final TextMeasurer TEN = new TextMeasurer() {
        @Override
        public float measure(String s, TextStyle style) {
            return s.length() * 10f;
        }

        @Override
        public float ascent(TextStyle style) {
            return 10f;
        }

        @Override
        public float descent(TextStyle style) {
            return 3f;
        }
    };

    private static final TextStyle STYLE = new TextStyle(Weight.REGULAR, 12);

    @Test
    public void breaksOnSpacesAndKeepsBlankLines() {
        List<String> lines = TextWrap.wrap(TEN, "one two three\n\nfour", STYLE, 90f);
        assertEquals(List.of("one two", "three", "", "four"), lines);
    }

    @Test
    public void chopsAWordWiderThanTheBox() {
        List<String> lines = TextWrap.wrap(TEN, "a https://example.invalid/x b", STYLE, 100f);
        assertEquals("a", lines.get(0));
        assertEquals("https://ex", lines.get(1));
        assertEquals("ample.inva", lines.get(2));
        // the tail of the chopped word shares its line with the next word
        assertEquals("lid/x b", lines.get(3));
        assertEquals(4, lines.size());
    }

    @Test
    public void nullAndWindowsNewlines() {
        assertTrue(TextWrap.wrap(TEN, null, STYLE, 50f).isEmpty());
        assertEquals(List.of("a", "b"), TextWrap.wrap(TEN, "a\r\nb", STYLE, 50f));
    }
}
