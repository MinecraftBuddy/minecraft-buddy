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

// how a hand on a mouse turns onto a mob: a fraction of what's left each tick (bigger fraction once it's close, that's
// tracking), a slow first couple of ticks after picking a new target, capped flicks, a bit of jitter in every step, and a
// slow wobble on top so two fights never trace the same line. no minecraft, CombatAim feeds it angles
public final class AimSmoother {

    // fraction of the error turned per tick: this much on a big turn...
    public static final double FAR_GAIN = 0.45;
    // ...easing up to this once the error is small, which is what keeps up with a walking mob
    public static final double NEAR_GAIN = 0.65;
    public static final double FAR_ERROR = 30;
    public static final double NEAR_ERROR = 10;
    // every engagement gets its own hand, somewhere in this band around the gains above
    public static final double GAIN_BAND = 0.1;
    // and every tick its own jitter on top
    public static final double NOISE = 0.15;
    // the first ticks after acquiring a new target are a hand starting to move, not a flick
    private static final double[] RAMP = {0.35, 0.65};
    // a mouse can only go so fast. this is still a 180 in about half a second
    public static final float MAX_YAW_STEP = 40;
    public static final float MAX_PITCH_STEP = 30;
    // close enough, take the rest in one go so it actually arrives instead of halving forever
    public static final float FINISH = 0.25f;
    // the wobble pulls back toward zero by this much per tick, so it wanders instead of drifting off for good
    private static final double WOBBLE_RETURN = 0.15;
    private static final double WOBBLE_STEP = 0.3;

    private final Random rand;
    private int ticks;
    private double gainScale = 1;
    // the wobble, as a fraction of its bound so a changed setting can't leave it out of range
    private double wobbleYaw;
    private double wobblePitch;

    public AimSmoother(Random rand) {
        this.rand = rand;
    }

    // a new target (or the same one after a long break): ramp from the start and a fresh hand
    public void acquire() {
        ticks = 0;
        gainScale = 1 + (rand.nextDouble() * 2 - 1) * GAIN_BAND;
    }

    public static double baseGain(double error) {
        if (error >= FAR_ERROR) {
            return FAR_GAIN;
        }
        if (error <= NEAR_ERROR) {
            return NEAR_GAIN;
        }
        return FAR_GAIN + (NEAR_GAIN - FAR_GAIN) * (FAR_ERROR - error) / (FAR_ERROR - NEAR_ERROR);
    }

    // one tick of turning from {yaw, pitch} toward {yaw, pitch}. yaw stays continuous (no wrap of the result), the error
    // is wrapped so a 350 degree "turn" is really a 10. smooth off is the snap, same target, one tick
    public float[] step(float yaw, float pitch, float targetYaw, float targetPitch, boolean smooth) {
        float errYaw = CombatAimMath.wrap(targetYaw - yaw);
        float errPitch = targetPitch - pitch;
        int t = ticks++;
        if (!smooth) {
            return new float[]{yaw + errYaw, targetPitch};
        }
        // one gain for both axes off the combined error, so a diagonal turn goes diagonal instead of yaw first
        double error = Math.sqrt(errYaw * errYaw + errPitch * errPitch);
        double gain = baseGain(error) * gainScale * (1 + (rand.nextDouble() * 2 - 1) * NOISE);
        if (t < RAMP.length) {
            gain *= RAMP[t];
        }
        return new float[]{yaw + axis(errYaw, gain, MAX_YAW_STEP), pitch + axis(errPitch, gain, MAX_PITCH_STEP)};
    }

    private static float axis(float error, double gain, float cap) {
        if (Math.abs(error) < FINISH) {
            return error;
        }
        float step = (float) (error * gain);
        return Math.max(-cap, Math.min(cap, step));
    }

    // the slow wobble for this tick, {yaw, pitch} in degrees, inside +-yawBound and +-pitchBound. goes on top of the step,
    // never into the target: in the target the smoothing would just eat it
    public float[] wobble(double yawBound, double pitchBound) {
        wobbleYaw = walk(wobbleYaw);
        wobblePitch = walk(wobblePitch);
        return new float[]{(float) (wobbleYaw * yawBound), (float) (wobblePitch * pitchBound)};
    }

    private double walk(double v) {
        double next = v * (1 - WOBBLE_RETURN) + rand.nextGaussian() * WOBBLE_STEP * 0.5;
        return Math.max(-1, Math.min(1, next));
    }

    public int ticks() {
        return ticks;
    }
}
