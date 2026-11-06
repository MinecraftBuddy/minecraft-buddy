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

import baritone.api.utils.BetterBlockPos;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class SwimStallsTest {

    private static final BetterBlockPos SRC = new BetterBlockPos(10, 62, -4);
    private static final BetterBlockPos DIAGONAL = new BetterBlockPos(11, 62, -3);
    private static final BetterBlockPos TRAVERSE = new BetterBlockPos(11, 62, -4);
    private static final Object OVERWORLD = new Object();
    private static final Object NETHER = new Object();

    private static double penalty(SwimStalls.Snapshot s, BetterBlockPos from, BetterBlockPos to) {
        return s.penalty(from.x, from.y, from.z, to.x, to.y, to.z);
    }

    @Test
    public void nothingRecordedCostsNothing() {
        SwimStalls stalls = new SwimStalls();
        SwimStalls.Snapshot s = stalls.snapshot(OVERWORLD, 0);
        assertSame(SwimStalls.Snapshot.NONE, s);
        assertEquals(0, penalty(s, SRC, DIAGONAL), 0);
    }

    @Test
    public void aStalledMoveCostsMoreForThirtySecondsThenItsBack() {
        SwimStalls stalls = new SwimStalls();
        stalls.record(SRC, DIAGONAL, OVERWORLD, 1000);
        assertEquals(SwimStalls.PENALTY, penalty(stalls.snapshot(OVERWORLD, 1000), SRC, DIAGONAL), 0);
        assertEquals(SwimStalls.PENALTY, penalty(stalls.snapshot(OVERWORLD, 1000 + 29_999), SRC, DIAGONAL), 0);
        assertEquals(0, penalty(stalls.snapshot(OVERWORLD, 1000 + 30_000), SRC, DIAGONAL), 0);
    }

    @Test
    public void onlyThatMoveAndOnlyThatWay() {
        SwimStalls stalls = new SwimStalls();
        stalls.record(SRC, TRAVERSE, OVERWORLD, 0);
        SwimStalls.Snapshot s = stalls.snapshot(OVERWORLD, 5000);
        assertEquals(SwimStalls.PENALTY, penalty(s, SRC, TRAVERSE), 0);
        // the diagonal next to it, the way back, and the same move one block up are all fine
        assertEquals(0, penalty(s, SRC, DIAGONAL), 0);
        assertEquals(0, penalty(s, TRAVERSE, SRC), 0);
        assertEquals(0, penalty(s, SRC.above(), TRAVERSE.above()), 0);
    }

    @Test
    public void stallingAgainRestartsTheClock() {
        SwimStalls stalls = new SwimStalls();
        stalls.record(SRC, TRAVERSE, OVERWORLD, 0);
        stalls.record(SRC, TRAVERSE, OVERWORLD, 20_000);
        assertEquals(SwimStalls.PENALTY, penalty(stalls.snapshot(OVERWORLD, 40_000), SRC, TRAVERSE), 0);
        assertEquals(0, penalty(stalls.snapshot(OVERWORLD, 50_000), SRC, TRAVERSE), 0);
    }

    @Test
    public void changingDimensionForgetsEverything() {
        SwimStalls stalls = new SwimStalls();
        stalls.record(SRC, DIAGONAL, OVERWORLD, 0);
        assertSame(SwimStalls.Snapshot.NONE, stalls.snapshot(NETHER, 1000));
        // and coming back doesn't bring it back either
        assertSame(SwimStalls.Snapshot.NONE, stalls.snapshot(OVERWORLD, 1000));
    }

    @Test
    public void aSnapshotDoesntChangeUnderTheSearch() {
        SwimStalls stalls = new SwimStalls();
        SwimStalls.Snapshot before = stalls.snapshot(OVERWORLD, 0);
        stalls.record(SRC, DIAGONAL, OVERWORLD, 0);
        assertEquals(0, penalty(before, SRC, DIAGONAL), 0);
    }
}
