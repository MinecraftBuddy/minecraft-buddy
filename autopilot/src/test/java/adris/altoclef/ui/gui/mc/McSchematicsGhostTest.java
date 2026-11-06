package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.mc.ghost.SchematicGhostRenderer;
import adris.altoclef.ui.gui.mc.ghost.SchematicGhostRenderer.Target;
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
import java.util.Queue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

// which schematic the ghost draws and when it stops, on the real McSchematics with a FakeGame
public class McSchematicsGhostTest {

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
            Runnable::run, (ext, title) -> FilePicker.Choice.cancelled(), game, () -> 0L);
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
    public void setAndClear() {
        McSchematics s = make();
        assertNull(s.ghost());
        s.setGhost("wall.schem");
        assertEquals("wall.schem", s.ghost());
        s.clearGhost();
        assertNull(s.ghost());
        s.setGhost("wall.schem");
        s.setGhost(null);
        assertNull(s.ghost());
    }

    @Test
    public void leavingTheWorldDropsIt() {
        McSchematics s = make();
        s.setGhost("wall.schem");
        game.leaveWorld();
        assertNull(s.ghost());
        // and it doesn't come back in the next world
        game.dimension = "minecraft:overworld";
        game.feet = new BlockPos(0, 64, 0);
        assertNull(s.ghost());
    }

    @Test
    public void theTargetIsTheTurnedFileAtItsPlacementOnceParsed() {
        McSchematics s = make();
        assertNull(SchematicGhostRenderer.target(s));
        s.setGhost("wall.schem");
        // the first ask starts the parse, nothing to draw until it's done
        assertNull(SchematicGhostRenderer.target(s));
        drain();
        Target t = SchematicGhostRenderer.target(s);
        assertNotNull(t);
        assertEquals(10, t.x());
        assertEquals(64, t.y());
        assertEquals(-20, t.z());
        assertEquals(5, t.schematic().widthX());
        s.rotate("wall.schem", true);
        Target turned = SchematicGhostRenderer.target(s);
        assertEquals(2, turned.schematic().widthX());
    }

    @Test
    public void placedInAnotherDimensionIsNotDrawnButStaysChosen() {
        McSchematics s = make();
        s.setGhost("wall.schem");
        // moving it pins it to the overworld
        s.move("wall.schem", 1, 0, 0);
        SchematicGhostRenderer.target(s);
        drain();
        assertNotNull(SchematicGhostRenderer.target(s));
        game.dimension = "minecraft:the_nether";
        assertNull(SchematicGhostRenderer.target(s));
        assertEquals("wall.schem", s.ghost());
        game.dimension = "minecraft:overworld";
        assertNotNull(SchematicGhostRenderer.target(s));
    }
}
