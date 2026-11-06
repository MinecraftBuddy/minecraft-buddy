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

public class MomentumJumpTest {

    private static MomentumJump shape(int runway, int dist, int pad, int dy) {
        return new MomentumJump(runway, dist, pad, dy, true, MomentumJump.SPRINT_GROUND);
    }

    @Test
    public void flatFiveNeedsAHop() {
        // a full speed run up lands 0.12 short of a 4 block gap. the hop's landing speed is what gets it there
        MomentumJump far = shape(6, 5, 0, 0);
        far.pick();
        assertTrue(Double.isNaN(far.plainMargin) || far.plainMargin < MomentumJump.PLAN_MARGIN);
        assertTrue(far.hopMargin >= MomentumJump.PLAN_MARGIN);
        assertTrue(far.picked.hop());
        // from a standstill, or with too little runway to hop on, there's nothing
        assertNull(MomentumJump.staging(0, 5, 0, 0, true));
        assertNull(MomentumJump.staging(2, 5, 0, 0, true));
        assertNotNull(MomentumJump.staging(3, 5, 0, 0, true));
        // and it's the same story one block further, nobody makes six
        assertNull(MomentumJump.staging(6, 6, 0, 0, true));
    }

    @Test
    public void rolloutIsWhatTheTableSays() {
        // what MovementMomentum will do from the staging spot, start to finish, has to clear what A* asked for
        MomentumJump.Staging st = MomentumJump.staging(4, 5, 0, 0, true);
        assertNotNull(st);
        assertTrue(st.hop());
        MomentumJump jump = shape(4, 5, 0, 0);
        double margin = jump.rolloutHop(st.u(), st.takeoff());
        assertTrue(margin >= MomentumJump.PLAN_MARGIN);
        assertEquals(st.margin(), margin, 1e-9);
        // a bit of run up along the way, and nowhere near an unbounded flight
        assertTrue(st.runup() > 0 && st.ticks() > 20 && st.ticks() < 60);
    }

    @Test
    public void oneUpAtFourNeedsMomentumToo() {
        assertNull(MomentumJump.staging(0, 4, 0, 1, true));
        assertNull(MomentumJump.staging(2, 4, 0, 1, true));
        assertTrue(MomentumJump.staging(3, 4, 0, 1, true).hop());
        // parkour already does these, and the sim agrees they're easy
        assertNotNull(MomentumJump.staging(0, 3, 0, 1, true));
    }

    @Test
    public void descendingJumpsLand() {
        // parkour never goes down. a jump from a standstill (runway 0) gets there, no momentum needed
        for (int dy = -3; dy <= -1; dy++) {
            for (int dist = 2; dist <= 5; dist++) {
                MomentumJump.Staging st = MomentumJump.staging(0, dist, 0, dy, true);
                assertNotNull("dy " + dy + " dist " + dist, st);
                assertFalse(st.hop());
            }
        }
        // falling longer buys distance
        assertNull(MomentumJump.staging(0, 6, 0, -1, true));
        assertNull(MomentumJump.staging(0, 6, 0, -2, true));
        assertNotNull(MomentumJump.staging(0, 6, 0, -3, true));
        assertTrue(MomentumJump.staging(3, 6, 0, -2, true).hop());
        assertNull(MomentumJump.staging(6, 7, 0, -3, true));
    }

    @Test
    public void padChainNeedsLandThenJump() {
        // flat, a pad two out and the landing seven out. the second jump is 5 flat, which from a standstill is impossible,
        // and the only reason it works here is that we jump the very tick we touch down with the speed of the first
        assertNull(MomentumJump.staging(0, 5, 0, 0, true));
        MomentumJump.Staging chain = MomentumJump.staging(0, 7, 2, 0, true);
        assertNotNull(chain);
        assertFalse(chain.hop());
        MomentumJump jump = shape(0, 7, 2, 0);
        assertEquals(chain.margin(), jump.rollout(chain.u(), chain.takeoff()), 1e-9);
        // slower than one jump, there's two of them
        assertTrue(chain.ticks() > MomentumJump.staging(0, 4, 0, 0, true).ticks());
        // without the landing in the middle it's a seven block jump, which nobody makes
        assertNull(MomentumJump.staging(6, 7, 0, 0, true));
        // one up, 4 on the second jump: also a chain only thing
        assertNotNull(MomentumJump.staging(0, 6, 2, 1, true));
        assertNull(MomentumJump.staging(0, 4, 0, 1, true));
    }

    @Test
    public void everyChainJumpIsOneNobodyCouldDoStanding() {
        // standingReach is what emits() leans on, so it has to be what the sim says
        for (int dy = MomentumJump.MIN_DY; dy <= MomentumJump.MAX_DY; dy++) {
            int reach = MomentumJump.standingReach(dy);
            MomentumJump at = shape(0, reach, 0, dy);
            at.pick();
            assertNotNull("dy " + dy + " reach " + reach, at.picked);
            MomentumJump past = shape(0, reach + 1, 0, dy);
            past.pick();
            assertNull("dy " + dy + " past reach", past.picked);
        }
    }

    @Test
    public void parkourDoesTheRest() {
        assertTrue(MomentumJump.parkourCovers(4, 0));
        assertTrue(MomentumJump.parkourCovers(2, 0));
        assertTrue(MomentumJump.parkourCovers(3, 1));
        assertFalse(MomentumJump.parkourCovers(5, 0));
        assertFalse(MomentumJump.parkourCovers(4, 1));
        assertFalse(MomentumJump.parkourCovers(2, -1));
        for (MomentumJump.Job job : MomentumJump.jobs()) {
            if (job.pad() == 0) {
                assertFalse(MomentumJump.parkourCovers(job.dist(), job.dy()));
            } else {
                assertTrue(job.dist() - job.pad() > MomentumJump.standingReach(job.dy()));
            }
        }
    }

    // one of everything a table holds: a plain run up, a hop, a chain and a shape nobody can make
    private static AtomicReferenceArray<double[]> sample() {
        AtomicReferenceArray<double[]> table = MomentumJump.newTable();
        table.set(MomentumJump.key(0, 4, 0, -1, true), shape(0, 4, 0, -1).pick());
        table.set(MomentumJump.key(4, 5, 0, 0, true), shape(4, 5, 0, 0).pick());
        table.set(MomentumJump.key(0, 7, 2, 0, true), shape(0, 7, 2, 0).pick());
        table.set(MomentumJump.key(0, 6, 0, -1, false), new MomentumJump(0, 6, 0, -1, false, MomentumJump.SPRINT_GROUND).pick());
        return table;
    }

    @Test
    public void everyShapeIsInTheWarmup() {
        List<MomentumJump.Job> jobs = MomentumJump.jobs();
        assertFalse(jobs.isEmpty());
        assertEquals(jobs.size(), jobs.stream().map(MomentumJump.Job::key).distinct().count());
        for (MomentumJump.Job job : jobs) {
            assertEquals(job.key(), MomentumJump.key(job.runway(), job.dist(), job.pad(), job.dy(), job.after()));
            assertTrue(job.key() >= 0);
            assertTrue(MomentumJump.emits(job.dist(), job.pad(), job.dy()));
        }
        // no pad is not the same shape as a pad, and a pad has to leave a gap on both sides
        assertEquals(-1, MomentumJump.key(0, 5, 1, 0, true));
        assertEquals(-1, MomentumJump.key(0, 5, 4, 0, true));
        assertTrue(MomentumJump.key(0, 5, 3, 0, true) >= 0);
        assertEquals(-1, MomentumJump.key(0, 8, 0, 0, true));
        assertEquals(-1, MomentumJump.key(0, 5, 0, 2, true));
        assertEquals(-1, MomentumJump.key(7, 5, 0, 0, true));
        // the short ones first
        assertEquals(0, jobs.get(0).pad());
        assertEquals(0, jobs.get(0).runway());
    }

    @Test
    public void tableRoundTripsThroughAFile() throws IOException {
        Path dir = Files.createTempDirectory("momentum-table");
        Path file = dir.resolve("sub").resolve("momentum-table.txt");
        AtomicReferenceArray<double[]> table = sample();
        assertTrue(MomentumJump.save(file, table));
        assertTrue(Files.exists(file));
        assertFalse(Files.exists(dir.resolve("sub").resolve("momentum-table.txt.tmp")));

        AtomicReferenceArray<double[]> loaded = MomentumJump.newTable();
        int count = MomentumJump.load(file, loaded);
        assertTrue(count >= 3);
        for (MomentumJump.Job job : MomentumJump.jobs()) {
            double[] was = table.get(job.key()), now = loaded.get(job.key());
            assertEquals(was == null, now == null);
            if (was != null) {
                assertEquals(MomentumJump.unpack(was), MomentumJump.unpack(now));
            }
        }
        // the nobody-can-make-it answer has to come back as that, and not as a shape nobody has asked about
        int none = MomentumJump.key(0, 6, 0, -1, false);
        assertNotNull(loaded.get(none));
        assertNull(MomentumJump.unpack(loaded.get(none)));
        assertTrue(MomentumJump.unpack(loaded.get(MomentumJump.key(4, 5, 0, 0, true))).hop());
        assertFalse(MomentumJump.unpack(loaded.get(MomentumJump.key(0, 7, 2, 0, true))).hop());
        // loading doesn't stomp on what's already there
        assertEquals(0, MomentumJump.load(file, loaded));
    }

    @Test
    public void staleOrBrokenFilesAreIgnored() throws IOException {
        Path dir = Files.createTempDirectory("momentum-table");
        Path file = dir.resolve("momentum-table.txt");
        assertTrue(MomentumJump.save(file, sample()));
        List<String> lines = Files.readAllLines(file);
        assertTrue(lines.size() > 1);

        // a table from before somebody touched the physics
        List<String> stale = new ArrayList<>(lines);
        stale.set(0, MomentumJump.header(MomentumJump.TABLE_VERSION - 1));
        Files.write(file, stale);
        AtomicReferenceArray<double[]> table = MomentumJump.newTable();
        assertEquals(0, MomentumJump.load(file, table));
        assertEquals(0, filled(table));

        // same version, different limits
        List<String> other = new ArrayList<>(lines);
        other.set(0, lines.get(0).replace("maxRunway=" + MomentumJump.MAX_RUNWAY, "maxRunway=" + (MomentumJump.MAX_RUNWAY + 1)));
        Files.write(file, other);
        assertEquals(0, MomentumJump.load(file, table));

        // a good header and then a line that's off partway through: none of it counts
        List<String> broken = new ArrayList<>(lines);
        broken.set(broken.size() - 1, "0 5 0 0 1 0.5 banana");
        Files.write(file, broken);
        assertEquals(0, MomentumJump.load(file, table));
        assertEquals(0, filled(table));

        // a shape that isn't one
        broken.set(broken.size() - 1, "0 9 0 0 1 none");
        Files.write(file, broken);
        assertEquals(0, MomentumJump.load(file, table));
        broken.set(broken.size() - 1, "0 5 1 0 1 none");
        Files.write(file, broken);
        assertEquals(0, MomentumJump.load(file, table));

        Files.write(file, new byte[]{(byte) 0xff, (byte) 0xfe, 0, 1});
        assertEquals(0, MomentumJump.load(file, table));
        Files.write(file, new byte[0]);
        assertEquals(0, MomentumJump.load(file, table));
        assertEquals(0, MomentumJump.load(dir.resolve("nope.txt"), table));
        // and the original is fine, so it was the edits
        Files.write(file, lines);
        assertEquals(lines.size() - 1, MomentumJump.load(file, table));
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
