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

package adris.altoclef.util.helpers;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CreeperWayTest {
    // the run ended 24 blocks east of the creeper at the origin
    private static final double PX = 24, PZ = 0;

    @Test
    public void theLogByTheCreeperIsOnTheWay() {
        // the oak log 4 blocks past the creeper, the way back to it goes straight by it
        assertTrue(CreeperWay.onTheWay(PX, PZ, 0, 0, -4, 1));
        // a log right beside the creeper but behind it as seen from here, within NEAR
        assertTrue(CreeperWay.onTheWay(PX, PZ, 0, 0, 2, 5));
    }

    @Test
    public void aLogWhoseWayPassesTheCreeperIsOnTheWay() {
        // 30 blocks west, the straight way passes the creeper at 2 blocks
        assertTrue(CreeperWay.onTheWay(PX, PZ, 0, 0, -30, -5));
    }

    @Test
    public void aLogOffTheWayIsNot() {
        // north of here, the way never comes near the creeper
        assertFalse(CreeperWay.onTheWay(PX, PZ, 0, 0, 24, 20));
        // between us and the creeper but 8 to the side
        assertFalse(CreeperWay.onTheWay(PX, PZ, 0, 0, 12, 8));
        // behind us: the creeper is not between
        assertFalse(CreeperWay.onTheWay(PX, PZ, 0, 0, 40, 0));
    }

    @Test
    public void aLogShortOfTheCreeperIsNot() {
        // straight towards the creeper but 10 blocks short of it, more than NEAR away
        assertFalse(CreeperWay.onTheWay(PX, PZ, 0, 0, 10, 0));
    }

    @Test
    public void onlyWhatATaskGathers() {
        assertTrue(CreeperWay.gathered("oak_log"));
        assertTrue(CreeperWay.gathered("crimson_stem"));
        assertTrue(CreeperWay.gathered("deepslate_iron_ore"));
        assertTrue(CreeperWay.gathered("ancient_debris"));
        assertFalse(CreeperWay.gathered("crafting_table"));
        assertFalse(CreeperWay.gathered("obsidian"));
        assertFalse(CreeperWay.gathered("chest"));
    }
}
