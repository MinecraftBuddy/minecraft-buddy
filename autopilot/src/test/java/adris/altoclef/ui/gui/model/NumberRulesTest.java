package adris.altoclef.ui.gui.model;

import adris.altoclef.ui.gui.model.NumberRules.Type;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class NumberRulesTest {

    @Test
    public void wholeNumbersPass() {
        assertNull(NumberRules.check(Type.INT, "64"));
        assertNull(NumberRules.check(Type.INT, "-3"));
        assertNull(NumberRules.check(Type.INT, "  12  "));
        assertNull(NumberRules.check(Type.INT, "4.0"));
        assertNull(NumberRules.check(Type.LONG, "500"));
    }

    @Test
    public void garbageIsRejectedWithAnActionableSentence() {
        for (String bad : new String[] {"", " ", "-", ".", "-.", "abc", "1,5", "--1", "1e"}) {
            String e = NumberRules.check(Type.INT, bad);
            assertNotNull(bad, e);
            assertEquals("Not a number. Try 64.", e);
        }
        assertEquals("Not a number. Try 1.5.", NumberRules.check(Type.DOUBLE, ""));
    }

    @Test
    public void fractionsNeedADecimalType() {
        assertEquals("Whole numbers only. Try 64.", NumberRules.check(Type.INT, "1.5"));
        assertEquals("Whole numbers only. Try 64.", NumberRules.check(Type.LONG, "0.1"));
        assertNull(NumberRules.check(Type.FLOAT, "1.5"));
        assertNull(NumberRules.check(Type.DOUBLE, ".5"));
        assertNull(NumberRules.check(Type.DOUBLE, "-0.25"));
    }

    @Test
    public void intRangeIsInclusive() {
        assertNull(NumberRules.check(Type.INT, "2147483647"));
        assertNull(NumberRules.check(Type.INT, "-2147483648"));
        assertNotNull(NumberRules.check(Type.INT, "2147483648"));
        assertNotNull(NumberRules.check(Type.INT, "-2147483649"));
    }

    @Test
    public void longRangeIsInclusive() {
        assertNull(NumberRules.check(Type.LONG, "9223372036854775807"));
        assertNull(NumberRules.check(Type.LONG, "-9223372036854775808"));
        assertNotNull(NumberRules.check(Type.LONG, "9223372036854775808"));
    }

    @Test
    public void floatsAndDoublesMustBeFinite() {
        assertNotNull(NumberRules.check(Type.FLOAT, "1" + "0".repeat(39)));
        assertNull(NumberRules.check(Type.FLOAT, "1" + "0".repeat(38)));
        assertNull(NumberRules.check(Type.DOUBLE, "1" + "0".repeat(39)));
        assertNotNull(NumberRules.check(Type.DOUBLE, "1" + "0".repeat(309)));
    }

    @Test
    public void channelsAreZeroToTwoFiftyFive() {
        assertNull(NumberRules.checkChannel("0"));
        assertNull(NumberRules.checkChannel(" 255 "));
        assertEquals("Colours go from 0 to 255.", NumberRules.checkChannel("256"));
        assertEquals("Colours go from 0 to 255.", NumberRules.checkChannel("-1"));
        assertEquals("Use a whole number from 0 to 255.", NumberRules.checkChannel(""));
        assertEquals("Use a whole number from 0 to 255.", NumberRules.checkChannel("12.5"));
        assertEquals("Use a whole number from 0 to 255.", NumberRules.checkChannel("x"));
    }

    @Test
    public void rgbParsesTheWayHashSetPrintsIt() {
        assertArrayEquals(new int[] {255, 0, 0}, NumberRules.parseRgb("255,0,0"));
        assertArrayEquals(new int[] {1, 2, 3}, NumberRules.parseRgb(" 1 , 2 ,3 "));
        assertNull(NumberRules.parseRgb("255,0"));
        assertNull(NumberRules.parseRgb("255,0,0,0"));
        assertNull(NumberRules.parseRgb("256,0,0"));
        assertNull(NumberRules.parseRgb("a,b,c"));
        assertNull(NumberRules.parseRgb(""));
        assertNull(NumberRules.parseRgb(null));
    }

    @Test
    public void rgbFormatsAndClamps() {
        assertEquals("255,0,128", NumberRules.formatRgb(255, 0, 128));
        assertEquals("255,0,0", NumberRules.formatRgb(300, -5, 0));
    }
}
