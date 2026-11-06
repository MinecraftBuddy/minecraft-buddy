package adris.altoclef.ui.gui.kit;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

public class ThemeTest {

    @After
    public void restoreMotion() {
        Anim.enabled = true;
    }

    @Test
    public void darkIsDefaultAndAppearancesAreIndependent() {
        Theme dark = StubCanvas.theme();
        Theme light = StubCanvas.theme();
        assertTrue(dark.dark());
        light.setDark(false);
        assertFalse(light.dark());
        assertEquals(Theme.PANEL, dark.color(Theme.PANEL));
        assertNotEquals(dark.color(Theme.PANEL), light.color(Theme.PANEL));
        assertNotEquals(dark.color(Theme.TEXT), light.color(Theme.TEXT));
        assertEquals(0xFF123456, light.color(0xFF123456));
    }

    @Test
    public void appearanceReversalStartsFromTheDisplayedColors() {
        Theme theme = StubCanvas.theme();
        theme.setDark(false, 0);
        assertEquals(Theme.PANEL, theme.color(Theme.PANEL));
        theme.update(Anim.ms(80));
        int middle = theme.color(Theme.PANEL);
        assertNotEquals(Theme.PANEL, middle);
        theme.setDark(true, Anim.ms(80));
        assertEquals(middle, theme.color(Theme.PANEL));
        theme.update(Anim.ms(500));
        assertEquals(Theme.PANEL, theme.color(Theme.PANEL));
    }

    @Test
    public void reducedMotionChangesAppearanceImmediately() {
        Theme theme = StubCanvas.theme();
        Anim.enabled = false;
        theme.setDark(false, 0);
        int light = theme.color(Theme.PANEL);
        assertNotEquals(Theme.PANEL, light);
        theme.setDark(true, 0);
        assertEquals(Theme.PANEL, theme.color(Theme.PANEL));
    }
}
