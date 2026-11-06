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

import static baritone.process.ExploreProcess.Status.EXPLORED;
import static baritone.process.ExploreProcess.Status.NOT_EXPLORED;
import static baritone.process.ExploreProcess.Status.UNKNOWN;
import static baritone.process.ExploreProcess.decide;
import static org.junit.Assert.assertEquals;

public class ExploreChunkStatusTest {

    @Test
    public void loadedButNeverCachedCountsAsExplored() {
        // the packer queue dropped it, but we are standing in it
        assertEquals(EXPLORED, decide(true, false, true));
        assertEquals(EXPLORED, decide(true, false, false));
    }

    @Test
    public void cachedIsExplored() {
        assertEquals(EXPLORED, decide(false, true, true));
    }

    @Test
    public void notLoadedAndNoRegionIsUnknown() {
        assertEquals(UNKNOWN, decide(false, false, false));
    }

    @Test
    public void notLoadedWithRegionAndNotCachedIsNotExplored() {
        assertEquals(NOT_EXPLORED, decide(false, false, true));
    }
}
