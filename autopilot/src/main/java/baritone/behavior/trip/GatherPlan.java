/*
 * This file is part of Soprano.
 *
 * Soprano is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Soprano is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Soprano.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.behavior.trip;

// what a what-if path wants that the bag doesn't have. blocks is the shortfall, not the placement count
public record GatherPlan(Trip.Ask kind, int placements, int blocks, boolean pickaxe) {

    public boolean isEmpty() {
        return blocks <= 0 && !pickaxe;
    }

    // "about 23 blocks and a stone pickaxe". what the question and the progress lines say we're going for
    public String describe() {
        String b = blocks > 0 ? "about " + blocks + (blocks == 1 ? " block" : " blocks") : null;
        String p = pickaxe ? "a stone pickaxe" : null;
        if (b != null && p != null) {
            return b + " and " + p;
        }
        return b != null ? b : p != null ? p : "nothing";
    }
}
