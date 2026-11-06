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

package baritone.process;

// the numbers side of TorchProcess, no minecraft in here so the tests can poke it
final class TorchRules {

    static final int MAX_LIGHT = 15;

    private TorchRules() {
    }

    // sky light is the raw value, not darkened by night. a cave mouth at midnight still has sky light 15 and still gets
    // no torch, which is the whole trick that keeps the surface from turning into a torch farm. upstream only looked at
    // block light and torched every grass block it walked past
    static boolean dark(int blockLight, int skyLight, int level) {
        int max = clampLevel(level);
        return blockLight <= max && skyLight <= max;
    }

    static int clampLevel(int level) {
        return Math.max(0, Math.min(MAX_LIGHT, level));
    }

    // does the player's box poke into this block. strict, so a box just touching the face (pressed against it, or standing
    // exactly on the line) doesn't count. playerFeet is one column, the box is two of them half the time
    static boolean overlaps(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, int x, int y, int z) {
        return minX < x + 1 && maxX > x && minY < y + 1 && maxY > y && minZ < z + 1 && maxZ > z;
    }

    // past reach every spot would aim, fail the raytrace and get skipped anyway, so don't scan it
    static int radius(int setting, double reach) {
        return Math.max(0, Math.min(setting, (int) Math.ceil(reach)));
    }
}
