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

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// where the sand goes. a fake world, no minecraft: S is stone (stops a falling block), G is gravel (falls, and stops
// whatever falls on it), D is a stalactite (hangs, and stops things like any block), everything else is air
public class FallingColumnTest {

    private static final class World implements FallingColumn.Cells {
        final Map<Long, Character> cells = new HashMap<>();

        World put(int x, int y, int z, char c) {
            cells.put(key(x, y, z), c);
            return this;
        }

        // a column of the same thing from y0 to y1 inclusive
        World col(int x, int z, int y0, int y1, char c) {
            for (int y = y0; y <= y1; y++) {
                put(x, y, z, c);
            }
            return this;
        }

        static long key(int x, int y, int z) {
            return ((long) x & 0xFFFFF) << 40 | ((long) z & 0xFFFFF) << 20 | ((long) (y + 1000) & 0xFFFFF);
        }

        char at(int x, int y, int z) {
            return cells.getOrDefault(key(x, y, z), ' ');
        }

        @Override
        public boolean falls(int x, int y, int z) {
            return at(x, y, z) == 'G';
        }

        @Override
        public boolean stops(int x, int y, int z) {
            char c = at(x, y, z);
            return c == 'S' || c == 'G' || c == 'D';
        }

        @Override
        public boolean hangs(int x, int y, int z) {
            return at(x, y, z) == 'D';
        }
    }

    // a 2 high tunnel along x at y 65..66 (feet 65, head 66), floor at 64, stone roof at 67 with sand piled on it
    private static World tunnelUnderGravel(int gravel) {
        World w = new World();
        for (int x = -2; x <= 2; x++) {
            w.put(x, 64, 0, 'S');
            w.put(x, 67, 0, 'S');
        }
        w.col(0, 0, 68, 67 + gravel, 'G');
        return w;
    }

    @Test
    public void heightIsTheUnbrokenRunOnTop() {
        World w = tunnelUnderGravel(3);
        assertEquals(3, FallingColumn.heightAbove(w, 0, 67, 0));
        // a gap ends the run, what is above the gap is somebody else's problem
        w.put(0, 69, 0, ' ');
        assertEquals(1, FallingColumn.heightAbove(w, 0, 67, 0));
        // nothing on top of it
        assertEquals(0, FallingColumn.heightAbove(w, 1, 67, 0));
    }

    @Test
    public void aTallStackIsCappedSoTheLoopIsBounded() {
        World w = new World();
        w.col(0, 0, 1, 500, 'G');
        assertEquals(FallingColumn.MAX_HEIGHT, FallingColumn.heightAbove(w, 0, 0, 0));
    }

    @Test
    public void theStackLandsOnTheFloorOfTheOpenShaft() {
        World w = tunnelUnderGravel(2);
        // the roof at 67 goes, the stack falls through 67, 66, 65 and stops on the floor at 64
        assertEquals(65, FallingColumn.landingY(w, 0, 67, 0));
    }

    @Test
    public void aLandingIsNeverBelowSomethingSolid() {
        World w = tunnelUnderGravel(1);
        w.put(0, 65, 0, 'S'); // a step in the way, e.g. a half dug shaft
        assertEquals(66, FallingColumn.landingY(w, 0, 67, 0));
    }

    @Test
    public void aBottomlessShaftIsBoundedToo() {
        World w = new World();
        assertEquals(10 - FallingColumn.MAX_DROP, FallingColumn.landingY(w, 0, 10, 0));
    }

    @Test
    public void breakingTheRoofOverUsDropsTheStackOnUs() {
        World w = tunnelUnderGravel(2);
        // feet 65, head 66: the stack lands in 65..66
        assertTrue(FallingColumn.landsOn(w, 0, 67, 0, 65, 66));
    }

    @Test
    public void breakingTheRoofTwoToTheSideDoesNot() {
        World w = tunnelUnderGravel(2);
        // the same stack, but the roof block we break is one column over from where the stack is. nothing on top of it
        assertFalse(FallingColumn.landsOn(w, 1, 67, 0, 65, 66));
    }

    @Test
    public void aPlayerHalfInTheNextColumnStillGetsIt() {
        World w = tunnelUnderGravel(2);
        // the box runs from x -0.2 to 0.4, so it stands in cell -1 and in cell 0 at the same time
        assertTrue(FallingColumn.landsOnBox(w, 0, 67, 0, -0.2, 65, 0.2, 0.4, 66.8, 0.8));
        // all the way into -1: it is not under the stack at all
        assertFalse(FallingColumn.landsOnBox(w, 0, 67, 0, -0.9, 65, 0.2, -0.3, 66.8, 0.8));
    }

    @Test
    public void standingOnTheTopOfABlockIsTheCellAboveIt() {
        World w = tunnelUnderGravel(1);
        // feet exactly on 65.0, head top at 66.8 (a 1.8 tall player). the cells are 65 and 66
        assertTrue(FallingColumn.landsOnBox(w, 0, 67, 0, 0.2, 65.0, 0.2, 0.8, 66.8, 0.8));
    }

    @Test
    public void aStackThatLandsBelowOurFeetMissesUs() {
        World w = tunnelUnderGravel(2);
        // a pit under the roof column: floor at 60 not 64, and we are up on the ledge at 65..66 beside it. the roof block over
        // the pit column is broken and the stack falls past the ledge level to the bottom
        w.cells.remove(World.key(0, 64, 0));
        w.put(0, 60, 0, 'S');
        assertEquals(61, FallingColumn.landingY(w, 0, 67, 0));
        // we stand in the shaft at 65..66 though, and the stack passes through it on the way
        assertFalse(FallingColumn.landsOn(w, 0, 67, 0, 65, 66));
        // standing at the bottom of it, it ends up on us
        assertTrue(FallingColumn.landsOn(w, 0, 67, 0, 61, 62));
    }

    @Test
    public void aShortStackThatSitsAboveUsIsNotOnUs() {
        // a tall cave: floor at 64, a stone roof at 80 with one gravel on it
        World w = new World();
        w.put(0, 64, 0, 'S');
        w.put(0, 80, 0, 'S');
        w.put(0, 81, 0, 'G');
        // the stack drops 15 blocks and ends on the floor in cell 65. a ledge at 70 is never touched by it
        assertEquals(65, FallingColumn.landingY(w, 0, 80, 0));
        assertFalse(FallingColumn.landsOn(w, 0, 80, 0, 70, 71));
        assertTrue(FallingColumn.landsOn(w, 0, 80, 0, 65, 66));
    }

    @Test
    public void aTallStackReachesUpFromTheLanding() {
        World w = tunnelUnderGravel(3);
        // lands 65, 66, 67: the top one is above the head but the lower two are on us, and landsOn only needs one
        assertTrue(FallingColumn.landsOn(w, 0, 67, 0, 66, 66));
        // somebody standing on a ledge at 68 is above the pile, which is only 3 tall (65..67)
        assertFalse(FallingColumn.landsOn(w, 0, 67, 0, 68, 69));
    }

    @Test
    public void stalactitesUnderTheSupportFallToTheFloor() {
        World w = new World();
        // roof at 70, two stalactites at 69 and 68 hanging off it, floor at 60
        w.put(0, 70, 0, 'S').put(0, 69, 0, 'D').put(0, 68, 0, 'D').put(0, 60, 0, 'S');
        assertEquals(2, FallingColumn.hangingBelow(w, 0, 70, 0));
        // breaking the roof sends them through 67..61. we stand at 61..62
        assertTrue(FallingColumn.hangersFallOn(w, 0, 70, 0, 61, 62));
        // we are up beside the tips, nowhere in the column
        assertFalse(FallingColumn.hangersFallOn(w, 1, 70, 0, 61, 62));
    }

    @Test
    public void aStalagmiteOrNothingUnderTheRoofIsFine() {
        World w = new World();
        w.put(0, 70, 0, 'S').put(0, 60, 0, 'S');
        assertEquals(0, FallingColumn.hangingBelow(w, 0, 70, 0));
        assertFalse(FallingColumn.hangersFallOn(w, 0, 70, 0, 61, 62));
    }

    @Test
    public void aLandingSpotAboveTheStalactiteTipIsSafe() {
        World w = new World();
        w.put(0, 70, 0, 'S').put(0, 69, 0, 'D').put(0, 60, 0, 'S');
        // the stalactite and everything between it and the floor is the danger. 71 is over the roof
        assertFalse(FallingColumn.hangersFallOn(w, 0, 70, 0, 71, 72));
    }

    @Test
    public void theWaitIsLongerForATallerStackAndABiggerDrop() {
        assertEquals(0, FallingColumn.settleTicks(0, 10));
        assertTrue(FallingColumn.settleTicks(2, 3) > FallingColumn.settleTicks(1, 3));
        assertTrue(FallingColumn.settleTicks(1, 10) > FallingColumn.settleTicks(1, 3));
        assertEquals(0, FallingColumn.dropTicks(0));
        // a block drops a block in about 7 ticks with 0.04 gravity and 0.98 drag, it isn't a player
        int one = FallingColumn.dropTicks(1);
        assertTrue("one block took " + one, one >= 5 && one <= 9);
    }
}
