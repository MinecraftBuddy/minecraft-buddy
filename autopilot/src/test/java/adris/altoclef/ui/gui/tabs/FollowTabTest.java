package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.WorldAccess.EntityEntry;
import adris.altoclef.ui.gui.facade.Result;
import adris.altoclef.ui.gui.kit.Clipboard;
import adris.altoclef.ui.gui.kit.ShellContext;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.VirtualList;
import adris.altoclef.ui.gui.kit.Widget;
import adris.altoclef.ui.gui.model.EntityGroups.EntityRow;
import adris.altoclef.ui.gui.model.EntityGroups.GroupRow;
import adris.altoclef.ui.gui.model.EntityGroups.Row;
import adris.altoclef.ui.gui.preview.FakeFacade;
import adris.altoclef.ui.gui.preview.Preview;

import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FollowTabTest {

    FakeFacade f;
    Preview p;
    FollowTab tab;
    FollowContent c;
    final List<Result> toasts = new ArrayList<>();

    // the real context with an ear on toast, so a test can say what the player was told
    private ShellContext listening(ShellContext real) {
        return new ShellContext() {
            public Theme theme() {
                return real.theme();
            }

            public Clipboard clipboard() {
                return real.clipboard();
            }

            public void toast(Result result) {
                toasts.add(result);
                real.toast(result);
            }

            public void toast(String line, String command) {
                real.toast(line, command);
            }

            public void focus(Widget widget) {
                real.focus(widget);
            }

            public void blur() {
                real.blur();
            }

            public void openOverlay(Widget overlay) {
                real.openOverlay(overlay);
            }

            public void closeOverlay() {
                real.closeOverlay();
            }

            public void requestClose() {
                real.requestClose();
            }
        };
    }

    @Before
    public void setUp() throws Exception {
        f = new FakeFacade();
        p = new Preview(1600, 900, f, pv -> {
            tab = new FollowTab(pv.theme, pv.facade, listening(pv.ctx));
            return List.of(tab);
        });
        c = (FollowContent) tab.content();
        p.settle();
    }

    private float rowX(VirtualList<?> l) {
        return l.x() + 60;
    }

    private float rowY(VirtualList<?> l, int row) {
        return l.y() + l.rowHeight() * row + l.rowHeight() / 2f;
    }

    private int groupIndex(String typeId) {
        List<Row> rows = c.entityList().items();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i) instanceof GroupRow g && g.group().typeId().equals(typeId)) {
                return i;
            }
        }
        throw new AssertionError("no group " + typeId);
    }

    private void tickTimes(int n) {
        for (int i = 0; i < n; i++) {
            p.tick();
        }
    }

    @Test
    public void identity() {
        assertEquals("follow", tab.id());
        assertEquals("Follow", tab.title());
        assertEquals("Search players and entities", tab.searchPlaceholder());
        assertTrue(tab.hasSearch());
    }

    @Test
    public void listsFillFromTheFacade() {
        assertEquals(5, c.playerList().items().size());
        assertEquals("aver", c.playerList().items().get(0).name());
        assertEquals("Steve", c.playerList().items().get(1).name());
        assertEquals("Alex", c.playerList().items().get(2).name());
        assertEquals("Herobrine", c.playerList().items().get(3).name());
        assertEquals("xXx_Notch_xXx", c.playerList().items().get(4).name());
        assertEquals(11, c.entityList().items().size());
        assertTrue(c.entityList().items().stream().allMatch(r -> r instanceof GroupRow));
    }

    @Test
    public void stripOnlyShowsWhileFollowing() {
        assertFalse(c.stripVisible());
        f.world.following = "Steve";
        tickTimes(10);
        assertTrue(c.stripVisible());
        f.world.following = null;
        tickTimes(10);
        assertFalse(c.stripVisible());
    }

    @Test
    public void refreshesEveryTenTicksNotBefore() {
        f.world.entities.add(new EntityEntry(500, "minecraft:ghast", "Ghast", "", 1.0, null));
        tickTimes(9);
        assertEquals(11, c.entityList().items().size());
        tickTimes(1);
        assertEquals(12, c.entityList().items().size());
    }

    @Test
    public void clickingAPlayerFollowsThem() {
        p.click(rowX(c.playerList()), rowY(c.playerList(), 1));
        assertTrue(f.actions.contains("follow player Steve"));
        assertEquals("Steve", f.world.following);
        assertTrue(c.stripVisible());
    }

    @Test
    public void clickingYourselfDoesNothing() {
        p.click(rowX(c.playerList()), rowY(c.playerList(), 0));
        assertTrue(f.actions.isEmpty());
        assertEquals(null, f.world.following);
    }

    @Test
    public void unloadedPlayersSortLastAndStayClickable() {
        assertTrue(c.playerList().items().get(3).distance() < 0);
        p.click(rowX(c.playerList()), rowY(c.playerList(), 3));
        assertTrue(f.actions.contains("follow player Herobrine"));
    }

    @Test
    public void clickingAGroupExpandsItAndTheListKeepsItAcrossRefreshes() {
        int i = groupIndex("minecraft:zombie");
        p.click(rowX(c.entityList()), rowY(c.entityList(), i));
        assertTrue(c.expanded("minecraft:zombie"));
        assertEquals(14, c.entityList().items().size());
        assertTrue(c.entityList().items().get(i + 1) instanceof EntityRow);
        assertTrue(f.actions.isEmpty());
        tickTimes(20);
        assertEquals(14,c.entityList().items().size());
        p.click(rowX(c.entityList()), rowY(c.entityList(), i));
        assertFalse(c.expanded("minecraft:zombie"));
        assertEquals(11, c.entityList().items().size());
    }

    @Test
    public void clickingAMemberFollowsThatEntity() {
        int i = groupIndex("minecraft:zombie");
        p.click(rowX(c.entityList()), rowY(c.entityList(), i));
        p.click(rowX(c.entityList()), rowY(c.entityList(), i + 1));
        assertTrue(f.actions.contains("follow entity 100"));
    }

    @Test
    public void followAllOnAGroupFollowsTheType() {
        int i = groupIndex("minecraft:zombie");
        VirtualList<Row> l = c.entityList();
        float bx = l.x() + l.w() - p.theme.px(10) - p.theme.px(8) - p.theme.px(32);
        p.hover(rowX(l), rowY(l, i));
        p.click(bx, rowY(l, i));
        assertTrue(f.actions.contains("follow entity zombie"));
        assertFalse(c.expanded("minecraft:zombie"));
        assertEquals("every zombie", f.world.following);
    }

    @Test
    public void quickActionsAndStop() {
        Theme t = p.theme;
        p.click(c.allPlayersButton().x() + 5, c.allPlayersButton().y() + t.px(10));
        assertTrue(f.actions.contains("follow players"));
        assertTrue(c.stripVisible());
        p.click(c.allEntitiesButton().x() + 5, c.allEntitiesButton().y() + t.px(10));
        assertTrue(f.actions.contains("follow entities"));
        p.click(c.stopButton().x() + 5, c.stopButton().y() + 5);
        assertTrue(f.actions.contains("stop"));
        assertFalse(c.stripVisible());
        assertEquals(null, f.world.following);
    }

    @Test
    public void theButtonOnTheRowYouFollowStopsInsteadOfFollowing() {
        p.click(rowX(c.playerList()), rowY(c.playerList(), 1));
        assertEquals("Steve", f.world.following);
        f.actions.clear();
        VirtualList<?> l = c.playerList();
        float bx = l.x() + l.w() - p.theme.px(10) - p.theme.px(8) - p.theme.px(32);
        p.hover(rowX(l), rowY(l, 1));
        p.click(bx, rowY(l, 1));
        assertTrue(f.actions.contains("stop"));
        assertFalse(f.actions.contains("follow player Steve"));
        assertEquals(null, f.world.following);
    }

    @Test
    public void aFollowedGroupOffersStopNotFollowAll() {
        int i = groupIndex("minecraft:zombie");
        VirtualList<Row> l = c.entityList();
        float bx = l.x() + l.w() - p.theme.px(10) - p.theme.px(8) - p.theme.px(32);
        p.hover(rowX(l), rowY(l, i));
        p.click(bx, rowY(l, i));
        assertEquals("every zombie", f.world.following);
        f.actions.clear();
        p.hover(rowX(l), rowY(l, i));
        p.click(bx, rowY(l, i));
        assertTrue(f.actions.contains("stop"));
        assertFalse(f.actions.contains("follow entity zombie"));
        assertEquals(null, f.world.following);
    }

    @Test
    public void theButtonOnAFollowedMemberStops() {
        int i = groupIndex("minecraft:zombie");
        VirtualList<Row> l = c.entityList();
        p.click(rowX(l), rowY(l, i));
        p.click(rowX(l), rowY(l, i + 1));
        assertTrue(f.actions.contains("follow entity 100"));
        f.actions.clear();
        float bx = l.x() + l.w() - p.theme.px(10) - p.theme.px(8) - p.theme.px(32);
        p.hover(rowX(l), rowY(l, i + 1));
        p.click(bx, rowY(l, i + 1));
        assertTrue(f.actions.contains("stop"));
        assertFalse(f.actions.contains("follow entity 100"));
        assertEquals(null, f.world.following);
    }

    @Test
    public void stopReleasedOffTheButtonDoesNothing() {
        p.click(rowX(c.playerList()), rowY(c.playerList(), 1));
        f.actions.clear();
        VirtualList<?> l = c.playerList();
        float bx = l.x() + l.w() - p.theme.px(10) - p.theme.px(8) - p.theme.px(32);
        p.hover(rowX(l), rowY(l, 1));
        p.press(bx, rowY(l, 1));
        p.release(rowX(l), rowY(l, 1));
        assertTrue(f.actions.isEmpty());
        assertEquals("Steve", f.world.following);
    }

    @Test
    public void searchFiltersBothLists() {
        tab.onSearch("zom");
        assertEquals(1, c.entityList().items().size());
        assertTrue(c.playerList().items().isEmpty());
        tab.onSearch("STEV");
        assertEquals(1, c.playerList().items().size());
        assertTrue(c.entityList().items().isEmpty());
        tab.onSearch("");
        assertEquals(5, c.playerList().items().size());
        assertEquals(11, c.entityList().items().size());
    }

    @Test
    public void everyActionIsToastedOkAndFailAlike() {
        p.click(rowX(c.playerList()), rowY(c.playerList(), 1));
        assertEquals(1, toasts.size());
        assertTrue(toasts.get(0).ok());
        assertEquals("follow player Steve", toasts.get(0).command());

        // the zombie leaves between the last refresh and the click: the fake fails the follow
        int i = groupIndex("minecraft:zombie");
        p.click(rowX(c.entityList()), rowY(c.entityList(), i));
        f.world.entities.removeIf(e -> e.id() == 100);
        p.click(rowX(c.entityList()), rowY(c.entityList(), i + 1));
        assertEquals(2, toasts.size());
        assertFalse(toasts.get(1).ok());
        // and the list caught up on its own
        assertEquals(2, ((GroupRow) c.entityList().items().get(groupIndex("minecraft:zombie"))).group().count());
    }

    @Test
    public void aNullEntryFromTheFacadeIsSkipped() {
        f.world.players.clear();
        f.world.players.add(null);
        tickTimes(10);
        assertTrue(c.playerList().items().isEmpty());
        p.settle();
    }

    @Test
    public void aRefreshBetweenPressAndReleaseNeverClicksTheWrongRow() {
        VirtualList<?> l = c.playerList();
        p.press(rowX(l), rowY(l, 1));
        // somebody walks up closer than Steve and takes his row
        f.world.players.add(new adris.altoclef.ui.gui.facade.WorldAccess.PlayerEntry("Zed", 1.0, false));
        tickTimes(10);
        assertEquals("Zed", c.playerList().items().get(1).name());
        p.release(rowX(l), rowY(l, 1));
        assertTrue(f.actions.isEmpty());
    }

    @Test
    public void followAllReleasedOffTheButtonDoesNothing() {
        int i = groupIndex("minecraft:zombie");
        VirtualList<Row> l = c.entityList();
        float bx = l.x() + l.w() - p.theme.px(10) - p.theme.px(8) - p.theme.px(32);
        p.press(bx, rowY(l, i));
        p.release(l.x() + 60, rowY(l, i));
        assertTrue(f.actions.isEmpty());
        assertFalse(c.expanded("minecraft:zombie"));
    }

    @Test
    public void aPressThatNeverGetsItsReleaseDoesNotEatTheNextClick() {
        int i = groupIndex("minecraft:zombie");
        VirtualList<Row> l = c.entityList();
        float bx = l.x() + l.w() - p.theme.px(10) - p.theme.px(8) - p.theme.px(32);
        p.press(bx, rowY(l, i));
        p.click(c.allPlayersButton().x() + 5, c.allPlayersButton().y() + p.theme.px(10));
        assertTrue(f.actions.contains("follow players"));
        assertFalse(f.actions.contains("follow entity zombie"));
    }

    @Test
    public void aGroupFoundByANamedMemberHasNoFollowAll() {
        tab.onSearch("biscu");
        VirtualList<Row> l = c.entityList();
        float bx = l.x() + l.w() - p.theme.px(10) - p.theme.px(8) - p.theme.px(32);
        p.click(bx, rowY(l, 0));
        assertTrue(f.actions.isEmpty());
        // the click was a plain row click, so it closed the group
        assertEquals(1, c.entityList().items().size());
    }

    @Test
    public void followAllIgnoresTheBandAboveAndBelowTheButton() {
        int i = groupIndex("minecraft:zombie");
        VirtualList<Row> l = c.entityList();
        float bx = l.x() + l.w() - p.theme.px(10) - p.theme.px(8) - p.theme.px(32);
        // the very top of the row is outside the 26 px button, so this is a plain row click: it expands
        p.click(bx, l.y() + l.rowHeight() * i + 1);
        assertTrue(f.actions.isEmpty());
        assertTrue(c.expanded("minecraft:zombie"));
    }

    @Test
    public void searchingAPetNameOpensItsGroup() {
        tab.onSearch("biscu");
        assertEquals(2, c.entityList().items().size());
        assertTrue(c.entityList().items().get(1) instanceof EntityRow);
        // the player can still close it
        p.click(rowX(c.entityList()), rowY(c.entityList(), 0));
        assertEquals(1, c.entityList().items().size());
        // a type match is not auto opened
        tab.onSearch("wolf");
        assertEquals(1, c.entityList().items().size());
    }

    @Test
    public void theStackedPlayersListGrowsWhenPlayersArriveAfterLayout() throws Exception {
        FakeFacade empty = new FakeFacade();
        empty.world.players.clear();
        FollowTab[] holder = new FollowTab[1];
        Preview small = new Preview(960, 700, empty, pv -> {
            holder[0] = new FollowTab(pv.theme, pv.facade, pv.ctx);
            return List.of(holder[0]);
        });
        FollowContent sc = (FollowContent) holder[0].content();
        int before = sc.playerList().h();
        empty.world.players.add(new adris.altoclef.ui.gui.facade.WorldAccess.PlayerEntry("Steve", 5, false));
        empty.world.players.add(new adris.altoclef.ui.gui.facade.WorldAccess.PlayerEntry("Alex", 6, false));
        empty.world.players.add(new adris.altoclef.ui.gui.facade.WorldAccess.PlayerEntry("Amy", 7, false));
        empty.world.players.add(new adris.altoclef.ui.gui.facade.WorldAccess.PlayerEntry("Bob", 8, false));
        for (int k = 0; k < 10; k++) {
            small.tick();
        }
        assertTrue(sc.playerList().h() > before);
        assertEquals(0, sc.playerList().h() % sc.playerList().rowHeight());
    }

    @Test
    public void scrollOffsetSurvivesARefresh() {
        for (int k = 0; k < 40; k++) {
            f.world.entities.add(new EntityEntry(1000 + k, "minecraft:type" + k, "Type" + k, "", 70 + k, null));
        }
        tickTimes(10);
        p.scroll(rowX(c.entityList()), rowY(c.entityList(), 1), -3);
        p.advance(400);
        p.settle();
        float before = c.entityList().scroller().target();
        assertTrue(before > 0);
        tickTimes(10);
        assertEquals(before, c.entityList().scroller().target(), 0.001f);
    }
}
