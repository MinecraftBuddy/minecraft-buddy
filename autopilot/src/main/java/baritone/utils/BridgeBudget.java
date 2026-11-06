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

package baritone.utils;

public final class BridgeBudget {

    private BridgeBudget() {
    }

    // the planner only knows yes or no on the bag, so a bridge it can't finish looks as cheap as one it can. over lava that
    // is a dead end with no blocks left to walk back on. a dearer block makes it go round when there is a way round, and an
    // island still gets its bridge because nothing else is cheaper than it
    public static int placeCostScale(int spare, boolean nether) {
        if (!nether || spare >= 64) {
            return 1;
        }
        if (spare >= 32) {
            return 2;
        }
        return spare >= 16 ? 4 : 8;
    }
}
