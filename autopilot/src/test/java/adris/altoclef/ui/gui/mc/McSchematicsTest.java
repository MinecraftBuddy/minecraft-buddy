package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.facade.SchematicsAccess.ImportReport;
import adris.altoclef.ui.gui.facade.SchematicsAccess.Kind;
import adris.altoclef.ui.gui.facade.SchematicsAccess.Line;
import adris.altoclef.ui.gui.facade.SchematicsAccess.Listing;
import adris.altoclef.ui.gui.facade.SchematicsAccess.Materials;
import adris.altoclef.ui.gui.facade.SchematicsAccess.State;
import adris.altoclef.util.helpers.ItemFixture;
import baritone.utils.schematic.library.SchematicFixtures;
import baritone.utils.schematic.library.SchematicLibrary;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

// the real thing minus the game: a temp folder with real (tiny) schematic files, an executor the test steps by hand
// and a pretend inventory
public class McSchematicsTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Path dir;
    private final Queue<Runnable> queue = new ArrayDeque<>();
    private final Map<String, Integer> inventory = new HashMap<>();
    private FilePicker.Choice choice = FilePicker.Choice.cancelled();
    private final FakeGame game = new FakeGame();

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    @Before
    public void setUp() throws Exception {
        dir = folder.newFolder("schematics").toPath();
    }

    private McSchematics manual() {
        return new McSchematics(new SchematicLibrary(dir.toFile()), queue::add, () -> new HashMap<>(inventory),
            id -> id.equals("minecraft:stone"), Runnable::run, (ext, title) -> choice, game, System::currentTimeMillis);
    }

    private McSchematics direct() {
        return new McSchematics(new SchematicLibrary(dir.toFile()), Runnable::run, () -> new HashMap<>(inventory),
            id -> id.equals("minecraft:stone"), Runnable::run, (ext, title) -> choice, game, System::currentTimeMillis);
    }

    private void drain() {
        Runnable next;
        while ((next = queue.poll()) != null) {
            next.run();
        }
    }

    // 2x1x2: three stone, one block this version has never heard of (it turns into air)
    private void stoneBuild(String relative) throws Exception {
        SchematicFixtures.writeSponge(dir.resolve(relative), 2, 1, 2,
            new String[]{"minecraft:air", "minecraft:stone", "minecraft:gone_in_a_later_version"}, new int[]{1, 1, 1, 2});
    }

    @Test
    public void firstListStartsTheScanAndStaysLoadingUntilItFinishes() throws Exception {
        stoneBuild("a.schem");
        McSchematics s = manual();
        Listing first = s.list();
        assertEquals(State.LOADING, first.state());
        assertTrue(first.rows().isEmpty());
        // asking again doesn't queue a second scan
        s.list();
        assertEquals(1, queue.size());
        drain();
        Listing done = s.list();
        assertEquals(State.READY, done.state());
        assertEquals(1, done.rows().size());
        assertEquals("a", done.rows().get(0).name());
        assertEquals("a.schem", done.rows().get(0).relativePath());
        assertEquals("2 x 1 x 2", done.rows().get(0).sizeText());
        assertEquals(3, done.rows().get(0).blockCount());
        assertNotNull(done.rows().get(0).unknownSummary());
    }

    @Test
    public void refreshKeepsTheOldRowsWhileItRescans() throws Exception {
        stoneBuild("a.schem");
        McSchematics s = manual();
        s.list();
        drain();
        stoneBuild("b.schem");
        s.refresh();
        Listing during = s.list();
        assertEquals(State.LOADING, during.state());
        assertEquals(1, during.rows().size());
        drain();
        assertEquals(2, s.list().rows().size());
        assertEquals(State.READY, s.list().state());
    }

    @Test
    public void aRefreshDuringAScanRunsOneMoreScan() throws Exception {
        stoneBuild("a.schem");
        McSchematics s = manual();
        s.list();
        s.refresh();
        s.refresh();
        // one queued scan, the other two asks collapsed into "go around again"
        assertEquals(1, queue.size());
        stoneBuild("late.schem");
        drain();
        assertEquals(2, s.list().rows().size());
        assertEquals(State.READY, s.list().state());
    }

    @Test
    public void aFolderThatDoesNotExistIsAnEmptyReadyList() {
        McSchematics s = new McSchematics(new SchematicLibrary(dir.resolve("nope").toFile()), Runnable::run, HashMap::new,
            id -> false, Runnable::run, (ext, title) -> choice, game, System::currentTimeMillis);
        Listing listing = s.list();
        assertEquals(State.READY, listing.state());
        assertTrue(listing.rows().isEmpty());
    }

    @Test
    public void materialsCountTheFileAndTheInventory() throws Exception {
        stoneBuild("a.schem");
        McSchematics s = direct();
        inventory.put("minecraft:stone", 2);
        Materials m = s.materials("a.schem");
        assertEquals(State.READY, m.state());
        assertNull(m.error());
        assertEquals(1, m.items().size());
        Line stone = m.items().get(0);
        assertEquals("minecraft:stone", stone.itemId());
        assertEquals("minecraft:stone", stone.iconItemId());
        assertEquals(3, stone.needed());
        assertEquals(2, stone.have());
        assertEquals(1, stone.missing());
        assertEquals(Kind.ITEM, stone.kind());
        assertTrue(stone.gettable());
        assertFalse(stone.displayName().isEmpty());
        assertFalse(m.allCovered());
        assertFalse(m.notes().isEmpty());
    }

    @Test
    public void haveFollowsTheInventoryButTheSameInventoryIsNotRecomputed() throws Exception {
        stoneBuild("a.schem");
        McSchematics s = direct();
        Materials one = s.materials("a.schem");
        assertEquals(0, one.items().get(0).have());
        assertSame(one, s.materials("a.schem"));
        inventory.put("minecraft:stone", 64);
        Materials two = s.materials("a.schem");
        assertEquals(64, two.items().get(0).have());
        assertTrue(two.allCovered());
    }

    @Test
    public void materialsAreLoadingUntilTheBackgroundCountFinishes() throws Exception {
        stoneBuild("a.schem");
        McSchematics s = manual();
        assertEquals(State.LOADING, s.materials("a.schem").state());
        drain();
        // the scan ran, the count was queued behind it by the next ask
        assertEquals(State.LOADING, s.materials("a.schem").state());
        drain();
        Materials m = s.materials("a.schem");
        assertEquals(State.READY, m.state());
        assertEquals(3, m.items().get(0).needed());
    }

    @Test
    public void aChangedFileIsCountedAgain() throws Exception {
        stoneBuild("a.schem");
        McSchematics s = direct();
        assertEquals(3, s.materials("a.schem").items().get(0).needed());
        SchematicFixtures.writeSponge(dir.resolve("a.schem"), 2, 1, 2,
            new String[]{"minecraft:air", "minecraft:stone"}, new int[]{1, 1, 1, 1});
        // bump the mtime, a rewrite inside the same millisecond-ish window would look unchanged to the cache
        Files.setLastModifiedTime(dir.resolve("a.schem"), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));
        s.refresh();
        assertEquals(4, s.materials("a.schem").items().get(0).needed());
    }

    @Test
    public void unknownAndBrokenFilesAnswerWithAnError() throws Exception {
        Files.writeString(dir.resolve("broken.schem"), "this is not nbt");
        McSchematics s = direct();
        Materials nope = s.materials("nope.schem");
        assertEquals(State.READY, nope.state());
        assertNotNull(nope.error());
        Materials broken = s.materials("broken.schem");
        assertEquals(State.READY, broken.state());
        assertNotNull(broken.error());
        assertTrue(broken.items().isEmpty());
        assertFalse(s.list().rows().get(0).ok());
    }

    @Test
    public void droppedFilesLandInTheFolderAndTheListRefreshes() throws Exception {
        McSchematics s = direct();
        assertTrue(s.list().rows().isEmpty());
        Path outside = folder.newFolder("downloads").toPath();
        Path source = outside.resolve("cabin.schem");
        SchematicFixtures.writeSponge(source, 1, 1, 1, new String[]{"minecraft:air", "minecraft:stone"}, new int[]{1});
        Files.writeString(outside.resolve("readme.txt"), "x");

        ImportReport report = s.importDropped(List.of(source, outside.resolve("readme.txt")));

        assertEquals(List.of("cabin.schem"), report.imported());
        assertEquals(1, report.skipped().size());
        assertTrue(Files.exists(dir.resolve("cabin.schem")));
        assertEquals(1, s.list().rows().size());
        // dropping the same file again doesn't overwrite, it numbers
        assertEquals(List.of("cabin (2).schem"), s.importDropped(List.of(source)).imported());
        assertEquals(2, s.list().rows().size());
    }

    @Test
    public void pickerResultsComeBackThroughTheCallback() throws Exception {
        McSchematics s = direct();
        Path outside = folder.newFolder("picked").toPath();
        Path source = outside.resolve("barn.schem");
        SchematicFixtures.writeSponge(source, 1, 1, 1, new String[]{"minecraft:air", "minecraft:stone"}, new int[]{1});

        choice = new FilePicker.Choice(List.of(source), null);
        assertEquals(List.of("barn.schem"), pick(s).imported());

        choice = FilePicker.Choice.cancelled();
        assertTrue(pick(s).cancelled());

        choice = FilePicker.Choice.failed("No file picker found");
        ImportReport failed = pick(s);
        assertFalse(failed.toResult().ok());
        assertEquals("No file picker found", failed.errors().get(0));
    }

    @Test
    public void aSecondPickWhileOneIsOpenIsRefused() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        McSchematics s = new McSchematics(new SchematicLibrary(dir.toFile()), Runnable::run, HashMap::new, id -> false,
            Runnable::run, (ext, title) -> {
                inside.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return FilePicker.Choice.cancelled();
            }, game, System::currentTimeMillis);
        AtomicReference<ImportReport> first = new AtomicReference<>();
        CountDownLatch firstDone = new CountDownLatch(1);
        s.importFiles(r -> {
            first.set(r);
            firstDone.countDown();
        });
        assertTrue(inside.await(5, TimeUnit.SECONDS));
        ImportReport second = pick(s);
        assertFalse(second.toResult().ok());
        assertTrue(second.errors().get(0).contains("already open"));
        release.countDown();
        assertTrue(firstDone.await(5, TimeUnit.SECONDS));
        assertTrue(first.get().cancelled());
        // and it's free again afterwards
        assertTrue(pick(s).cancelled());
    }

    private static ImportReport pick(McSchematics s) throws InterruptedException {
        AtomicReference<ImportReport> got = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        s.importFiles(r -> {
            got.set(r);
            latch.countDown();
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        return got.get();
    }

    @Test
    public void folderPathIsTheLibraryFolder() {
        assertEquals(dir.toAbsolutePath().toString(), direct().folderPath());
    }

    @Test
    public void pickerWindowsScriptQuotesTheTitleAndListsTheExtensions() {
        String script = FilePicker.windowsScript(List.of("litematic", "schem"), "Bob's schematics");
        assertTrue(script.contains("'Bob''s schematics'"));
        assertTrue(script.contains("*.litematic;*.schem"));
        assertTrue(script.contains("Multiselect = $true"));
    }

    @Test
    public void pickerOutputParsesOnePathPerLine() {
        FilePicker.Choice c = FilePicker.parse(new ArrayList<>(List.of("C:\\a\\one.schem", "", "  C:\\b\\two.litematic  ")));
        assertEquals(2, c.files().size());
        assertTrue(FilePicker.parse(List.of("", " ")).wasCancelled());
        // powershell's byte order mark on the first line, and a line that isn't a path at all
        FilePicker.Choice bom = FilePicker.parse(List.of("﻿C:\\a\\one.schem", "bad\0line"));
        assertEquals(1, bom.files().size());
        // a backslash is only a separator on windows, ci runs on linux, so check the text and not getFileName
        String first = bom.files().get(0).toString();
        assertTrue(first.endsWith("one.schem"));
        assertTrue(first.startsWith("C:"));
    }
}
