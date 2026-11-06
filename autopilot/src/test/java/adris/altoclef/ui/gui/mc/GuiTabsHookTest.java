package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.facade.BotFacade;
import adris.altoclef.ui.gui.kit.DeferredContext;
import adris.altoclef.ui.gui.kit.Theme;
import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import static org.junit.Assert.assertTrue;

public class GuiTabsHookTest {

    // the screen finds the tabs by name, so a renamed or reshaped Tabs.create would quietly give an empty panel
    @Test
    public void tabsCreateIsWhereTheScreenLooksForIt() throws Exception {
        Class<?> c = Class.forName("adris.altoclef.ui.gui.tabs.Tabs");
        Method m = c.getMethod("create", BotFacade.class, Theme.class, DeferredContext.class);
        assertTrue(Modifier.isStatic(m.getModifiers()));
        assertTrue(List.class.isAssignableFrom(m.getReturnType()));
    }

    @Test
    public void theFactoryHookStartsOutAsTheLookup() {
        assertTrue(GuiScreen.tabFactory != null);
    }
}
