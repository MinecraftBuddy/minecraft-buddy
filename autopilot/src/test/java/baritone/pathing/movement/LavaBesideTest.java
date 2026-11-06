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

import baritone.pathing.movement.CliffAvoidance.Cell;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LavaBesideTest {

    // everything is open air except the lava cells we list
    private static CliffAvoidance.Cells world(Set<List<Integer>> lava) {
        return (x, y, z) -> lava.contains(Arrays.asList(x, y, z)) ? Cell.LAVA : Cell.OPEN;
    }

    private static Set<List<Integer>> lava(int... xyz) {
        Set<List<Integer>> set = new HashSet<>();
        for (int i = 0; i < xyz.length; i += 3) {
            set.add(Arrays.asList(xyz[i], xyz[i + 1], xyz[i + 2]));
        }
        return set;
    }

    // heading +x from 0,0,0, so the sides are z +1 and z -1
    @Test
    public void lavaOnTheLeftCounts() {
        assertTrue(CliffAvoidance.lavaBeside(world(lava(0, 0, 1)), 0, 0, 0, 1, 0));
    }

    @Test
    public void lavaOnTheRightCounts() {
        assertTrue(CliffAvoidance.lavaBeside(world(lava(0, 0, -1)), 0, 0, 0, 1, 0));
    }

    @Test
    public void lavaUnderTheSideCellCounts() {
        assertTrue(CliffAvoidance.lavaBeside(world(lava(0, -1, 1)), 0, 0, 0, 1, 0));
    }

    @Test
    public void noLavaIsFine() {
        assertFalse(CliffAvoidance.lavaBeside(world(lava()), 0, 0, 0, 1, 0));
    }

    @Test
    public void aPlainDropBesideUsDoesNotCount() {
        CliffAvoidance.Cells drop = (x, y, z) -> Cell.OPEN;
        assertFalse(CliffAvoidance.lavaBeside(drop, 0, 0, 0, 1, 0));
    }

    @Test
    public void lavaAheadOrBehindIsNotBeside() {
        assertFalse(CliffAvoidance.lavaBeside(world(lava(1, 0, 0, -1, 0, 0)), 0, 0, 0, 1, 0));
    }

    @Test
    public void sidesFollowTheHeading() {
        // heading +z, the sides are x
        assertTrue(CliffAvoidance.lavaBeside(world(lava(1, 0, 0)), 0, 0, 0, 0, 1));
        assertFalse(CliffAvoidance.lavaBeside(world(lava(0, 0, 1)), 0, 0, 0, 0, 1));
    }

    @Test
    public void traverseCountsEitherEnd() {
        assertTrue(CliffAvoidance.traverseByLava(world(lava(1, 0, 1)), 0, 0, 0, 1, 0));
        assertTrue(CliffAvoidance.traverseByLava(world(lava(0, 0, -1)), 0, 0, 0, 1, 0));
        assertFalse(CliffAvoidance.traverseByLava(world(lava(5, 0, 5)), 0, 0, 0, 1, 0));
    }

    @Test
    public void diagonalSeesLavaAroundEitherEnd() {
        assertTrue(CliffAvoidance.diagonalByLava(world(lava(-1, -1, 0)), 0, 0, 0, 1, 0, 1));
        assertTrue(CliffAvoidance.diagonalByLava(world(lava(2, 0, 2)), 0, 0, 0, 1, 0, 1));
        assertFalse(CliffAvoidance.diagonalByLava(world(lava(4, 0, 4)), 0, 0, 0, 1, 0, 1));
    }

    @Test
    public void diagonalChecksTheDestAtItsOwnHeight() {
        // descending diagonal (0,64,0) to (1,63,1), lava beside the dest at its feet-1
        assertTrue(CliffAvoidance.diagonalByLava(world(lava(2, 62, 1)), 0, 64, 0, 1, 63, 1));
        assertFalse(CliffAvoidance.diagonalByLava(world(lava(2, 62, 1)), 0, 64, 0, 1, 64, 1));
    }
}
