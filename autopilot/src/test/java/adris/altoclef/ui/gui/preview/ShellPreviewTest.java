package adris.altoclef.ui.gui.preview;

import adris.altoclef.ui.gui.facade.StatusAccess;
import adris.altoclef.ui.gui.kit.Button;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.Label;
import adris.altoclef.ui.gui.kit.Tab;
import adris.altoclef.ui.gui.kit.TextField;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.Toggle;
import adris.altoclef.ui.gui.kit.Widget;

import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

// the chrome on its own with stand-in tabs: sidebar, header, status strip, toast, open animation. the real tabs
// have their own preview tests
public class ShellPreviewTest {

    static final class Stand extends Widget {
        final Label hint;
        final Button primary;
        final Button secondary;
        final Toggle toggle;
        final TextField field;

        Stand(Theme theme, String text) {
            super(theme);
            hint = new Label(theme, text, theme.body, Theme.TEXT_2);
            primary = new Button(theme, "Get 67 items", Button.Kind.PRIMARY, null, () -> { });
            secondary = new Button(theme, "Set goal only", Button.Kind.SECONDARY, null, () -> { });
            toggle = new Toggle(theme, true, v -> { });
            field = new TextField(theme, "Name", s -> { });
        }

        @Override
        protected void layout() {
            int pad = theme.px(Theme.PAD);
            hint.setBounds(x + pad, y + pad, w - 2 * pad, theme.px(20));
            field.setBounds(x + pad, y + pad + theme.px(32), theme.px(240), theme.px(Theme.CONTROL_H));
            primary.setBounds(x + pad, y + pad + theme.px(76), primary.preferredWidth(), theme.px(Theme.CONTROL_H));
            secondary.setBounds(x + pad + primary.preferredWidth() + theme.px(8), y + pad + theme.px(76), secondary.preferredWidth(), theme.px(Theme.CONTROL_H));
            toggle.setBounds(x + pad, y + pad + theme.px(120), toggle.preferredWidth(), toggle.preferredHeight());
        }

        @Override
        public List<Widget> children() {
            return List.of(hint, field, primary, secondary, toggle);
        }

        @Override
        public void draw(Canvas c, Frame f) {
            for (Widget k : children()) {
                k.draw(c, f);
            }
        }
    }

    static Tab tab(Theme theme, String id, String title, Icon icon, boolean search) {
        Stand stand = new Stand(theme, "Stand-in content for " + title);
        return new Tab() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String title() {
                return title;
            }

            @Override
            public Icon icon() {
                return icon;
            }

            @Override
            public Widget content() {
                return stand;
            }

            @Override
            public boolean hasSearch() {
                return search;
            }
        };
    }

    static List<Tab> tabs(Preview p) {
        Theme t = p.theme;
        return List.of(
                tab(t, "get", "Get", Icon.GET, true),
                tab(t, "goto", "Go to", Icon.GOTO, false),
                tab(t, "follow", "Follow", Icon.FOLLOW, true),
                tab(t, "waypoints", "Waypoints", Icon.WAYPOINTS, true),
                tab(t, "schematics", "Schematics", Icon.SCHEMATICS, true),
                tab(t, "stats", "Stats", Icon.STATS, false),
                tab(t, "help", "Help", Icon.HELP, true),
                tab(t, "settings", "Settings", Icon.SETTINGS, true));
    }

    @Test
    public void shellStates() throws Exception {
        FakeFacade f = new FakeFacade();
        Preview p = new Preview(f, ShellPreviewTest::tabs);
        p.save("shell_idle");

        // the bot is busy and a toast is up
        f.status.activity = new StatusAccess.Activity(true, "Mining iron_ore", "goal 120 64 -30", null);
        p.tick();
        p.shell.toast("Getting 64 oak_log, 3 iron_ingot", "get oak_log 64 iron_ingot 3");
        p.settle();
        p.advance(400);
        p.save("shell_busy_toast");

        // a gamer run shows its phase, a failure toast is red
        f.status.activity = new StatusAccess.Activity(true, "Smelting 37 iron", "furnace at 118 63 -29", "IRON");
        p.tick();
        p.advance(4000);
        p.settle();
        p.shell.toast(adris.altoclef.ui.gui.facade.Result.fail("Unknown command \"gett\"", "gett oak_log"));
        p.settle();
        p.advance(400);
        p.savePanel("shell_gamer_fail");

        // hovering the third tab, mid slide after clicking it
        p.hover(p.shell.x() + 40, p.shell.tabRowY(2) + 10);
        p.click(p.shell.x() + 40, p.shell.tabRowY(2) + 10);
        p.advance(60);
        p.savePanel("shell_tab_sliding");
        p.advance(300);

        // key capture in the sidebar footer
        p.click(p.shell.keyCapture().x() + 10, p.shell.keyCapture().y() + 10);
        p.advance(50);
        p.savePanel("shell_key_capture");
        p.key(adris.altoclef.ui.gui.kit.Keys.RIGHT_CONTROL);
        p.advance(300);
        p.savePanel("shell_key_rebound");
        assertTrue(f.actions.contains("rebind 345"));

        // opening frame
        p.shell.open(p.now);
        p.advance(70);
        p.save("shell_opening_70ms");

        // a small window at gui scale irrelevant: the panel scales with the window height
        Preview small = new Preview(960, 540, new FakeFacade(), ShellPreviewTest::tabs);
        small.save("shell_small_window");
    }
}
