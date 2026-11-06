package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.SettingsAccess.SettingView;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.Keys;
import adris.altoclef.ui.gui.kit.Label;
import adris.altoclef.ui.gui.kit.Tab;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.Widget;
import adris.altoclef.ui.gui.model.SettingCategories;
import adris.altoclef.ui.gui.preview.FakeFacade;
import adris.altoclef.ui.gui.preview.Preview;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SettingsTabTest {

    private FakeFacade f;
    private Preview p;
    private SettingsTab tab;

    @Before
    public void setUp() throws Exception {
        f = new FakeFacade();
        p = new Preview(f, pv -> {
            tab = new SettingsTab(pv.theme, pv.facade, pv.ctx);
            return List.of(tab);
        });
        p.settle();
        // these tests poke settings of every kind, so keep the advanced ones in sight
        tab.setAdvanced(true);
        p.settle();
    }

    private SettingRow row(String name) {
        for (SettingRow r : tab.rows()) {
            if (name.equals(r.name()) && r.visible()) {
                return r;
            }
        }
        return null;
    }

    // narrows the list to one setting so its row is on screen, then returns it
    private SettingRow show(String name) {
        tab.onSearch(name);
        p.settle();
        p.settle();
        SettingRow r = row(name);
        assertNotNull(name + " is not on screen", r);
        return r;
    }

    private void clickCentre(Widget w) {
        p.click(w.x() + w.w() / 2f, w.y() + w.h() / 2f);
        p.settle();
    }

    private SettingView view(String name) {
        return f.settings.get(name);
    }

    private void category(String label) {
        clickCentre(tab.toolbar().categories());
        p.type(label);
        p.key(Keys.ENTER);
        p.settle();
    }

    @Test
    public void listsEverySettingButOnlyBuildsAFewRows() {
        assertEquals(f.settings.all().size(), tab.list().items().size() + tab.common().size());
        assertTrue("rows are pooled, got " + tab.rows().size(), tab.rows().size() <= 20);
        tab.settingsContent().browser().scroller().snapTo(tab.common().heightFor(tab.common().w()) + p.theme.px(30));
        p.settle();
        assertTrue(tab.list().liveRows() > 3);
    }

    @Test
    public void rowsAreBuiltOnceNoMatterHowFarYouScroll() {
        tab.settingsContent().browser().scroller().snapTo(tab.common().heightFor(tab.common().w()) + p.theme.px(30));
        p.settle();
        int before = tab.rows().size();
        for (int i = 0; i < 30; i++) {
            p.scroll(p.shell.contentX() + p.shell.contentW() / 2f, p.shell.contentY() + p.shell.contentH() / 2f, -3);
            p.settle();
        }
        // a row half on screen at each end can need one more widget, never one per scroll step
        assertTrue(tab.rows().size() <= before + 2);
    }

    @Test
    public void countLineOnlyShowsUpWhenSomethingNarrowedTheList() {
        SettingsContent content = tab.settingsContent();
        assertEquals("", content.countLine());
        int listTop = content.browser().y();
        tab.onSearch("parkour");
        p.settle();
        assertEquals(tab.list().items().size() + " matching", content.countLine());
        assertTrue("the list gives way to the line", content.browser().y() > listTop);
        tab.onSearch("");
        p.settle();
        assertEquals("", content.countLine());
        assertEquals(listTop, content.browser().y());
        tab.onSearch("zzzzzz");
        p.settle();
        assertEquals("", content.countLine());
    }

    @Test
    public void toggleCallsSetAndShowsTheModifiedDot() {
        SettingRow r = show("allowParkour");
        assertFalse(view("allowParkour").modified());
        clickCentre(r.toggle());
        assertTrue(f.actions.contains("set allowParkour true"));
        assertTrue(view("allowParkour").modified());
        assertTrue(r.toggle().on());
    }

    @Test
    public void numberRejectsGarbageAndKeepsTheOldValue() {
        SettingRow r = show("followRadius");
        clickCentre(r.number().field());
        // select all, then a lone minus: the box parses nothing
        p.shell.keyDown(Keys.A, 2);
        p.type("-");
        p.key(Keys.ENTER);
        p.settle();
        assertNotNull(r.errorText());
        assertTrue(r.errorText(), r.errorText().startsWith("Not a number"));
        assertEquals("3", view("followRadius").value());
        assertFalse(f.actions.stream().anyMatch(a -> a.startsWith("set followRadius")));
    }

    @Test
    public void numberCommitsOnEnter() {
        SettingRow r = show("followRadius");
        clickCentre(r.number().field());
        p.shell.keyDown(Keys.A, 2);
        p.type("12");
        p.key(Keys.ENTER);
        p.settle();
        assertNull(r.errorText());
        assertEquals("12", view("followRadius").value());
        assertTrue(f.actions.contains("set followRadius 12"));
    }

    @Test
    public void numberCommitsWhenYouClickAway() {
        SettingRow r = show("followRadius");
        clickCentre(r.number().field());
        p.shell.keyDown(Keys.A, 2);
        p.type("9");
        p.settle();
        // the hover on the search field of the header is as good as any other empty spot
        p.click(p.shell.contentX() + 4, p.shell.contentY() + p.shell.contentH() - 4);
        p.settle();
        p.settle();
        assertEquals("9", view("followRadius").value());
    }

    @Test
    public void pendingEditIsCommittedBeforeACategoryClickReshufflesTheList() {
        SettingRow r = show("followRadius");
        clickCentre(r.number().field());
        p.shell.keyDown(Keys.A, 2);
        p.type("7");
        var col = tab.toolbar();
        category("Tasks");
        p.settle();
        assertEquals("7", view("followRadius").value());
    }

    @Test
    public void pendingEditIsCommittedWhenTheTabIsHidden() {
        SettingRow r = show("prefix");
        clickCentre(r.text());
        p.shell.keyDown(Keys.A, 2);
        p.type("%");
        tab.onHidden();
        assertEquals("%", view("prefix").value());
    }

    @Test
    public void realTabSwitchCommitsEvenThoughTheShellBlursFirst() throws Exception {
        FakeFacade ff = new FakeFacade();
        Preview two = new Preview(ff, pv -> {
            tab = new SettingsTab(pv.theme, pv.facade, pv.ctx);
            Tab other = new Tab() {
                final Widget w = new Label(pv.theme, "other", pv.theme.body, Theme.TEXT);

                @Override
                public String id() {
                    return "other";
                }

                @Override
                public String title() {
                    return "Other";
                }

                @Override
                public Icon icon() {
                    return Icon.GET;
                }

                @Override
                public Widget content() {
                    return w;
                }
            };
            return List.of(tab, other);
        });
        two.settle();
        tab.onSearch("prefix");
        two.settle();
        two.advance(300);
        two.settle();
        SettingRow r = null;
        for (SettingRow x : tab.rows()) {
            if ("prefix".equals(x.name()) && x.visible()) {
                r = x;
            }
        }
        assertNotNull(r);
        two.click(r.text().x() + 10, r.text().y() + r.text().h() / 2f);
        two.shell.keyDown(Keys.A, 2);
        two.type("%");
        two.settle();
        two.shell.switchTab(1);
        assertEquals("%", ff.settings.get("prefix").value());
    }

    @Test
    public void clickingInsideTheBoxBeingTypedInIsNotLeavingIt() {
        SettingRow r = show("followRadius");
        clickCentre(r.number().field());
        p.shell.keyDown(Keys.A, 2);
        p.type("1");
        p.settle();
        // a click to move the caret (past the end of the text), inside the same box
        p.click(r.number().field().x() + p.theme.px(40), r.number().field().y() + r.number().field().h() / 2f);
        p.settle();
        assertEquals("3", view("followRadius").value());
        p.type("00");
        p.key(Keys.ENTER);
        assertEquals("100", view("followRadius").value());
    }

    @Test
    public void colourChannelsCommitOnceWhenYouLeaveTheGroup() {
        SettingRow r = show("colorCurrentPath");
        clickCentre(r.channel(0));
        p.shell.keyDown(Keys.A, 2);
        p.type("10");
        p.settle();
        clickCentre(r.channel(1));
        p.shell.keyDown(Keys.A, 2);
        p.type("20");
        p.settle();
        assertEquals("255,0,0", view("colorCurrentPath").value());
        // leave the group
        p.click(p.shell.contentX() + 4, p.shell.contentY() + p.shell.contentH() - 4);
        p.settle();
        p.settle();
        assertEquals("10,20,0", view("colorCurrentPath").value());
        assertEquals(1, f.actions.stream().filter(a -> a.startsWith("set colorCurrentPath")).count());
    }

    @Test
    public void detailStripFollowsTheMouseNotTheFocusedRow() {
        SettingRow typing = show("followRadius");
        clickCentre(typing.number().field());
        tab.onSearch("");
        p.settle();
        p.advance(300);
        p.settle();
        Widget other = tab.common().children().get(3);
        p.hover(other.x() + other.w() / 2f, other.y() + other.h() / 2f);
        p.settle();
        assertEquals("allowSprint", tab.settingsContent().detail().name());
    }

    @Test
    public void nudgeButtonsStepByOne() {
        SettingRow r = show("followRadius");
        var plus = r.number().children().get(2);
        clickCentre(plus);
        assertEquals("4", view("followRadius").value());
    }

    @Test
    public void resetPutsTheDefaultBack() {
        SettingRow r = show("altoFoodMinimum");
        assertTrue(view("altoFoodMinimum").modified());
        p.hover(r.x() + 20, r.y() + r.h() / 2f);
        p.settle();
        clickCentre(r.resetButton());
        // the first click only arms the reset, the second one commits it
        assertEquals("70", view("altoFoodMinimum").value());
        clickCentre(r.resetButton());
        assertEquals("24", view("altoFoodMinimum").value());
        assertTrue(f.actions.contains("set reset altoFoodMinimum"));
        assertFalse(view("altoFoodMinimum").modified());
    }

    @Test
    public void colourBoxesCommitAsRgb() {
        SettingRow r = show("colorCurrentPath");
        clickCentre(r.channel(0));
        p.shell.keyDown(Keys.A, 2);
        p.type("10");
        p.key(Keys.ENTER);
        p.settle();
        assertEquals("10,0,0", view("colorCurrentPath").value());
    }

    @Test
    public void colourChannelOverTwoFiftyFiveIsRefused() {
        SettingRow r = show("colorCurrentPath");
        clickCentre(r.channel(1));
        p.shell.keyDown(Keys.A, 2);
        p.type("300");
        p.key(Keys.ENTER);
        p.settle();
        assertEquals("Colours go from 0 to 255.", r.errorText());
        assertEquals("255,0,0", view("colorCurrentPath").value());
    }

    @Test
    public void colourChannelNudgesWithArrowKeys() {
        SettingRow r = show("colorNextPath");
        clickCentre(r.channel(0));
        p.key(Keys.UP);
        p.settle();
        assertEquals("1,255,0", view("colorNextPath").value());
    }

    @Test
    public void otherKindShowsTheParseErrorInline() {
        SettingRow r = show("blocksToAvoid");
        clickCentre(r.other());
        p.type("!nope");
        p.key(Keys.ENTER);
        p.settle();
        assertNotNull(r.errorText());
        assertTrue(r.errorText(), r.errorText().contains("Unknown block"));
        assertTrue(r.other().invalid());
        // typing again clears it
        p.type("x");
        assertNull(r.errorText());
    }

    @Test
    public void stringCommitsOnEnter() {
        SettingRow r = show("prefix");
        clickCentre(r.text());
        p.shell.keyDown(Keys.A, 2);
        p.type("!");
        p.key(Keys.ENTER);
        p.settle();
        assertEquals("!", view("prefix").value());
    }

    @Test
    public void enumPicksFromTheDropdown() {
        SettingRow r = show("mirror");
        clickCentre(r.dropdown());
        p.settle();
        p.key(Keys.DOWN);
        p.key(Keys.ENTER);
        p.settle();
        assertEquals("LEFT_RIGHT", view("mirror").value());
    }

    @Test
    public void pinnedKeyRebindsAndTellsTheShell() {
        clickCentre(tab.keyCard().capture());
        assertTrue(tab.keyCard().capture().capturing());
        p.key(Keys.RIGHT_CONTROL);
        p.settle();
        assertEquals("345", view("guiKey").value());
        assertTrue(f.actions.contains("set guiKey 345"));
        // the shell's own copy of the key moved too
        assertEquals(345, p.shell.keyCapture().bind().key);
    }

    @Test
    public void pinnedKeyBackspaceUnbinds() {
        clickCentre(tab.keyCard().capture());
        p.key(Keys.BACKSPACE);
        p.settle();
        assertEquals("-1", view("guiKey").value());
        assertEquals(-1, p.shell.keyCapture().bind().key);
    }

    @Test
    public void keyRowAndPinnedCardStayInSync() {
        SettingRow r = show("guiKey");
        clickCentre(r.key());
        p.key(Keys.RIGHT_CONTROL);
        p.settle();
        assertEquals(345, tab.keyCard().capture().bind().key);
        assertEquals(345, p.shell.keyCapture().bind().key);
    }

    @Test
    public void enabledToggleSetsGuiKeyEnabled() {
        clickCentre(tab.keyCard().enabledToggle());
        assertEquals("false", view("guiKeyEnabled").value());
        assertTrue(f.actions.contains("set guiKeyEnabled false"));
    }

    @Test
    public void searchNarrowsAndEmptyStateAppears() {
        tab.onSearch("parkour");
        p.settle();
        assertTrue(tab.list().items().stream().allMatch(v -> v.name().toLowerCase().contains("parkour")
                || f.settings.description(v.name()).toLowerCase().contains("parkour")));
        tab.onSearch("zzzzzz");
        p.settle();
        assertTrue(tab.list().items().isEmpty());
        tab.onSearch("");
        assertEquals(f.settings.all().size(), tab.list().items().size() + tab.common().size());
    }

    @Test
    public void categoryAndModifiedOnlyNarrow() {
        int all = tab.list().items().size();
        var col = tab.toolbar();
        // the first category in the toolbar, Tasks
        category(SettingCategories.ALTO);
        p.settle();
        assertTrue(tab.list().items().size() < all);
        assertTrue(tab.list().items().stream().allMatch(v -> v.name().startsWith("alto")));
    }
}
