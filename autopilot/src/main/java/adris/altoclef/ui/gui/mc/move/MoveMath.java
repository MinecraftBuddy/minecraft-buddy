package adris.altoclef.ui.gui.mc.move;

// the geometry behind move mode, no minecraft in it so the tests can poke every corner. vectors are double[3].
// angles are minecraft's: yaw 0 looks south (+z), 90 looks west, pitch 90 looks straight down. the camera basis is
// the one Camera.setRotation builds, so a ray from here is the ray the player sees
public final class MoveMath {

    // the axis drag gives up when the ray runs along the axis. at |1 - dot^2| under this the closest point flies off
    // to infinity with every pixel of mouse
    public static final double PARALLEL = 0.02;
    // straight up or down the right vector is undefined, so the orbit stops a hair short
    public static final double MAX_PITCH = 89;

    private MoveMath() {
    }

    public static double[] look(double yawDeg, double pitchDeg) {
        double yaw = Math.toRadians(yawDeg);
        double pitch = Math.toRadians(pitchDeg);
        double cp = Math.cos(pitch);
        return new double[]{-Math.sin(yaw) * cp, -Math.sin(pitch), Math.cos(yaw) * cp};
    }

    // forward x world up. looking south that's west, which is right when you're facing south
    public static double[] right(double yawDeg) {
        double yaw = Math.toRadians(yawDeg);
        return new double[]{-Math.cos(yaw), 0, -Math.sin(yaw)};
    }

    public static double[] up(double yawDeg, double pitchDeg) {
        return cross(right(yawDeg), look(yawDeg, pitchDeg));
    }

    // where the eye sits when it looks at center from dist away
    public static double[] orbitEye(double[] center, double yawDeg, double pitchDeg, double dist) {
        double[] f = look(yawDeg, pitchDeg);
        return new double[]{center[0] - f[0] * dist, center[1] - f[1] * dist, center[2] - f[2] * dist};
    }

    // the world direction under a point of the screen. ndc is -1..1 both ways, y up. tanHalfFov is tan(fovY / 2) and
    // aspect is width / height, both read off the frame's projection matrix in game so fov effects come along
    public static double[] ray(double yawDeg, double pitchDeg, double tanHalfFov, double aspect, double ndcX, double ndcY) {
        double[] f = look(yawDeg, pitchDeg);
        double[] r = right(yawDeg);
        double[] u = cross(r, f);
        double sx = ndcX * tanHalfFov * aspect;
        double sy = ndcY * tanHalfFov;
        return normalize(new double[]{f[0] + r[0] * sx + u[0] * sy, f[1] + r[1] * sx + u[1] * sy, f[2] + r[2] * sx + u[2] * sy});
    }

    // pixels to ndc, screen y grows down
    public static double ndcX(double px, double width) {
        return px / width * 2 - 1;
    }

    public static double ndcY(double py, double height) {
        return 1 - py / height * 2;
    }

    // the screen point of a world point in pixels, null behind the eye (or so close to it that it may as well be)
    public static double[] project(double[] p, double[] eye, double yawDeg, double pitchDeg, double tanHalfFov,
                                   double aspect, double width, double height) {
        double[] v = sub(p, eye);
        double[] f = look(yawDeg, pitchDeg);
        double z = dot(v, f);
        if (z < 0.05) {
            return null;
        }
        double[] r = right(yawDeg);
        double[] u = cross(r, f);
        double sx = dot(v, r) / (z * tanHalfFov * aspect);
        double sy = dot(v, u) / (z * tanHalfFov);
        return new double[]{(sx + 1) / 2 * width, (1 - sy) / 2 * height};
    }

    // how far along axis (a unit vector from origin) the point closest to the ray is. NaN when the ray is near
    // parallel to the axis (PARALLEL) or the closest point on the ray is behind the eye, both mean "don't move"
    public static double alongAxis(double[] origin, double[] axis, double[] rayFrom, double[] rayDir) {
        double b = dot(axis, rayDir);
        double denom = 1 - b * b;
        if (Math.abs(denom) < PARALLEL) {
            return Double.NaN;
        }
        double[] w = sub(origin, rayFrom);
        double e = dot(axis, w);
        double f = dot(rayDir, w);
        double t = (f - b * e) / denom;
        if (t < 0) {
            return Double.NaN;
        }
        return (b * f - e) / denom;
    }

    // whole blocks moved since the grab: the delta from where the click landed on the axis, so a click doesn't jump
    // the build half a block to put its middle under the cursor
    public static int blocks(double grabbed, double now) {
        if (Double.isNaN(grabbed) || Double.isNaN(now)) {
            return 0;
        }
        return (int) Math.round(now - grabbed);
    }

    // point to segment in 2d, pixels. what the arrow pick compares against its tolerance
    public static double segmentDistance(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax;
        double dy = by - ay;
        double len2 = dx * dx + dy * dy;
        double t = len2 < 1e-9 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len2));
        double cx = ax + dx * t - px;
        double cy = ay + dy * t - py;
        return Math.sqrt(cx * cx + cy * cy);
    }

    // keeps the eye inside a ball around the player. the center comes along by the same amount, so the view turns
    // into a slide instead of the orbit suddenly aiming somewhere else. hands back the shift (zero when inside)
    public static double[] leash(double[] eye, double[] player, double maxDist) {
        double[] v = sub(eye, player);
        double len = Math.sqrt(dot(v, v));
        if (len <= maxDist || len < 1e-9) {
            return new double[]{0, 0, 0};
        }
        double k = (maxDist - len) / len;
        return new double[]{v[0] * k, v[1] * k, v[2] * k};
    }

    public static double clampPitch(double pitch) {
        return Math.max(-MAX_PITCH, Math.min(MAX_PITCH, pitch));
    }

    static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    static double[] sub(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    static double[] normalize(double[] a) {
        double len = Math.sqrt(dot(a, a));
        return len < 1e-12 ? a : new double[]{a[0] / len, a[1] / len, a[2] / len};
    }
}
