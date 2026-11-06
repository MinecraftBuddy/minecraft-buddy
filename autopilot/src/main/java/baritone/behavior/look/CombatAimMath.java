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

// the geometry half of melee aim: where on a mob to look, which way that is, and whether a look ray hits a box. no
// minecraft in here so the tests can throw numbers at it. boxes are {minX, minY, minZ, maxX, maxY, maxZ}, points and
// directions are {x, y, z}, angles are degrees in minecraft's convention (yaw 0 = +z, pitch down is positive)
public final class CombatAimMath {

    // the middle half of the box sideways and 35% to 85% of the way up. a crosshair parked in here survives the mob
    // shuffling a bit, and nobody aims at a zombie's toes
    public static final double SIDE_INSET = 0.25;
    public static final double LOW = 0.35;
    public static final double HIGH = 0.85;
    // where the aim wanders to when it has nothing better to do: the middle, a bit above the belt
    public static final double PREFERRED_HEIGHT = 0.7;
    // how much of the way to the preferred point the aim moves per tick. slow on purpose, it's a lean not a snap
    public static final double DRIFT = 0.1;
    // a preferred point outside the inner box gets pulled this far (blocks) toward its middle, see aimPoint
    public static final double OUTSIDE_PULL = 0.1;

    private CombatAimMath() {
    }

    // an enderman takes a stare as a dare: its eye sits at 88% of its height, inside a cone of 1 - 0.025/dist around the
    // line to it, so everything we aim at stays well under that. the legs are fine, it has a lot of them
    public static final double ENDERMAN_LOW = 0.1;
    public static final double ENDERMAN_HIGH = 0.4;

    public static double[] innerBox(double[] box) {
        return innerBox(box, LOW, HIGH);
    }

    public static double[] innerBox(double[] box, double low, double high) {
        double w = box[3] - box[0];
        double d = box[5] - box[2];
        double h = box[4] - box[1];
        return new double[]{
                box[0] + w * SIDE_INSET, box[1] + h * low, box[2] + d * SIDE_INSET,
                box[3] - w * SIDE_INSET, box[1] + h * high, box[5] - d * SIDE_INSET
        };
    }

    // the same point with its height pulled down to at most this fraction of the box
    public static double[] capHeight(double[] point, double[] box, double fraction) {
        double top = box[1] + (box[4] - box[1]) * fraction;
        return new double[]{point[0], Math.min(point[1], top), point[2]};
    }

    // would a stare along dir from eye land on a target eye (vanilla's isLookingAtMe: the dot product of the look and the
    // line to its eye has to beat 1 - 0.025/distance). on top of each other is a yes, we are in its face
    public static boolean inEyeCone(double[] eye, double[] dir, double[] targetEye) {
        double dx = targetEye[0] - eye[0];
        double dy = targetEye[1] - eye[1];
        double dz = targetEye[2] - eye[2];
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 1e-6) {
            return true;
        }
        double dot = (dx * dir[0] + dy * dir[1] + dz * dir[2]) / dist;
        return dot > 1 - 0.025 / dist;
    }

    // the middle, 70% up. or lower, down to the bottom of the inner box, when our eye is lower than that: a mob up a slope
    // aimed at its chest is a ray that enters its box from underneath a long way in, past reach, while its knees were right there
    public static double[] preferredPoint(double[] box, double eyeY) {
        double h = box[4] - box[1];
        double y = Math.max(box[1] + h * LOW, Math.min(box[1] + h * PREFERRED_HEIGHT, eyeY));
        return new double[]{(box[0] + box[3]) / 2, y, (box[2] + box[5]) / 2};
    }

    public static boolean inside(double[] point, double[] box) {
        return point[0] >= box[0] && point[0] <= box[3] && point[1] >= box[1] && point[1] <= box[4]
                && point[2] >= box[2] && point[2] <= box[5];
    }

    // the point the crosshair is already on (the look ray at the depth of the box), pulled into the inner box, then nudged
    // a little toward the preferred point. when the crosshair is already inside, the clamp does nothing and only the drift
    // moves it, which is the whole point: no twitching at a mob we are already looking at. a preferred point outside the
    // inner box is a caller who knows better (only the top of its head is visible), that one is aimed at nearly straight
    public static double[] aimPoint(double[] eye, double[] dir, double[] inner, double[] preferred) {
        if (!inside(preferred, inner)) {
            // a tenth of a block in from it: a point on the very edge of the box with the wobble on top is a crosshair
            // that's on the mob every other tick. no more than that, the caller picked that point for a reason (the only
            // bit we can see, the only bit in reach)
            double[] toMiddle = new double[3];
            double len = 0;
            for (int i = 0; i < 3; i++) {
                toMiddle[i] = (inner[i] + inner[i + 3]) / 2 - preferred[i];
                len += toMiddle[i] * toMiddle[i];
            }
            len = Math.sqrt(len);
            double k = len < 1e-9 ? 0 : Math.min(OUTSIDE_PULL, len) / len;
            return new double[]{preferred[0] + toMiddle[0] * k, preferred[1] + toMiddle[1] * k, preferred[2] + toMiddle[2] * k};
        }
        double cx = (inner[0] + inner[3]) / 2 - eye[0];
        double cy = (inner[1] + inner[4]) / 2 - eye[1];
        double cz = (inner[2] + inner[5]) / 2 - eye[2];
        // behind us is depth 0, which is the eye, which clamps to the near side of the box. fine for a 180
        double t = Math.max(0, cx * dir[0] + cy * dir[1] + cz * dir[2]);
        double[] p = new double[3];
        for (int i = 0; i < 3; i++) {
            p[i] = clamp(eye[i] + dir[i] * t, inner[i], inner[i + 3]);
            p[i] += (preferred[i] - p[i]) * DRIFT;
        }
        return p;
    }

    // {yaw, pitch} to look from eye at point, same as RotationUtils.calcRotationFromVec3d minus the wrapping
    public static float[] rotationTo(double[] eye, double[] point) {
        double dx = point[0] - eye[0];
        double dy = point[1] - eye[1];
        double dz = point[2] - eye[2];
        double yaw = Math.toDegrees(Math.atan2(dz, dx)) - 90;
        double pitch = -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        return new float[]{(float) yaw, (float) pitch};
    }

    // unit look vector, same as Entity.calculateViewVector
    public static double[] direction(float yaw, float pitch) {
        double y = Math.toRadians(yaw);
        double p = Math.toRadians(pitch);
        return new double[]{-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p)};
    }

    // into [-180, 180)
    public static float wrap(float degrees) {
        float d = degrees % 360;
        if (d >= 180) d -= 360;
        if (d < -180) d += 360;
        return d;
    }

    // degrees one mouse pixel turns the camera. same formula as the aim processor's mouseToAngle, (s*0.6+0.2)^3 * 8 * 0.15
    // with the float casts minecraft does
    public static float mouseQuantum(double sensitivity) {
        double f = sensitivity * (double) 0.6f + (double) 0.2f;
        return (float) (f * f * f * 8.0d) * 0.15f;
    }

    // a turn from current toward target, rounded to whole mouse pixels like a real mouse would
    public static float quantize(float current, float target, double sensitivity) {
        float px = mouseQuantum(sensitivity);
        return current + (float) (Math.round((target - current) / px) * (double) px);
    }

    // slab test. how far along the ray (dir is unit length) it enters the box: 0 when the ray starts inside, NaN when it
    // misses or the box is behind us. grazing an edge counts, the server is not pickier than that
    public static double rayEntry(double[] origin, double[] dir, double[] box) {
        double near = 0;
        double far = Double.POSITIVE_INFINITY;
        for (int i = 0; i < 3; i++) {
            double lo = box[i];
            double hi = box[i + 3];
            if (Math.abs(dir[i]) < 1e-12) {
                // parallel to this slab: inside it or never
                if (origin[i] < lo || origin[i] > hi) {
                    return Double.NaN;
                }
                continue;
            }
            double t1 = (lo - origin[i]) / dir[i];
            double t2 = (hi - origin[i]) / dir[i];
            near = Math.max(near, Math.min(t1, t2));
            far = Math.min(far, Math.max(t1, t2));
            if (near > far) {
                return Double.NaN;
            }
        }
        return near;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
