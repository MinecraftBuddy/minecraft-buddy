package baritone.utils.schematic.library;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.schematic.IStaticSchematic;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class SchematicLibraryTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Path dir;
    private SchematicLibrary library;

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    @Before
    public void setUp() throws Exception {
        dir = folder.newFolder("schematics").toPath();
        library = new SchematicLibrary(dir.toFile());
    }

    // 2x2x1: three stone and one bogus block, so the count is 3 non-air plus an unknown that became air
    private static final String[] PALETTE = {"minecraft:air", "minecraft:stone", "minecraft:gone_in_a_later_version"};

    private Path sponge(String relative) throws Exception {
        return SchematicFixtures.write(dir.resolve(relative),
            SchematicFixtures.spongeV3(2, 1, 2, PALETTE, new int[]{1, 1, 1, 2}));
    }

    @Test
    public void missingFolderIsJustEmpty() {
        assertTrue(new SchematicLibrary(new File(dir.toFile(), "nope")).list().isEmpty());
    }

    @Test
    public void forGameDirLooksInSchematics() {
        assertEquals(new File(new File("somewhere"), "schematics").getAbsoluteFile(), SchematicLibrary.forGameDir(new File("somewhere")).directory());
    }

    @Test
    public void listsEveryFormatRecursivelyAndIgnoresOtherFiles() throws Exception {
        sponge("a.schem");
        SchematicFixtures.write(dir.resolve("nested/deeper/b.litematic"), SchematicFixtures.litematic(7, 2, 1, 2, PALETTE, new int[]{1, 1, 1, 2}));
        SchematicFixtures.write(dir.resolve("c.schematic"), SchematicFixtures.mcedit(2, 1, 2, new int[]{1, 1, 1, 4000}));
        Files.writeString(dir.resolve("notes.txt"), "not a schematic");
        Files.writeString(dir.resolve("nested/readme.md"), "nope");

        List<SchematicInfo> list = library.list();
        assertEquals(List.of("a.schem", "c.schematic", "nested/deeper/b.litematic"),
            list.stream().map(SchematicInfo::relativePath).toList());
        assertEquals(List.of("a", "c", "b"), list.stream().map(SchematicInfo::name).toList());
        assertEquals(List.of("Sponge", "MCEdit", "Litematica"), list.stream().map(SchematicInfo::format).toList());
        assertEquals(List.of(3, 0, 7), list.stream().map(SchematicInfo::version).toList());
        for (SchematicInfo info : list) {
            assertTrue(info.error(), info.ok());
            assertEquals(2, info.sizeX());
            assertEquals(1, info.sizeY());
            assertEquals(2, info.sizeZ());
            assertEquals(3, info.blockCount());
            assertTrue(info.fileSize() > 0);
        }
    }

    @Test
    public void unknownBlocksComeWithCounts() throws Exception {
        sponge("a.schem");
        SchematicInfo info = library.list().get(0);
        assertEquals(1, info.unknownBlocks().size());
        assertEquals("minecraft:gone_in_a_later_version", info.unknownBlocks().get(0).id());
        assertEquals(1, info.unknownBlocks().get(0).count());
        assertEquals("1 block unrecognized: minecraft:gone_in_a_later_version (1)", info.unknownSummary());
    }

    @Test
    public void unknownSummaryNamesTheBiggestFirst() throws Exception {
        SchematicFixtures.write(dir.resolve("m.schem"), SchematicFixtures.spongeV2(4, 1, 1,
            new String[]{"minecraft:stone", "minecraft:a_few", "minecraft:lots"}, new int[]{1, 2, 2, 0}));
        SchematicInfo info = library.list().get(0);
        assertEquals("3 blocks unrecognized: minecraft:lots (2), minecraft:a_few (1)", info.unknownSummary());
        assertEquals(3, info.unknownTotal());
    }

    @Test
    public void cleanFilesHaveNoUnknownSummary() throws Exception {
        SchematicFixtures.write(dir.resolve("ok.schem"), SchematicFixtures.spongeV2(1, 1, 1, new String[]{"minecraft:stone"}, new int[]{0}));
        SchematicInfo info = library.list().get(0);
        assertTrue(info.unknownBlocks().isEmpty());
        assertNull(info.unknownSummary());
    }

    @Test
    public void brokenAndUnsupportedFilesAreListedWithAnError() throws Exception {
        Files.write(dir.resolve("junk.schem"), new byte[]{1, 2, 3, 4});
        SchematicFixtures.write(dir.resolve("old.litematic"), SchematicFixtures.litematic(4, 2, 1, 2, PALETTE, new int[]{1, 1, 1, 2}));
        Files.write(dir.resolve("empty.schematic"), new byte[0]);

        List<SchematicInfo> list = library.list();
        assertEquals(3, list.size());
        for (SchematicInfo info : list) {
            assertFalse(info.relativePath(), info.ok());
            assertNotNull(info.error());
            assertEquals(0, info.blockCount());
            assertEquals(0, info.sizeX());
        }
        SchematicInfo old = list.stream().filter(i -> i.name().equals("old")).findFirst().orElseThrow();
        assertTrue(old.error(), old.error().contains("1.13"));
        assertEquals("Litematica", old.format());
    }

    @Test
    public void sameFileIsNotParsedTwice() throws Exception {
        sponge("a.schem");
        SchematicInfo first = library.list().get(0);
        SchematicInfo second = library.list().get(0);
        assertSame(first, second);
    }

    @Test
    public void changedFileIsParsedAgain() throws Exception {
        Path file = sponge("a.schem");
        SchematicInfo first = library.list().get(0);
        // different size, and force the mtime to move too since a fast test can land in the same millisecond
        SchematicFixtures.write(file, SchematicFixtures.spongeV3(3, 1, 1, PALETTE, new int[]{1, 1, 1}));
        file.toFile().setLastModified(first.lastModified() + 5000);
        SchematicInfo second = library.list().get(0);
        assertFalse(first == second);
        assertEquals(3, second.sizeX());
        assertEquals(3, second.blockCount());
        assertTrue(second.unknownBlocks().isEmpty());
    }

    @Test
    public void sameSizeRewriteWithNewMtimeIsParsedAgain() throws Exception {
        Path file = sponge("a.schem");
        SchematicInfo first = library.list().get(0);
        file.toFile().setLastModified(first.lastModified() + 5000);
        assertFalse(first == library.list().get(0));
    }

    @Test
    public void deletedFilesDropOut() throws Exception {
        Path file = sponge("a.schem");
        sponge("b.schem");
        assertEquals(2, library.list().size());
        Files.delete(file);
        assertEquals(List.of("b.schem"), library.list().stream().map(SchematicInfo::relativePath).toList());
    }

    @Test
    public void loadHandsBackTheCachedSchematicAndReparsesWhenItIsGone() throws Exception {
        sponge("a.schem");
        SchematicInfo info = library.list().get(0);
        IStaticSchematic loaded = library.load(info);
        assertEquals(2, loaded.widthX());
        assertSame(loaded, library.load(info));
    }

    @Test
    public void loadParsesFilesTheListHasNotSeen() throws Exception {
        Path file = sponge("a.schem");
        SchematicInfo info = library.list().get(0);
        library.clearCache();
        IStaticSchematic loaded = library.load(info);
        assertEquals(2, loaded.lengthZ());
        assertTrue(Files.exists(file));
    }

    @Test
    public void multiRegionLitematicWithAGapStillCounts() throws Exception {
        net.minecraft.nbt.CompoundTag root = new net.minecraft.nbt.CompoundTag();
        root.putInt("Version", 7);
        net.minecraft.nbt.CompoundTag regions = new net.minecraft.nbt.CompoundTag();
        regions.put("a", SchematicFixtures.litematicRegion(0, 0, 0, 1, 1, 1, PALETTE, new int[]{1}));
        regions.put("b", SchematicFixtures.litematicRegion(4, 0, 0, 1, 1, 1, PALETTE, new int[]{1}));
        root.put("Regions", regions);
        SchematicFixtures.write(dir.resolve("gap.litematic"), root);
        SchematicInfo info = library.list().get(0);
        assertTrue(info.error(), info.ok());
        assertEquals(5, info.sizeX());
        assertEquals(2, info.blockCount());
    }
}
