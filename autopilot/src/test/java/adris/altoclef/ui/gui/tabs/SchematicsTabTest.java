package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.SchematicsAccess.ImportReport;
import adris.altoclef.ui.gui.facade.SchematicsAccess.Row;
import adris.altoclef.ui.gui.kit.Keys;
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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class SchematicsTabTest {

    private FakeFacade facade;
    private Preview p;
    private SchematicsTab tab;

    @Before
    public void setUp() throws Exception {
        facade = new FakeFacade();
        SchematicsTab[] holder = new SchematicsTab[1];
        p = new Preview(facade, pv -> {
            holder[0] = new SchematicsTab(pv.theme, pv.facade, pv.ctx);
            return List.of(holder[0]);
        });
        tab = holder[0];
        tab.onShown();
        p.settle();
    }

    private List<String> paths() {
        List<String> out = new ArrayList<>();
        for (Row r : tab.list().items()) {
            out.add(r.relativePath());
        }
        return out;
    }

    // a click shows the file, the tick after it draws the map (terrain is the tick's business, not the click's)
    private void clickRow(int i) {
        p.click(tab.list().x() + 60, tab.rowMidY(i));
        p.tick();
        p.settle();
    }

    // a different window: the 540p and 640-wide cases
    private void reopen(int w, int h) throws Exception {
        facade = new FakeFacade();
        SchematicsTab[] holder = new SchematicsTab[1];
        p = new Preview(w, h, facade, pv -> {
            holder[0] = new SchematicsTab(pv.theme, pv.facade, pv.ctx);
            return List.of(holder[0]);
        });
        tab = holder[0];
        tab.onShown();
        p.settle();
    }

    private void clickBuild() {
        p.click(tab.buildButton().x() + 10, tab.buildButton().y() + 10);
        p.settle();
    }

    private void ticks(int n) {
        for (int i = 0; i < n; i++) {
            p.tick();
        }
    }

    @Test
    public void sidebarContract() {
        assertEquals("schematics", tab.id());
        assertEquals("Schematics", tab.title());
        assertTrue(tab.hasSearch());
        assertEquals("Search schematics", tab.searchPlaceholder());
    }

    @Test
    public void listsEveryFileInTheFolder() {
        assertEquals(List.of("Small Castle.litematic", "tower.schem", "broken.litematic", "villages/farm.schematic"), paths());
        assertEquals("4 schematics · 1 didn't parse", tab.countLine());
        assertNull(tab.selectedPath());
        assertFalse(tab.buildButton().enabled());
    }

    @Test
    public void theGhostFollowsThePick() {
        assertNull(facade.schematics.ghost());
        clickRow(0);
        assertEquals("Small Castle.litematic", facade.schematics.ghost());
        clickRow(1);
        assertEquals("tower.schem", facade.schematics.ghost());
        // a file that vanishes takes its ghost with it
        facade.schematics.remove("tower.schem");
        ticks(2);
        p.settle();
        assertNull(facade.schematics.ghost());
    }

    @Test
    public void searchFiltersByPath() {
        tab.onSearch("villages");
        assertEquals(List.of("villages/farm.schematic"), paths());
        assertEquals("1 of 4 schematics · 1 didn't parse", tab.countLine());
        tab.onSearch("LITEMATIC");
        assertEquals(List.of("Small Castle.litematic", "broken.litematic"), paths());
        tab.onSearch("zzz");
        assertTrue(paths().isEmpty());
        assertEquals("No schematics match", tab.listState());
        tab.onSearch("");
        assertEquals(4, paths().size());
    }

    @Test
    public void clickingARowShowsItsMaterials() {
        clickRow(0);
        assertEquals("Small Castle.litematic", tab.selectedPath());
        assertTrue(tab.pane().showsList());
        assertEquals("3,557 blocks · 7 kinds · 3,490 short", tab.pane().summary());
        assertEquals("3,490 short. It pauses when it runs out.", tab.pane().warning());
        assertTrue(tab.buildButton().enabled());
        // 7 items, the two folded kinds, one note
        assertEquals(10, tab.pane().list().items().size());
    }

    @Test
    public void keyboardSelectsToo() {
        p.click(tab.list().x() + 60, tab.rowMidY(0));
        p.key(Keys.DOWN);
        p.key(Keys.ENTER);
        assertEquals("tower.schem", tab.selectedPath());
    }

    @Test
    public void foldedKindsOpenOnClick() {
        clickRow(0);
        var mats = tab.pane().list();
        // the section rows sit after the seven items, past the first page now that the map has the top of the card
        mats.ensureVisible(9);
        p.settle();
        p.advance(600);
        p.settle();
        float y = mats.rowMidY(7);
        p.click(mats.x() + 40, y);
        p.settle();
        assertTrue(tab.pane().openSections().contains("Can't place"));
        assertEquals(11, mats.items().size());
        assertEquals(MaterialRows.Type.SUB, mats.items().get(8).type());
        p.click(mats.x() + 40, y);
        p.settle();
        assertEquals(10, mats.items().size());
    }

    @Test
    public void everythingInHandReadsAsCovered() {
        for (String id : List.of("minecraft:stone_bricks", "minecraft:oak_log", "minecraft:glass", "minecraft:oak_stairs",
                "minecraft:torch", "minecraft:farmland", "minecraft:beacon")) {
            facade.schematics.have.put(id, 5000);
        }
        clickRow(0);
        assertEquals("3,557 blocks · 7 kinds · all in your inventory", tab.pane().summary());
        // fire can't be placed, that still gets a line
        assertEquals("Some blocks can't be placed, those spots stay empty.", tab.pane().warning());
    }

    @Test
    public void inventoryChangesShowUpOnTick() {
        clickRow(0);
        facade.schematics.have.put("minecraft:stone_bricks", 3120);
        ticks(1);
        assertEquals("3,557 blocks · 7 kinds · 370 short", tab.pane().summary());
    }

    @Test
    public void selectionIsByPathNotIndex() {
        clickRow(1);
        assertEquals("tower.schem", tab.selectedPath());
        // a new file lands at the end, the index of nothing else moves, but a vanished first row would
        facade.schematics.remove("Small Castle.litematic");
        ticks(1);
        assertEquals(List.of("tower.schem", "broken.litematic", "villages/farm.schematic"), paths());
        assertEquals("tower.schem", tab.selectedPath());
        assertEquals(0, tab.list().items().indexOf(tab.list().items().stream().filter(r -> r.relativePath().equals("tower.schem")).findFirst().get()));
    }

    @Test
    public void aVanishedSelectionFallsBackToNothing() {
        clickRow(0);
        clickBuild();
        assertTrue(tab.armed());
        facade.schematics.remove("Small Castle.litematic");
        ticks(1);
        assertNull(tab.selectedPath());
        assertFalse(tab.armed());
        assertFalse(tab.buildButton().enabled());
        assertFalse(tab.pane().showsList());
    }

    @Test
    public void buildTakesTwoClicks() {
        clickRow(0);
        clickBuild();
        assertTrue(tab.armed());
        assertEquals("Click again to build at your feet", tab.buildButton().text());
        assertTrue(facade.actions.isEmpty());
        clickBuild();
        assertFalse(tab.armed());
        assertEquals("Build here", tab.buildButton().text());
        // straight to the build: where it goes is the map's business now, no detour through the feet
        assertEquals(List.of("schematics build Small Castle.litematic"), facade.actions);
        assertEquals("Building Small Castle.litematic at 100, 64, -40", p.shell.toast().currentLine());
        assertTrue(p.shell.toast().currentOk());
    }

    @Test
    public void anArmedButtonLapsesOnItsOwn() {
        clickRow(0);
        clickBuild();
        ticks(59);
        assertTrue(tab.armed());
        ticks(1);
        assertFalse(tab.armed());
        clickBuild();
        assertTrue(facade.actions.isEmpty());
    }

    @Test
    public void theArmedButtonSaysWhereItBuilds() {
        clickRow(0);
        facade.schematics.move("Small Castle.litematic", 1, 0, 0);
        clickBuild();
        assertEquals("Click again to build at 101 64 -40", tab.buildButton().text());
    }

    @Test
    public void aLongPlaceFallsBackToTheTooltipAtTheFloor() throws Exception {
        reopen(830, 540);
        clickRow(0);
        facade.schematics.move("Small Castle.litematic", -29_000_000, 0, -29_000_000);
        clickBuild();
        var b = tab.buildButton();
        assertEquals("Click again to build", b.text());
        assertTrue(b.x() >= tab.pane().x());
        assertEquals("Click again to build at -28999900 64 -29000040", tab.pane().tipAt(b.x() + 2, b.y() + 2));
    }

    @Test
    public void aBuildWithNothingPickedSaysSo() {
        tab.build();
        p.settle();
        assertFalse(tab.armed());
        assertEquals("Pick a schematic first", p.shell.toast().currentLine());
        assertFalse(p.shell.toast().currentOk());
        assertTrue(facade.actions.isEmpty());
    }

    @Test
    public void changingTheSelectionDisarms() {
        clickRow(0);
        clickBuild();
        clickRow(3);
        assertFalse(tab.armed());
        assertEquals("villages/farm.schematic", tab.selectedPath());
        assertTrue(facade.actions.isEmpty());
    }

    @Test
    public void leavingTheTabOrSearchingDisarms() {
        clickRow(0);
        clickBuild();
        tab.onHidden();
        assertFalse(tab.armed());
        clickBuild();
        tab.onSearch("a");
        assertFalse(tab.armed());
        assertTrue(facade.actions.isEmpty());
    }

    @Test
    public void aFailedBuildToastsRed() {
        facade.schematics.buildError = "No world loaded";
        clickRow(0);
        clickBuild();
        clickBuild();
        p.settle();
        assertEquals("No world loaded", p.shell.toast().currentLine());
        assertFalse(p.shell.toast().currentOk());
    }

    @Test
    public void noWorldMeansNoBuild() {
        facade.world.pos = null;
        facade.schematics.inWorld = false;
        clickRow(0);
        assertFalse(tab.buildButton().enabled());
        assertEquals("Join a world to build", tab.pane().warning());
        assertEquals(PlacementMap.Mode.NO_WORLD, map().mode());
        for (var b : tab.pane().controls()) {
            assertFalse(b.enabled());
        }
        clickBuild();
        assertFalse(tab.armed());
        assertTrue(facade.actions.isEmpty());
    }

    @Test
    public void aBrokenFileIsSelectableButNotBuildable() {
        clickRow(2);
        assertEquals("broken.litematic", tab.selectedPath());
        assertEquals("EOFException: unexpected end of file", tab.pane().errorText());
        // nowhere to place a file with no size: the map band is left out, not shown empty
        assertEquals(PlacementMap.Mode.HIDDEN, map().mode());
        assertFalse(map().visible());
        assertFalse(tab.pane().showsList());
        assertFalse(tab.buildButton().enabled());
        assertEquals("Didn't parse", tab.pane().summary());
    }

    @Test
    public void importToastsTheReportAndShowsTheFile() {
        p.click(tab.importButton().x() + 10, tab.importButton().y() + 10);
        p.settle();
        assertEquals(List.of("schematics pick"), facade.actions);
        assertEquals("Imported picked.litematic", p.shell.toast().currentLine());
        assertTrue(paths().contains("picked.litematic"));
        assertEquals("5 schematics · 1 didn't parse", tab.countLine());
    }

    @Test
    public void aCancelledPickerIsAQuietToast() {
        facade.schematics.nextPick = ImportReport.nothingChosen();
        p.click(tab.importButton().x() + 10, tab.importButton().y() + 10);
        p.settle();
        assertEquals("Nothing chosen", p.shell.toast().currentLine());
        assertEquals(4, paths().size());
    }

    @Test
    public void openFolderToasts() {
        p.click(tab.folderButton().x() + 10, tab.folderButton().y() + 10);
        p.settle();
        assertEquals(List.of("schematics folder"), facade.actions);
        assertEquals("Opened the schematics folder", p.shell.toast().currentLine());
    }

    @Test
    public void refreshKeepsTheRowsUpWhileItScans() {
        facade.schematics.loadingPolls = 3;
        p.click(tab.refreshButton().x() + 10, tab.refreshButton().y() + 10);
        p.settle();
        assertEquals(List.of("schematics refresh"), facade.actions);
        assertEquals(4, paths().size());
        assertEquals("4 schematics · 1 didn't parse · Refreshing…", tab.countLine());
        assertEquals("", tab.listState());
        ticks(3);
        assertEquals("4 schematics · 1 didn't parse", tab.countLine());
    }

    @Test
    public void theFirstScanHasItsOwnLine() {
        facade.schematics.clear();
        facade.schematics.loadingForever = true;
        tab.onShown();
        assertTrue(paths().isEmpty());
        assertEquals("Reading the schematics folder…", tab.listState());
        assertEquals("", tab.countLine());
        assertFalse(tab.pane().visible());
        facade.schematics.loadingForever = false;
        ticks(1);
        assertEquals("", tab.listState());
        assertEquals("0 schematics", tab.countLine());
        assertFalse(tab.pane().visible());
        facade.schematics.importDropped(List.of(java.nio.file.Path.of("new.litematic")));
        ticks(1);
        assertTrue(tab.pane().visible());
    }

    @Test
    public void materialsStillCountingShowsALoadingPane() {
        facade.schematics.loadingForever = true;
        clickRow(0);
        assertTrue(tab.pane().loading());
        assertEquals("Counting blocks…", tab.pane().summary());
        // no building before the count says what you are short of
        assertFalse(tab.buildButton().enabled());
        assertEquals("Counting blocks…", tab.pane().warning());
        facade.schematics.loadingForever = false;
        ticks(1);
        assertTrue(tab.buildButton().enabled());
    }

    @Test
    public void aFileThatCannotBeCountedStaysUnbuildable() {
        facade.schematics.importDropped(List.of(java.nio.file.Path.of("odd.litematic")));
        facade.schematics.forgetMaterials("odd.litematic");
        ticks(1);
        clickRow(4);
        assertEquals("Couldn't read this file", tab.pane().errorText());
        assertEquals("Couldn't count the blocks", tab.pane().warning());
        assertFalse(tab.buildButton().enabled());
    }

    @Test
    public void tooltipShowsTheErrorOrTheCutPathAfterARest() {
        p.hover(tab.list().x() + 60, tab.rowMidY(2));
        p.settle();
        assertNull(tab.tipText());
        ticks(10);
        assertEquals("EOFException: unexpected end of file", tab.tipText());
        p.hover(-100, -100);
        p.settle();
        assertNull(tab.tipText());
        // a path that fits gets no tip, however long it is
        p.hover(tab.list().x() + 60, tab.rowMidY(3));
        p.settle();
        ticks(10);
        assertNull(tab.tipText());
        // the fake keeps the file name only, the real importer keeps a dropped folder's sub folders
        facade.schematics.importDropped(List.of(java.nio.file.Path.of("a path long enough that the row has to cut it short somewhere.litematic")));
        ticks(1);
        p.hover(tab.list().x() + 60, tab.rowMidY(4));
        p.settle();
        ticks(10);
        assertEquals("a path long enough that the row has to cut it short somewhere.litematic", tab.tipText());
    }

    @Test
    public void unchangedDataNeverRebuildsTheLists() {
        clickRow(0);
        List<Row> rowsBefore = tab.list().items();
        var matsBefore = tab.pane().list().items();
        ticks(5);
        assertSame(rowsBefore, tab.list().items());
        assertSame(matsBefore, tab.pane().list().items());
        // a rescan that finds the same files leaves the rows alone too (the fake answers LOADING to the count as
        // well while it polls, so only the rows are checked past the refresh)
        facade.schematics.loadingPolls = 2;
        p.click(tab.refreshButton().x() + 10, tab.refreshButton().y() + 10);
        ticks(5);
        assertSame(rowsBefore, tab.list().items());
    }

    @Test
    public void theWarningMarkIsForUnknownBlocksOnly() {
        assertFalse(tab.warned(0));
        assertTrue(tab.warned(1));
        assertFalse(tab.warned(2));
        assertFalse(tab.warned(3));
    }

    @Test
    public void theKeyboardCursorFollowsTheSelectionThroughARefresh() {
        clickRow(2);
        assertEquals(2, tab.list().selected());
        facade.schematics.remove("Small Castle.litematic");
        ticks(1);
        assertEquals(1, tab.list().selected());
        tab.onSearch("broken");
        assertEquals(0, tab.list().selected());
        tab.onSearch("tower");
        assertEquals(-1, tab.list().selected());
        assertEquals("broken.litematic", tab.selectedPath());
    }

    @Test
    public void tabOrderIsToolbarThenListThenPane() {
        p.key(Keys.TAB);
        assertSame(p.shell.search(), p.shell.focusedWidget());
        p.key(Keys.TAB);
        assertSame(tab.importButton(), p.shell.focusedWidget());
        p.key(Keys.TAB);
        assertSame(tab.folderButton(), p.shell.focusedWidget());
        p.key(Keys.TAB);
        assertSame(tab.refreshButton(), p.shell.focusedWidget());
        p.key(Keys.TAB);
        assertSame(tab.list(), p.shell.focusedWidget());
    }

    @Test
    public void rules() {
        assertEquals("1 schematic", SchematicRules.count(1, 1, 0));
        assertEquals("0 schematics", SchematicRules.count(0, 0, 0));
        assertEquals("2 of 9 schematics · 3 didn't parse", SchematicRules.count(2, 9, 3));
        assertEquals("120 B", SchematicRules.fileSize(120));
        assertEquals("47 KB", SchematicRules.fileSize(48_211));
        assertEquals("1.5 MB", SchematicRules.fileSize(1_572_864));
        assertEquals("3,120", SchematicRules.num(3120));
        List<Row> rows = facade.schematics.list().rows();
        assertEquals("Litematica v6 · 24 x 18 x 24 · 47 KB", SchematicRules.meta(rows.get(0)));
        assertEquals("Litematica · 120 B", SchematicRules.meta(rows.get(2)));
        assertEquals("Didn't parse", SchematicRules.sub(rows.get(2)));
        assertEquals("MCEdit · 15 x 6 x 12", SchematicRules.sub(rows.get(3)));
    }

    // ----- where it goes: the map band and its controls

    private PlacementMap map() {
        return tab.pane().map();
    }

    private void clickControl(String name) {
        clickControl(name, 0);
    }

    private void clickControl(String name, int mods) {
        var b = tab.pane().control(name);
        p.click(b.x() + b.w() / 2f, b.y() + b.h() / 2f, mods);
        p.settle();
    }

    @Test
    public void theControlsEditThePlacementThroughTheFacade() {
        clickRow(0);
        assertEquals(PlacementMap.Mode.MAP, map().mode());
        assertTrue(map().visible());
        assertEquals("x 100 y 64 z -40 · 0°", map().coords());
        clickControl("west");
        clickControl("north");
        clickControl("south");
        clickControl("east");
        clickControl("up");
        clickControl("down");
        clickControl("turnRight");
        clickControl("turnLeft");
        clickControl("mirror");
        clickControl("feet");
        assertEquals(List.of(
            "schematics move Small Castle.litematic -1 0 0",
            "schematics move Small Castle.litematic 0 0 -1",
            "schematics move Small Castle.litematic 0 0 1",
            "schematics move Small Castle.litematic 1 0 0",
            "schematics move Small Castle.litematic 0 1 0",
            "schematics move Small Castle.litematic 0 -1 0",
            "schematics rotate Small Castle.litematic cw",
            "schematics rotate Small Castle.litematic ccw",
            "schematics mirror Small Castle.litematic",
            "schematics feet Small Castle.litematic"), facade.actions);
        // move to me keeps the flip, and the chip shows the new placement the same tick
        assertEquals("x 100 y 64 z -40 · 0° · mirrored", map().coords());
    }

    @Test
    public void shiftStepsEightBlocks() {
        clickRow(0);
        clickControl("east", Keys.MOD_SHIFT);
        clickControl("up", Keys.MOD_SHIFT);
        clickControl("north", Keys.MOD_SHIFT);
        assertEquals(List.of(
            "schematics move Small Castle.litematic 8 0 0",
            "schematics move Small Castle.litematic 0 8 0",
            "schematics move Small Castle.litematic 0 0 -8"), facade.actions);
        assertEquals("x 108 y 72 z -48 · 0°", map().coords());
    }

    @Test
    public void theChipReadsTheTurnAndTheFlip() {
        clickRow(0);
        clickControl("turnRight");
        assertEquals("x 100 y 64 z -40 · 90°", map().coords());
        // a flip inverts the turn (the fake mirrors the real rule)
        clickControl("mirror");
        assertEquals("x 100 y 64 z -40 · 270° · mirrored", map().coords());
        clickControl("turnLeft");
        assertEquals("x 100 y 64 z -40 · 180° · mirrored", map().coords());
    }

    @Test
    public void draggingTheFootprintMovesItOnceOnRelease() {
        clickRow(0);
        PlacementMap m = map();
        int s = m.pxPerBlock();
        float cx = m.footprintCentreX();
        float cy = m.footprintCentreY();
        p.press(cx, cy);
        assertTrue(m.dragging());
        p.drag(cx + 3 * s, cy - 2 * s);
        // the chip follows the pointer, nothing is saved until the drop
        assertEquals("x 103 y 64 z -42 · 0°", m.coords());
        assertTrue(facade.actions.isEmpty());
        p.release(cx + 3 * s, cy - 2 * s);
        p.settle();
        assertFalse(m.dragging());
        assertEquals(List.of("schematics move Small Castle.litematic 3 0 -2"), facade.actions);
        assertEquals("x 103 y 64 z -42 · 0°", m.coords());
        // a press that goes nowhere is not a move
        p.press(m.footprintCentreX(), m.footprintCentreY());
        p.release(m.footprintCentreX(), m.footprintCentreY());
        assertEquals(1, facade.actions.size());
        // and the terrain around the footprint is not a handle
        p.press(m.x() + 10, m.y() + m.h() - 10);
        assertFalse(m.dragging());
        p.release(m.x() + 10, m.y() + m.h() - 10);
    }

    @Test
    public void aDragThatLosesItsFileIsDropped() {
        clickRow(0);
        PlacementMap m = map();
        float cx = m.footprintCentreX();
        float cy = m.footprintCentreY();
        p.press(cx, cy);
        p.drag(cx + 4 * m.pxPerBlock(), cy);
        facade.schematics.remove("Small Castle.litematic");
        ticks(1);
        assertFalse(m.dragging());
        p.release(cx + 4 * m.pxPerBlock(), cy);
        assertTrue(facade.actions.isEmpty());
    }

    @Test
    public void theWheelZoomsTheMap() {
        clickRow(0);
        PlacementMap m = map();
        float cx = m.x() + m.w() / 2f;
        float cy = m.y() + m.h() / 2f;
        assertEquals(PlacementMap.DEFAULT_SCALE, m.scaleIndex());
        p.scroll(cx, cy, 1);
        assertEquals(2, m.scaleIndex());
        p.scroll(cx, cy, 1);
        assertEquals(2, m.scaleIndex());
        p.scroll(cx, cy, -1);
        p.scroll(cx, cy, -1);
        assertEquals(0, m.scaleIndex());
        p.scroll(cx, cy, -1);
        assertEquals(0, m.scaleIndex());
    }

    @Test
    public void anEditDisarmsTheBuildButton() {
        clickRow(0);
        clickBuild();
        assertTrue(tab.armed());
        clickControl("north");
        assertFalse(tab.armed());
        assertEquals(List.of("schematics move Small Castle.litematic 0 0 -1"), facade.actions);
    }

    @Test
    public void placedInAnotherDimensionOnlyMoveToMeWorks() {
        clickRow(0);
        // the placement was made in the overworld, then the player went through a portal
        facade.schematics.dimension = "minecraft:the_nether";
        ticks(1);
        p.settle();
        assertEquals(PlacementMap.Mode.ELSEWHERE, map().mode());
        assertEquals("minecraft:overworld", map().elsewhere());
        assertEquals("Placed in the Overworld. Move it here first.", tab.pane().warning());
        assertFalse(tab.buildButton().enabled());
        assertFalse(tab.pane().control("north").enabled());
        assertFalse(tab.pane().control("turnLeft").enabled());
        assertTrue(tab.pane().control("feet").enabled());
        clickBuild();
        assertFalse(tab.armed());
        assertTrue(facade.actions.isEmpty());
        clickControl("feet");
        assertEquals(List.of("schematics feet Small Castle.litematic"), facade.actions);
        assertEquals(PlacementMap.Mode.MAP, map().mode());
        assertTrue(tab.buildButton().enabled());
        assertTrue(tab.pane().control("north").enabled());
    }

    @Test
    public void theBuildBarFollowsTheBuildRequest() {
        facade.schematics.parsePolls = 2;
        facade.schematics.runPolls = 2;
        clickRow(0);
        clickBuild();
        clickBuild();
        assertEquals(List.of("schematics build Small Castle.litematic"), facade.actions);
        assertEquals("Reading the file…", tab.pane().warning());
        assertFalse(tab.buildButton().enabled());
        ticks(3);
        assertEquals("Building it. Stop is in the bar below.", tab.pane().warning());
        assertTrue(tab.buildButton().enabled());
        // the builder finished: back to what you are short of
        ticks(2);
        assertEquals("3,490 short. It pauses when it runs out.", tab.pane().warning());
    }

    @Test
    public void aBuildThatFailsAfterTheClickSaysWhy() {
        facade.schematics.buildFailsLater = "The file vanished while reading it";
        clickRow(0);
        clickBuild();
        clickBuild();
        ticks(2);
        assertEquals("The file vanished while reading it", tab.pane().warning());
        assertTrue(tab.buildButton().enabled());
        // another file's build request is not this file's news
        clickRow(1);
        assertFalse(tab.pane().warning().contains("vanished"));
    }

    @Test
    public void theMapOnlyReuploadsWhenTheTerrainStampMoves() {
        clickRow(0);
        var img = map().image();
        int v = img.version();
        ticks(10);
        assertSame(img, map().image());
        assertEquals(v, img.version());
        facade.schematics.terrainStamp++;
        ticks(1);
        assertEquals(v + 1, img.version());
        ticks(10);
        assertEquals(v + 1, img.version());
    }

    @Test
    public void terrainIsAskedOnceATickAndNeverFromAClick() {
        clickRow(0);
        int n = facade.schematics.terrainCalls;
        ticks(3);
        assertEquals(n + 3, facade.schematics.terrainCalls);
        clickControl("north");
        clickControl("turnRight");
        PlacementMap m = map();
        p.press(m.footprintCentreX(), m.footprintCentreY());
        p.drag(m.footprintCentreX() + 2 * m.pxPerBlock(), m.footprintCentreY());
        p.release(m.footprintCentreX() + 2 * m.pxPerBlock(), m.footprintCentreY());
        p.settle();
        p.settle();
        assertEquals(n + 3, facade.schematics.terrainCalls);
        // the last snapshot stays up between ticks, the band is never blank after a click
        assertNotNull(m.image());
        ticks(1);
        assertEquals(n + 4, facade.schematics.terrainCalls);
    }

    @Test
    public void everyControlStaysAtTheNarrowestPanel() throws Exception {
        // 58% of 830 is the 640 design px floor at the 0.75 scale a 540 window picks
        reopen(830, 540);
        clickRow(0);
        var pane = tab.pane();
        for (var b : pane.controls()) {
            assertTrue(b.visible());
            assertTrue(b.x() >= pane.x());
            assertTrue(b.x() + b.w() <= pane.x() + pane.w());
        }
        // three rows here: the turns and mirror under the arrows, move to me on its own with its word
        assertTrue(pane.control("turnLeft").y() > pane.control("north").y());
        assertTrue(pane.control("feet").y() > pane.control("turnLeft").y());
        assertEquals("Move to me", pane.control("feet").text());
        assertEquals("Mirror", pane.control("mirror").text());
        assertEquals(pane.control("north").y(), pane.control("down").y());
        assertEquals(pane.control("turnLeft").y(), pane.control("mirror").y());
        // and they still work
        clickControl("west");
        assertEquals(List.of("schematics move Small Castle.litematic -1 0 0"), facade.actions);
    }

    @Test
    public void aShortBandFitsTheFootprintAndDropsTheCoordinatesChip() throws Exception {
        reopen(960, 540);
        clickRow(0);
        PlacementMap m = map();
        assertTrue(m.visible());
        // 24 blocks at the default 2 px would spill a 56 design px band, so it starts at 1 px
        assertEquals(0, m.scaleIndex());
        assertTrue(24 * m.pxPerBlock() <= m.h());
        assertFalse(m.showsCoordsChip());
        // the wheel overrides the fit
        p.scroll(m.x() + m.w() / 2f, m.y() + m.h() / 2f, 1);
        assertEquals(1, m.scaleIndex());
        p.scroll(m.x() + m.w() / 2f, m.y() + m.h() / 2f, -1);
        assertEquals(0, m.scaleIndex());
        // a roomy window keeps 2 px a block and the chip
        reopen(1600, 900);
        clickRow(0);
        assertEquals(PlacementMap.DEFAULT_SCALE, map().scaleIndex());
        assertTrue(map().showsCoordsChip());
    }

    @Test
    public void controlsShowTheirNamesInTheTooltip() throws Exception {
        clickRow(0);
        var north = tab.pane().control("north");
        p.hover(north.x() + north.w() / 2f, north.y() + north.h() / 2f);
        ticks(9);
        assertNull(tab.tipText());
        ticks(1);
        assertEquals("North", tab.tipText());
        // both words fit at this width, so the worded buttons have nothing to add
        var mirror = tab.pane().control("mirror");
        assertEquals("Mirror", mirror.text());
        p.hover(mirror.x() + 5, mirror.y() + 5);
        ticks(10);
        assertNull(tab.tipText());
        // a pane just too narrow for both words (the panel only shrinks below about 1345 wide): mirror is a glyph
        // and its word is the tooltip, move to me kept its
        reopen(1280, 900);
        clickRow(0);
        mirror = tab.pane().control("mirror");
        assertEquals("", mirror.text());
        p.hover(mirror.x() + 5, mirror.y() + 5);
        ticks(10);
        assertEquals("Mirror", tab.tipText());
        var feet = tab.pane().control("feet");
        assertEquals("Move to me", feet.text());
        p.hover(feet.x() + 5, feet.y() + 5);
        ticks(10);
        assertNull(tab.tipText());
        // off the controls the tip goes
        p.hover(-100, -100);
        ticks(10);
        assertNull(tab.tipText());
    }

    @Test
    public void aWindowTooShortForTheControlsKeepsTheMapAndTheDrag() throws Exception {
        reopen(830, 540);
        clickRow(0);
        // three rows here leave the band 28 px and the list nothing, but the band and the drag are still there
        var pane = tab.pane();
        PlacementMap m = map();
        assertTrue(m.visible());
        assertTrue(m.h() > 0);
        assertTrue(24 * m.pxPerBlock() <= m.h());
        assertTrue(pane.control("north").visible());
        assertTrue(pane.list().h() >= 0);
        p.press(m.footprintCentreX(), m.footprintCentreY());
        assertTrue(m.dragging());
        p.release(m.footprintCentreX(), m.footprintCentreY());
    }

    @Test
    public void mapRules() {
        assertEquals("the Nether", SchematicRules.dimensionName("minecraft:the_nether"));
        assertEquals("the End", SchematicRules.dimensionName("the_end"));
        assertEquals("another dimension", SchematicRules.dimensionName(null));
        assertEquals("sky islands", SchematicRules.dimensionName("somemod:sky_islands"));
        assertEquals(0, PlacementMap.originCorner(0, false));
        assertEquals(1, PlacementMap.originCorner(0, true));
        assertEquals(3, PlacementMap.originCorner(3, false));
        assertEquals(0, PlacementMap.originCorner(3, true));
    }

    private void clickMove() {
        var b = tab.moveButton();
        p.click(b.x() + b.w() / 2f, b.y() + b.h() / 2f);
        p.settle();
    }

    @Test
    public void moveStartsMoveModeAndStopMovingEndsIt() {
        assertFalse(tab.moveButton().enabled());
        clickRow(0);
        assertTrue(tab.moveButton().enabled());
        assertEquals("Move", tab.moveButton().text());
        clickMove();
        assertEquals(List.of("schematics startMoving Small Castle.litematic"), facade.actions);
        assertEquals("Small Castle.litematic", facade.schematics.moving());
        // the real one swaps the screen out here. if the gui is back while it's still on, the button turns it off
        ticks(1);
        p.settle();
        assertEquals("Stop moving", tab.moveButton().text());
        clickMove();
        assertEquals(List.of("schematics startMoving Small Castle.litematic", "schematics stopMoving"), facade.actions);
        assertNull(facade.schematics.moving());
        assertEquals("Move", tab.moveButton().text());
    }

    @Test
    public void moveWaitsForAPlacementInThisDimension() {
        clickRow(0);
        facade.schematics.dimension = "minecraft:the_nether";
        ticks(1);
        p.settle();
        assertFalse(tab.moveButton().enabled());
        clickMove();
        assertTrue(facade.actions.isEmpty());
        assertNull(facade.schematics.moving());
    }

    @Test
    public void moveDisarmsTheBuildButton() {
        clickRow(0);
        clickBuild();
        assertTrue(tab.armed());
        clickMove();
        assertFalse(tab.armed());
        assertEquals("Small Castle.litematic", facade.schematics.moving());
    }

    // ----- the boxes: which rows go into the build

    private void clickBox(int i) {
        var mats = tab.pane().list();
        p.click(tab.pane().boxMidX(), mats.rowMidY(i));
        p.settle();
    }

    private MaterialRows.Entry mat(int i) {
        return tab.pane().list().items().get(i);
    }

    @Test
    public void everyItemStartsChecked() {
        clickRow(0);
        for (int i = 0; i < 7; i++) {
            assertEquals(MaterialRows.Type.ITEM, mat(i).type());
            assertTrue(mat(i).checked());
        }
        assertTrue(facade.schematics.excluded("Small Castle.litematic").isEmpty());
        assertEquals("Unchecked items are left out of the build. A block others rest on can leave them floating.", tab.pane().hint());
        assertTrue(tab.pane().hintShown());
        assertFalse(tab.pane().rows().disabled());
    }

    @Test
    public void aClickOnTheBoxClearsItAndTheRowDrawsUnchecked() {
        clickRow(0);
        // the farmland row is the sixth, past the first page once the map has the top of the card
        tab.pane().list().ensureVisible(5);
        p.settle();
        p.advance(600);
        p.settle();
        clickBox(5);
        assertEquals(List.of("schematics exclude Small Castle.litematic minecraft:farmland"), facade.actions);
        assertEquals(java.util.Set.of("minecraft:farmland"), facade.schematics.excluded("Small Castle.litematic"));
        // the poll after the click already shows it, no tick needed
        assertFalse(mat(5).checked());
        assertTrue(mat(4).checked());
        clickBox(5);
        assertTrue(mat(5).checked());
        assertEquals("schematics include Small Castle.litematic minecraft:farmland", facade.actions.get(1));
    }

    @Test
    public void anUncheckedRowLeavesTheShortfall() {
        clickRow(0);
        assertEquals("3,557 blocks · 7 kinds · 3,490 short", tab.pane().summary());
        // stone bricks are 3,120 of the 3,490 short
        clickBox(0);
        assertEquals("3,557 blocks · 7 kinds · 1 left out · 370 short", tab.pane().summary());
        assertEquals("370 short. It pauses when it runs out.", tab.pane().warning());
        // everything still in the build is in hand: the warning drops to the can't place line
        for (String id : List.of("minecraft:oak_log", "minecraft:glass", "minecraft:oak_stairs", "minecraft:torch", "minecraft:farmland", "minecraft:beacon")) {
            facade.schematics.have.put(id, 5000);
        }
        ticks(1);
        assertEquals("3,557 blocks · 7 kinds · 1 left out · rest in your inventory", tab.pane().summary());
        assertEquals("Some blocks can't be placed, those spots stay empty.", tab.pane().warning());
    }

    @Test
    public void aChangeMadeElsewhereShowsOnTheNextTick() {
        clickRow(0);
        facade.schematics.setIncluded("Small Castle.litematic", "minecraft:beacon", false);
        assertTrue(mat(6).checked());
        ticks(1);
        assertFalse(mat(6).checked());
        assertEquals("3,557 blocks · 7 kinds · 1 left out · 3,489 short", tab.pane().summary());
    }

    @Test
    public void subRowsHaveNoBoxAndStillFold() {
        clickRow(0);
        var mats = tab.pane().list();
        mats.ensureVisible(9);
        p.settle();
        p.advance(600);
        p.settle();
        p.click(tab.pane().boxMidX(), mats.rowMidY(7));
        p.settle();
        assertEquals(MaterialRows.Type.SUB, mat(8).type());
        assertFalse(mat(8).checked());
        // a click on a sub row's left edge is not a box
        p.click(tab.pane().boxMidX(), mats.rowMidY(8));
        p.settle();
        assertTrue(facade.actions.isEmpty());
        assertTrue(facade.schematics.excluded("Small Castle.litematic").isEmpty());
    }

    @Test
    public void theBoxesGreyWhileThisFileBuildsAndStillToggle() {
        facade.schematics.parsePolls = 1;
        clickRow(0);
        clickBuild();
        clickBuild();
        assertTrue(tab.pane().rows().disabled());
        assertEquals("Changes apply on the next start, not this build.", tab.pane().hint());
        ticks(2);
        assertEquals("Building it. Stop is in the bar below.", tab.pane().warning());
        assertTrue(tab.pane().rows().disabled());
        clickBox(1);
        assertFalse(mat(1).checked());
        assertEquals("schematics exclude Small Castle.litematic minecraft:oak_log", facade.actions.get(1));
        // another file's build is not this one's: its boxes are live
        clickRow(3);
        assertFalse(tab.pane().rows().disabled());
        assertEquals("Unchecked items are left out of the build. A block others rest on can leave them floating.", tab.pane().hint());
    }

    @Test
    public void anUnticksDisarmsTheBuildButton() {
        clickRow(0);
        clickBuild();
        assertTrue(tab.armed());
        clickBox(2);
        assertFalse(tab.armed());
    }

    @Test
    public void theCaptionGoesBeforeTheMapAtTheFloor() throws Exception {
        reopen(830, 540);
        clickRow(0);
        assertFalse(tab.pane().hintShown());
        assertTrue(24 * map().pxPerBlock() <= map().h());
        reopen(1600, 900);
        clickRow(0);
        assertTrue(tab.pane().hintShown());
        // the whole sentence wraps to two lines at this width, and the list starts under them
        List<String> lines = tab.pane().hintDrawn();
        assertEquals(2, lines.size());
        assertEquals("Unchecked items are left out of the build. A block others rest on can leave them floating.", String.join(" ", lines));
        assertEquals(tab.pane().list().y(), tab.pane().control("feet").y() + tab.pane().control("feet").h() + p.theme.px(4) + 1 + hintH(2), 0.01f);
        // a build in flight swaps in the one line sentence and the list moves up with it
        facade.schematics.parsePolls = 1000;
        facade.schematics.build("Small Castle.litematic");
        ticks(1);
        p.settle();
        assertEquals(List.of("Changes apply on the next start, not this build."), tab.pane().hintDrawn());
        assertEquals(tab.pane().list().y(), tab.pane().control("feet").y() + tab.pane().control("feet").h() + p.theme.px(4) + 1 + hintH(1), 0.01f);
    }

    private int hintH(int lines) {
        return lines * Math.round(p.theme.measurer.lineHeight(p.theme.small)) + 2 * p.theme.px(4);
    }

    @Test
    public void moveFitsBesideTheArmedBuildButtonAtTheFloor() throws Exception {
        reopen(830, 540);
        clickRow(0);
        clickBuild();
        var pane = tab.pane();
        var move = tab.moveButton();
        var build = tab.buildButton();
        assertTrue(move.visible());
        assertTrue(move.x() >= pane.x());
        assertTrue(move.x() + move.w() <= build.x());
        // too tight for both words: the glyph stays, the word goes to the tooltip
        assertEquals("", move.text());
        p.hover(move.x() + move.w() / 2f, move.y() + move.h() / 2f);
        ticks(11);
        assertEquals("Move", tab.tipText());
        // a roomy window keeps the word
        reopen(1600, 900);
        clickRow(0);
        clickBuild();
        assertEquals("Move", tab.moveButton().text());
    }
}
