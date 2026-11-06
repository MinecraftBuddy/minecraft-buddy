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

package baritone.api.process;

import baritone.api.schematic.ISchematic;
import baritone.api.schematic.IStaticSchematic;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @author Brady
 * @since 1/15/2019
 */
public interface IBuilderProcess extends IBaritoneProcess {

    /**
     * Requests a build for the specified schematic, labeled as specified, with the specified origin.
     *
     * @param name      A user-friendly name for the schematic
     * @param schematic The object representation of the schematic
     * @param origin    The origin position of the schematic being built
     */
    void build(String name, ISchematic schematic, Vec3i origin);

    /**
     * Requests a build for the specified schematic, labeled as specified, with the specified origin.
     *
     * @param name      A user-friendly name for the schematic
     * @param schematic The file path of the schematic
     * @param origin    The origin position of the schematic being built
     * @return Whether or not the schematic was able to load from file
     */
    boolean build(String name, File schematic, Vec3i origin);

    /**
     * Requests a build of an already parsed schematic in an explicit orientation. Map art, selection, substitutes and
     * skip blocks apply the same way they do for {@link #build(String, File, Vec3i)}, but the global
     * {@code buildSchematicRotation} / {@code buildSchematicMirror} settings and the {@code schematicOrientation*}
     * offsets are ignored, and the settings are never written.
     * <p>
     * The schematic is transformed with {@link baritone.api.schematic.SchematicTransforms#apply}, so mirror first and
     * rotation second, and {@code origin} is the minimum corner of the transformed footprint.
     *
     * @param name      A user-friendly name for the schematic
     * @param schematic The parsed schematic, untransformed
     * @param origin    The minimum corner of the transformed footprint
     * @param rotation  The rotation to build with
     * @param mirror    The mirror to build with
     */
    void build(String name, IStaticSchematic schematic, Vec3i origin, Rotation rotation, Mirror mirror);

    @Deprecated
    default boolean build(String schematicFile, BlockPos origin) {
        File file = new File(new File(Minecraft.getInstance().gameDirectory, "schematics"), schematicFile);
        return build(schematicFile, file, origin);
    }

    /**
     * Every build started on this process gets the next number, starting at 1, whichever way it was started.
     *
     * @return The number of the build currently running, or 0 when nothing is being built
     */
    long buildSerial();

    void buildOpenSchematic();

    void buildOpenLitematic(int i);

    void pause();

    boolean isPaused();

    void resume();

    /**
     * What the builder found missing the last time it gave up and paused for lack of materials: every block state it
     * wanted to place and had no item for, with how many positions want it. It is only what the builder could see at
     * the time (the positions near the player), so it is a lower bound, not the cost of the rest of the build.
     * <p>
     * Empty when the builder is not paused, or paused for some other reason (it can't reach or place what is left).
     * Cleared by {@link #resume()}, by cancelling and by starting a new build. Written and read on the game thread.
     *
     * @return A snapshot of the missing block states and their counts, never null
     */
    Map<BlockState, Integer> missingAtPause();

    void clearArea(BlockPos corner1, BlockPos corner2);

    /**
     * A rule for blocks the builder should leave standing even though the schematic wants something else there, for
     * example scaffolding the caller placed and wants to clean up itself once the build is done.
     */
    @FunctionalInterface
    interface Keep {

        /**
         * Asked from the game thread and from the path thread, so it must not touch the world itself.
         *
         * @param x       The x coordinate of the block
         * @param y       The y coordinate of the block
         * @param z       The z coordinate of the block
         * @param current The block state currently at that position
         * @return Whether the block counts as fine where it is, so it is neither broken nor replaced
         */
        boolean keep(int x, int y, int z, BlockState current);
    }

    /**
     * Sets the blocks the current build should leave alone. Kept blocks never count as incorrect, are never broken
     * by the builder, and breaking them while pathing costs as much as breaking an already correct block.
     * <p>
     * Every new build and the builder stopping clear it, so it only ever applies to the build that was running when it
     * was set. Set it right after starting the build. Null means nothing is kept, which is the default.
     *
     * @param keep The rule, or null for none
     */
    void setKeep(Keep keep);

    /**
     * @return A list of block states that are estimated to be placeable by this builder process. You can use this in
     * schematics, for example, to pick a state that the builder process will be happy with, because any variation will
     * cause it to give up. This is updated every tick, but only while the builder process is active.
     */
    List<BlockState> getApproxPlaceable();
    /**
     * Returns the lower bound of the current mining layer if mineInLayers is true.
     * If mineInLayers is false, this will return an empty optional.
     * @return The lower bound of the current mining layer
     */
    Optional<Integer> getMinLayer();

    /**
     * Returns the upper bound of the current mining layer if mineInLayers is true.
     * If mineInLayers is false, this will return an empty optional.
     * @return The upper bound of the current mining layer
     */
    Optional<Integer> getMaxLayer();
}
