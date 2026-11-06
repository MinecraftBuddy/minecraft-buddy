package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.facade.Result;
import adris.altoclef.util.helpers.ItemFixture;
import baritone.utils.schematic.library.PlacementStore;
import baritone.utils.schematic.library.SchematicFixtures;
import baritone.utils.schematic.library.SchematicLibrary;
import net.minecraft.core.BlockPos;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Queue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// the move mode bookkeeping on the real McSchematics: which file, when it refuses, and that a world change drops it
public class McSchematicsMoveTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Path dir;
    private final Queue<Runnable> queue = new ArrayDeque<>();
    private final FakeGame game = new FakeGame();

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    @Before
    public void setUp() throws Exception {
        dir = folder.newFolder("schematics").toPath();
        game.file = folder.getRoot().toPath().resolve("world").resolve("altoclef").resolve(PlacementStore.FILE_NAME);
        SchematicFixtures.writeSponge(dir.resolve("wall.schem"), 5, 1, 2, new String[]{"minecraft:stone"}, new int[10]);
    }

    private McSchematics make() {
        McSchematics s = new McSchematics(new SchematicLibrary(dir.toFile()), queue::add, HashMap::new, id -> false,
            Runnable::run, (ext, title) -> FilePicker.Choice.cancelled(), game, () -> 1_000_000L);
        s.list();
        Runnable next;
        while ((next = queue.poll()) != null) {
            next.run();
        }
        return s;
    }

    @Test
    public void startRemembersTheFileAndAsksForTheScreen() {
        McSchematics s = make();
        assertNull(s.moving());
        Result r = s.startMoving("wall.schem");
        assertTrue(r.ok());
        assertEquals("wall.schem", s.moving());
        // the arrows go on the ghost, so the ghost is this file now
        assertEquals("wall.schem", s.ghost());
        assertEquals(List.of("wall.schem"), game.movers);
        s.stopMoving();
        assertNull(s.moving());
    }

    @Test
    public void refusesWhatItCannotMove() {
        McSchematics s = make();
        assertFalse(s.startMoving("nope.schem").ok());
        // placed in the overworld, then through a portal
        s.move("wall.schem", 1, 0, 0);
        game.dimension = "minecraft:the_nether";
        assertFalse(s.startMoving("wall.schem").ok());
        game.dimension = "minecraft:overworld";
        // too far for the camera's leash
        game.feet = new BlockPos(10 + 200, 64, -20);
        Result far = s.startMoving("wall.schem");
        assertFalse(far.ok());
        assertTrue(far.message(), far.message().contains("128"));
        assertNull(s.moving());
        assertTrue(game.movers.isEmpty());
        // a short render distance is a short leash
        game.feet = new BlockPos(10 + 100, 64, -20);
        game.moveRange = 64;
        Result shortLeash = s.startMoving("wall.schem");
        assertFalse(shortLeash.ok());
        assertTrue(shortLeash.message(), shortLeash.message().contains("64"));
        // just inside it is fine
        game.moveRange = 128;
        assertTrue(s.startMoving("wall.schem").ok());
    }

    @Test
    public void leavingTheWorldEndsIt() {
        McSchematics s = make();
        assertTrue(s.startMoving("wall.schem").ok());
        game.leaveWorld();
        assertNull(s.moving());
    }
}
