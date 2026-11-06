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

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class BridgeBudgetTest {

    @Test
    public void bandsEdges() {
        assertEquals(1, BridgeBudget.placeCostScale(64, true));
        assertEquals(1, BridgeBudget.placeCostScale(500, true));
        assertEquals(2, BridgeBudget.placeCostScale(63, true));
        assertEquals(2, BridgeBudget.placeCostScale(32, true));
        assertEquals(4, BridgeBudget.placeCostScale(31, true));
        assertEquals(4, BridgeBudget.placeCostScale(16, true));
        assertEquals(8, BridgeBudget.placeCostScale(15, true));
        assertEquals(8, BridgeBudget.placeCostScale(0, true));
    }

    @Test
    public void overworldNeverScales() {
        assertEquals(1, BridgeBudget.placeCostScale(0, false));
        assertEquals(1, BridgeBudget.placeCostScale(20, false));
    }

    @Test
    public void zeroAndInfinityStayPut() {
        // the lava escape zeroes the penalty, a scale must not bring it back
        assertEquals(0.0, 0.0 * BridgeBudget.placeCostScale(0, true), 0);
        assertEquals(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY * BridgeBudget.placeCostScale(0, true), 0);
    }
}
