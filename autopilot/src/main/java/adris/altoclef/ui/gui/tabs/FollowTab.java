package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.BotFacade;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.ShellContext;
import adris.altoclef.ui.gui.kit.Tab;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.Widget;

public final class FollowTab implements Tab {

    private final FollowContent content;

    public FollowTab(Theme theme, BotFacade facade, ShellContext ctx) {
        this.content = new FollowContent(theme, facade, ctx);
    }

    @Override
    public String id() {
        return "follow";
    }

    @Override
    public String title() {
        return "Follow";
    }

    @Override
    public Icon icon() {
        return Icon.FOLLOW;
    }

    @Override
    public Widget content() {
        return content;
    }

    @Override
    public boolean hasSearch() {
        return true;
    }

    @Override
    public String searchPlaceholder() {
        return "Search players and entities";
    }

    @Override
    public void onSearch(String query) {
        content.setQuery(query);
    }

    @Override
    public void onShown() {
        content.shown();
    }
}
