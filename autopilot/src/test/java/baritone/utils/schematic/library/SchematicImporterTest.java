package baritone.utils.schematic.library;

import adris.altoclef.util.helpers.ItemFixture;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SchematicImporterTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Path library;
    private Path elsewhere;

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    @Before
    public void setUp() throws Exception {
        // the library folder doesn't exist yet on a fresh install, the importer has to make it
        library = folder.getRoot().toPath().resolve("game").resolve("schematics");
        elsewhere = folder.newFolder("downloads").toPath();
    }

    private Path file(String relative, String content) throws Exception {
        Path path = elsewhere.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }

    private SchematicImporter.Report run(Path... sources) {
        return new SchematicImporter(library).importAll(List.of(sources));
    }

    @Test
    public void copiesIntoAFolderThatDidNotExist() throws Exception {
        SchematicImporter.Report report = run(file("castle.litematic", "a"));
        assertEquals(List.of("castle.litematic"), report.imported());
        assertTrue(report.skipped().isEmpty());
        assertTrue(report.errors().isEmpty());
        assertEquals("a", Files.readString(library.resolve("castle.litematic")));
        // a copy, the download is still where it was
        assertTrue(Files.exists(elsewhere.resolve("castle.litematic")));
    }

    @Test
    public void clashesGetANumberAndKeepTheExtension() throws Exception {
        Path source = file("castle.litematic", "new");
        Files.createDirectories(library);
        Files.writeString(library.resolve("castle.litematic"), "old");
        Files.writeString(library.resolve("castle (2).litematic"), "old2");

        SchematicImporter.Report report = run(source);

        assertEquals(List.of("castle (3).litematic"), report.imported());
        assertEquals("old", Files.readString(library.resolve("castle.litematic")));
        assertEquals("old2", Files.readString(library.resolve("castle (2).litematic")));
        assertEquals("new", Files.readString(library.resolve("castle (3).litematic")));
    }

    @Test
    public void twoSourcesWithTheSameNameInOneBatchDontOverwriteEachOther() throws Exception {
        Path a = file("a/house.schem", "one");
        Path b = file("b/house.schem", "two");
        SchematicImporter.Report report = run(a, b);
        assertEquals(List.of("house.schem", "house (2).schem"), report.imported());
        assertEquals("one", Files.readString(library.resolve("house.schem")));
        assertEquals("two", Files.readString(library.resolve("house (2).schem")));
    }

    @Test
    public void wrongExtensionsAreSkippedNotCopied() throws Exception {
        SchematicImporter.Report report = run(file("notes.txt", "x"), file("map.png", "x"), file("ok.SCHEM", "x"));
        assertEquals(List.of("ok.SCHEM"), report.imported());
        assertEquals(2, report.skipped().size());
        assertTrue(report.skipped().get(0).startsWith("notes.txt: not a schematic"));
        assertFalse(Files.exists(library.resolve("notes.txt")));
    }

    @Test
    public void aMissingFileIsAnErrorAndTheRestStillImport() throws Exception {
        SchematicImporter.Report report = run(elsewhere.resolve("gone.litematic"), file("here.litematic", "x"));
        assertEquals(List.of("here.litematic"), report.imported());
        assertEquals(1, report.errors().size());
        assertTrue(report.errors().get(0).startsWith("gone.litematic"));
    }

    @Test
    public void filesAlreadyInTheLibraryAreLeftAlone() throws Exception {
        Files.createDirectories(library);
        Path inside = library.resolve("mine.litematic");
        Files.writeString(inside, "x");
        SchematicImporter.Report report = run(inside);
        assertTrue(report.imported().isEmpty());
        assertEquals(1, report.skipped().size());
        assertTrue(report.skipped().get(0).contains("already in the library"));
        assertFalse(Files.exists(library.resolve("mine (2).litematic")));
    }

    @Test
    public void aDroppedFolderKeepsItsShape() throws Exception {
        file("pack/readme.txt", "x");
        file("pack/castle.litematic", "x");
        file("pack/nested/deeper/tower.schematic", "x");
        SchematicImporter.Report report = run(elsewhere.resolve("pack"));
        assertEquals(List.of("pack/castle.litematic", "pack/nested/deeper/tower.schematic"), report.imported());
        assertTrue(Files.exists(library.resolve("pack").resolve("nested").resolve("deeper").resolve("tower.schematic")));
        assertTrue(report.errors().isEmpty());
        // the readme in the folder is not worth a complaint
        assertTrue(report.skipped().isEmpty());
    }

    @Test
    public void aDroppedFolderIsOnlyWalkedSoDeep() throws Exception {
        file("deep/ok.schem", "x");
        file("deep/1/2/3/4/5/6/7/8/9/too-far.schem", "x");
        SchematicImporter.Report report = run(elsewhere.resolve("deep"));
        assertEquals(List.of("deep/ok.schem"), report.imported());
    }

    @Test
    public void aFolderWithNothingInItSaysSo() throws Exception {
        file("empty/readme.txt", "x");
        SchematicImporter.Report report = run(elsewhere.resolve("empty"));
        assertTrue(report.imported().isEmpty());
        assertEquals(1, report.skipped().size());
        assertTrue(report.skipped().get(0).contains("no schematics"));
    }

    @Test
    public void nullAndEmptyInputsDoNothing() {
        assertTrue(new SchematicImporter(library).importAll(null).nothingHappened());
        assertTrue(new SchematicImporter(library).importAll(List.of()).nothingHappened());
        assertFalse(Files.exists(library));
    }

    @Test
    public void extensionsListTheRealFormats() {
        assertTrue(SchematicImporter.extensions().containsAll(List.of("litematic", "schem", "schematic")));
    }
}
