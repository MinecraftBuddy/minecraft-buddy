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

import java.util.Random;

import static org.junit.Assert.*;

public class AimSmootherTest {

    private static final double[] SENSITIVITIES = {0, 0.5, 1};
    private static final int SEEDS = 200;

    private static AimSmoother fresh(long seed) {
        AimSmoother s = new AimSmoother(new Random(seed));
        s.acquire();
        return s;
    }

    // ticks until a turn of `degrees` (yaw) is within `within` of the target, with mouse pixels at that sensitivity
    private static int ticksToTurn(long seed, float degrees, double sensitivity, double within) {
        AimSmoother s = fresh(seed);
        float yaw = 0;
        float pitch = 0;
        for (int tick = 1; tick <= 60; tick++) {
            float[] next = s.step(yaw, pitch, degrees, 0, true);
            yaw = CombatAimMath.quantize(yaw, next[0], sensitivity);
            pitch = CombatAimMath.quantize(pitch, next[1], sensitivity);
            if (Math.abs(CombatAimMath.wrap(degrees - yaw)) < within) {
                return tick;
            }
        }
        return Integer.MAX_VALUE;
    }

    @Test
    public void aNinetyTakesAboutSevenTicksAndNeverMoreThanTen() {
        for (double sens : SENSITIVITIES) {
            long total = 0;
            for (long seed = 0; seed < SEEDS; seed++) {
                int ticks = ticksToTurn(seed, 90, sens, 2);
                assertTrue("seed " + seed + " sens " + sens + ": " + ticks, ticks <= 10);
                // not a snap either: a hand needs a few ticks for a quarter turn
                assertTrue("seed " + seed + " sens " + sens + ": " + ticks, ticks >= 5);
                total += ticks;
            }
            double mean = (double) total / SEEDS;
            assertTrue("sens " + sens + " mean " + mean, mean >= 6 && mean <= 8);
        }
    }

    @Test
    public void aOneEightyTakesAboutNineTicksAndNeverMoreThanTwelve() {
        for (double sens : SENSITIVITIES) {
            long total = 0;
            for (long seed = 0; seed < SEEDS; seed++) {
                int ticks = ticksToTurn(seed, 180, sens, 2);
                assertTrue("seed " + seed + " sens " + sens + ": " + ticks, ticks <= 12 && ticks >= 6);
                total += ticks;
            }
            double mean = (double) total / SEEDS;
            assertTrue("sens " + sens + " mean " + mean, mean >= 7.5 && mean <= 10);
        }
    }

    @Test
    public void itActuallyArrivesInsteadOfHalvingForever() {
        // sensitivity 0 has pixels small enough that only the finish snap gets it the last bit of the way
        for (long seed = 0; seed < 20; seed++) {
            assertTrue(ticksToTurn(seed, 90, 0, 0.05) <= 20);
        }
    }

    @Test
    public void theCapsAreNeverExceeded() {
        for (long seed = 0; seed < SEEDS; seed++) {
            AimSmoother s = fresh(seed);
            float yaw = 0;
            float pitch = 0;
            for (int tick = 0; tick < 30; tick++) {
                // a target that jumps around the whole circle and up and down
                float targetYaw = (tick % 3) * 170 - 170;
                float targetPitch = tick % 2 == 0 ? 89 : -89;
                float[] next = s.step(yaw, pitch, targetYaw, targetPitch, true);
                assertTrue(Math.abs(next[0] - yaw) <= AimSmoother.MAX_YAW_STEP + 1e-4);
                assertTrue(Math.abs(next[1] - pitch) <= AimSmoother.MAX_PITCH_STEP + 1e-4);
                yaw = next[0];
                pitch = next[1];
            }
        }
    }

    @Test
    public void theWrongWayRoundIsNeverTaken() {
        // from 170 to -170 is 20 degrees to the right, not 340 to the left
        AimSmoother s = fresh(1);
        float[] next = s.step(170, 0, -170, 0, true);
        assertTrue(next[0] > 170);
        // and the yaw stays continuous instead of jumping to -180
        assertTrue(next[0] < 190);
    }

    @Test
    public void theFirstTicksAfterAcquiringAreSlower() {
        for (long seed = 0; seed < SEEDS; seed++) {
            AimSmoother s = fresh(seed);
            // same big error on each tick so only the gain differs
            float first = s.step(0, 0, 90, 0, true)[0];
            float second = s.step(0, 0, 90, 0, true)[0];
            float third = s.step(0, 0, 90, 0, true)[0];
            assertTrue(first < second && second < third);
            double max = 90 * AimSmoother.FAR_GAIN * (1 + AimSmoother.GAIN_BAND) * (1 + AimSmoother.NOISE);
            assertTrue(first <= max * 0.35 + 1e-4);
            assertTrue(second <= max * 0.65 + 1e-4);
            // and acquiring again starts the ramp over
            s.acquire();
            assertTrue(s.step(0, 0, 90, 0, true)[0] <= max * 0.35 + 1e-4);
        }
    }

    @Test
    public void onTargetNothingMoves() {
        AimSmoother s = fresh(3);
        for (int tick = 0; tick < 10; tick++) {
            float[] next = s.step(12.5f, -4, 12.5f, -4, true);
            assertEquals(12.5f, next[0], 0);
            assertEquals(-4, next[1], 0);
        }
    }

    @Test
    public void smoothOffIsASnap() {
        AimSmoother s = fresh(4);
        float[] next = s.step(0, 0, 135, 30, false);
        assertEquals(135, next[0], 1e-4);
        assertEquals(30, next[1], 1e-4);
        // the short way round there too
        next = s.step(170, 0, -170, 0, false);
        assertEquals(190, next[0], 1e-4);
    }

    @Test
    public void aMobWalkingAcrossAtFiveDegreesATickStaysInsideItsHitbox() {
        // a zombie (0.6 wide) three blocks out is about 5.7 degrees either side of its middle. the lead is the one tick of
        // movement CombatAim adds, so the aim is at where it will be when the swing lands
        double halfWidth = Math.toDegrees(Math.atan2(0.3, 3));
        for (long seed = 0; seed < SEEDS; seed++) {
            AimSmoother s = fresh(seed);
            float yaw = 0;
            for (int tick = 0; tick < 60; tick++) {
                float mob = 5f * tick;
                float lead = mob + 5f;
                yaw = CombatAimMath.quantize(yaw, s.step(yaw, 0, lead, 0, true)[0], 0.5);
                if (tick >= 15) {
                    // the swing next tick sees this rotation and the mob one step on
                    assertTrue("seed " + seed + " tick " + tick, Math.abs(yaw - (mob + 5f)) < halfWidth);
                }
            }
        }
    }

    @Test
    public void aMobUpASlopeEndsUpInReachWellBeforeTheKillTaskCallsItAStall() {
        // three up and 2.2 out: in the plan's reach by the gap, but the ray to its chest enters the box past 3. the aim
        // starts parked at the top of the inner box and has to drift down to where the ray is short enough. the kill task
        // gives the aim 30 ticks before it walks closer instead
        double[] eye = {0, 1.62, 0};
        double[] box = {-0.3, 3, 1.9, 0.3, 4.95, 2.5};
        double[] inner = CombatAimMath.innerBox(box);
        double[] pref = CombatAimMath.preferredPoint(box, eye[1]);
        for (long seed = 0; seed < 50; seed++) {
            AimSmoother s = fresh(seed);
            float[] rot = CombatAimMath.rotationTo(eye, new double[]{0, inner[4], 2.2});
            int landed = -1;
            for (int tick = 1; tick <= 60 && landed < 0; tick++) {
                double[] dir = CombatAimMath.direction(rot[0], rot[1]);
                float[] to = CombatAimMath.rotationTo(eye, CombatAimMath.aimPoint(eye, dir, inner, pref));
                float[] next = s.step(rot[0], rot[1], to[0], to[1], true);
                rot = new float[]{CombatAimMath.quantize(rot[0], next[0], 0.5), CombatAimMath.quantize(rot[1], next[1], 0.5)};
                double entry = CombatAimMath.rayEntry(eye, CombatAimMath.direction(rot[0], rot[1]), box);
                if (!Double.isNaN(entry) && entry <= 3) {
                    landed = tick;
                }
            }
            assertTrue("seed " + seed + " landed " + landed, landed > 0 && landed <= 25);
        }
    }

    @Test
    public void theWobbleStaysInsideItsBoundAndActuallyWobbles() {
        AimSmoother s = fresh(5);
        double sumYaw = 0;
        double minYaw = 0;
        double maxYaw = 0;
        for (int tick = 0; tick < 20000; tick++) {
            float[] w = s.wobble(1.0, 0.01);
            assertTrue(Math.abs(w[0]) <= 1.0 + 1e-6);
            assertTrue(Math.abs(w[1]) <= 0.01 + 1e-6);
            sumYaw += w[0];
            minYaw = Math.min(minYaw, w[0]);
            maxYaw = Math.max(maxYaw, w[0]);
        }
        // it goes places, both ways, and keeps coming back toward the middle
        assertTrue(maxYaw > 0.3 && minYaw < -0.3);
        assertEquals(0, sumYaw / 20000, 0.1);
        // a setting of 0 is no wobble at all
        float[] none = s.wobble(0, 0);
        assertEquals(0, none[0], 0);
        assertEquals(0, none[1], 0);
    }

    @Test
    public void theWobbleIsSlowNotAShake() {
        AimSmoother s = fresh(6);
        float prev = s.wobble(1.0, 0)[0];
        double totalChange = 0;
        for (int tick = 0; tick < 5000; tick++) {
            float now = s.wobble(1.0, 0)[0];
            totalChange += Math.abs(now - prev);
            prev = now;
        }
        // the old per tick reroll moved about a third of its whole range every tick
        assertTrue(totalChange / 5000 < 0.2);
    }
}
