package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.WorldAccess.WaypointEntry;
import adris.altoclef.ui.gui.kit.Keys;
import adris.altoclef.ui.gui.preview.FakeFacade;
import adris.altoclef.ui.gui.preview.Preview;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class WaypointsTabTest {

    private FakeFacade facade;
    private Preview p;
    private WaypointsTab tab;

    @Before
    public void setUp() throws Exception {
        facade = new FakeFacade();
        WaypointsTab[] holder = new WaypointsTab[1];
        p = new Preview(facade, pv -> {
            holder[0] = new WaypointsTab(pv.theme, pv.facade, pv.ctx);
            return List.of(holder[0]);
        });
        tab = holder[0];
        tab.onShown();
        p.settle();
    }

    private List<String> names() {
        List<String> out = new ArrayList<>();
        for (WaypointEntry e : tab.list().items()) {
            out.add(e.name());
        }
        return out;
    }

    private void clickZone(int row, int which) {
        int[] z = tab.zone(row, which);
        p.click(z[0] + z[2] / 2f, z[1] + z[3] / 2f);
        p.settle();
        // every cancel test starts here, so it must really be asking or "now it is null" proves nothing
        if (which == WaypointRows.DELETE) {
            assertTrue(tab.confirming() != null);
        }
    }

    @Test
    public void anUnansweredConfirmLapsesOnItsOwn() {
        clickZone(0, WaypointRows.DELETE);
        for (int i = 0; i < 99; i++) {
            p.tick();
        }
        assertTrue(tab.confirming() != null);
        p.tick();
        assertNull(tab.confirming());
        assertTrue(facade.actions.isEmpty());
        assertEquals(5, names().size());
    }

    @Test
    public void listsNearestFirst() {
        assertEquals(List.of("home", "portal", "iron cave", "village", "death"), names());
    }

    @Test
    public void sortByName() {
        p.click(tab.sort().x() + tab.sort().w() - 10, tab.sort().y() + tab.sort().h() / 2f);
        assertEquals(1, tab.sort().selected());
        assertEquals(List.of("death", "home", "iron cave", "portal", "village"), names());
        p.click(tab.sort().x() + 10, tab.sort().y() + tab.sort().h() / 2f);
        assertEquals(List.of("home", "portal", "iron cave", "village", "death"), names());
    }

    @Test
    public void searchFiltersNameAndTag() {
        tab.onSearch("cave");
        assertEquals(List.of("iron cave"), names());
        // "user" is a tag, not in any name
        tab.onSearch("user");
        assertEquals(List.of("portal", "iron cave", "village"), names());
        tab.onSearch("DEATH");
        assertEquals(List.of("death"), names());
        tab.onSearch("");
        assertEquals(5, names().size());
    }

    @Test
    public void infoLineHasTheDimensionAndTheCount() {
        assertEquals("overworld · 5 waypoints", tab.info());
        tab.onSearch("user");
        assertEquals("overworld · 3 of 5 waypoints", tab.info());
        facade.world.waypoints.subList(1, 5).clear();
        facade.world.dimension = "the_nether";
        tab.onSearch("");
        assertEquals("the nether · 1 waypoint", tab.info());
    }

    @Test
    public void emptyStates() {
        facade.world.waypoints.clear();
        tab.onShown();
        assertTrue(names().isEmpty());
        p.settle();
        tab.onSearch("zzz");
        assertEquals("overworld · 0 waypoints", tab.info());
        facade.world.waypoints.add(new WaypointEntry("home", "home", 1, 2, 3, 0));
        tab.onSearch("zzz");
        assertTrue(names().isEmpty());
        assertEquals("overworld · 0 of 1 waypoint", tab.info());
    }

    @Test
    public void refreshesEveryTwentyTicks() {
        facade.world.waypoints.add(new WaypointEntry("new one", "user", 121, 64, -30, 0));
        for (int i = 0; i < 19; i++) {
            p.tick();
        }
        assertEquals(5, names().size());
        p.tick();
        assertEquals(6, names().size());
        assertEquals("new one", names().get(0));
    }

    @Test
    public void addHere() {
        p.click(tab.nameField().x() + 10, tab.nameField().y() + 10);
        p.type("base");
        p.click(tab.addButton().x() + 10, tab.addButton().y() + 10);
        assertEquals(List.of("wp save user base"), facade.actions);
        assertEquals("", tab.nameField().text());
        assertTrue(names().contains("base"));
        assertNull(tab.addError());
    }

    @Test
    public void addWithTheTagYouPicked() {
        tab.tagDrop().setSelected(2);
        p.click(tab.nameField().x() + 10, tab.nameField().y() + 10);
        p.type("grave");
        p.key(Keys.ENTER);
        assertEquals(List.of("wp save death grave"), facade.actions);
    }

    @Test
    public void emptyNameIsAnInlineErrorNotAnAction() {
        p.click(tab.addButton().x() + 10, tab.addButton().y() + 10);
        assertEquals("Give it a name", tab.addError());
        assertTrue(tab.nameField().invalid());
        assertTrue(facade.actions.isEmpty());
        // spaces are empty too
        p.click(tab.nameField().x() + 10, tab.nameField().y() + 10);
        p.type("  ");
        p.key(Keys.ENTER);
        assertEquals("Give it a name", tab.addError());
        assertTrue(facade.actions.isEmpty());
        // typing clears it
        p.type("x");
        assertNull(tab.addError());
        assertFalse(tab.nameField().invalid());
    }

    @Test
    public void goButtonOnARow() {
        clickZone(0, WaypointRows.GO);
        assertEquals(List.of("goto 118 64 -32"), facade.actions);
    }

    @Test
    public void copyButtonOnARow() {
        clickZone(1, WaypointRows.COPY);
        assertEquals("130 63 -28", facade.clipboard.get());
        p.settle();
        assertEquals("Copied 130 63 -28", p.shell.toast().currentLine());
        assertTrue(facade.actions.isEmpty());
    }

    @Test
    public void rowBodyClickOnlySelects() {
        int[] z = tab.zone(0, WaypointRows.GO);
        p.click(tab.list().x() + 50, z[1] + z[3] / 2f);
        assertEquals(0, tab.list().selected());
        assertTrue(facade.actions.isEmpty());
    }

    @Test
    public void deleteAsksInsideTheRowThenYesDeletes() {
        clickZone(0, WaypointRows.DELETE);
        assertEquals("home", tab.confirming().name());
        assertTrue(facade.actions.isEmpty());
        assertEquals(5, names().size());
        clickZone(0, WaypointRows.YES);
        assertEquals(List.of("wp delete home"), facade.actions);
        assertNull(tab.confirming());
        assertEquals(4, names().size());
        assertFalse(names().contains("home"));
    }

    @Test
    public void noCancels() {
        clickZone(0, WaypointRows.DELETE);
        clickZone(0, WaypointRows.NO);
        assertNull(tab.confirming());
        assertEquals(5, names().size());
        assertTrue(facade.actions.isEmpty());
    }

    @Test
    public void escapeCancelsAndDoesNotCloseTheGui() {
        clickZone(2, WaypointRows.DELETE);
        assertEquals("iron cave", tab.confirming().name());
        p.key(Keys.ESCAPE);
        assertNull(tab.confirming());
        assertFalse(facade.actions.contains("close"));
        assertEquals(5, names().size());
    }

    @Test
    public void clickingElsewhereCancels() {
        clickZone(0, WaypointRows.DELETE);
        p.click(tab.sort().x() + 5, tab.sort().y() + 5);
        assertNull(tab.confirming());

        clickZone(0, WaypointRows.DELETE);
        p.click(tab.nameField().x() + 10, tab.nameField().y() + 10);
        assertNull(tab.confirming());

        // another row's body
        clickZone(0, WaypointRows.DELETE);
        int[] z = tab.zone(3, WaypointRows.GO);
        p.click(tab.list().x() + 50, z[1] + z[3] / 2f);
        assertNull(tab.confirming());
        assertEquals(5, names().size());
        assertTrue(facade.actions.isEmpty());
    }

    @Test
    public void clickingAnotherRowsActionCancelsAndStillDoesIt() {
        clickZone(0, WaypointRows.DELETE);
        clickZone(1, WaypointRows.GO);
        assertNull(tab.confirming());
        assertEquals(List.of("goto 130 63 -28"), facade.actions);
    }

    @Test
    public void clickingTheEmptySpaceUnderTheRowsCancels() {
        clickZone(0, WaypointRows.DELETE);
        int[] last = tab.zone(4, WaypointRows.GO);
        p.click(tab.list().x() + 200, last[1] + last[3] + p.theme.px(40));
        assertNull(tab.confirming());
        assertTrue(facade.actions.isEmpty());
        assertEquals(5, names().size());
    }

    @Test
    public void scrollingCancels() {
        clickZone(0, WaypointRows.DELETE);
        p.scroll(tab.list().x() + 200, tab.list().y() + 20, -1);
        assertNull(tab.confirming());
    }

    @Test
    public void tabbingAwayCancels() {
        clickZone(0, WaypointRows.DELETE);
        p.key(Keys.TAB);
        assertNull(tab.confirming());
    }

    @Test
    public void typingALetterCancelsAndGoesToTheSearchBox() {
        clickZone(0, WaypointRows.DELETE);
        p.type("c");
        assertNull(tab.confirming());
        assertSame(p.shell.search(), p.shell.focusedWidget());
        assertEquals(List.of("iron cave"), names());
    }

    @Test
    public void searchBoxDrivesTheFilter() {
        p.click(p.shell.search().x() + 20, p.shell.search().y() + 10);
        p.type("vil");
        assertEquals(List.of("village"), names());
        p.type("zz");
        assertTrue(names().isEmpty());
    }

    @Test
    public void pickingATagFromTheOpenMenuIsWhatAddUses() {
        p.click(tab.tagDrop().x() + 20, tab.tagDrop().y() + 10);
        assertTrue(p.shell.overlay() != null);
        // down twice and enter: user -> home -> death
        p.key(Keys.DOWN);
        p.key(Keys.DOWN);
        p.key(Keys.ENTER);
        assertEquals(2, tab.tagDrop().selected());
        p.click(tab.nameField().x() + 10, tab.nameField().y() + 10);
        p.type("grave");
        p.key(Keys.ENTER);
        assertEquals(List.of("wp save death grave"), facade.actions);
    }

    @Test
    public void aChosenTagSurvivesTheRefresh() {
        tab.tagDrop().setSelected(3);
        for (int i = 0; i < 20; i++) {
            p.tick();
        }
        assertEquals(3, tab.tagDrop().selected());
    }

    @Test
    public void leavingTheTabCancels() {
        clickZone(0, WaypointRows.DELETE);
        tab.onHidden();
        assertNull(tab.confirming());
    }

    @Test
    public void searchingCancels() {
        clickZone(0, WaypointRows.DELETE);
        tab.onSearch("p");
        assertNull(tab.confirming());
    }

    @Test
    public void aRowThatVanishedCancelsItsConfirm() {
        clickZone(0, WaypointRows.DELETE);
        facade.world.waypoints.remove(0);
        for (int i = 0; i < 20; i++) {
            p.tick();
        }
        assertNull(tab.confirming());
    }

    @Test
    public void keyboardGoesAndDeletes() {
        int[] z = tab.zone(1, WaypointRows.GO);
        p.click(tab.list().x() + 50, z[1] + z[3] / 2f);
        p.key(Keys.ENTER);
        assertEquals(List.of("goto 130 63 -28"), facade.actions);
        p.key(Keys.DELETE);
        assertEquals("portal", tab.confirming().name());
        p.key(Keys.ENTER);
        assertEquals("wp delete portal", facade.actions.get(1));
        assertEquals(4, names().size());
    }

    @Test
    public void arrowsMoveOffAConfirm() {
        int[] z = tab.zone(1, WaypointRows.GO);
        p.click(tab.list().x() + 50, z[1] + z[3] / 2f);
        p.key(Keys.DELETE);
        assertEquals("portal", tab.confirming().name());
        p.key(Keys.DOWN);
        assertNull(tab.confirming());
        assertEquals(2, tab.list().selected());
    }

    @Test
    public void tabOrderIsAddRowThenSortThenList() {
        // the shell's search box comes first
        p.key(Keys.TAB);
        assertSame(p.shell.search(), p.shell.focusedWidget());
        p.key(Keys.TAB);
        assertSame(tab.nameField(), p.shell.focusedWidget());
        p.key(Keys.TAB);
        assertSame(tab.tagDrop(), p.shell.focusedWidget());
        p.key(Keys.TAB);
        assertSame(tab.addButton(), p.shell.focusedWidget());
        p.key(Keys.TAB);
        assertSame(tab.sort(), p.shell.focusedWidget());
        p.key(Keys.TAB);
        assertSame(tab.list(), p.shell.focusedWidget());
    }

    @Test
    public void sidebarContract() {
        assertEquals("waypoints", tab.id());
        assertEquals("Waypoints", tab.title());
        assertTrue(tab.hasSearch());
        assertEquals("Search waypoints", tab.searchPlaceholder());
    }

    @Test
    public void rulesSortAndFilter() {
        List<WaypointEntry> all = facade.world.waypoints;
        var pos = facade.world.pos;
        assertEquals("death", WaypointRules.arrange(all, "", true, pos).get(0).name());
        assertEquals("home", WaypointRules.arrange(all, "", false, pos).get(0).name());
        // nobody home: distance order has nothing to go on, fall back to names
        assertEquals("death", WaypointRules.arrange(all, "", false, null).get(0).name());
        assertEquals(-1, WaypointRules.distance(all.get(0), null), 0);
        assertTrue(WaypointRules.matches(all.get(0), null));
        assertEquals("118 64 -32", WaypointRules.coords(all.get(0)));
        assertEquals("1 waypoint", WaypointRules.count(1, 1));
        assertEquals("0 waypoints", WaypointRules.count(0, 0));
        assertEquals("2 of 9 waypoints", WaypointRules.count(2, 9));
    }
}
