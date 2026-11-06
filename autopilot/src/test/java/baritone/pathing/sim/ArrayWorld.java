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

package baritone.pathing.sim;

// a few blocks in a box (public so the executor-side tests in other packages can stand on it), everything outside it is air. no minecraft registries needed, which is the point
public final class ArrayWorld implements CollisionProvider {

    public static final byte AIR = 0, STONE = 1, SLAB = 2, ICE = 3;

    private final byte[] blocks;
    private final int sx, sy, sz;

    public ArrayWorld(int sx, int sy, int sz) {
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        this.blocks = new byte[sx * sy * sz];
    }

    public void set(int x, int y, int z, byte type) {
        blocks[(x * sy + y) * sz + z] = type;
    }

    // fills a cuboid, corners inclusive
    public void fill(int x0, int y0, int z0, int x1, int y1, int z1, byte type) {
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    set(x, y, z, type);
                }
            }
        }
    }

    private byte at(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) {
            return AIR;
        }
        return blocks[(x * sy + y) * sz + z];
    }

    @Override
    public void boxes(int x, int y, int z, BoxList out) {
        switch (at(x, y, z)) {
            case STONE:
            case ICE:
                out.add(x, y, z, x + 1, y + 1, z + 1);
                break;
            case SLAB:
                out.add(x, y, z, x + 1, y + 0.5, z + 1);
                break;
            default:
                break;
        }
    }

    @Override
    public boolean plain(int x, int y, int z) {
        return at(x, y, z) != ICE;
    }
}
