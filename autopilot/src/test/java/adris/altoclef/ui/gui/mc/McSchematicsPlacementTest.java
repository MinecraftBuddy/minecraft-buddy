package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.facade.Result;
import adris.altoclef.ui.gui.facade.SchematicsAccess.BuildState;
import adris.altoclef.ui.gui.facade.SchematicsAccess.Building;
import adris.altoclef.ui.gui.facade.SchematicsAccess.Footprint;
import adris.altoclef.ui.gui.facade.SchematicsAccess.Placement;
import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.schematic.ISchematic;
import baritone.utils.schematic.library.PlacementStore;
import baritone.utils.schematic.library.SchematicFixtures;
import baritone.utils.schematic.library.SchematicLibrary;
import baritone.utils.schematic.library.SchematicPlacement;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// placements, the async build and the map on the real McSchematics, with a FakeGame for the world and a queue for the
// background thread that the test steps by hand
public class McSchematicsPlacementTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Path dir;
    private Path saveFile;
    private final Queue<Runnable> queue = new ArrayDeque<>();
    private final FakeGame game = new FakeGame();
    private long now = 1_000_000;

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    @Before
    public void setUp() throws Exception {
        dir = folder.newFolder("schematics").toPath();
        saveFile = folder.getRoot().toPath().resolve("world").resolve("altoclef").resolve(PlacementStore.FILE_NAME);
        game.file = saveFile;
        // 5 wide, 1 tall, 2 long, all stone
        SchematicFixtures.writeSponge(dir.resolve("wall.schem"), 5, 1, 2, new String[]{"minecraft:stone"}, new int[10]);
        Files.writeString(dir.resolve("broken.schem"), "not nbt");
    }

    private McSchematics make() {
        McSchematics s = new McSchematics(new SchematicLibrary(dir.toFile()), queue::add, HashMap::new, id -> false,
            Runnable::run, (ext, title) -> FilePicker.Choice.cancelled(), game, () -> now);
        s.list();
        drain();
        return s;
    }

    private void drain() {
        Runnable next;
        while ((next = queue.poll()) != null) {
            next.run();
        }
    }

    @Test
    public void theFirstAskIsAtTheFeetAndIsNotSaved() {
        McSchematics s = make();
        Placement p = s.placement("wall.schem");
        assertEquals(new Placement(10, 64, -20, 0, false, "minecraft:overworld", true), p);
        // walking away doesn't drag it along, it was made once
        game.feet = new BlockPos(500, 70, 500);
        assertEquals(10, s.placement("wall.schem").x());
        assertFalse(Files.exists(saveFile));
    }

    @Test
    public void nothingWithoutAWorldOrForARowThatDidNotParse() {
        McSchematics s = make();
        assertNull(s.placement("broken.schem"));
        assertNull(s.placement("nope.schem"));
        assertNull(s.footprint("broken.schem"));
        game.leaveWorld();
        assertNull(s.placement("wall.schem"));
        assertNull(s.move("wall.schem", 1, 0, 0));
        assertNull(s.footprint("wall.schem"));
        assertNull(s.terrain(0, 0, 8));
        assertFalse(s.build("wall.schem").ok());
    }

    @Test
    public void editsAreSavedAndComeBackInANewSession() {
        McSchematics s = make();
        s.move("wall.schem", 3, -1, 2);
        s.rotate("wall.schem", true);
        Placement p = s.mirror("wall.schem");
        assertTrue(Files.exists(saveFile));
        // 5 x 2 turned is 2 x 5: the corner moves (5-2)/2 = 1 east and (2-5)/2 = -1 north to keep the middle
        assertEquals(new Placement(14, 63, -19, 3, true, "minecraft:overworld", true), p);
        McSchematics again = make();
        assertEquals(p, again.placement("wall.schem"));
    }

    @Test
    public void theFootprintIsTheTurnedSize() {
        McSchematics s = make();
        Footprint f = s.footprint("wall.schem");
        assertEquals(new Footprint(10, 64, -20, 5, 1, 2), f);
        s.rotate("wall.schem", false);
        Footprint turned = s.footprint("wall.schem");
        assertEquals(2, turned.sizeX());
        assertEquals(5, turned.sizeZ());
        assertEquals(turned.x() + 1, turned.maxX());
    }

    @Test
    public void placeAtFeetKeepsTheTurnAndTakesTheDimension() {
        McSchematics s = make();
        s.rotate("wall.schem", true);
        game.dimension = "minecraft:the_nether";
        game.feet = new BlockPos(-3, 40, 8);
        Placement p = s.placeAtFeet("wall.schem");
        assertEquals(new Placement(-3, 40, 8, 1, false, "minecraft:the_nether", true), p);
    }

    @Test
    public void aChosenPlacementStaysInItsDimensionAndADefaultFollowsYou() {
        McSchematics s = make();
        s.move("wall.schem", 1, 0, 0);
        game.dimension = "minecraft:the_nether";
        Placement chosen = s.placement("wall.schem");
        assertFalse(chosen.inThisDimension());
        assertEquals("minecraft:overworld", chosen.dimension());
        Result r = s.build("wall.schem");
        assertFalse(r.ok());
        assertTrue(r.message().contains("another dimension"));
        assertTrue(game.built.isEmpty());
    }

    @Test
    public void anUnsavedDefaultFollowsThePlayerThroughAPortal() throws Exception {
        SchematicFixtures.writeSponge(dir.resolve("hut.schem"), 2, 2, 2, new String[]{"minecraft:stone"}, new int[8]);
        McSchematics s = make();
        assertEquals("minecraft:overworld", s.placement("hut.schem").dimension());
        game.dimension = "minecraft:the_nether";
        game.feet = new BlockPos(1, 2, 3);
        Placement p = s.placement("hut.schem");
        assertEquals("minecraft:the_nether", p.dimension());
        assertTrue(p.inThisDimension());
        assertEquals(1, p.x());
    }

    @Test
    public void aDifferentWorldHasItsOwnPlacements() {
        McSchematics s = make();
        s.move("wall.schem", 50, 0, 0);
        game.file = folder.getRoot().toPath().resolve("other").resolve("altoclef").resolve(PlacementStore.FILE_NAME);
        assertEquals(10, s.placement("wall.schem").x());
        game.file = saveFile;
        assertEquals(60, s.placement("wall.schem").x());
    }

    @Test
    public void placementsForDeletedFilesArePrunedOnLoad() throws Exception {
        PlacementStore.save(saveFile, Map.of(
            "wall.schem", SchematicPlacement.at(new BlockPos(7, 7, 7), "minecraft:overworld"),
            "gone.schem", SchematicPlacement.at(new BlockPos(1, 1, 1), "minecraft:overworld")));
        McSchematics s = make();
        assertEquals(7, s.placement("wall.schem").x());
        // the next save only has what's still there
        s.move("wall.schem", 0, 1, 0);
        assertEquals(List.of("wall.schem"), List.copyOf(PlacementStore.load(saveFile, p -> true).keySet()));
    }

    @Test
    public void buildReadsInTheBackgroundThenStartsOnTheGameThread() {
        McSchematics s = make();
        s.rotate("wall.schem", true);
        s.mirror("wall.schem");
        Placement p = s.placement("wall.schem");
        assertEquals(Building.IDLE, s.building());
        Result r = s.build("wall.schem");
        assertTrue(r.ok());
        assertNull(r.command());
        assertEquals(BuildState.PARSING, s.building().state());
        assertTrue(game.built.isEmpty());
        drain();
        assertEquals(BuildState.STARTED, s.building().state());
        assertEquals("wall.schem", s.building().relativePath());
        assertEquals(1, game.built.size());
        FakeGame.Built b = game.built.get(0);
        assertEquals(new BlockPos(p.x(), p.y(), p.z()), b.origin());
        assertEquals(SchematicPlacement.rotationOf(p.turns()), b.rotation());
        assertEquals(Mirror.FRONT_BACK, b.mirror());
        assertEquals(5, b.schematic().widthX());
    }

    @Test
    public void uncheckedRowsAreSavedAndReachTheBuild() {
        McSchematics s = make();
        assertTrue(s.excluded("wall.schem").isEmpty());
        s.setIncluded("wall.schem", "minecraft:stone", false);
        assertEquals(Set.of("minecraft:stone"), s.excluded("wall.schem"));
        // saved straight away, without the placement ever being chosen
        assertTrue(Files.exists(saveFile));
        assertTrue(PlacementStore.loadAll(saveFile, p -> true).placements().isEmpty());
        McSchematics again = make();
        assertEquals(Set.of("minecraft:stone"), again.excluded("wall.schem"));
        again.build("wall.schem");
        drain();
        assertEquals(Set.of("minecraft:stone"), game.built.get(0).memory().excluded());
        // building pins the placement, and that save keeps the choices
        assertEquals(Set.of("minecraft:stone"), PlacementStore.loadAll(saveFile, p -> true).choices().get("wall.schem").excluded());
        again.setIncluded("wall.schem", "minecraft:stone", true);
        assertTrue(again.excluded("wall.schem").isEmpty());
        assertTrue(PlacementStore.loadAll(saveFile, p -> true).choices().isEmpty());
    }

    @Test
    public void theNetherAnswerIsKeptUntilTheBuildMovesOrFinishes() {
        McSchematics s = make();
        s.build("wall.schem");
        drain();
        var memory = game.built.get(0).memory();
        assertNull(memory.nether());
        memory.netherAnswered(false, Set.of("minecraft:brewing_stand"));
        // the left out ones show unticked in the tab, and both survive a new session
        assertEquals(Set.of("minecraft:brewing_stand"), s.excluded("wall.schem"));
        McSchematics again = make();
        again.build("wall.schem");
        drain();
        var resumed = game.built.get(1).memory();
        assertEquals(Boolean.FALSE, resumed.nether());
        resumed.netherAnswered(true, Set.of());
        assertEquals(Boolean.TRUE, resumed.nether());
        // done: the next build asks again, the unticked rows stay
        resumed.finished();
        assertNull(resumed.nether());
        assertEquals(Set.of("minecraft:brewing_stand"), again.excluded("wall.schem"));
        // a moved build is a new build too
        resumed.netherAnswered(true, Set.of());
        again.move("wall.schem", 1, 0, 0);
        assertNull(resumed.nether());
        assertEquals(Set.of("minecraft:brewing_stand"), again.excluded("wall.schem"));
    }

    @Test
    public void aStaleBuildDoesNotWriteIntoAnotherWorld() {
        McSchematics s = make();
        s.build("wall.schem");
        drain();
        var memory = game.built.get(0).memory();
        game.file = folder.getRoot().toPath().resolve("other").resolve("altoclef").resolve(PlacementStore.FILE_NAME);
        memory.netherAnswered(false, Set.of("minecraft:brewing_stand"));
        assertTrue(s.excluded("wall.schem").isEmpty());
        game.file = saveFile;
        assertTrue(s.excluded("wall.schem").isEmpty());
    }

    @Test
    public void aTickWhileTheFileIsReadWaitsForTheNextStart() {
        McSchematics s = make();
        s.setIncluded("wall.schem", "minecraft:stone", false);
        s.build("wall.schem");
        // the tab greys the boxes from the click on, so this one is for the next build
        s.setIncluded("wall.schem", "minecraft:stone", true);
        drain();
        assertEquals(Set.of("minecraft:stone"), game.built.get(0).memory().excluded());
        assertTrue(s.excluded("wall.schem").isEmpty());
    }

    @Test
    public void choicesBelongToTheirWorld() {
        McSchematics s = make();
        s.setIncluded("wall.schem", "minecraft:stone", false);
        game.file = folder.getRoot().toPath().resolve("other").resolve("altoclef").resolve(PlacementStore.FILE_NAME);
        assertTrue(s.excluded("wall.schem").isEmpty());
        // an unknown file or no world is quietly nothing
        s.setIncluded("nope.schem", "minecraft:stone", false);
        assertTrue(s.excluded("nope.schem").isEmpty());
        game.file = saveFile;
        assertEquals(Set.of("minecraft:stone"), s.excluded("wall.schem"));
    }

    @Test
    public void startedGoesBackToIdleOnceTheBuilderIsNotOnOurBuild() {
        McSchematics s = make();
        s.build("wall.schem");
        drain();
        assertEquals(BuildState.STARTED, s.building().state());
        assertEquals(BuildState.STARTED, s.building().state());

        // the builder finished (or got cancelled)
        game.running = 0;
        assertEquals(Building.IDLE, s.building());
        // and it stays idle even if something else starts later
        game.running = 5;
        assertEquals(Building.IDLE, s.building());

        s.build("wall.schem");
        assertEquals(BuildState.PARSING, s.building().state());
        drain();
        assertEquals(BuildState.STARTED, s.building().state());
        assertEquals(2, game.running);
        assertEquals(BuildState.STARTED, s.building().state());

        // a chat #build took the builder over
        game.running = 99;
        assertEquals(Building.IDLE, s.building());
    }

    @Test
    public void anErrorStaysPutWhateverTheBuilderDoes() throws Exception {
        McSchematics s = make();
        s.placement("wall.schem");
        s.build("wall.schem");
        Files.delete(dir.resolve("wall.schem"));
        drain();
        game.running = 0;
        assertEquals(BuildState.ERROR, s.building().state());
    }

    @Test
    public void theClickIsTheSnapshot() {
        McSchematics s = make();
        s.build("wall.schem");
        // moving it while the file is still being read doesn't move the build
        s.move("wall.schem", 100, 0, 0);
        drain();
        assertEquals(new BlockPos(10, 64, -20), game.built.get(0).origin());
        assertEquals(Rotation.NONE, game.built.get(0).rotation());
        // and building pinned the default, so it's saved now
        assertTrue(Files.exists(saveFile));
    }

    @Test
    public void aSecondClickWinsAndAWorldChangeCancels() {
        McSchematics s = make();
        s.build("wall.schem");
        s.build("wall.schem");
        drain();
        assertEquals(1, game.built.size());

        s.build("wall.schem");
        game.dimension = "minecraft:the_end";
        drain();
        assertEquals(1, game.built.size());
        assertEquals(BuildState.ERROR, s.building().state());
        assertEquals(1, game.said.size());
    }

    @Test
    public void aRefusedBuildSaysWhy() {
        McSchematics s = make();
        game.refuse = "Can't build in adventure mode";
        s.build("wall.schem");
        drain();
        assertEquals(BuildState.ERROR, s.building().state());
        assertEquals("Can't build in adventure mode", s.building().message());
        assertEquals(List.of("Can't build in adventure mode"), game.said);
        assertTrue(game.built.isEmpty());
    }

    @Test
    public void aFileThatWillNotReadIsAnErrorInChatToo() throws Exception {
        McSchematics s = make();
        s.placement("wall.schem");
        s.build("wall.schem");
        // deleted between the click and the read. the size no longer matches, so the soft cache can't paper over it
        Files.delete(dir.resolve("wall.schem"));
        drain();
        assertEquals(BuildState.ERROR, s.building().state());
        assertTrue(s.building().message().contains("wall"));
        assertEquals(1, game.said.size());
        assertTrue(game.built.isEmpty());
    }

    @Test
    public void transformedIsReadOnceAndFollowsTheTurn() {
        McSchematics s = make();
        assertNull(s.transformed("wall.schem"));
        drain();
        ISchematic t = s.transformed("wall.schem");
        assertNotNull(t);
        assertEquals(5, t.widthX());
        assertTrue(t == s.transformed("wall.schem"));
        s.rotate("wall.schem", true);
        ISchematic turned = s.transformed("wall.schem");
        assertEquals(2, turned.widthX());
        assertEquals(5, turned.lengthZ());
        assertEquals(Blocks.STONE, turned.desiredState(0, 0, 0, Blocks.AIR.defaultBlockState(), List.of()).getBlock());
        assertNull(s.transformed("broken.schem"));
    }

    @Test
    public void leavingTheWorldForgetsTheMap() {
        McSchematics s = make();
        game.terrain = sand();
        for (int i = 0; i < 4; i++) {
            s.terrain(0, 0, 2);
        }
        assertTrue(s.terrain(0, 0, 2).complete());
        Object sameLevel = game.level;
        game.leaveWorld();
        assertNull(s.terrain(0, 0, 2));
        // back in, even with the very same level object: the old map is gone and fills in again
        game.dimension = "minecraft:overworld";
        game.feet = new BlockPos(0, 64, 0);
        game.level = sameLevel;
        assertFalse(s.terrain(0, 0, 2).complete());
    }

    @Test
    public void anUnreadablePlacementsFileIsNeverSavedOver() throws Exception {
        // a folder squatting on the name is "there but unreadable", the same road a file locked by a virus scanner takes
        Files.createDirectories(saveFile.resolve("in-the-way"));
        McSchematics s = make();
        Placement moved = s.move("wall.schem", 1, 0, 0);
        // the edit still works in memory, it just never gets written over something we couldn't read
        assertEquals(11, moved.x());
        assertEquals(11, s.placement("wall.schem").x());
        assertTrue(Files.isDirectory(saveFile.resolve("in-the-way")));

        // the scanner lets go: the next save reads the file first and keeps what was in it
        Files.delete(saveFile.resolve("in-the-way"));
        Files.delete(saveFile);
        SchematicFixtures.writeSponge(dir.resolve("other.schem"), 1, 1, 1, new String[]{"minecraft:stone"}, new int[1]);
        PlacementStore.save(saveFile, Map.of(
            "other.schem", SchematicPlacement.at(new BlockPos(7, 7, 7), "minecraft:overworld"),
            "wall.schem", SchematicPlacement.at(new BlockPos(-99, 0, 0), "minecraft:overworld")));
        s.move("wall.schem", 1, 0, 0);
        Map<String, SchematicPlacement> saved = PlacementStore.load(saveFile, p -> true);
        assertEquals(new BlockPos(7, 7, 7), saved.get("other.schem").origin());
        // this session's edits win over what was on disk
        assertEquals(12, saved.get("wall.schem").origin().getX());
    }

    @Test
    public void readsGiveAnUnreadableFileAnotherGoEveryFiveSeconds() throws Exception {
        Files.createDirectories(saveFile.resolve("in-the-way"));
        McSchematics s = make();
        assertEquals(11, s.move("wall.schem", 1, 0, 0).x());

        Files.delete(saveFile.resolve("in-the-way"));
        Files.delete(saveFile);
        SchematicFixtures.writeSponge(dir.resolve("other.schem"), 1, 1, 1, new String[]{"minecraft:stone"}, new int[1]);
        s.refresh();
        drain();
        PlacementStore.save(saveFile, Map.of(
            "other.schem", SchematicPlacement.at(new BlockPos(7, 7, 7), "minecraft:overworld"),
            "wall.schem", SchematicPlacement.at(new BlockPos(-99, 0, 0), "minecraft:overworld")));

        // too soon: the on-disk one isn't picked up, other.schem is still the made-up default at the feet
        now += 4_999;
        assertEquals(10, s.placement("other.schem").x());
        assertEquals(11, s.placement("wall.schem").x());

        now += 1;
        assertEquals(new Placement(7, 7, 7, 0, false, "minecraft:overworld", true), s.placement("other.schem"));
        // the in-memory edit wins over the file
        assertEquals(11, s.placement("wall.schem").x());
        assertEquals(11, s.footprint("wall.schem").x());
    }

    private static TerrainMap.Source sand() {
        return new TerrainMap.Source() {
            @Override
            public boolean hasChunk(int chunkX, int chunkZ) {
                return true;
            }

            @Override
            public int surfaceY(int x, int z) {
                return 0;
            }

            @Override
            public int minY() {
                return 0;
            }

            @Override
            public net.minecraft.world.level.block.state.BlockState state(int x, int y, int z) {
                return Blocks.SAND.defaultBlockState();
            }

            @Override
            public net.minecraft.world.level.material.MapColor color(net.minecraft.world.level.block.state.BlockState state, int x, int y, int z) {
                return net.minecraft.world.level.material.MapColor.SAND;
            }
        };
    }

    @Test
    public void transformedSaysWhenTheReadFailed() throws Exception {
        McSchematics s = make();
        Files.delete(dir.resolve("wall.schem"));
        assertNull(s.transformed("wall.schem"));
        assertFalse(s.previewFailed("wall.schem"));
        drain();
        assertNull(s.transformed("wall.schem"));
        assertTrue(s.previewFailed("wall.schem"));
    }

    @Test
    public void terrainComesFromTheGameLevel() {
        McSchematics s = make();
        assertNull(s.terrain(0, 0, 8));
        game.terrain = new TerrainMap.Source() {
            @Override
            public boolean hasChunk(int chunkX, int chunkZ) {
                return true;
            }

            @Override
            public int surfaceY(int x, int z) {
                return 0;
            }

            @Override
            public int minY() {
                return 0;
            }

            @Override
            public net.minecraft.world.level.block.state.BlockState state(int x, int y, int z) {
                return Blocks.SAND.defaultBlockState();
            }

            @Override
            public net.minecraft.world.level.material.MapColor color(net.minecraft.world.level.block.state.BlockState state, int x, int y, int z) {
                return net.minecraft.world.level.material.MapColor.SAND;
            }
        };
        assertNotNull(s.terrain(0, 0, 1));
        assertTrue(s.terrain(0, 0, 1).knownAt(0, 0));
    }

    @Test
    public void aBuildThatBlowsUpSaysWhatBlewUp() {
        McSchematics s = make();
        game.boom = new IllegalStateException("nope");
        s.build("wall.schem");
        drain();
        assertEquals(BuildState.ERROR, s.building().state());
        assertEquals("Could not start building: IllegalStateException", s.building().message());
        assertEquals(List.of("Could not start building: IllegalStateException (wall)"), game.said);
    }

    @Test
    public void couldNotStartNamesWhatItCan() {
        assertEquals("Too big to build (out of memory)", McSchematics.couldNotStart(new OutOfMemoryError()));
        assertTrue(McSchematics.couldNotStart(new java.util.concurrent.RejectedExecutionException()).contains("reader stopped"));
        assertTrue(McSchematics.couldNotStart(new java.io.UncheckedIOException(new java.io.IOException())).contains("file"));
        assertEquals("Could not start building: IllegalArgumentException",
            McSchematics.couldNotStart(new IllegalArgumentException("declares multiple JSON fields named 'a'")));
    }

    @Test
    public void aSaveThatCannotLandStillBuildsAndKeepsTheEdit() throws Exception {
        // a plain file where the altoclef folder should be, so every save fails
        Files.createDirectories(saveFile.getParent().getParent());
        Files.writeString(saveFile.getParent(), "in the way");
        McSchematics s = make();
        Placement moved = s.move("wall.schem", 4, 0, 0);
        assertNotNull(moved);
        assertEquals(14, s.placement("wall.schem").x());
        assertTrue(s.build("wall.schem").ok());
        drain();
        assertEquals(new BlockPos(14, 64, -20), game.built.get(0).origin());
    }

    @Test
    public void pinnedHereIsOnlyWhatSomebodyChoseInThisDimension() {
        McSchematics s = make();
        // looked at, never touched: that's the made-up feet default, #build must not treat it as placed
        s.placement("wall.schem");
        assertTrue(s.pinnedHere().isEmpty());
        s.move("wall.schem", 2, 0, 0);
        assertEquals(Map.of("wall.schem", new SchematicPlacement(new BlockPos(12, 64, -20), Rotation.NONE, Mirror.NONE,
            "minecraft:overworld")), s.pinnedHere());
        game.dimension = "minecraft:the_nether";
        assertTrue(s.pinnedHere().isEmpty());
        game.leaveWorld();
        assertTrue(s.pinnedHere().isEmpty());
    }

    @Test
    public void pinnedHereDoesNotWaitForTheFolderScan() {
        PlacementStore.save(saveFile, Map.of("wall.schem", SchematicPlacement.at(new BlockPos(7, 8, 9), "minecraft:overworld")));
        // no list(), no drain: the scan never ran
        McSchematics s = new McSchematics(new SchematicLibrary(dir.toFile()), queue::add, HashMap::new, id -> false,
            Runnable::run, (ext, title) -> FilePicker.Choice.cancelled(), game, () -> now);
        assertEquals(new BlockPos(7, 8, 9), s.pinnedHere().get("wall.schem").origin());
    }

    @Test
    public void atFeetIsTheCornerOnTheFeet() {
        McSchematics s = make();
        assertTrue(s.atFeet("wall.schem"));
        s.move("wall.schem", 1, 0, 0);
        assertFalse(s.atFeet("wall.schem"));
        s.placeAtFeet("wall.schem");
        assertTrue(s.atFeet("wall.schem"));
        game.feet = new BlockPos(0, 0, 0);
        assertFalse(s.atFeet("wall.schem"));
        assertFalse(s.atFeet("broken.schem"));
    }
}
