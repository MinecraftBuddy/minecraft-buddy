package baritone.pathing.movement;

// the pure half of god bridging: walk backwards over a gap and place each block against the far face of the block underfoot
// the moment that face can be hit. everything here is along the travel axis, e is how far the player's centre is past the
// edge of the block underfoot (negative = still on it), so the movement only has to feed it numbers and press what comes out
//
// the geometry: the face we want points at dest. the eye sits EYE above the top of the block, and the face runs from the
// top down one block, so at e past the plane a ray that crosses the plane t below the top has tan(pitch) = (EYE + t) / e.
// there is nothing to hit until e > 0, and the player stops being supported once the centre is HALF_WIDTH past the edge
public final class GodBridge {
    // standing eye height above the feet, which are on top of the block underfoot
    public static final double EYE = 1.62;
    // half the player box. supported while the box still overlaps the block, ie centre less than this past the edge
    public static final double HALF_WIDTH = 0.3;
    // slack under the support limit, for a prediction that is a hair off
    public static final double MARGIN = 0.03;
    // the farthest a tick may end past the edge without a block or a sneak
    public static final double SAFE_END = HALF_WIDTH - MARGIN;
    // vanilla scales the key input by this before it becomes acceleration
    public static final double INPUT_SCALE = 0.98;
    // velocity left after a tick on a 0.6 friction block, 0.6 * 0.91
    public static final double GROUND_DRAG = 0.546;
    // closest to the plane we aim at. past this the pitch is within a degree of straight down and one mouse step
    // (0.15 degrees) is a big slice of the face
    public static final double MIN_AIM = 0.05;
    // how far down the face we aim, 0 is the top edge and 1 the bottom. the middle gives the most slack both ways
    public static final double FACE_AIM = 0.5;
    // sideways, off the middle of the bridge. past entry we only keep going while the box is well over the block
    public static final double ENTRY_LATERAL = 0.3;
    public static final double BAIL_LATERAL = 0.4;
    // and how fast we may be drifting sideways when we start
    public static final double ENTRY_LATERAL_SPEED = 0.06;

    public static final class Plan {
        // press right click this tick
        public final boolean click;
        // press sneak this tick
        public final boolean sneak;
        // stop god bridging this block and let the sneaking back place have it
        public final boolean abandon;
        // pitch to set this tick, so the next tick's click sees it
        public final double pitch;

        Plan(boolean click, boolean sneak, boolean abandon, double pitch) {
            this.click = click;
            this.sneak = sneak;
            this.abandon = abandon;
            this.pitch = pitch;
        }
    }

    private GodBridge() {
    }

    // acceleration per tick with the back key held: attribute speed, which is the walk speed once sprint is off
    public static double accel(double speedAttribute) {
        return speedAttribute * INPUT_SCALE;
    }

    // how far the centre moves this tick. velocity is what the player carries into the tick (already dragged), the
    // acceleration is added before the move
    public static double step(double velocity, double accel) {
        return velocity + accel;
    }

    // velocity carried out of a tick that moved by `moved`
    public static double carry(double moved) {
        return moved * GROUND_DRAG;
    }

    // pitch that puts the ray through the face at depth FACE_AIM when the centre is e past the edge
    public static double aimPitch(double e) {
        return Math.toDegrees(Math.atan2(EYE + FACE_AIM, Math.max(e, MIN_AIM)));
    }

    // how far down the face the ray lands, from the eye at e past the plane. 0 to 1 is on the face, anything else misses it
    public static double depthHit(double pitchDegrees, double e) {
        return e * Math.tan(Math.toRadians(pitchDegrees)) - EYE;
    }

    public static boolean depthOnFace(double depth) {
        return depth >= 0 && depth <= 1;
    }

    // the whole decision for one tick. e and velocity are as the tick starts, onFace is the crosshair the click will
    // use (ray cast now, from the rotation set last tick), clickReady is the right click throttle plus hands being free
    public static Plan decide(double e, double velocity, double accel, boolean onFace, boolean clickReady) {
        double next = e + step(velocity, accel);
        // the rotation we set now is what the click sees one tick from now, from where we will be standing then
        double pitch = aimPitch(next);
        if (onFace && clickReady && e > 0) {
            // the block goes in before the player moves, so no sneak here. a sneak tap on every placement would be the
            // thing we are trying not to do (and it drags the next tick to 0.3 speed). the checks that got us to a click
            // are the same ones the click helper makes, a click that still goes nowhere is caught by godClicked next tick
            return new Plan(true, false, false, pitch);
        }
        if (next > SAFE_END) {
            // no click this tick and the move would take us off. sneak now, it clamps this very tick's move
            return new Plan(false, true, true, pitch);
        }
        return new Plan(false, false, false, pitch);
    }

    // position of the player along dir (a unit axis step) from the edge of the block at (srcX, srcZ) toward dir
    public static double pastEdge(double px, double pz, int srcX, int srcZ, int dx, int dz) {
        return (px - (srcX + 0.5)) * dx + (pz - (srcZ + 0.5)) * dz - 0.5;
    }

    // same axis, no offset, for velocities
    public static double along(double vx, double vz, int dx, int dz) {
        return vx * dx + vz * dz;
    }

    // off the middle of the bridge, perpendicular to dir
    public static double lateral(double px, double pz, int srcX, int srcZ, int dx, int dz) {
        return -(px - (srcX + 0.5)) * dz + (pz - (srcZ + 0.5)) * dx;
    }

    // sideways speed, same axis as lateral
    public static double lateralSpeed(double vx, double vz, int dx, int dz) {
        return -vx * dz + vz * dx;
    }

    // facing the way we came from: yaw so the look vector is -dir. minecraft yaw 0 is +z and the look vector is
    // (-sin, cos), so -dir = (-dx, -dz) gives atan2(dx, -dz)
    public static float facingYaw(int dx, int dz) {
        return (float) Math.toDegrees(Math.atan2(dx, -dz));
    }
}
