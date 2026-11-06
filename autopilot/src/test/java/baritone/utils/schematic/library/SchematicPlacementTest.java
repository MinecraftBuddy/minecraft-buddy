package baritone.utils.schematic.library;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.schematic.ISchematic;
import baritone.api.schematic.RotatedSchematic;
import baritone.api.schematic.SchematicTransforms;
import baritone.utils.schematic.StaticSchematic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

// the transform the build and the preview share, and the placement edits on top of it. the composition tests are the
// point: a mirror click has to flip what's in the world now, whatever turn came before it
public class SchematicPlacementTest {

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    private static final List<Rotation> ROTATIONS = List.of(Rotation.values());
    private static final List<Mirror> MIRRORS = List.of(Mirror.NONE, Mirror.FRONT_BACK);

    private static BlockState stairs(Direction facing) {
        return Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, facing);
    }

    // 3 wide, 2 tall, 2 long, every cell a different block so a position mixup can't hide. (0,0,0) is a stair facing
    // east and (2,0,1) a door facing north, so the state turns show up too
    private static StaticSchematic fixture() {
        BlockState[] palette = {
            stairs(Direction.EAST), Blocks.STONE.defaultBlockState(), Blocks.DIRT.defaultBlockState(),
            Blocks.OAK_LOG.defaultBlockState(), Blocks.GLASS.defaultBlockState(), Blocks.SAND.defaultBlockState(),
            Blocks.GRAVEL.defaultBlockState(), Blocks.BRICKS.defaultBlockState(), Blocks.CLAY.defaultBlockState(),
            Blocks.OAK_PLANKS.defaultBlockState(),
            Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH)
                .setValue(DoorBlock.HINGE, DoorHingeSide.LEFT),
            Blocks.SPONGE.defaultBlockState()
        };
        BlockState[][][] states = new BlockState[3][2][2];
        int i = 0;
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 2; z++) {
                for (int y = 0; y < 2; y++) {
                    states[x][z][y] = palette[i++];
                }
            }
        }
        return new StaticSchematic(states);
    }

    private static BlockState at(ISchematic s, int x, int y, int z) {
        assertTrue(s.inSchematic(x, y, z, Blocks.AIR.defaultBlockState()));
        return s.desiredState(x, y, z, Blocks.AIR.defaultBlockState(), List.of());
    }

    private static ISchematic placed(StaticSchematic file, SchematicPlacement p) {
        return SchematicTransforms.apply(file, p.rotation(), p.mirror());
    }

    @Test
    public void footprintMatchesTheWrapperForEveryTurnAndFlip() {
        StaticSchematic file = fixture();
        for (Rotation r : ROTATIONS) {
            for (Mirror m : MIRRORS) {
                ISchematic t = SchematicTransforms.apply(file, r, m);
                Vec3i size = SchematicTransforms.size(file.widthX(), file.heightY(), file.lengthZ(), r);
                String what = r + " " + m;
                assertEquals(what, t.widthX(), size.getX());
                assertEquals(what, t.heightY(), size.getY());
                assertEquals(what, t.lengthZ(), size.getZ());
                // and the placement record agrees
                Vec3i viaPlacement = new SchematicPlacement(BlockPos.ZERO, r, m, "d").size(3, 2, 2);
                assertEquals(what, size, viaPlacement);
                // every cell of the turned box maps back inside the file: local 0,0,0 is the min corner, nothing negative
                for (int x = 0; x < t.widthX(); x++) {
                    for (int y = 0; y < t.heightY(); y++) {
                        for (int z = 0; z < t.lengthZ(); z++) {
                            assertNotNull(what, at(t, x, y, z));
                        }
                    }
                }
            }
        }
    }

    @Test
    public void aQuarterTurnClockwiseTurnsTheStairAndTheCorner() {
        ISchematic t = SchematicTransforms.apply(fixture(), Rotation.CLOCKWISE_90, Mirror.NONE);
        assertEquals(2, t.widthX());
        assertEquals(3, t.lengthZ());
        // the file's north west corner ends up north east, facing south now (east turned clockwise)
        assertEquals(stairs(Direction.SOUTH), at(t, 1, 0, 0));
    }

    @Test
    public void theMirrorFlipsEastWestAndTheHinge() {
        ISchematic t = SchematicTransforms.apply(fixture(), Rotation.NONE, SchematicPlacement.MIRROR);
        assertEquals(stairs(Direction.WEST), at(t, 2, 0, 0));
        BlockState door = at(t, 0, 0, 1);
        assertEquals(Direction.NORTH, door.getValue(DoorBlock.FACING));
        assertEquals(DoorHingeSide.RIGHT, door.getValue(DoorBlock.HINGE));
    }

    @Test
    public void mirrorThenRotateIsTheOrder() {
        // flip first: east stair -> west stair at the east end, then clockwise: west -> north, and the east end of a 3x2
        // goes to the south of a 2x3
        ISchematic t = SchematicTransforms.apply(fixture(), Rotation.CLOCKWISE_90, Mirror.FRONT_BACK);
        assertEquals(stairs(Direction.NORTH), at(t, 1, 0, 2));
    }

    @Test
    public void flippingFlipsWhatIsInTheWorldWhateverTheTurn() {
        StaticSchematic file = fixture();
        for (Rotation r : ROTATIONS) {
            for (Mirror m : MIRRORS) {
                SchematicPlacement before = new SchematicPlacement(BlockPos.ZERO, r, m, "d");
                SchematicPlacement after = before.flipped();
                ISchematic was = placed(file, before);
                ISchematic now = placed(file, after);
                assertEquals(was.widthX(), now.widthX());
                assertEquals(before.origin(), after.origin());
                for (int x = 0; x < was.widthX(); x++) {
                    for (int y = 0; y < was.heightY(); y++) {
                        for (int z = 0; z < was.lengthZ(); z++) {
                            BlockState expected = at(was, was.widthX() - 1 - x, y, z).mirror(Mirror.FRONT_BACK);
                            assertEquals(r + " " + m + " at " + x + "," + y + "," + z, expected, at(now, x, y, z));
                        }
                    }
                }
                // flipping twice is nothing
                assertEquals(before, after.flipped());
            }
        }
    }

    @Test
    public void turningTurnsWhatIsInTheWorldWhateverTheFlip() {
        StaticSchematic file = fixture();
        for (Rotation r : ROTATIONS) {
            for (Mirror m : MIRRORS) {
                for (boolean clockwise : new boolean[]{true, false}) {
                    SchematicPlacement before = new SchematicPlacement(BlockPos.ZERO, r, m, "d");
                    ISchematic expected = new RotatedSchematic(placed(file, before),
                        clockwise ? Rotation.CLOCKWISE_90 : Rotation.COUNTERCLOCKWISE_90);
                    ISchematic now = placed(file, before.rotated(clockwise, 3, 2, 2));
                    assertEquals(expected.widthX(), now.widthX());
                    assertEquals(expected.lengthZ(), now.lengthZ());
                    for (int x = 0; x < now.widthX(); x++) {
                        for (int y = 0; y < now.heightY(); y++) {
                            for (int z = 0; z < now.lengthZ(); z++) {
                                assertEquals(r + " " + m + " " + clockwise, at(expected, x, y, z), at(now, x, y, z));
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    public void fourTurnsLandWhereTheyStarted() {
        // odd size difference, the one that would drift with floor rounding
        SchematicPlacement p = SchematicPlacement.at(new BlockPos(100, 64, -50), "d");
        SchematicPlacement q = p;
        for (int i = 0; i < 4; i++) {
            q = q.rotated(true, 5, 3, 2);
        }
        assertEquals(p, q);
        q = p;
        for (int i = 0; i < 4; i++) {
            q = q.rotated(false, 5, 3, 2);
        }
        assertEquals(p, q);
        // and there and back again
        assertEquals(p, p.rotated(true, 5, 3, 2).rotated(false, 5, 3, 2));
    }

    @Test
    public void oddAndEvenSizesBothComeBack() {
        for (int[] size : new int[][]{{3, 4}, {4, 3}, {1, 8}, {7, 2}, {6, 6}}) {
            SchematicPlacement p = SchematicPlacement.at(new BlockPos(-7, 5, -13), "d");
            SchematicPlacement q = p;
            for (int i = 0; i < 4; i++) {
                q = q.rotated(true, size[0], 1, size[1]);
                // the middle never wanders more than half a block from where it started (doubled to stay in ints)
                Vec3i s = q.size(size[0], 1, size[1]);
                assertTrue(Math.abs((q.origin().getX() * 2 + s.getX()) - (p.origin().getX() * 2 + size[0])) <= 1);
                assertTrue(Math.abs((q.origin().getZ() * 2 + s.getZ()) - (p.origin().getZ() * 2 + size[1])) <= 1);
            }
            assertEquals(p, q);
        }
    }

    @Test
    public void aTurnKeepsTheMiddleWithinHalfABlock() {
        SchematicPlacement p = SchematicPlacement.at(new BlockPos(0, 0, 0), "d");
        // 9 x 3: middle at 4, 1. turned 3 x 9 should sit at 3..5, -3..5
        SchematicPlacement q = p.rotated(true, 9, 1, 3);
        assertEquals(new BlockPos(3, 0, -3), q.origin());
        assertEquals(1, q.turns());
        assertEquals(new Vec3i(3, 1, 9), q.size(9, 1, 3));
    }

    @Test
    public void movingAndPlacingKeepTheTurn() {
        SchematicPlacement p = SchematicPlacement.at(new BlockPos(1, 2, 3), "minecraft:overworld").rotated(true, 4, 4, 4).flipped();
        SchematicPlacement moved = p.moved(1, -2, 5);
        assertEquals(new BlockPos(2, 0, 8), moved.origin());
        assertEquals(p.rotation(), moved.rotation());
        assertEquals(p.mirror(), moved.mirror());
        SchematicPlacement there = p.movedTo(new BlockPos(9, 9, 9), "minecraft:the_nether");
        assertEquals("minecraft:the_nether", there.dimension());
        assertEquals(p.rotation(), there.rotation());
        assertTrue(there.isMirrored());
    }

    @Test
    public void leftRightIsStoredAsTheOneMirrorPlusHalfATurn() {
        StaticSchematic file = fixture();
        for (Rotation r : ROTATIONS) {
            ISchematic raw = SchematicTransforms.apply(file, r, Mirror.LEFT_RIGHT);
            SchematicPlacement p = new SchematicPlacement(BlockPos.ZERO, r, Mirror.LEFT_RIGHT, "d");
            assertEquals(Mirror.FRONT_BACK, p.mirror());
            ISchematic normal = placed(file, p);
            for (int x = 0; x < raw.widthX(); x++) {
                for (int y = 0; y < raw.heightY(); y++) {
                    for (int z = 0; z < raw.lengthZ(); z++) {
                        assertEquals(r.toString(), at(raw, x, y, z), at(normal, x, y, z));
                    }
                }
            }
        }
    }

    @Test
    public void turnsRoundTrip() {
        for (Rotation r : ROTATIONS) {
            SchematicPlacement p = new SchematicPlacement(BlockPos.ZERO, r, Mirror.NONE, "d");
            assertEquals(r, SchematicPlacement.rotationOf(p.turns()));
        }
        assertEquals(Rotation.COUNTERCLOCKWISE_90, SchematicPlacement.rotationOf(-1));
        assertFalse(SchematicPlacement.at(BlockPos.ZERO, "d").isMirrored());
    }
}
