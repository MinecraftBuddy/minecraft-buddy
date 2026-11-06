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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// planned versus actual ticks, grouped by what kind of movement it was. pure on purpose (no minecraft), it runs once
// when someone asks, so it is free to be as slow and allocation happy as it likes
public final class TimingStats {

    // fewer samples than this and a mean is a coin flip, the row is left out
    public static final int DEFAULT_MIN_SAMPLES = 8;
    public static final int DEFAULT_TOP = 15;

    public static final class Row {
        public final String kind;
        public final int dx, dy, dz;
        public final boolean broke, water, sprint;
        public int count;
        // how many of them were priced differently when they started than when the path was planned (the world changed,
        // or the plan reused part of an older path and the favoring discount is baked into its cost). the plan column
        // uses the start price for those, or a discounted movement would look twice as slow as it is
        public int rechecked;
        public double meanPlanned, meanActual;
        // 10th and 90th percentile of the actual ticks
        public int p10, p90;
        // count * |meanActual - meanPlanned|: ticks the planner is off by in total over everything we saw. this is what
        // the table is sorted by, a tiny error on a movement that happens constantly beats a big one that is rare
        public double totalError;

        private int[] actuals = new int[16];
        private double plannedSum;
        private double actualSum;

        private Row(String kind, int dx, int dy, int dz, boolean broke, boolean water, boolean sprint) {
            this.kind = kind;
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
            this.broke = broke;
            this.water = water;
            this.sprint = sprint;
        }

        // actual over planned, so above 1 means the planner thinks it is faster than it is
        public double ratio() {
            return meanPlanned > 0 ? meanActual / meanPlanned : Double.NaN;
        }

        private void add(TimingSample s) {
            if (count == actuals.length) {
                actuals = Arrays.copyOf(actuals, count * 2);
            }
            actuals[count++] = s.actual;
            if (s.recost < 0 || Math.abs(s.recost - s.planned) > 0.05) {
                rechecked++;
            }
            plannedSum += s.recost >= 0 ? s.recost : s.planned;
            actualSum += s.actual;
        }

        private void finish() {
            meanPlanned = plannedSum / count;
            meanActual = actualSum / count;
            totalError = count * Math.abs(meanActual - meanPlanned);
            int[] sorted = Arrays.copyOf(actuals, count);
            Arrays.sort(sorted);
            p10 = percentile(sorted, 0.10);
            p90 = percentile(sorted, 0.90);
        }
    }

    private final Map<String, Row> rows = new LinkedHashMap<>();
    private int total;

    public void add(TimingSample s) {
        total++;
        boolean broke = s.breaks > 0;
        String key = s.kind + '|' + s.dx + '|' + s.dy + '|' + s.dz + '|' + broke + '|' + s.water + '|' + s.sprint;
        rows.computeIfAbsent(key, k -> new Row(s.kind, s.dx, s.dy, s.dz, broke, s.water, s.sprint)).add(s);
    }

    public int totalSamples() {
        return total;
    }

    // how many groups there were before the minimum and the top cut, for the header line
    public int groups() {
        return rows.size();
    }

    // worst first. can be called more than once, samples can still be added in between
    public List<Row> top(int minSamples, int limit) {
        List<Row> out = new ArrayList<>();
        for (Row row : rows.values()) {
            if (row.count >= minSamples && row.count > 0) {
                row.finish();
                out.add(row);
            }
        }
        out.sort(Comparator.<Row>comparingDouble(r -> -r.totalError)
                .thenComparingInt(r -> -r.count)
                .thenComparing(r -> r.kind)
                .thenComparingInt(r -> r.dx).thenComparingInt(r -> r.dy).thenComparingInt(r -> r.dz));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    // nearest rank: the smallest value that at least p of the samples are at or below
    public static int percentile(int[] sorted, double p) {
        int n = sorted.length;
        if (n == 0) {
            return 0;
        }
        int rank = (int) Math.ceil(p * n);
        return sorted[Math.min(n, Math.max(1, rank)) - 1];
    }

    public static List<String> format(TimingStats stats, int minSamples, int limit) {
        List<Row> rows = stats.top(minSamples, limit);
        List<String> lines = new ArrayList<>();
        if (stats.totalSamples() == 0) {
            lines.add("no movement timings yet, walk somewhere with recordMovementTimings on");
            return lines;
        }
        lines.add(String.format(Locale.ROOT, "%d movements in %d groups, showing the %d worst with at least %d samples (sorted by ticks off in total)",
                stats.totalSamples(), stats.groups(), rows.size(), minSamples));
        if (rows.isEmpty()) {
            lines.add("nothing has enough samples yet");
            return lines;
        }
        lines.add("movement offset [flags]: n, mean planned, mean real, real/planned, spread = 10th-90th percentile of real, ticks off in total");
        for (Row r : rows) {
            lines.add(String.format(Locale.ROOT, "%s (%d,%d,%d)%s: n=%d plan %.1f real %.1f x%.2f spread %d-%d off %.0f%s",
                    r.kind, r.dx, r.dy, r.dz, flags(r), r.count, r.meanPlanned, r.meanActual, r.ratio(), r.p10, r.p90, r.totalError,
                    r.rechecked > 0 ? " rechecked " + r.rechecked : ""));
        }
        return lines;
    }

    private static String flags(Row r) {
        if (!r.broke && !r.water && !r.sprint) {
            return "";
        }
        StringBuilder sb = new StringBuilder(" [");
        if (r.broke) {
            sb.append("break ");
        }
        if (r.water) {
            sb.append("water ");
        }
        if (r.sprint) {
            sb.append("sprint ");
        }
        sb.setLength(sb.length() - 1);
        return sb.append(']').toString();
    }
}
