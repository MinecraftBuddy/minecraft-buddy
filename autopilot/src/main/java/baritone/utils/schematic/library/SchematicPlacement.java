package baritone.utils.schematic.library;

import baritone.api.schematic.SchematicTransforms;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

// where a schematic goes in the world and which way round. origin is the min corner of the turned footprint, the same
// corner BuilderProcess's placed build takes, and the turn is SchematicTransforms order: mirror first, rotation second.
// dimension is the level id it was placed in ("minecraft:overworld"). immutable, every edit hands back a new one
public record SchematicPlacement(BlockPos origin, Rotation rotation, Mirror mirror, String dimension) {

    // the one mirror the gui offers. FRONT_BACK flips x, so the build is mirrored east to west across its own middle
    public static final Mirror MIRROR = Mirror.FRONT_BACK;

    public SchematicPlacement {
        // a hand edited LEFT_RIGHT is FRONT_BACK plus half a turn (both flip once, the turn makes up the axis), so only
        // NONE and MIRROR ever exist past here and flipped() can stay a toggle
        if (mirror == Mirror.LEFT_RIGHT) {
            mirror = MIRROR;
            rotation = rotation.getRotated(Rotation.CLOCKWISE_180);
        }
    }

    public static SchematicPlacement at(BlockPos origin, String dimension) {
        return new SchematicPlacement(origin.immutable(), Rotation.NONE, Mirror.NONE, dimension);
    }

    public boolean isMirrored() {
        return mirror != Mirror.NONE;
    }

    // quarter turns clockwise, 0..3
    public int turns() {
        return switch (rotation) {
            case NONE -> 0;
            case CLOCKWISE_90 -> 1;
            case CLOCKWISE_180 -> 2;
            case COUNTERCLOCKWISE_90 -> 3;
        };
    }

    public static Rotation rotationOf(int turns) {
        return switch (Math.floorMod(turns, 4)) {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    // the footprint size for an unturned sizeX x sizeY x sizeZ file
    public Vec3i size(int sizeX, int sizeY, int sizeZ) {
        return SchematicTransforms.size(sizeX, sizeY, sizeZ, rotation);
    }

    public SchematicPlacement moved(int dx, int dy, int dz) {
        return new SchematicPlacement(origin.offset(dx, dy, dz), rotation, mirror, dimension);
    }

    public SchematicPlacement movedTo(BlockPos where, String dimension) {
        return new SchematicPlacement(where.immutable(), rotation, mirror, dimension);
    }

    // a quarter turn of what's in the world right now, about the middle of the footprint. the corner shifts by half the
    // size change, rounded toward zero so it rounds the other way on the way back and four turns land where they started.
    // when x and z sizes differ by an odd number the middle can't stay put exactly, it wobbles half a block and back
    public SchematicPlacement rotated(boolean clockwise, int sizeX, int sizeY, int sizeZ) {
        Rotation next = rotation.getRotated(clockwise ? Rotation.CLOCKWISE_90 : Rotation.COUNTERCLOCKWISE_90);
        Vec3i before = size(sizeX, sizeY, sizeZ);
        Vec3i after = SchematicTransforms.size(sizeX, sizeY, sizeZ, next);
        BlockPos corner = origin.offset((before.getX() - after.getX()) / 2, 0, (before.getZ() - after.getZ()) / 2);
        return new SchematicPlacement(corner, next, mirror, dimension);
    }

    // flips what's in the world right now east to west, in place. the build is rotate(mirror(file)), and a flip on the
    // outside of a rotation is the same as the opposite rotation on the outside of the flip, so the turn inverts and the
    // mirror toggles. footprint size doesn't change, so neither does the corner
    public SchematicPlacement flipped() {
        return new SchematicPlacement(origin, inverse(rotation), isMirrored() ? Mirror.NONE : MIRROR, dimension);
    }

    static Rotation inverse(Rotation rotation) {
        return switch (rotation) {
            case CLOCKWISE_90 -> Rotation.COUNTERCLOCKWISE_90;
            case COUNTERCLOCKWISE_90 -> Rotation.CLOCKWISE_90;
            default -> rotation;
        };
    }
}
