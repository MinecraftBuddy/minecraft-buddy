package adris.altoclef.ui.gui.kit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import adris.altoclef.ui.gui.facade.Result;
import adris.altoclef.ui.gui.facade.StatusAccess;

import java.util.List;
import org.junit.Test;

// the key that opened the panel is still down when the panel starts taking input: its repeats must not close
// the panel again, and the char that rides along with a letter bind must not land in the search box
public class OpenKeyHeldTest {

    private static final long MS = 1_000_000L;

    private static Shell shell(int key, boolean[] closed) {
        Theme theme = new Theme(1f, new StubCanvas(1920, 1080));
        Tab tab = new Tab() {
            @Override
            public String id() {
                return "t";
            }

            @Override
            public String title() {
                return "T";
            }

            @Override
            public Icon icon() {
                return Icon.GET;
            }

            @Override
            public Widget content() {
                return new Label(theme, "x", theme.body, Theme.TEXT);
            }

            @Override
            public boolean hasSearch() {
                return true;
            }
        };
        StatusAccess status = new StatusAccess() {
            @Override
            public Activity activity() {
                return new Activity(false, "Idle", "", null);
            }

            @Override
            public Result stop() {
                return Result.ok("Stopped", "stop");
            }
        };
        Shell s = new Shell(theme, List.of(tab), status, k -> "K" + k, key, k -> { }, Clipboard.inMemory(), () -> closed[0] = true);
        s.setViewport(1920, 1080);
        s.open(10_000 * MS);
        return s;
    }

    @Test
    public void repeatsOfTheOpeningKeyDoNotClose() {
        boolean[] closed = {false};
        Shell s = shell(Keys.RIGHT_SHIFT, closed);
        s.keyDown(Keys.RIGHT_SHIFT, 0);
        s.keyDown(Keys.RIGHT_SHIFT, 0);
        assertFalse(s.closing());
        s.keyUp(Keys.RIGHT_SHIFT);
        s.keyDown(Keys.RIGHT_SHIFT, 0);
        assertTrue(s.closing());
    }

    @Test
    public void theLetterOfALetterBindDoesNotLandInSearch() {
        boolean[] closed = {false};
        Shell s = shell(Keys.V, closed);
        // glfw delivers the key, then the char, for the same press
        s.keyDown(Keys.V, 0);
        s.charTyped('v');
        assertEquals("", s.search().text());
        assertNull(s.focusedWidget());
        s.keyUp(Keys.V);
        s.charTyped('v');
        assertEquals("v", s.search().text());
    }

    @Test
    public void controlDigitSwitchesTabs() {
        boolean[] closed = {false};
        Shell s = shell(Keys.RIGHT_SHIFT, closed);
        s.keyUp(Keys.RIGHT_SHIFT);
        assertTrue(s.keyDown(Keys.ONE, Keys.MOD_CTRL));
        assertEquals(0, s.activeIndex());
        assertFalse(s.closing());
    }
}
