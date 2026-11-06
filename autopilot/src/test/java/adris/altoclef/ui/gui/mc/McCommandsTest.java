package adris.altoclef.ui.gui.mc;

import baritone.api.command.argument.ICommandArgument;
import baritone.command.manager.CommandManager;
import baritone.api.utils.Pair;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class McCommandsTest {

    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static String shortcut(String line) {
        Pair<String, List<ICommandArgument>> pair = CommandManager.expand(line);
        return McCommands.settingShortcut(pair.first(), pair.second(), line.substring(pair.first().length()));
    }

    @Test
    public void aBooleanOnItsOwnToggles() {
        assertEquals("set toggle allowBreak", shortcut("allowBreak"));
        assertEquals("set toggle allowBreak", shortcut("ALLOWBREAK"));
    }

    @Test
    public void aNumberOnItsOwnShowsAndWithAValueSets() {
        assertEquals("set primaryTimeoutMS", shortcut("primaryTimeoutMS"));
        assertEquals("set primaryTimeoutMS 250", shortcut("primaryTimeoutMS 250"));
    }

    @Test
    public void aStringSettingTakesTheRestOfTheLine() {
        assertEquals("set altoIdleCommand follow Jacob", shortcut("altoIdleCommand follow Jacob"));
    }

    @Test
    public void strangersAndTooManyWordsAreNotShortcuts() {
        assertNull(shortcut("nonsense"));
        assertNull(shortcut("nonsense 1"));
        assertNull(shortcut("allowBreak true false"));
    }
}
