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

package baritone.process.elytra;

import baritone.process.elytra.pathfinder.Chunk;
import baritone.process.elytra.pathfinder.NetherPathfinder;

/**
 * @author Brady
 */
public final class BlockStateOctreeInterface {

    private final NetherPathfinderContext context;
    private final NetherPathfinder pathfinder;
    private final int minY;
    // The chunk the last lookup fell in, so that a run of lookups inside one chunk costs one table
    // lookup. Cleared under the write lock by whatever replaces or culls chunks.
    // the solver thread and the game thread both come through here, so the coordinates and the chunk live in one
    // object with final fields. three loose fields could be read half from one thread's lookup and half from the other's
    private static final class Last {
        final int x;
        final int z;
        final Chunk chunk;

        Last(int x, int z, Chunk chunk) {
            this.x = x;
            this.z = z;
            this.chunk = chunk;
        }
    }

    private Last last;

    public BlockStateOctreeInterface(final NetherPathfinderContext context) {
        this.context = context;
        this.pathfinder = context.context;
        this.minY = context.minY;
    }

    public boolean get0(final int x, final int y, final int z) {
        final int adjustedY = y - this.minY;
        if (adjustedY < 0 || adjustedY > 383) {
            return false;
        }
        final int chunkX = x >> 4;
        final int chunkZ = z >> 4;
        // read it once, another thread may swap it out under us
        Last last = this.last;
        if (last == null || last.x != chunkX || last.z != chunkZ) {
            last = new Last(chunkX, chunkZ, this.pathfinder.getChunkOrDefault(chunkX, chunkZ, true));
            this.last = last;
        }
        return last.chunk.isSolid(x & 0xF, adjustedY, z & 0xF);
    }

    // only call this with the write lock held, no reader can be mid lookup then
    void clearCache() {
        this.last = null;
    }
}
