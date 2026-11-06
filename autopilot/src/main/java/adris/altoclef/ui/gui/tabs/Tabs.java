package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.BotFacade;
import adris.altoclef.ui.gui.kit.DeferredContext;
import adris.altoclef.ui.gui.kit.Tab;
import adris.altoclef.ui.gui.kit.Theme;

import java.util.List;

// the sidebar, in order. the screen finds this by name so the tabs and the screen never have to know each
// other at compile time (and a missing tab package still gives a panel, just an empty one)
public final class Tabs {

    private Tabs() {
    }

    public static List<Tab> create(BotFacade facade, Theme theme, DeferredContext ctx) {
        return List.of(
                new GetTab(theme, facade, ctx),
                new GotoTab(theme, facade, ctx),
                new FollowTab(theme, facade, ctx),
                new WaypointsTab(theme, facade, ctx),
                new SchematicsTab(theme, facade, ctx),
                new StatsTab(theme, facade, ctx),
                new HelpTab(theme, facade, ctx),
                new SettingsTab(theme, facade, ctx));
    }
}
