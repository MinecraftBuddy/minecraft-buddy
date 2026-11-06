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

package baritone.pathing.movement;

import baritone.pathing.movement.LavaWake.Cell;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LavaWakeTest {

    // solid rock everywhere except what a test digs out
    private static final class World implements LavaWake.Cells {
        final Map<List<Integer>, Cell> cells = new HashMap<>();

        World set(Cell cell, int x, int y, int z) {
            cells.put(Arrays.asList(x, y, z), cell);
            return this;
        }

        @Override
        public Cell at(int x, int y, int z) {
            return cells.getOrDefault(Arrays.asList(x, y, z), Cell.OTHER);
        }
    }

    // a block going down right on top of a source that has air off to one side
    @Test
    public void aBlockOnTopOfAnExposedSourceWakesIt() {
        World w = new World().set(Cell.SOURCE, 10, 40, -3).set(Cell.AIR, 10, 40, -2).set(Cell.AIR, 10, 41, -3);
        assertTrue(LavaWake.wakes(w, 10, 41, -3));
    }

    @Test
    public void airUnderTheSourceCountsToo() {
        World w = new World().set(Cell.SOURCE, 10, 40, -3).set(Cell.AIR, 10, 39, -3);
        assertTrue(LavaWake.wakes(w, 10, 41, -3));
    }

    @Test
    public void everyNeighbourOfTheCellCounts() {
        int[][] around = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}};
        for (int[] d : around) {
            int x = d[0], y = 50 + d[1], z = d[2];
            // air on all its open sides, one of them will be the cell we're filling and that one doesn't count
            World w = new World().set(Cell.SOURCE, x, y, z).set(Cell.AIR, x + 1, y, z).set(Cell.AIR, x - 1, y, z)
                    .set(Cell.AIR, x, y, z + 1).set(Cell.AIR, x, y, z - 1).set(Cell.AIR, x, y - 1, z);
            assertTrue(Arrays.toString(d), LavaWake.wakes(w, 0, 50, 0));
        }
    }

    // the middle of a lake: sources all round, nothing to flow into, the only air is above it and lava doesn't climb
    @Test
    public void aBridgeOverALakeIsFine() {
        World w = new World();
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                w.set(Cell.SOURCE, x, 30, z).set(Cell.AIR, x, 31, z).set(Cell.AIR, x, 32, z);
            }
        }
        assertFalse(LavaWake.wakes(w, 0, 31, 0));
    }

    @Test
    public void aSourceWithRockOnEverySideIsFine() {
        World w = new World().set(Cell.SOURCE, 5, 20, 5).set(Cell.AIR, 5, 21, 5);
        assertFalse(LavaWake.wakes(w, 5, 21, 5));
    }

    // the air next to the source is the very cell we're filling, after the click it has nowhere to go
    @Test
    public void theCellWeAreFillingDoesNotCountAsAnOutlet() {
        World w = new World().set(Cell.SOURCE, 1, 20, 0).set(Cell.AIR, 0, 20, 0);
        assertFalse(LavaWake.wakes(w, 0, 20, 0));
        // and a second gap on the far side still does
        w.set(Cell.AIR, 2, 20, 0);
        assertTrue(LavaWake.wakes(w, 0, 20, 0));
    }

    // already moving, a place can't wake what's awake. what flows from it is the walking rules' problem
    @Test
    public void flowingLavaIsNotASource() {
        World w = new World().set(Cell.FLOW, 1, 20, 0).set(Cell.AIR, 2, 20, 0).set(Cell.AIR, 0, 20, 0);
        assertFalse(LavaWake.wakes(w, 0, 20, 0));
    }

    @Test
    public void aFarAwaySourceIsFine() {
        World w = new World().set(Cell.SOURCE, 2, 20, 0).set(Cell.AIR, 3, 20, 0).set(Cell.AIR, 0, 20, 0);
        assertFalse(LavaWake.wakes(w, 0, 20, 0));
    }

    @Test
    public void lavaBesideTheLandingFeetOrHeadCounts() {
        assertTrue(LavaWake.besideLanding(new World().set(Cell.SOURCE, 8, 12, 4), 7, 12, 4));
        assertTrue(LavaWake.besideLanding(new World().set(Cell.FLOW, 6, 12, 4), 7, 12, 4));
        assertTrue(LavaWake.besideLanding(new World().set(Cell.FLOW, 7, 13, 5), 7, 12, 4));
        assertTrue(LavaWake.besideLanding(new World().set(Cell.SOURCE, 7, 13, 3), 7, 12, 4));
    }

    @Test
    public void lavaElsewhereAroundTheLandingDoesNotCount() {
        // two over, a corner, under the floor beside us, and over our head
        assertFalse(LavaWake.besideLanding(new World().set(Cell.SOURCE, 9, 12, 4), 7, 12, 4));
        assertFalse(LavaWake.besideLanding(new World().set(Cell.SOURCE, 8, 12, 5), 7, 12, 4));
        assertFalse(LavaWake.besideLanding(new World().set(Cell.SOURCE, 8, 11, 4), 7, 12, 4));
        assertFalse(LavaWake.besideLanding(new World().set(Cell.SOURCE, 8, 14, 4), 7, 12, 4));
    }
}
