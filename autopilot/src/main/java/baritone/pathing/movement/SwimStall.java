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

package baritone.pathing.movement;

// a swimmer pinned on a block keeps the swim state, keeps sprinting and goes nowhere, and the only way out used to be
// the movement timeout. this watches the distance to dest: no progress for a second and a half means try a dive under
// whatever is at head height, and if that goes nowhere either, give up so the executor replans (around it, see SwimStalls).
// no game in here so it can be tested on its own, Movement feeds it a distance and whether a dive makes sense
final class SwimStall {

    enum Verdict {
        SWIM, DIVE, GIVE_UP
    }

    // a second of sprint swimming covers five blocks, a tenth of one in a second and a half is pinned, not slow.
    // the extra half second is slack for a door or a clumsy climb out onto the bank
    static final int WINDOW = 30;
    static final double MIN_PROGRESS = 0.1;
    static final int DIVE_TICKS = 20;

    private double windowStart = Double.NaN;
    private int ticks;
    private int diveLeft;
    // temporary (swimprobe): which give-up fired, read by Movement's probe line
    String lastGiveUp = "";

    boolean diving() {
        return diveLeft > 0;
    }

    void reset() {
        windowStart = Double.NaN;
        ticks = 0;
        diveLeft = 0;
    }

    // dist is how far we are from dest right now, canDive is breath, water below and something actually over our head
    Verdict tick(double dist, boolean canDive) {
        if (Double.isNaN(windowStart)) {
            // first look, nothing to compare to yet
            windowStart = dist;
            ticks = 0;
            return Verdict.SWIM;
        }
        boolean progressed = windowStart - dist >= MIN_PROGRESS;
        if (diving()) {
            if (!canDive) {
                // out of breath (surfacing wins) or out from under the blocker. a dive that got us somewhere is done
                // and the next stall starts from scratch, one that didn't is a wall diving won't fix
                diveLeft = 0;
                return startOver(dist, progressed);
            }
            if (--diveLeft > 0) {
                return Verdict.DIVE;
            }
            if (progressed) {
                // still under it and still moving, keep going under
                diveLeft = DIVE_TICKS;
                windowStart = dist;
                return Verdict.DIVE;
            }
            lastGiveUp = "dive ended without progress";
            return Verdict.GIVE_UP;
        }
        if (progressed) {
            windowStart = dist;
            ticks = 0;
            return Verdict.SWIM;
        }
        if (++ticks < WINDOW) {
            return Verdict.SWIM;
        }
        if (!canDive) {
            // the blocker is at our feet level, or there's nowhere to go down, or no air to spare
            lastGiveUp = "window stalled, cannot dive";
            return Verdict.GIVE_UP;
        }
        diveLeft = DIVE_TICKS;
        windowStart = dist;
        ticks = 0;
        return Verdict.DIVE;
    }

    private Verdict startOver(double dist, boolean progressed) {
        if (!progressed) {
            lastGiveUp = "dive abandoned (cannot dive), no progress";
            return Verdict.GIVE_UP;
        }
        windowStart = dist;
        ticks = 0;
        return Verdict.SWIM;
    }
}
