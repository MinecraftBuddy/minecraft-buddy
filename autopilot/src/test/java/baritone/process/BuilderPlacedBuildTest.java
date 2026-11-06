package baritone.process;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.Baritone;
import baritone.api.Settings;
import baritone.api.schematic.ISchematic;
import baritone.utils.schematic.StaticSchematic;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// the placed build next to the old one. no game here: the builder is allocated without its constructor (nothing in
// build() touches the baritone it would have had unless buildOnlySelection is on) and the result is read off its fields
public class BuilderPlacedBuildTest {

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    private Settings s;
    private boolean oldX;
    private boolean oldY;
    private boolean oldZ;
    private Rotation oldRotation;
    private Mirror oldMirror;
    private List<Block> oldSkip;
    private boolean oldMapArt;
    private boolean oldOnlySelection;

    @Before
    public void remember() {
        s = Baritone.settings();
        oldX = s.schematicOrientationX.value;
        oldY = s.schematicOrientationY.value;
        oldZ = s.schematicOrientationZ.value;
        oldRotation = s.buildSchematicRotation.value;
        oldMirror = s.buildSchematicMirror.value;
        oldSkip = s.buildSkipBlocks.value;
        oldMapArt = s.mapArtMode.value;
        oldOnlySelection = s.buildOnlySelection.value;
        s.buildOnlySelection.value = false;
        s.mapArtMode.value = false;
    }

    @After
    public void restore() {
        s.schematicOrientationX.value = oldX;
        s.schematicOrientationY.value = oldY;
        s.schematicOrientationZ.value = oldZ;
        s.buildSchematicRotation.value = oldRotation;
        s.buildSchematicMirror.value = oldMirror;
        s.buildSkipBlocks.value = oldSkip;
        s.mapArtMode.value = oldMapArt;
        s.buildOnlySelection.value = oldOnlySelection;
    }

    private static BuilderProcess builder() throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (BuilderProcess) ((sun.misc.Unsafe) f.get(null)).allocateInstance(BuilderProcess.class);
    }

    private static Object field(BuilderProcess b, String name) throws Exception {
        Field f = BuilderProcess.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(b);
    }

    private static BlockState stairs(Direction facing) {
        return Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, facing);
    }

    // 3 wide, 1 tall, 2 long. an east facing stair in the north west corner, stone everywhere else
    private static StaticSchematic file() {
        BlockState[][][] states = new BlockState[3][2][1];
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 2; z++) {
                states[x][z][0] = Blocks.STONE.defaultBlockState();
            }
        }
        states[0][0][0] = stairs(Direction.EAST);
        return new StaticSchematic(states);
    }

    private static BlockState at(ISchematic s, int x, int y, int z) {
        return s.desiredState(x, y, z, Blocks.AIR.defaultBlockState(), List.of());
    }

    @Test
    public void thePlacedBuildIgnoresTheGlobalTurnAndTheOrientationOffsets() throws Exception {
        s.schematicOrientationX.value = true;
        s.schematicOrientationY.value = true;
        s.schematicOrientationZ.value = true;
        s.buildSchematicRotation.value = Rotation.CLOCKWISE_180;
        s.buildSchematicMirror.value = Mirror.LEFT_RIGHT;
        BuilderProcess b = builder();
        b.build("placed", file(), new Vec3i(5, 6, 7), Rotation.CLOCKWISE_90, Mirror.NONE);
        // origin is the corner it was given, no offsets
        assertEquals(new Vec3i(5, 6, 7), field(b, "origin"));
        ISchematic built = (ISchematic) field(b, "schematic");
        assertEquals(2, built.widthX());
        assertEquals(3, built.lengthZ());
        // same spot and facing SchematicTransforms gives: north west corner to north east, east to south
        assertEquals(stairs(Direction.SOUTH), at(built, 1, 0, 0));
        // and the globals were read, not written
        assertEquals(Rotation.CLOCKWISE_180, s.buildSchematicRotation.value);
        assertEquals(Mirror.LEFT_RIGHT, s.buildSchematicMirror.value);
    }

    @Test
    public void thePlacedBuildMirrorsFirst() throws Exception {
        BuilderProcess b = builder();
        b.build("placed", file(), new Vec3i(0, 0, 0), Rotation.CLOCKWISE_90, Mirror.FRONT_BACK);
        ISchematic built = (ISchematic) field(b, "schematic");
        // flip: stair to the east end facing west. turn: the east end goes south, west turns north
        assertEquals(stairs(Direction.NORTH), at(built, 1, 0, 2));
    }

    @Test
    public void thePlacedBuildStillSkipsBlocks() throws Exception {
        s.buildSkipBlocks.value = List.of(Blocks.STONE);
        BuilderProcess b = builder();
        b.build("placed", file(), new Vec3i(0, 0, 0), Rotation.NONE, Mirror.NONE);
        ISchematic built = (ISchematic) field(b, "schematic");
        assertFalse(built.inSchematic(1, 0, 0, Blocks.AIR.defaultBlockState()));
        assertTrue(built.inSchematic(0, 0, 0, Blocks.AIR.defaultBlockState()));
    }

    @Test
    public void mapArtReadsTheUnturnedColumns() throws Exception {
        // two layers. every column has its top block at y 1 except the north west one, whose top is y 0, so map art
        // keeps both blocks there and only the top block everywhere else
        BlockState[][][] states = new BlockState[3][2][2];
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 2; z++) {
                states[x][z][0] = Blocks.STONE.defaultBlockState();
                states[x][z][1] = Blocks.DIRT.defaultBlockState();
            }
        }
        states[0][0][1] = Blocks.AIR.defaultBlockState();
        s.mapArtMode.value = true;
        BuilderProcess b = builder();
        b.build("art", new StaticSchematic(states), new Vec3i(0, 0, 0), Rotation.CLOCKWISE_90, Mirror.NONE);
        ISchematic built = (ISchematic) field(b, "schematic");
        BlockState air = Blocks.AIR.defaultBlockState();
        // the file's north west column turned clockwise is the north east one, 1,0 in a 2 x 3
        assertTrue(built.inSchematic(1, 0, 0, air));
        assertFalse(built.inSchematic(0, 0, 0, air));
        assertTrue(built.inSchematic(0, 1, 0, air));
    }

    @Test
    public void theOldBuildStillUsesTheGlobalsAndTheUnturnedOffsets() throws Exception {
        s.schematicOrientationX.value = true;
        s.schematicOrientationY.value = false;
        s.schematicOrientationZ.value = true;
        s.buildSchematicRotation.value = Rotation.CLOCKWISE_90;
        s.buildSchematicMirror.value = Mirror.NONE;
        BuilderProcess b = builder();
        b.build("old", file(), new Vec3i(5, 6, 7));
        // the offsets are the file's own width and length, before the turn, exactly like before
        assertEquals(new Vec3i(5 + 3, 6, 7 + 2), field(b, "origin"));
        ISchematic built = (ISchematic) field(b, "schematic");
        assertEquals(2, built.widthX());
        assertEquals(stairs(Direction.SOUTH), at(built, 1, 0, 0));
    }

    @Test
    public void theOldBuildWithDefaultsIsUntouched() throws Exception {
        s.schematicOrientationX.value = false;
        s.schematicOrientationY.value = false;
        s.schematicOrientationZ.value = false;
        s.buildSchematicRotation.value = Rotation.NONE;
        s.buildSchematicMirror.value = Mirror.NONE;
        BuilderProcess b = builder();
        b.build("old", file(), new Vec3i(5, 6, 7));
        assertEquals(new Vec3i(5, 6, 7), field(b, "origin"));
        ISchematic built = (ISchematic) field(b, "schematic");
        assertEquals(3, built.widthX());
        assertEquals(stairs(Direction.EAST), at(built, 0, 0, 0));
    }
}
