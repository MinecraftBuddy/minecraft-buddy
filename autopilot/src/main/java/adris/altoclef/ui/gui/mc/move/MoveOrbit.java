package adris.altoclef.ui.gui.mc.move;

// the free camera as a turntable: a point it looks at, which way it looks and how far back it sits. the eye is worked
// out from those, so orbiting never drifts off the build. no minecraft in here either
public final class MoveOrbit {

    static final double MIN_DIST = 3;
    static final double MAX_DIST = 160;
    // degrees per pixel of right drag. vanilla's mouse look at default sensitivity is in the same ballpark
    static final double TURN = 0.25;
    // one wheel notch is 15% closer or further, so zoom feels the same at 5 blocks and at 100
    static final double ZOOM = 0.85;

    private final double[] center = new double[3];
    private double yaw;
    private double pitch;
    private double dist;

    public MoveOrbit(double cx, double cy, double cz, double yaw, double pitch, double dist) {
        center[0] = cx;
        center[1] = cy;
        center[2] = cz;
        this.yaw = yaw;
        this.pitch = MoveMath.clampPitch(pitch);
        this.dist = clampDist(dist);
    }

    // looking at the build from the player's side, 45 down, far enough back that the whole footprint is in view with
    // fov 70 (half the screen height is 0.7 of the distance, so 1.2x the biggest side has some margin). from the
    // player's side and not over the build's far side: that keeps the eye between the player and the build, inside
    // the leash, so a build near the edge of the range doesn't start with the leash dragging the view off it.
    // standing inside the footprint there's no "side", so it's the way the player faces
    public static MoveOrbit framing(double cx, double cy, double cz, int sizeX, int sizeY, int sizeZ, double playerX,
                                    double playerZ, double playerYaw, double maxDist) {
        int biggest = Math.max(sizeX, Math.max(sizeY, sizeZ));
        double d = Math.min(Math.max(8, biggest * 1.2 + 6), Math.max(MIN_DIST, maxDist * 0.9));
        double dx = cx - playerX;
        double dz = cz - playerZ;
        double yaw = dx * dx + dz * dz > 4 ? Math.toDegrees(Math.atan2(-dx, dz)) : playerYaw;
        return new MoveOrbit(cx, cy, cz, wrap(yaw), 45, d);
    }

    private static double clampDist(double d) {
        return Math.max(MIN_DIST, Math.min(MAX_DIST, d));
    }

    // drag right looks right, drag down looks down, like the mouse does in game
    public void turn(double dxPx, double dyPx) {
        yaw = wrap(yaw + dxPx * TURN);
        pitch = MoveMath.clampPitch(pitch + dyPx * TURN);
    }

    // notches up is closer
    public void zoom(double notches) {
        dist = clampDist(dist * Math.pow(ZOOM, notches));
    }

    // slides the whole rig. right and forward are flat (forward is where the camera faces, minus the tilt) so wasd
    // doesn't dig into the ground when looking down at the build
    public void pan(double right, double up, double forward) {
        double[] r = MoveMath.right(yaw);
        double[] f = MoveMath.look(yaw, 0);
        center[0] += r[0] * right + f[0] * forward;
        center[1] += up;
        center[2] += r[2] * right + f[2] * forward;
    }

    // a drag with the middle button: the world follows the cursor. pxToBlocks is how many blocks one pixel covers
    // at the center's distance
    public void drag(double dxPx, double dyPx, double pxToBlocks) {
        double[] r = MoveMath.right(yaw);
        double[] u = MoveMath.up(yaw, pitch);
        double sx = -dxPx * pxToBlocks;
        double sy = dyPx * pxToBlocks;
        for (int i = 0; i < 3; i++) {
            center[i] += r[i] * sx + u[i] * sy;
        }
    }

    // keeps the eye within maxDist of the player, see MoveMath.leash
    public void leash(double[] player, double maxDist) {
        double[] shift = MoveMath.leash(eye(), player, maxDist);
        for (int i = 0; i < 3; i++) {
            center[i] += shift[i];
        }
    }

    public void lookAt(double cx, double cy, double cz) {
        center[0] = cx;
        center[1] = cy;
        center[2] = cz;
    }

    public double[] eye() {
        return MoveMath.orbitEye(center, yaw, pitch, dist);
    }

    public double[] center() {
        return center.clone();
    }

    public double yaw() {
        return yaw;
    }

    public double pitch() {
        return pitch;
    }

    public double dist() {
        return dist;
    }

    private static double wrap(double deg) {
        double d = deg % 360;
        return d < 0 ? d + 360 : d;
    }
}
