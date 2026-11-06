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

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static baritone.pathing.timing.TimingSampleTest.sample;
import static org.junit.Assert.*;

public class TimingStatsTest {

    private static void add(TimingStats stats, int times, String kind, int dx, int dy, int dz, float planned, int actual, int breaks, boolean water, boolean sprint) {
        for (int i = 0; i < times; i++) {
            stats.add(sample(kind, dx, dy, dz, planned, actual, breaks, water, sprint));
        }
    }

    // actual walks through base .. base + width - 1 over and over, so the percentiles have something to say
    private static void spread(TimingStats stats, int times, String kind, int dx, int dy, int dz, float planned, int base, int width, int breaks, boolean water, boolean sprint) {
        for (int i = 0; i < times; i++) {
            stats.add(sample(kind, dx, dy, dz, planned, base + i % width, breaks, water, sprint));
        }
    }

    @Test
    public void groupsByKindOffsetAndTheThreeFlags() {
        TimingStats stats = new TimingStats();
        add(stats, 10, "Traverse", 1, 0, 0, 4.6f, 5, 0, false, false);
        add(stats, 10, "Traverse", 1, 0, 0, 4.6f, 5, 0, true, false);
        add(stats, 10, "Traverse", 1, 0, 0, 4.6f, 5, 0, false, true);
        add(stats, 10, "Traverse", 1, 0, 0, 4.6f, 5, 3, false, false);
        add(stats, 10, "Traverse", 0, 0, 1, 4.6f, 5, 0, false, false);
        add(stats, 10, "Diagonal", 1, 0, 1, 4.6f, 5, 0, false, false);
        // three blocks broken and one broken are the same group, only whether it broke at all matters
        add(stats, 5, "Traverse", 1, 0, 0, 4.6f, 5, 1, false, false);
        assertEquals(6, stats.groups());
        List<TimingStats.Row> rows = stats.top(1, 50);
        assertEquals(6, rows.size());
        int brokeCount = 0;
        for (TimingStats.Row r : rows) {
            if (r.broke) {
                brokeCount = r.count;
            }
        }
        assertEquals(15, brokeCount);
        assertEquals(65, stats.totalSamples());
    }

    @Test
    public void meansRatioAndPercentiles() {
        TimingStats stats = new TimingStats();
        // actual 1..10 against a plan of 4: mean 5.5, p10 = 1, p90 = 9 (nearest rank)
        for (int actual = 1; actual <= 10; actual++) {
            stats.add(sample("Ascend", 1, 1, 0, 4f, actual, 0, false, false));
        }
        TimingStats.Row r = stats.top(1, 5).get(0);
        assertEquals(10, r.count);
        assertEquals(4.0, r.meanPlanned, 1e-9);
        assertEquals(5.5, r.meanActual, 1e-9);
        assertEquals(1.375, r.ratio(), 1e-9);
        assertEquals(1, r.p10);
        assertEquals(9, r.p90);
        assertEquals(15.0, r.totalError, 1e-9);
    }

    @Test
    public void percentileEdges() {
        assertEquals(0, TimingStats.percentile(new int[0], 0.5));
        assertEquals(7, TimingStats.percentile(new int[]{7}, 0.1));
        assertEquals(7, TimingStats.percentile(new int[]{7}, 0.9));
        assertEquals(1, TimingStats.percentile(new int[]{1, 2}, 0.5));
        assertEquals(2, TimingStats.percentile(new int[]{1, 2}, 1.0));
        assertEquals(1, TimingStats.percentile(new int[]{1, 2}, 0.0));
    }

    @Test
    public void sortedByTotalTicksOffNotByRatio() {
        TimingStats stats = new TimingStats();
        // wildly off but rare: ratio 5, 10 samples, 40 ticks off each = 400
        add(stats, 10, "Parkour", 3, 0, 0, 10f, 50, 0, false, false);
        // slightly off but constant: ratio 1.25, 1000 samples, 1 tick off each = 1000
        add(stats, 1000, "Traverse", 1, 0, 0, 4f, 5, 0, false, false);
        // over-estimated counts too, it is the size of the error: 100 * 2 = 200
        add(stats, 100, "Descend", 1, -1, 0, 6f, 4, 0, false, false);
        // right on the money
        add(stats, 500, "Pillar", 0, 1, 0, 6f, 6, 0, false, false);
        List<TimingStats.Row> rows = stats.top(1, 10);
        assertEquals("Traverse", rows.get(0).kind);
        assertEquals("Parkour", rows.get(1).kind);
        assertEquals("Descend", rows.get(2).kind);
        assertEquals("Pillar", rows.get(3).kind);
        assertEquals(0.0, rows.get(3).totalError, 1e-9);
    }

    @Test
    public void minimumSamplesAndTopLimit() {
        TimingStats stats = new TimingStats();
        add(stats, 7, "Fall", 0, -5, 0, 10f, 30, 0, false, false);
        add(stats, 8, "Traverse", 1, 0, 0, 4f, 5, 0, false, false);
        assertEquals(1, stats.top(TimingStats.DEFAULT_MIN_SAMPLES, 15).size());
        assertEquals("Traverse", stats.top(TimingStats.DEFAULT_MIN_SAMPLES, 15).get(0).kind);
        assertEquals(2, stats.top(7, 15).size());
        assertEquals(1, stats.top(1, 1).size());
        assertEquals("Fall", stats.top(1, 1).get(0).kind);
        assertTrue(new TimingStats().top(1, 15).isEmpty());
    }

    @Test
    public void truncatedAndBrokenLinesAreSkippedFromAFile() throws Exception {
        Path dir = Files.createTempDirectory("timings");
        TimingFile file = new TimingFile(dir, TimingFile.DEFAULT_CAP);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sample("Traverse", 1, 0, 0, 4f, 5, 0, false, false).appendCsv(sb);
            sb.append('\n');
        }
        sb.append("Traverse,1,0,0,4.00,5,4.0"); // killed mid write
        sb.append("\nnot a line at all\n,,,,,,,,,,,,,\n");
        file.append(sb);
        TimingStats stats = new TimingStats();
        assertEquals(20, file.read(stats::add));
        assertEquals(20, stats.totalSamples());
    }

    @Test
    public void reportLooksLikeThis() {
        TimingStats stats = new TimingStats();
        spread(stats, 212, "Traverse", 1, 0, 0, 4.6f, 5, 4, 0, true, false);
        spread(stats, 90, "Parkour", 3, 0, 0, 14f, 16, 7, 0, false, true);
        spread(stats, 60, "Descend", 1, -1, 0, 6.2f, 4, 3, 0, false, false);
        spread(stats, 40, "Traverse", 1, 0, 0, 9f, 11, 6, 1, false, false);
        add(stats, 3, "Fall", 0, -9, 0, 20f, 40, 0, false, false);
        List<String> lines = TimingStats.format(stats, TimingStats.DEFAULT_MIN_SAMPLES, TimingStats.DEFAULT_TOP);
        assertEquals(6, lines.size());
        assertTrue(lines.get(0), lines.get(0).contains("405 movements in 5 groups, showing the 4 worst"));
        assertTrue(lines.get(2), lines.get(2).startsWith("Parkour (3,0,0) [sprint]: n=90"));
        assertTrue(lines.get(4), lines.get(4).startsWith("Traverse (1,0,0) [break]: n=40"));
    }

    @Test
    public void rowsCountSamplesWhereTheRecheckDisagreed() {
        TimingStats stats = new TimingStats();
        add(stats, 6, "Traverse", 1, 0, 0, 4.6f, 5, 0, false, false);
        for (int i = 0; i < 3; i++) {
            // the world changed: dearer on arrival, and one that came back impossible
            stats.add(new TimingSample("Traverse", 1, 0, 0, 4.6f, 9, i == 2 ? -1f : 8.6f, 0, 0, false, false, false, TimingSample.Floor.PLAIN, false));
        }
        TimingStats.Row row = stats.top(1, 5).get(0);
        assertEquals(9, row.count);
        assertEquals(3, row.rechecked);
        // the start price stands in for the plan: 6 at 4.6, two at 8.6, one impossible (falls back to 4.6)
        assertEquals((6 * 4.6 + 2 * 8.6 + 4.6) / 9, row.meanPlanned, 1e-4);
        assertTrue(TimingStats.format(stats, 1, 5).get(2).endsWith("rechecked 3"));
    }

    @Test
    public void emptyReportsSayWhy() {
        assertEquals(1, TimingStats.format(new TimingStats(), 8, 15).size());
        TimingStats few = new TimingStats();
        add(few, 2, "Traverse", 1, 0, 0, 4f, 5, 0, false, false);
        assertEquals("nothing has enough samples yet", TimingStats.format(few, 8, 15).get(1));
    }
}
