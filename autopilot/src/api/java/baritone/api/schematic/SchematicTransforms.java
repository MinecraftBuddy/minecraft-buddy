/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.api.schematic;

import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

/**
 * The one place that turns a schematic into its placed orientation. Anything that needs to agree with what the builder
 * will actually place (a preview, a footprint, the build itself) should go through here.
 * <p>
 * The mirror is applied first and the rotation second, so the result is "rotate(mirror(schematic))". Both are anchored
 * at the minimum corner: local (0, 0, 0) of the result is the minimum corner of the transformed footprint.
 */
public final class SchematicTransforms {

    private SchematicTransforms() {}

    /**
     * Wraps a schematic in its mirror and rotation, skipping the wrappers that would do nothing.
     *
     * @param schematic The schematic to transform
     * @param rotation  The rotation, applied after the mirror
     * @param mirror    The mirror, applied first
     * @return The transformed schematic, or {@code schematic} itself when both are no-ops
     */
    public static ISchematic apply(ISchematic schematic, Rotation rotation, Mirror mirror) {
        ISchematic out = schematic;
        if (mirror != Mirror.NONE) {
            out = new MirroredSchematic(out, mirror);
        }
        if (rotation != Rotation.NONE) {
            out = new RotatedSchematic(out, rotation);
        }
        return out;
    }

    /**
     * The size of a schematic after {@link #apply}. Mirroring never changes it, a quarter turn swaps x and z.
     *
     * @param sizeX    The untransformed width along x
     * @param sizeY    The untransformed height
     * @param sizeZ    The untransformed length along z
     * @param rotation The rotation
     * @return The transformed size as (widthX, heightY, lengthZ)
     */
    public static Vec3i size(int sizeX, int sizeY, int sizeZ, Rotation rotation) {
        return swapsXZ(rotation) ? new Vec3i(sizeZ, sizeY, sizeX) : new Vec3i(sizeX, sizeY, sizeZ);
    }

    /**
     * @param rotation The rotation
     * @return Whether the rotation is a quarter turn, which swaps the x and z sizes
     */
    public static boolean swapsXZ(Rotation rotation) {
        return rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90;
    }
}
