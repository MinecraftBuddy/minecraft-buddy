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

import java.util.Arrays;

// axis aligned boxes as a flat array, 6 doubles each (minX minY minZ maxX maxY maxZ). the sim asks for collisions a few
// hundred times per decision and a List<AABB> per question is a lot of garbage for a thing that gets thrown away
public final class BoxList {

    double[] data = new double[96];
    private int size;

    public void clear() {
        size = 0;
    }

    public int size() {
        return size;
    }

    public void add(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        int at = size * 6;
        if (at + 6 > data.length) {
            data = Arrays.copyOf(data, data.length * 2);
        }
        data[at] = minX;
        data[at + 1] = minY;
        data[at + 2] = minZ;
        data[at + 3] = maxX;
        data[at + 4] = maxY;
        data[at + 5] = maxZ;
        size++;
    }

    // 0..2 is the min corner, 3..5 the max corner
    public double get(int box, int component) {
        return data[box * 6 + component];
    }
}
