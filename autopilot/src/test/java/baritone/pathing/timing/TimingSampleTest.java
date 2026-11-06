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

package baritone.pathing.timing;

import baritone.pathing.timing.TimingSample.Floor;
import org.junit.Test;

import static org.junit.Assert.*;

public class TimingSampleTest {

    static TimingSample sample(String kind, int dx, int dy, int dz, float planned, int actual, int breaks, boolean water, boolean sprint) {
        return new TimingSample(kind, dx, dy, dz, planned, actual, planned, breaks, 0, sprint, water, false, Floor.PLAIN, false);
    }

    @Test
    public void csvRoundTrips() {
        TimingSample in = new TimingSample("Parkour", 3, -1, 0, 12.25f, 17, -1f, 2, 1, true, false, true, Floor.SOUL_SAND, true);
        assertEquals("Parkour,3,-1,0,12.25,17,-1,2,1,1,0,1,SOUL_SAND,1", in.toString());
        TimingSample out = TimingSample.parse(in.toString());
        assertNotNull(out);
        assertEquals("Parkour", out.kind);
        assertEquals(3, out.dx);
        assertEquals(-1, out.dy);
        assertEquals(0, out.dz);
        assertEquals(12.25f, out.planned, 0);
        assertEquals(17, out.actual);
        assertEquals(-1f, out.recost, 0);
        assertEquals(2, out.breaks);
        assertEquals(1, out.places);
        assertTrue(out.sprint);
        assertFalse(out.water);
        assertTrue(out.still);
        assertEquals(Floor.SOUL_SAND, out.floor);
        assertTrue(out.fast);
        assertEquals(in.toString(), out.toString());
    }

    @Test
    public void decimalsAreTwoPlacesWithLeadingZero() {
        TimingSample s = new TimingSample("Traverse", 1, 0, 0, 4.6f, 5, 4.05f, 0, 0, false, false, false, Floor.PLAIN, false);
        assertTrue(s.toString(), s.toString().startsWith("Traverse,1,0,0,4.60,5,4.05,"));
    }

    @Test
    public void headerAndGarbageAreSkipped() {
        assertNull(TimingSample.parse(TimingSample.HEADER));
        assertNull(TimingSample.parse(""));
        assertNull(TimingSample.parse(null));
        assertNull(TimingSample.parse("hello"));
        assertNull(TimingSample.parse("Traverse,1,0,0,4.60,five,4.05,0,0,0,0,0,PLAIN,0"));
        assertNull(TimingSample.parse("Traverse,1,0,0,4.60,5,4.05,0,0,0,0,0,CARPET,0"));
        assertNull(TimingSample.parse("Traverse,1,0,0,4.60,5,4.05,0,0,2,0,0,PLAIN,0"));
        assertNull(TimingSample.parse("Traverse,1,0,0,4.60,-5,4.05,0,0,0,0,0,PLAIN,0"));
        assertNull(TimingSample.parse("Traverse,1,0,0,4.60,999999,4.05,0,0,0,0,0,PLAIN,0"));
        assertNull(TimingSample.parse("Traverse,1,0,0,NaN,5,4.05,0,0,0,0,0,PLAIN,0"));
        assertNull(TimingSample.parse("Traverse,1,0,0,4.60,5,4.05,0,0,0,0,0,PLAIN,0,extra"));
    }

    @Test
    public void everyTruncationOfAGoodLineIsRejected() {
        String good = sample("Traverse", 1, 0, 0, 4.6f, 5, 0, false, false).toString();
        // the file is append only and a crash can cut a line at any byte, a cut line must never read as a sample
        for (int i = 0; i < good.length(); i++) {
            assertNull("cut at " + i + ": " + good.substring(0, i), TimingSample.parse(good.substring(0, i)));
        }
        assertNotNull(TimingSample.parse(good));
    }
}
