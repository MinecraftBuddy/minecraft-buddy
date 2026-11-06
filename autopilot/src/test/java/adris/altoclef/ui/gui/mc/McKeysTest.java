package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.kit.Keys;
import com.mojang.blaze3d.platform.InputConstants;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// the sdl scancodes the game sends against the glfw numbers the kit and the guiKey setting use
public class McKeysTest {

    @Test
    public void theKitsKeysLineUpWithTheGamesConstants() {
        assertEquals(Keys.RIGHT_SHIFT, McKeys.toKit(InputConstants.KEY_RSHIFT));
        assertEquals(Keys.LEFT_SHIFT, McKeys.toKit(InputConstants.KEY_LSHIFT));
        assertEquals(Keys.RIGHT_CONTROL, McKeys.toKit(InputConstants.KEY_RCONTROL));
        assertEquals(Keys.LEFT_CONTROL, McKeys.toKit(InputConstants.KEY_LCONTROL));
        assertEquals(Keys.RIGHT_ALT, McKeys.toKit(InputConstants.KEY_RALT));
        assertEquals(Keys.LEFT_ALT, McKeys.toKit(InputConstants.KEY_LALT));
        assertEquals(Keys.ESCAPE, McKeys.toKit(InputConstants.KEY_ESCAPE));
        assertEquals(Keys.ENTER, McKeys.toKit(InputConstants.KEY_RETURN));
        assertEquals(Keys.KP_ENTER, McKeys.toKit(InputConstants.KEY_NUMPADENTER));
        assertEquals(Keys.TAB, McKeys.toKit(InputConstants.KEY_TAB));
        assertEquals(Keys.BACKSPACE, McKeys.toKit(InputConstants.KEY_BACKSPACE));
        assertEquals(Keys.DELETE, McKeys.toKit(InputConstants.KEY_DELETE));
        assertEquals(Keys.SPACE, McKeys.toKit(InputConstants.KEY_SPACE));
        assertEquals(Keys.UP, McKeys.toKit(InputConstants.KEY_UP));
        assertEquals(Keys.DOWN, McKeys.toKit(InputConstants.KEY_DOWN));
        assertEquals(Keys.LEFT, McKeys.toKit(InputConstants.KEY_LEFT));
        assertEquals(Keys.RIGHT, McKeys.toKit(InputConstants.KEY_RIGHT));
        assertEquals(Keys.HOME, McKeys.toKit(InputConstants.KEY_HOME));
        assertEquals(Keys.END, McKeys.toKit(InputConstants.KEY_END));
        assertEquals(Keys.PAGE_UP, McKeys.toKit(InputConstants.KEY_PAGEUP));
        assertEquals(Keys.PAGE_DOWN, McKeys.toKit(InputConstants.KEY_PAGEDOWN));
        assertEquals(Keys.INSERT, McKeys.toKit(InputConstants.KEY_INSERT));
        assertEquals(Keys.F2, McKeys.toKit(InputConstants.KEY_F2));
        assertEquals(Keys.F11, McKeys.toKit(InputConstants.KEY_F11));
        assertEquals(Keys.A, McKeys.toKit(InputConstants.KEY_A));
        assertEquals(Keys.C, McKeys.toKit(InputConstants.KEY_C));
        assertEquals(Keys.V, McKeys.toKit(InputConstants.KEY_V));
        assertEquals(Keys.X, McKeys.toKit(InputConstants.KEY_X));
        assertEquals(Keys.Z, McKeys.toKit(InputConstants.KEY_Z));
        assertEquals(Keys.ONE, McKeys.toKit(InputConstants.KEY_1));
        assertEquals('0', McKeys.toKit(InputConstants.KEY_0));
        assertEquals('9', McKeys.toKit(InputConstants.KEY_9));
        assertEquals('Z', McKeys.toKit(InputConstants.KEY_Z));
    }

    @Test
    public void theDefaultBindIsRightShiftOnTheGamesSide() {
        // the setting default is 344, that has to land on the game's own right shift key
        assertEquals(InputConstants.KEY_RSHIFT, McKeys.toGame(344));
    }

    @Test
    public void everyKnownKeyRoundTrips() {
        int seen = 0;
        for (int sc = 1; sc < 232; sc++) {
            int kit = McKeys.toKit(sc);
            assertEquals("scancode " + sc, sc, McKeys.toGame(kit));
            seen++;
        }
        assertTrue(seen > 200);
    }

    @Test
    public void aKeyGlfwNeverNumberedRidesAboveItsRange() {
        // media play: sdl has it, glfw does not
        int kit = McKeys.toKit(262);
        assertTrue(kit > 1000);
        assertEquals(262, McKeys.toGame(kit));
    }

    @Test
    public void nothingAndGarbageStayNothing() {
        assertEquals(Keys.NONE, McKeys.toKit(0));
        assertEquals(Keys.NONE, McKeys.toKit(-1));
        assertEquals(0, McKeys.toGame(-1));
        assertEquals(0, McKeys.toGame(0));
        // a glfw code with no key behind it
        assertEquals(0, McKeys.toGame(314));
    }

    @Test
    public void mouseButtonsDropByOne() {
        assertEquals(0, McKeys.buttonToKit(InputConstants.MOUSE_BUTTON_LEFT));
        assertEquals(1, McKeys.buttonToKit(InputConstants.MOUSE_BUTTON_MIDDLE));
        assertEquals(2, McKeys.buttonToKit(InputConstants.MOUSE_BUTTON_RIGHT));
    }
}
