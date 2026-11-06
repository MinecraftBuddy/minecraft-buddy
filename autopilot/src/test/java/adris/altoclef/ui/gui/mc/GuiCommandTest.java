package adris.altoclef.ui.gui.mc;

import baritone.api.IBaritone;
import baritone.command.defaults.GuiCommand;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GuiCommandTest {

    // a baritone that answers every question with another one that answers every question with nothing. a command
    // constructor only stores what it is handed, so this is enough to build one
    private static Object blank(Class<?> type) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            Class<?> r = method.getReturnType();
            if (r == boolean.class) {
                return false;
            }
            if (r == int.class) {
                return 0;
            }
            if (r == long.class) {
                return 0L;
            }
            if (r == double.class) {
                return 0.0;
            }
            if (r == float.class) {
                return 0f;
            }
            if (r.isInterface()) {
                return blank(r);
            }
            return null;
        });
    }

    private static IBaritone baritone() {
        return (IBaritone) blank(IBaritone.class);
    }

    @Test
    public void namesAndDescriptionsAreSane() {
        GuiCommand c = new GuiCommand(baritone());
        assertEquals(List.of("gui", "clickgui"), c.getNames());
        assertEquals("Open the click gui", c.getShortDesc());
        assertFalse(c.hiddenFromHelp());
        String all = String.join("\n", c.getLongDesc());
        assertTrue(all.contains("Right shift"));
        assertTrue(all.contains("guiKey"));
        assertTrue(all.contains("works whether or not the key is bound"));
        // long descs get printed in chat, which has no use for a dash that is not a hyphen
        assertFalse(all.indexOf((char) 0x2014) >= 0);
    }

    @Test
    public void itIsInTheDefaultCommands() throws Exception {
        // building the whole list wants a real player context (BuildCommand reads the game directory in its
        // constructor), so read the registration instead
        String src = Files.readString(Path.of("src/main/java/baritone/command/defaults/DefaultCommands.java"));
        assertTrue(src.contains("new GuiCommand(baritone)"));
    }
}
