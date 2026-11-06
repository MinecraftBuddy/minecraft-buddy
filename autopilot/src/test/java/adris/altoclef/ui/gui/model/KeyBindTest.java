package adris.altoclef.ui.gui.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class KeyBindTest {

    private static final int RSHIFT = 344;
    private static final int G = 71;
    private static final int ESC = 256;
    private static final int BACKSPACE = 259;
    private static final int DELETE = 261;

    @Test
    public void unboundNeverOpens() {
        KeyBind b = new KeyBind(-1);
        assertFalse(b.opens(-1, false));
        assertFalse(b.opens(RSHIFT, false));
        assertFalse(b.closes(-1));
    }

    @Test
    public void disabledNeverOpens() {
        KeyBind b = new KeyBind(RSHIFT);
        b.enabled = false;
        assertFalse(b.opens(RSHIFT, false));
        assertFalse(b.closes(RSHIFT));
    }

    @Test
    public void theBoundKeyOpensOnlyWithNoScreen() {
        KeyBind b = new KeyBind(RSHIFT);
        assertTrue(b.opens(RSHIFT, false));
        assertFalse(b.opens(RSHIFT, true));
        assertFalse(b.opens(G, false));
    }

    @Test
    public void theBoundKeyClosesAgain() {
        KeyBind b = new KeyBind(RSHIFT);
        assertTrue(b.closes(RSHIFT));
        assertFalse(b.closes(G));
    }

    @Test
    public void whileCapturingTheBoundKeyDoesNotClose() {
        KeyBind b = new KeyBind(RSHIFT);
        b.startCapture();
        assertFalse(b.closes(RSHIFT));
        assertFalse(b.opens(RSHIFT, false));
    }

    @Test
    public void rebindingTakesEffectAtOnce() {
        KeyBind b = new KeyBind(RSHIFT);
        b.startCapture();
        assertEquals(KeyBind.Outcome.BOUND, b.press(G));
        assertFalse(b.capturing);
        assertEquals(G, b.key);
        assertTrue(b.opens(G, false));
        assertTrue(b.closes(G));
        assertFalse(b.opens(RSHIFT, false));
    }

    @Test
    public void aModifierIsAFineBind() {
        KeyBind b = new KeyBind(G);
        b.startCapture();
        assertEquals(KeyBind.Outcome.BOUND, b.press(RSHIFT));
        assertEquals(RSHIFT, b.key);
    }

    @Test
    public void escapeCancelsAndKeepsTheOldKey() {
        KeyBind b = new KeyBind(RSHIFT);
        b.startCapture();
        assertEquals(KeyBind.Outcome.CANCELLED, b.press(ESC));
        assertFalse(b.capturing);
        assertEquals(RSHIFT, b.key);
    }

    @Test
    public void backspaceAndDeleteClear() {
        KeyBind b = new KeyBind(RSHIFT);
        b.startCapture();
        assertEquals(KeyBind.Outcome.CLEARED, b.press(BACKSPACE));
        assertEquals(-1, b.key);
        assertFalse(b.opens(RSHIFT, false));
        b.key = G;
        b.startCapture();
        assertEquals(KeyBind.Outcome.CLEARED, b.press(DELETE));
        assertEquals(-1, b.key);
    }

    @Test
    public void pressingWhenNotCapturingIsIgnored() {
        KeyBind b = new KeyBind(RSHIFT);
        assertEquals(KeyBind.Outcome.IGNORED, b.press(G));
        assertEquals(RSHIFT, b.key);
        b.startCapture();
        assertEquals(KeyBind.Outcome.IGNORED, b.press(-1));
        assertTrue(b.capturing);
    }

    @Test
    public void cancelCaptureStopsWaiting() {
        KeyBind b = new KeyBind(RSHIFT);
        b.startCapture();
        b.cancelCapture();
        assertEquals(KeyBind.Outcome.IGNORED, b.press(G));
        assertEquals(RSHIFT, b.key);
    }

    @Test
    public void fullscreenAndScreenshotKeysAreRefusedAsBinds() {
        KeyBind b = new KeyBind(344);
        b.startCapture();
        assertEquals(KeyBind.Outcome.IGNORED, b.press(291));
        assertEquals(KeyBind.Outcome.IGNORED, b.press(300));
        assertTrue(b.capturing);
        assertEquals(344, b.key);
        assertEquals(KeyBind.Outcome.BOUND, b.press(345));
        assertEquals(345, b.key);
    }
}
