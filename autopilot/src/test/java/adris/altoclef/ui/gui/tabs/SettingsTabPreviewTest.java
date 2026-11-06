package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.kit.Keys;
import adris.altoclef.ui.gui.kit.Widget;
import adris.altoclef.ui.gui.model.SettingCategories;
import adris.altoclef.ui.gui.preview.FakeFacade;
import adris.altoclef.ui.gui.preview.Preview;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertTrue;

// lives next to the tab (not in the preview package) because it clicks things by asking the tab where they are
public class SettingsTabPreviewTest {

    private SettingsTab tab;

    private Preview open(int w, int h) throws Exception {
        Preview p = new Preview(w, h, new FakeFacade(), pv -> {
            tab = new SettingsTab(pv.theme, pv.facade, pv.ctx);
            return List.of(tab);
        });
        p.settle();
        // these previews reach for settings of every kind, so keep the advanced ones in sight
        tab.setAdvanced(true);
        p.settle();
        p.advance(400);
        p.settle();
        return p;
    }

    // through the shell's own box, so the header says what the list is filtered by
    private void search(Preview p, String text) {
        p.shell.search().clearAndNotify();
        clickCentre(p, p.shell.search());
        p.type(text);
    }

    private SettingRow show(Preview p, String name) {
        search(p, name);
        p.settle();
        p.advance(300);
        p.settle();
        for (SettingRow r : tab.rows()) {
            if (name.equals(r.name()) && r.visible()) {
                return r;
            }
        }
        throw new AssertionError(name + " is not on screen");
    }

    private void clickCentre(Preview p, Widget w) {
        p.click(w.x() + w.w() / 2f, w.y() + w.h() / 2f);
        p.settle();
    }

    private void category(Preview p, String label) {
        clickCentre(p, tab.toolbar().categories());
        p.type(label);
        p.key(Keys.ENTER);
        p.settle();
    }

    @Test
    public void states() throws Exception {
        Preview p = open(1600, 900);
        assertTrue(p.savePanel("settings_default").toFile().length() > 0);

        // hovering a row fills the detail strip
        Widget first = tab.common().children().get(1);
        p.hover(first.x() + 40, first.y() + first.h() / 2f);
        p.settle();
        p.savePanel("settings_hover");

        // categories unfold from their own control and keep keyboard navigation
        clickCentre(p, tab.toolbar().categories());
        p.advance(300);
        p.savePanel("settings_category_menu");
        p.key(Keys.ESCAPE);
        var col = tab.toolbar();
        category(p, SettingCategories.MOVEMENT);
        p.settle();
        p.savePanel("settings_category_movement");

        // back to all, then the search
        category(p, "All");
        p.settle();
        search(p, "parkour");
        p.settle();
        p.savePanel("settings_search_parkour");
        p.shell.search().clearAndNotify();

        // modified only
        clickCentre(p, col.modifiedToggle());
        p.advance(300);
        p.savePanel("settings_modified_only");
        clickCentre(p, col.modifiedToggle());

        // a number box with an error
        SettingRow r = show(p, "followRadius");
        clickCentre(p, r.number().field());
        p.shell.keyDown(Keys.A, 2);
        p.type("-");
        p.key(Keys.ENTER);
        p.settle();
        p.savePanel("settings_number_error");

        // the colour editor
        r = show(p, "colorCurrentPath");
        clickCentre(p, r.channel(1));
        p.savePanel("settings_colour");

        // the enum dropdown open
        r = show(p, "mirror");
        clickCentre(p, r.dropdown());
        p.advance(200);
        p.savePanel("settings_enum_open");
        p.key(Keys.ESCAPE);

        // the key capture waiting
        clickCentre(p, tab.keyCard().capture());
        p.advance(200);
        p.savePanel("settings_key_capture");
        p.key(Keys.ESCAPE);

        // the other editor with a parse error
        r = show(p, "blocksToAvoid");
        clickCentre(p, r.other());
        p.type("!nope");
        p.key(Keys.ENTER);
        p.settle();
        p.savePanel("settings_other_error");

        // no results
        search(p, "zzzzzz");
        p.settle();
        p.savePanel("settings_no_results");
    }

    @Test
    public void minimumResizeKeepsTheSettingsBrowserVisible() throws Exception {
        Preview p = open(1920, 1080);
        p.shell.setBounds(p.shell.x(), p.shell.y(), p.theme.px(560), p.theme.px(360));
        assertTrue(tab.settingsContent().browser().h() >= p.theme.px(48));
        assertTrue(tab.toolbar().categories().x() + tab.toolbar().categories().w()
                < tab.toolbar().togglesLeft());
        p.settle();
        p.savePanel("settings_minimum_size");
    }

    // the hover fill follows the card: rounded at the top on the first row, at the bottom on the last, square between
    @Test
    public void rowHoverFollowsTheCardCorners() throws Exception {
        Preview p = open(1600, 900);
        tab.onSearch("allow");
        p.settle();
        p.advance(400);
        p.settle();
        List<SettingRow> rows = tab.rows().stream().filter(SettingRow::visible).sorted(java.util.Comparator.comparingInt(SettingRow::y)).toList();
        assertTrue(rows.size() >= 3);
        SettingRow first = rows.get(0);
        SettingRow middle = rows.get(1);
        for (SettingRow r : List.of(first, middle)) {
            p.hover(r.x() + 60, r.y() + r.h() / 2f);
            p.settle();
            p.savePanel("settings_row_hover_" + (r == first ? "first" : "middle"));
        }
        // the last row is only on screen once the list is scrolled to the end
        p.shell.focus(tab.settingsContent().browser());
        p.key(Keys.END);
        p.advance(600);
        p.settle();
        SettingRow last = tab.rows().stream().filter(SettingRow::visible)
                .filter(r -> r.y() + r.h() <= tab.settingsContent().browser().y() + tab.settingsContent().browser().h())
                .max(java.util.Comparator.comparingInt(SettingRow::y)).orElseThrow();
        p.hover(last.x() + 60, last.y() + last.h() / 2f);
        p.settle();
        p.savePanel("settings_row_hover_last");
        // a pinned row makes room for the pin instead of sitting on the name
        String pinned = first.name();
        adris.altoclef.ui.gui.model.Pinned.toggleSetting(pinned);
        try {
            p.settle();
            p.advance(300);
            p.savePanel("settings_row_pinned");
        } finally {
            adris.altoclef.ui.gui.model.Pinned.toggleSetting(pinned);
        }
    }

    @Test
    public void smallWindow() throws Exception {
        Preview p = open(960, 540);
        p.savePanel("settings_960x540");
        SettingRow r = show(p, "colorCurrentPath");
        p.hover(r.x() + 40, r.y() + r.h() / 2f);
        p.settle();
        p.savePanel("settings_960x540_colour");
        p.shell.search().clearAndNotify();
        p.settle();
        var col = tab.toolbar();
        category(p, SettingCategories.PATHING);
        p.settle();
        p.savePanel("settings_960x540_category");
    }
}
