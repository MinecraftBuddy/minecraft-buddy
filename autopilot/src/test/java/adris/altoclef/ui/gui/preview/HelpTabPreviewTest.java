package adris.altoclef.ui.gui.preview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import adris.altoclef.ui.gui.facade.CommandsAccess;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.Keys;
import adris.altoclef.ui.gui.kit.Tab;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.tabs.HelpTab;

import java.util.List;
import org.junit.Test;

public class HelpTabPreviewTest {

    private static final int HELP = 4;

    private static List<Tab> tabs(Preview p, HelpTab[] out) {
        Theme t = p.theme;
        out[0] = new HelpTab(t, p.facade, p.ctx);
        return List.of(
                ShellPreviewTest.tab(t, "get", "Get", Icon.GET, true),
                ShellPreviewTest.tab(t, "goto", "Go to", Icon.GOTO, false),
                ShellPreviewTest.tab(t, "follow", "Follow", Icon.FOLLOW, true),
                ShellPreviewTest.tab(t, "waypoints", "Waypoints", Icon.WAYPOINTS, true),
                out[0],
                ShellPreviewTest.tab(t, "settings", "Settings", Icon.SETTINGS, true));
    }

    // the real commands write usage as "> goto <x> <z> - Go to an X,Z position"
    private static void realShaped(FakeFacade f) {
        for (int i = 0; i < f.commands.list.size(); i++) {
            CommandsAccess.CommandInfo c = f.commands.list.get(i);
            if (c.name().equals("goto")) {
                f.commands.list.set(i, new CommandsAccess.CommandInfo(c.names(), c.shortDesc(), List.of(
                        "Tell Baritone to path to a position or a block. The coordinates can be absolute or relative to you with a tilde.", "",
                        "Usage:", "> goto <y> - Go to a Y level", "> goto <x> <z> - Go to an X,Z position",
                        "> goto <x> <y> <z> - Go to an X,Y,Z position", "> goto <block> - Go to the nearest block of a type")));
            }
        }
    }

    private static Preview open(FakeFacade f, HelpTab[] tab, int w, int h) throws Exception {
        realShaped(f);
        Preview p = new Preview(w, h, f, pv -> tabs(pv, tab));
        p.shell.switchTab(HELP);
        p.advance(400);
        p.settle();
        // rows fade in on their first frame, wait it out so the png is the resting state
        p.advance(300);
        p.settle();
        return p;
    }

    private static void clickAt(Preview p, float[] xy) {
        assertNotNull(xy);
        p.click(xy[0], xy[1]);
    }

    private static void search(Preview p, String text) {
        p.click(p.shell.search().x() + 20, p.shell.search().y() + 10);
        p.type(text);
        p.advance(300);
        p.settle();
    }

    @Test
    public void states() throws Exception {
        FakeFacade f = new FakeFacade();
        HelpTab[] tab = new HelpTab[1];
        Preview p = open(f, tab, 1600, 900);
        p.savePanel("help_default");

        // hover a row
        float[] goto_ = tab[0].headerCenter("goto");
        p.hover(goto_[0], goto_[1] + 44 * 2);
        p.advance(200);
        p.savePanel("help_hover");

        // open a row, caught mid slide and then settled
        p.settle();
        clickAt(p, tab[0].headerCenter("goto"));
        p.advance(25);
        p.settle();
        p.savePanel("help_expanding");
        p.advance(300);
        p.settle();
        assertEquals("goto", tab[0].expandedCommand());
        p.hover(-100, -100);
        p.savePanel("help_expanded");

        // hover a usage line, then click it: the line lands in the command line, focused
        float[] use = tab[0].usageCenter("goto", 1);
        p.hover(use[0], use[1]);
        p.advance(200);
        p.settle();
        p.savePanel("help_usage_hover");
        p.click(use[0], use[1]);
        p.advance(300);
        p.settle();
        assertEquals("goto <x> <z>", tab[0].field().text());
        assertSame(tab[0].field(), p.shell.focusedWidget());
        p.hover(-100, -100);
        p.savePanel("help_usage_filled");

        // type a real command over it and run it
        tab[0].field().setText("");
        p.type("goto 100 64 -30");
        p.savePanel("help_typing");
        p.key(Keys.ENTER);
        p.advance(300);
        p.settle();
        assertEquals("goto 100 64 -30", f.commands.lastRun);
        p.advance(300);
        p.savePanel("help_run_ok");

        // a failure keeps the text and shows red, once the first toast has gone
        p.advance(3500);
        p.settle();
        p.advance(300);
        f.commands.failNext = true;
        p.type("#gett oak_log 4");
        p.key(Keys.ENTER);
        p.advance(300);
        p.settle();
        assertEquals("gett oak_log 4", f.commands.lastRun);
        assertTrue(tab[0].inlineText().contains("Unknown command"));
        p.advance(300);
        p.savePanel("help_run_fail");

        // up arrow brings the last line back
        tab[0].field().setText("");
        p.key(Keys.UP);
        assertEquals("gett oak_log 4", tab[0].field().text());
        p.key(Keys.UP);
        assertEquals("goto 100 64 -30", tab[0].field().text());
    }

    @Test
    public void searching() throws Exception {
        FakeFacade f = new FakeFacade();
        HelpTab[] tab = new HelpTab[1];
        Preview p = open(f, tab, 1600, 900);
        search(p, "go");
        assertEquals("goto", tab[0].showing().get(0).name());
        p.savePanel("help_search_go");

        p.shell.search().setText("");
        search(p, "xyz");
        assertTrue(tab[0].showing().isEmpty());
        p.savePanel("help_no_results");
    }

    @Test
    public void emptyRegistry() throws Exception {
        FakeFacade f = new FakeFacade();
        f.commands.list.clear();
        HelpTab[] tab = new HelpTab[1];
        Preview p = open(f, tab, 1600, 900);
        p.savePanel("help_empty_registry");
    }

    @Test
    public void smallWindow() throws Exception {
        FakeFacade f = new FakeFacade();
        HelpTab[] tab = new HelpTab[1];
        Preview p = open(f, tab, 960, 540);
        p.save("help_small_default");
        clickAt(p, tab[0].headerCenter("mine"));
        p.advance(400);
        p.settle();
        p.hover(-100, -100);
        p.save("help_small_expanded");
    }
}
