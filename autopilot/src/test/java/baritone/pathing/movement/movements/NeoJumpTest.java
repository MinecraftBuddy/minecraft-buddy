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

package baritone.pathing.movement.movements;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static org.junit.Assert.*;

public class NeoJumpTest {

    // which neos a run up can actually make. if the sim changes and this table changes, go look at why before updating
    // it, these were all worked out by hand first
    @Test
    public void standingStarts() {
        assertNotNull(NeoJump.staging(2, 0b1, 1, true));
        assertNotNull(NeoJump.staging(3, 0b01, 1, true));
        assertNotNull(NeoJump.staging(3, 0b10, 1, true));
        assertNotNull(NeoJump.staging(2, 0b1, 3, true));
        assertNotNull(NeoJump.staging(3, 0b01, 2, false));
        // two thick (the classic 3b neo) needs a full speed run along the very edge, and one block of runway isn't
        // enough to get up to speed
        assertNull(NeoJump.staging(3, 0b11, 1, true));
        assertNotNull(NeoJump.staging(3, 0b11, 2, true));
        assertNotNull(NeoJump.staging(3, 0b11, 2, false));
        // not neos at all
        assertNull(NeoJump.staging(3, 0, 1, true));
        // dist 4 is a real shape now (MAX_DIST), this one just needs more run up than a single block gives
        assertNull(NeoJump.staging(4, 0b1, 1, true));
        assertNull(NeoJump.staging(2, 0b1, 0, true));
    }

    @Test
    public void stagingSpotFlies() {
        NeoJump.Staging st = NeoJump.staging(2, 0b1, 1, true);
        assertNotNull(st);
        // behind the takeoff block, off to the open side, and from a standstill it has to run first
        assertTrue(st.u() > -1.5 && st.u() < -0.5);
        assertTrue(st.v() > 0 && st.v() < 0.8);
        assertTrue(st.runup() > 0);
    }

    @Test
    public void threeBlockNeoLands() {
        // what MovementNeo will actually do, start to finish, has to clear by at least what A* asked for
        NeoJump.Staging st = NeoJump.staging(3, 0b11, 2, true);
        NeoJump neo = new NeoJump(3, 0b11, 2, true, NeoJump.SPRINT_GROUND);
        assertTrue(neo.rollout(st.u(), st.v(), st.runTarget(), st.takeoff()) >= NeoJump.PLAN_MARGIN);
    }

    @Test
    public void hopBeatsPlainOnTheClassicNeo() {
        // a runway long enough to hop on: land from a hop, jump the very next tick, and the whole flight is faster. the
        // 3b neo goes from ~0.038 of room to ~0.049
        NeoJump neo = new NeoJump(3, 0b11, 5, true, NeoJump.SPRINT_GROUND);
        double[] spot = neo.pick();
        assertEquals(1, spot[5], 0);
        assertTrue(neo.hopMargin > neo.plainMargin);
        assertTrue(neo.hopMargin >= NeoJump.PLAN_MARGIN);
        NeoJump.Staging st = NeoJump.staging(3, 0b11, 5, true);
        assertNotNull(st);
        assertTrue(st.hop());
        assertEquals(neo.hopMargin, neo.rolloutHop(st.u(), st.v(), st.runTarget(), st.takeoff()), 0.02);
        // there's no room for a hop on the short runways, those stay plain
        assertFalse(NeoJump.staging(3, 0b11, 2, true).hop());
    }

    @Test
    public void hopComesDownOnTheRunway() {
        // the neo takeoff after a hop is on the takeoff block, and the plan from there has to clear what A* asked for
        NeoJump.Staging st = NeoJump.staging(3, 0b11, 6, false);
        assertNotNull(st);
        assertTrue(st.hop());
        assertTrue(st.takeoff() > -0.5 - 0.3 && st.takeoff() < 0.5);
        NeoJump neo = new NeoJump(3, 0b11, 6, false, NeoJump.SPRINT_GROUND);
        assertTrue(neo.rolloutHop(st.u(), st.v(), st.runTarget(), st.takeoff()) >= NeoJump.PLAN_MARGIN);
    }

    @Test
    public void distFour() {
        // one block in the middle is plain sprint jumping around it, no hop needed
        NeoJump.Staging plain = NeoJump.staging(4, 0b10, 1, true);
        assertNotNull(plain);
        assertFalse(plain.hop());
        // these only exist with hop speed, and need the runway to get up to it
        assertNull(NeoJump.staging(4, 0b100, 3, true));
        assertNotNull(NeoJump.staging(4, 0b100, 4, true));
        assertTrue(NeoJump.staging(4, 0b100, 4, true).hop());
        assertNotNull(NeoJump.staging(4, 0b110, 4, true));
        assertNull(NeoJump.staging(4, 0b1, 4, true));
        assertNotNull(NeoJump.staging(4, 0b1, 5, true));
        assertNotNull(NeoJump.staging(4, 0b11, 5, true));
        // not even a hop gets around these
        assertNull(NeoJump.staging(4, 0b101, 6, true));
        assertNull(NeoJump.staging(4, 0b111, 6, true));
    }

    // one of everything a table holds: a plain run up, a hop, and a shape nobody can make
    private static AtomicReferenceArray<double[]> sample() {
        AtomicReferenceArray<double[]> table = NeoJump.newTable();
        table.set(NeoJump.key(2, 0b1, 1, true), new NeoJump(2, 0b1, 1, true, NeoJump.SPRINT_GROUND).pick());
        table.set(NeoJump.key(3, 0b11, 5, true), new NeoJump(3, 0b11, 5, true, NeoJump.SPRINT_GROUND).pick());
        table.set(NeoJump.key(4, 0b1, 1, true), new NeoJump(4, 0b1, 1, true, NeoJump.SPRINT_GROUND).pick());
        return table;
    }

    @Test
    public void everyShapeIsInTheWarmup() {
        // 1 + 3 + 7 wall patterns, six runways, open beyond or not
        List<NeoJump.Job> jobs = NeoJump.jobs();
        assertEquals(11 * 6 * 2, jobs.size());
        assertEquals(jobs.size(), jobs.stream().map(NeoJump.Job::key).distinct().count());
        for (NeoJump.Job job : jobs) {
            assertEquals(job.key(), NeoJump.key(job.dist(), job.walls(), job.runway(), job.openBeyond()));
            assertTrue(job.key() >= 0);
        }
        // dist 2 and 3 with the short runways go first, they're what you actually run into
        assertTrue(jobs.get(0).dist() == 2 && jobs.get(0).runway() == 1);
        assertEquals(4, jobs.get(jobs.size() - 1).dist());
    }

    @Test
    public void tableRoundTripsThroughAFile() throws IOException {
        Path dir = Files.createTempDirectory("neo-table");
        Path file = dir.resolve("sub").resolve("neo-table.txt");
        AtomicReferenceArray<double[]> table = sample();
        assertTrue(NeoJump.save(file, table));
        assertTrue(Files.exists(file));
        assertFalse(Files.exists(dir.resolve("sub").resolve("neo-table.txt.tmp")));

        AtomicReferenceArray<double[]> loaded = NeoJump.newTable();
        assertEquals(3, NeoJump.load(file, loaded));
        int filled = 0;
        for (NeoJump.Job job : NeoJump.jobs()) {
            double[] was = table.get(job.key()), now = loaded.get(job.key());
            assertEquals(was == null, now == null);
            if (was != null) {
                filled++;
                assertEquals(NeoJump.unpack(was), NeoJump.unpack(now));
            }
        }
        assertEquals(3, filled);
        // the nobody-can-make-it answer has to come back as that, and not as a shape nobody has asked about
        assertNull(NeoJump.unpack(loaded.get(NeoJump.key(4, 0b1, 1, true))));
        assertNotNull(loaded.get(NeoJump.key(4, 0b1, 1, true)));
        assertTrue(NeoJump.unpack(loaded.get(NeoJump.key(3, 0b11, 5, true))).hop());
        // loading doesn't stomp on what's already there
        assertEquals(0, NeoJump.load(file, loaded));
    }

    @Test
    public void staleOrBrokenFilesAreIgnored() throws IOException {
        Path dir = Files.createTempDirectory("neo-table");
        Path file = dir.resolve("neo-table.txt");
        assertTrue(NeoJump.save(file, sample()));
        List<String> lines = Files.readAllLines(file);

        // a table from before somebody touched the physics
        List<String> stale = new ArrayList<>(lines);
        stale.set(0, NeoJump.header(NeoJump.TABLE_VERSION - 1));
        Files.write(file, stale);
        AtomicReferenceArray<double[]> table = NeoJump.newTable();
        assertEquals(0, NeoJump.load(file, table));
        assertEquals(0, filled(table));

        // same version, different limits
        List<String> other = new ArrayList<>(lines);
        other.set(0, lines.get(0).replace("maxRunway=" + NeoJump.MAX_RUNWAY, "maxRunway=" + (NeoJump.MAX_RUNWAY + 1)));
        Files.write(file, other);
        assertEquals(0, NeoJump.load(file, table));

        // a good header and then a line that's off partway through: none of it counts
        List<String> broken = new ArrayList<>(lines);
        broken.set(broken.size() - 1, "4 1 1 1 0.5 banana");
        Files.write(file, broken);
        assertEquals(0, NeoJump.load(file, table));
        assertEquals(0, filled(table));

        // a shape that isn't one
        broken.set(broken.size() - 1, "9 1 1 1 none");
        Files.write(file, broken);
        assertEquals(0, NeoJump.load(file, table));

        Files.write(file, new byte[]{(byte) 0xff, (byte) 0xfe, 0, 1});
        assertEquals(0, NeoJump.load(file, table));
        Files.write(file, new byte[0]);
        assertEquals(0, NeoJump.load(file, table));
        assertEquals(0, NeoJump.load(dir.resolve("nope.txt"), table));
        // and the original is fine, so it was the edits
        Files.write(file, lines);
        assertEquals(3, NeoJump.load(file, table));
    }

    private static int filled(AtomicReferenceArray<double[]> table) {
        int n = 0;
        for (int i = 0; i < table.length(); i++) {
            if (table.get(i) != null) {
                n++;
            }
        }
        return n;
    }
}
