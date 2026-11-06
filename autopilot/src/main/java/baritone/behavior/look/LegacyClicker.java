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

package baritone.behavior.look;

import java.util.Random;

// a 1.8 server has no cooldown, so the only thing pacing our swings there is how fast a hand clicks. around 10 a second,
// drifting a little between clicks and never the same gap twice in a row, because a dead even 10 cps is a macro and
// every old anticheat knows it. no minecraft, the callers hand it the game tick
public final class LegacyClicker {

    static final double CPS = 10;
    static final double MIN_CPS = 8;
    static final double MAX_CPS = 11.5;
    // how hard the drift gets pulled back to CPS each click, and how much it wanders
    private static final double PULL = 0.1;
    private static final double DRIFT = 0.35;
    // per click jitter on the gap, as a fraction of it
    private static final double JITTER = 0.15;
    static final double MAX_JITTER = 0.35;
    static final double MIN_INTERVAL = 1;
    static final double MAX_INTERVAL = 4;

    private final Random rand;
    private double cps = CPS;
    // fractional game tick the next click is due on. starts due
    private double next = Double.NEGATIVE_INFINITY;

    public LegacyClicker(Random rand) {
        this.rand = rand;
    }

    public boolean due(long now) {
        // the server sets the clock with time packets and it can go backwards. a next that is further out than any gap we
        // ever schedule is from the old clock, and waiting it out would be a bot that stops hitting for no reason
        if (next - now > MAX_INTERVAL) {
            next = now;
        }
        return now >= next;
    }

    public void clicked(long now) {
        double interval = nextInterval();
        if (now - next < 1) {
            // on time: keep the fraction so the long run rate really is cps and not cps rounded to whole ticks. the
            // now + 1 floor is what keeps two callers from clicking in the same tick
            next = Math.max(next + interval, now + 1);
        } else {
            // late (the aim was off the mob, nobody wanted a swing): start over from now. carrying the debt would turn
            // the wait into a burst of catch-up clicks, which no hand does
            next = now + interval;
        }
    }

    private double nextInterval() {
        cps = clamp(cps + (CPS - cps) * PULL + rand.nextGaussian() * DRIFT, MIN_CPS, MAX_CPS);
        double jitter = clamp(JITTER * rand.nextGaussian(), -MAX_JITTER, MAX_JITTER);
        return clamp(20 / cps * (1 + jitter), MIN_INTERVAL, MAX_INTERVAL);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
