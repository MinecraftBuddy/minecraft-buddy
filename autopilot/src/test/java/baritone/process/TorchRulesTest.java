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

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TorchRulesTest {

    @Test
    public void darkNeedsBothLights() {
        assertTrue(TorchRules.dark(0, 0, 7));
        assertTrue(TorchRules.dark(7, 7, 7));
        assertFalse(TorchRules.dark(8, 0, 7));
        // the surface at night: no block light, full raw sky light. no torch
        assertFalse(TorchRules.dark(0, 15, 7));
        assertFalse(TorchRules.dark(0, 8, 7));
    }

    @Test
    public void levelIsClamped() {
        assertEquals(0, TorchRules.clampLevel(-3));
        assertEquals(15, TorchRules.clampLevel(99));
        assertEquals(7, TorchRules.clampLevel(7));
        assertTrue(TorchRules.dark(0, 0, -5));
        assertFalse(TorchRules.dark(1, 0, -5));
        assertTrue(TorchRules.dark(15, 15, 40));
    }

    @Test
    public void boxOverlapIsStrict() {
        // a player box (0.6 wide, 1.8 tall) standing in the middle of 10,64,10
        double minX = 10.2, minY = 64, minZ = 10.2, maxX = 10.8, maxY = 65.8, maxZ = 10.8;
        assertTrue(TorchRules.overlaps(minX, minY, minZ, maxX, maxY, maxZ, 10, 64, 10));
        assertTrue(TorchRules.overlaps(minX, minY, minZ, maxX, maxY, maxZ, 10, 65, 10));
        assertFalse(TorchRules.overlaps(minX, minY, minZ, maxX, maxY, maxZ, 11, 64, 10));
        assertFalse(TorchRules.overlaps(minX, minY, minZ, maxX, maxY, maxZ, 10, 63, 10));
        assertFalse(TorchRules.overlaps(minX, minY, minZ, maxX, maxY, maxZ, 10, 66, 10));
        // straddling the line between two columns: both are ours
        assertTrue(TorchRules.overlaps(10.7, 64, 10.2, 11.3, 65.8, 10.8, 10, 64, 10));
        assertTrue(TorchRules.overlaps(10.7, 64, 10.2, 11.3, 65.8, 10.8, 11, 64, 10));
        // just touching the face isn't in it
        assertFalse(TorchRules.overlaps(10.4, 64, 10.2, 11.0, 65.8, 10.8, 11, 64, 10));
        // negative coords, the int is the block's low corner
        assertTrue(TorchRules.overlaps(-0.8, 64, -0.8, -0.2, 65.8, -0.2, -1, 64, -1));
        assertFalse(TorchRules.overlaps(-0.8, 64, -0.8, -0.2, 65.8, -0.2, 0, 64, 0));
    }

    @Test
    public void radiusStopsAtReach() {
        assertEquals(4, TorchRules.radius(4, 4.5));
        assertEquals(5, TorchRules.radius(10, 4.5));
        assertEquals(3, TorchRules.radius(3, 4.5));
        assertEquals(0, TorchRules.radius(-2, 4.5));
    }
}
