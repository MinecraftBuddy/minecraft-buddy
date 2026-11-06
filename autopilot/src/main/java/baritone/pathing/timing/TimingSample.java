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

import java.util.Locale;

// one movement that ran from its first tick to SUCCESS without anything in between. no minecraft imports on purpose,
// the aggregation and the file format can be tested (and one day reused by something that tunes costs) without a game
public final class TimingSample {

    // the floor under the src block, only the kinds the planner prices on their own
    public enum Floor {
        PLAIN, SOUL_SAND, ICE, STAIRS, SLAB, MAGMA
    }

    public static final String HEADER = "kind,dx,dy,dz,planned,actual,recost,breaks,places,sprint,water,still,floor,fast";
    private static final int FIELDS = 14;
    // nothing real takes longer than this, a bigger number is a corrupt line
    private static final int MAX_TICKS = 100_000;

    public final String kind;
    public final int dx, dy, dz;
    // what the planner charged for it, in ticks
    public final float planned;
    // game ticks from the first tick of the movement to the tick it said SUCCESS
    public final int actual;
    // the cost the executor's recheck came up with on the first tick, -1 if it was infinite. differs from planned when
    // the world changed between planning and arriving, or the path was favored (reusing an older path discounts its cost)
    public final float recost;
    public final int breaks, places;
    public final boolean sprint, water, still, fast;
    public final Floor floor;

    public TimingSample(String kind, int dx, int dy, int dz, float planned, int actual, float recost, int breaks, int places,
                        boolean sprint, boolean water, boolean still, Floor floor, boolean fast) {
        this.kind = kind;
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
        this.planned = planned;
        this.actual = actual;
        this.recost = recost;
        this.breaks = breaks;
        this.places = places;
        this.sprint = sprint;
        this.water = water;
        this.still = still;
        this.floor = floor;
        this.fast = fast;
    }

    // the writer thread builds these, the game thread never does
    public void appendCsv(StringBuilder out) {
        out.append(kind).append(',').append(dx).append(',').append(dy).append(',').append(dz).append(',');
        appendFloat(out, planned).append(',').append(actual).append(',');
        appendFloat(out, recost).append(',').append(breaks).append(',').append(places).append(',');
        out.append(sprint ? 1 : 0).append(',').append(water ? 1 : 0).append(',').append(still ? 1 : 0).append(',');
        out.append(floor.name()).append(',').append(fast ? 1 : 0);
    }

    // two decimals is plenty, the planner's costs are not that precise to begin with
    private static StringBuilder appendFloat(StringBuilder out, float value) {
        if (value < 0) {
            return out.append("-1");
        }
        long hundredths = Math.round(value * 100.0);
        out.append(hundredths / 100).append('.');
        long frac = hundredths % 100;
        if (frac < 10) {
            out.append('0');
        }
        return out.append(frac);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        appendCsv(sb);
        return sb.toString();
    }

    // null for anything that isn't a whole good line: the header, a line cut off by a crash, or garbage. the file is
    // append only and can be killed mid write, so reading never throws
    public static TimingSample parse(String line) {
        if (line == null) {
            return null;
        }
        String[] f = line.split(",", -1);
        if (f.length != FIELDS || f[0].isEmpty() || f[0].equals("kind")) {
            return null;
        }
        try {
            int dx = Integer.parseInt(f[1]);
            int dy = Integer.parseInt(f[2]);
            int dz = Integer.parseInt(f[3]);
            float planned = Float.parseFloat(f[4]);
            int actual = Integer.parseInt(f[5]);
            float recost = Float.parseFloat(f[6]);
            int breaks = Integer.parseInt(f[7]);
            int places = Integer.parseInt(f[8]);
            boolean sprint = bool(f[9]);
            boolean water = bool(f[10]);
            boolean still = bool(f[11]);
            Floor floor = Floor.valueOf(f[12].toUpperCase(Locale.ROOT));
            boolean fast = bool(f[13]);
            if (!(planned >= 0) || Float.isInfinite(planned) || actual < 0 || actual > MAX_TICKS || Float.isNaN(recost)
                    || breaks < 0 || places < 0) {
                return null;
            }
            return new TimingSample(f[0], dx, dy, dz, planned, actual, recost, breaks, places, sprint, water, still, floor, fast);
        } catch (IllegalArgumentException e) { // NumberFormatException is one of these, and so is a floor we never wrote
            return null;
        }
    }

    // exactly 0 or 1, so a line cut off in the middle of the last field can't pass as a good one
    private static boolean bool(String s) {
        if (s.equals("1")) {
            return true;
        }
        if (s.equals("0")) {
            return false;
        }
        throw new IllegalArgumentException(s);
    }
}
