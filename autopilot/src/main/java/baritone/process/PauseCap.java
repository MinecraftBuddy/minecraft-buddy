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

// (inv_pause_cap, a fix of 14 Nov 2026, on) the inventory pauser's pauses held in check: a run of pause requests with gaps of
// less than GAP_TICKS between them may hold the pathing CAP_TICKS at most, then the pathing has FREE_TICKS before the next
// pause is honoured. at the gold mine a swap loop asked for a pause four times a second for 18 minutes and no path search ran.
// pure, one call a tick
public final class PauseCap {
    public static final int CAP_TICKS = 40;
    public static final int GAP_TICKS = 20;
    public static final int FREE_TICKS = 60;

    private int run;
    private int gap = GAP_TICKS;
    private int free;

    // this tick: true when a pause asked for may hold the pathing
    public boolean tick(boolean requested) {
        if (free > 0) {
            free--;
            run = 0;
            gap = GAP_TICKS;
            return false;
        }
        if (!requested) {
            if (gap < GAP_TICKS) {
                gap++;
            }
            if (gap >= GAP_TICKS) {
                run = 0;
            }
            return false;
        }
        // a gap shorter than GAP_TICKS keeps the run going, its ticks counted too
        run += gap < GAP_TICKS ? gap + 1 : 1;
        gap = 0;
        if (run > CAP_TICKS) {
            free = FREE_TICKS;
            run = 0;
            gap = GAP_TICKS;
            return false;
        }
        return true;
    }

    public boolean freeing() {
        return free > 0;
    }
}
