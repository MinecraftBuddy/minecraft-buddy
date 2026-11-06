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

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.*;

public class LegacyClickerTest {

    private static final int SEEDS = 20;
    private static final long START = 123_456;

    // the shortest and longest gap the clamps can produce: fastest cps with the most negative jitter, slowest with the most
    // positive. the interval clamp sits outside both, so these are what's actually reachable
    private static final double SHORTEST = 20 / LegacyClicker.MAX_CPS * (1 - LegacyClicker.MAX_JITTER);
    private static final double LONGEST = 20 / LegacyClicker.MIN_CPS * (1 + LegacyClicker.MAX_JITTER);

    // one caller that wants a swing every tick, the click ticks it got
    private static List<Long> run(LegacyClicker clicker, long from, long ticks) {
        List<Long> clicks = new ArrayList<>();
        for (long now = from; now < from + ticks; now++) {
            if (clicker.due(now)) {
                clicker.clicked(now);
                clicks.add(now);
            }
        }
        return clicks;
    }

    @Test
    public void reachableGapsAreInsideTheIntervalClamp() {
        // if this ever fails the clamp became the real bound and the window math below needs redoing
        assertTrue(SHORTEST > LegacyClicker.MIN_INTERVAL);
        assertTrue(LONGEST < LegacyClicker.MAX_INTERVAL);
    }

    @Test
    public void averagesAboutTenCps() {
        for (int seed = 0; seed < SEEDS; seed++) {
            long ticks = 20 * 60 * 10;
            List<Long> clicks = run(new LegacyClicker(new Random(seed)), START, ticks);
            double cps = clicks.size() * 20.0 / ticks;
            assertTrue("seed " + seed + ": " + cps, cps >= 9.5 && cps <= 10.5);
        }
    }

    @Test
    public void everyFortyTickWindowStaysHuman() {
        // a click is due on the first whole tick at or after its fractional time, so a gap is at most ceil(LONGEST) ticks.
        // the other way the fraction carries over, k clicks span at least (k - 1) * SHORTEST - 1 ticks
        int least = (int) Math.floor(40 / Math.ceil(LONGEST));
        int most = (int) Math.floor(40 / SHORTEST) + 1;
        for (int seed = 0; seed < SEEDS; seed++) {
            List<Long> clicks = run(new LegacyClicker(new Random(seed)), START, 20 * 60 * 5);
            int[] perTick = new int[20 * 60 * 5];
            for (long t : clicks) {
                perTick[(int) (t - START)]++;
            }
            int window = 0;
            for (int i = 0; i < perTick.length; i++) {
                window += perTick[i];
                if (i >= 40) {
                    window -= perTick[i - 40];
                }
                if (i >= 39) {
                    assertTrue("seed " + seed + " at " + i + ": " + window, window >= least && window <= most);
                }
            }
        }
    }

    @Test
    public void gapsVary() {
        for (int seed = 0; seed < SEEDS; seed++) {
            List<Long> clicks = run(new LegacyClicker(new Random(seed)), START, 400);
            Set<Long> gaps = new HashSet<>();
            for (int i = 1; i < clicks.size(); i++) {
                gaps.add(clicks.get(i) - clicks.get(i - 1));
            }
            assertTrue("seed " + seed + " clicks like a metronome", gaps.size() > 1);
        }
    }

    @Test
    public void neverTwoClicksInOneTick() {
        for (int seed = 0; seed < SEEDS; seed++) {
            LegacyClicker clicker = new LegacyClicker(new Random(seed));
            for (long now = START; now < START + 2000; now++) {
                int clicks = 0;
                // the kill task and self defense both asking in the same tick, back to back
                for (int caller = 0; caller < 3; caller++) {
                    if (clicker.due(now)) {
                        clicker.clicked(now);
                        clicks++;
                    }
                }
                assertTrue("seed " + seed + " at " + now, clicks <= 1);
            }
        }
    }

    @Test
    public void aStallDoesNotTurnIntoABurst() {
        for (int seed = 0; seed < SEEDS; seed++) {
            LegacyClicker clicker = new LegacyClicker(new Random(seed));
            List<Long> before = run(clicker, START, 200);
            // 40 ticks of asking but never clicking (aim off the mob)
            long resume = before.get(before.size() - 1) + 41;
            for (long now = before.get(before.size() - 1) + 1; now < resume; now++) {
                clicker.due(now);
            }
            assertTrue("seed " + seed + " should be due right away", clicker.due(resume));
            List<Long> after = run(clicker, resume, 40);
            assertEquals(resume, (long) after.get(0));
            // late, so the next one is a whole fresh gap away and not the debt paid back in a 1 tick rush
            assertTrue("seed " + seed + ": " + after, after.get(1) - after.get(0) >= Math.ceil(SHORTEST));
        }
    }

    @Test
    public void aBackwardsClockKeepsClicking() {
        for (int seed = 0; seed < SEEDS; seed++) {
            LegacyClicker clicker = new LegacyClicker(new Random(seed));
            List<Long> before = run(clicker, START, 200);
            long jumped = before.get(before.size() - 1) - 100;
            List<Long> after = run(clicker, jumped, 40);
            assertFalse("seed " + seed + " stalled", after.isEmpty());
            assertEquals(jumped, (long) after.get(0));
            assertTrue("seed " + seed + ": " + after.size(), after.size() >= 40 / Math.ceil(LONGEST));
        }
    }
}
