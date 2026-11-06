package adris.altoclef.ui.gui.kit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import adris.altoclef.ui.gui.kit.NumberField.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class NumberFieldTest {

    private final StubCanvas canvas = new StubCanvas();
    private final Theme theme = new Theme(1f, canvas);
    private final List<String> committed = new ArrayList<>();

    private NumberField of(NumberFormat kind) {
        NumberField n = new NumberField(theme, kind, committed::add);
        n.setBounds(0, 0, 200, 30);
        n.field().setFocused(true);
        return n;
    }

    private static void type(NumberField n, String s) {
        for (char c : s.toCharArray()) {
            n.field().charTyped(c);
        }
    }

    @Test
    public void intFieldOnlyTakesDigitsAndMinus() {
        NumberField n = of(NumberFormat.INT);
        type(n, "-1a2.5~x");
        assertEquals("-125", n.text());
    }

    @Test
    public void floatFieldTakesADot() {
        NumberField n = of(NumberFormat.DOUBLE);
        type(n, "1.5");
        assertEquals("1.5", n.text());
    }

    @Test
    public void tildeOnlyWhenRelativeIsAllowed() {
        NumberField n = of(NumberFormat.INT);
        type(n, "~5");
        assertEquals("5", n.text());
        NumberField r = of(NumberFormat.INT).setAllowRelative(true);
        type(r, "~5");
        assertEquals("~5", r.text());
    }

    @Test
    public void pastingGarbageKeepsTheNumbers() {
        NumberField n = of(NumberFormat.INT);
        Clipboard clip = Clipboard.inMemory();
        clip.set("x 12 y3");
        n.field().withClipboard(clip);
        n.field().keyDown(Keys.V, Keys.MOD_CTRL);
        assertEquals("123", n.text());
    }

    @Test
    public void enterCommitsAGoodNumber() {
        NumberField n = of(NumberFormat.INT);
        type(n, "64");
        n.field().keyDown(Keys.ENTER, 0);
        assertEquals(List.of("64"), committed);
        assertNull(n.error());
        assertEquals("64", n.committed());
    }

    @Test
    public void aBadNumberIsNotCommittedAndRevertGoesBack() {
        NumberField n = of(NumberFormat.INT);
        n.setValue("10");
        n.field().edit().setText("-");
        n.field().keyDown(Keys.ENTER, 0);
        assertTrue(committed.isEmpty());
        assertEquals("Not a number. Try 64.", n.error());
        assertTrue(n.field().invalid());
        n.revert();
        assertEquals("10", n.text());
        assertNull(n.error());
        assertFalse(n.field().invalid());
    }

    @Test
    public void typingAgainClearsTheError() {
        NumberField n = of(NumberFormat.INT);
        type(n, "-");
        n.field().keyDown(Keys.ENTER, 0);
        assertNotNull(n.error());
        type(n, "5");
        assertNull(n.error());
        assertFalse(n.field().invalid());
    }

    @Test
    public void emptyIsNotANumber() {
        NumberField n = of(NumberFormat.INT);
        n.field().keyDown(Keys.ENTER, 0);
        assertNotNull(n.error());
        assertTrue(committed.isEmpty());
    }

    @Test
    public void relativeMessageMentionsTilde() {
        NumberField n = of(NumberFormat.INT).setAllowRelative(true);
        type(n, "-");
        n.field().keyDown(Keys.ENTER, 0);
        assertEquals("Not a number. Try 64 or ~10.", n.error());
    }

    @Test
    public void bareTildeIsFineWhenRelative() {
        NumberField n = of(NumberFormat.INT).setAllowRelative(true);
        type(n, "~");
        n.field().keyDown(Keys.ENTER, 0);
        assertNull(n.error());
        assertEquals(List.of("~"), committed);
    }

    @Test
    public void intRangeIsChecked() {
        NumberField n = of(NumberFormat.INT);
        type(n, "99999999999");
        n.field().keyDown(Keys.ENTER, 0);
        assertNotNull(n.error());
        NumberField l = of(NumberFormat.LONG);
        type(l, "99999999999");
        l.field().keyDown(Keys.ENTER, 0);
        assertNull(l.error());
    }

    @Test
    public void validatorHookBlocksCommit() {
        NumberField n = of(NumberFormat.INT);
        n.setValidate(t -> Integer.parseInt(t) > 100 ? "Keep it under 100." : null);
        type(n, "500");
        n.field().keyDown(Keys.ENTER, 0);
        assertEquals("Keep it under 100.", n.error());
        assertTrue(committed.isEmpty());
        n.field().edit().setText("50");
        n.field().keyDown(Keys.ENTER, 0);
        assertEquals(List.of("50"), committed);
    }

    @Test
    public void aThrowingValidatorDoesNotBlockOrCrash() {
        NumberField n = of(NumberFormat.INT);
        n.setValidate(t -> {
            throw new IllegalStateException("oops");
        });
        type(n, "5");
        n.field().keyDown(Keys.ENTER, 0);
        assertEquals(List.of("5"), committed);
    }

    @Test
    public void intNudgesByOneTenHundred() {
        NumberField n = of(NumberFormat.INT);
        n.setValue("5");
        n.nudge(1, 0);
        assertEquals("6", n.text());
        n.nudge(1, Keys.MOD_SHIFT);
        assertEquals("16", n.text());
        n.nudge(-1, Keys.MOD_CTRL);
        assertEquals("-84", n.text());
        assertEquals(List.of("6", "16", "-84"), committed);
    }

    @Test
    public void floatNudgesByTenthsOnesAndTens() {
        NumberField n = of(NumberFormat.DOUBLE);
        n.setValue("1.5");
        n.nudge(1, 0);
        assertEquals("1.6", n.text());
        n.nudge(1, Keys.MOD_SHIFT);
        assertEquals("2.6", n.text());
        n.nudge(-1, Keys.MOD_CTRL);
        assertEquals("-7.4", n.text());
        n.setValue("0.1");
        n.nudge(-1, 0);
        assertEquals("0", n.text());
    }

    @Test
    public void nudgeKeepsTheTilde() {
        NumberField n = of(NumberFormat.INT).setAllowRelative(true);
        n.setValue("~5");
        n.nudge(1, 0);
        assertEquals("~6", n.text());
        n.setValue("~");
        n.nudge(1, 0);
        assertEquals("~1", n.text());
        n.nudge(-1, 0);
        assertEquals("~", n.text());
    }

    @Test
    public void nudgeFromGarbageUsesTheLastGoodValue() {
        NumberField n = of(NumberFormat.INT);
        n.setValue("7");
        n.field().edit().setText("--");
        n.nudge(1, 0);
        assertEquals("8", n.text());
    }

    @Test
    public void thePlusButtonWorksWithTheMouse() {
        NumberField n = of(NumberFormat.INT);
        n.setValue("5");
        n.draw(canvas, new Frame(0, 0.016f, -1, -1));
        float px = 200 - 3 - 12;
        assertTrue(n.mouseDown(px, 15, 0, 0));
        n.mouseUp(px, 15, 0, 0);
        assertEquals("6", n.text());
        assertTrue(n.mouseDown(px, 15, 0, Keys.MOD_SHIFT));
        n.mouseUp(px, 15, 0, Keys.MOD_SHIFT);
        assertEquals("16", n.text());
    }

    @Test
    public void theMinusButtonSitsLeftOfPlus() {
        NumberField n = of(NumberFormat.INT);
        n.setValue("5");
        float mx = 200 - 3 - 24 - 2 - 12;
        n.mouseDown(mx, 15, 0, 0);
        n.mouseUp(mx, 15, 0, 0);
        assertEquals("4", n.text());
    }

    @Test
    public void onlyTheFieldTakesTabFocus() {
        NumberField n = of(NumberFormat.INT);
        List<Widget> out = new ArrayList<>();
        n.collectFocusable(out);
        assertEquals(1, out.size());
        assertTrue(out.get(0) == n.field());
    }
}
