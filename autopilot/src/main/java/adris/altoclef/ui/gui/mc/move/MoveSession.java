package adris.altoclef.ui.gui.mc.move;

import adris.altoclef.ui.gui.facade.SchematicsAccess.Footprint;
import adris.altoclef.ui.gui.facade.SchematicsAccess.Placement;
import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.mc.McSchematics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

// one go at moving a build with the free camera: the orbit, the arrows and the drag. McSchematics.moving() says which
// file is being moved, this is everything that only matters while the move screen is up. the camera mixin asks
// pose() every frame and gets null whenever anything is off, so the worst case is the normal camera
public final class MoveSession {

    public static final int NONE = -1;
    static final double[][] AXES = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
    // the arrows are this much of the distance to the eye long, so they stay the same size on screen at any zoom
    static final double ARROW = 0.16;
    // how close the pointer has to be to an arrow, as a share of the screen height (10 px at 1080p)
    static final double PICK = 0.0095;
    // blocks a second of wasd, plus a share of the zoom so a far camera doesn't crawl
    static final double SLIDE = 6;
    static final double SLIDE_PER_DIST = 0.6;
    // never more than 8 chunks from the player: past that the chunks around the camera aren't loaded anyway
    static final int LEASH_CHUNKS = 8;

    private static MoveSession current;

    public final String path;
    private final MoveOrbit orbit;
    private final Object level;
    private float lastHealth;
    private int lastHurt;

    private Placement placement;
    private Footprint footprint;

    private int hover = NONE;
    private int dragAxis = NONE;
    private double grab = Double.NaN;
    private double[] grabOrigin;
    private int dragBlocks;

    // the last frame's projection, read off the matrix the world was drawn with so the arrows line up with it
    private double tanHalfFov = Math.tan(Math.toRadians(35));
    private double aspect = 16 / 9.0;
    private double[] eye;
    private long lastFrame;

    // wasd, space, shift held down
    public static final int FORWARD = 0;
    public static final int BACK = 1;
    public static final int LEFT = 2;
    public static final int RIGHT = 3;
    public static final int UP = 4;
    public static final int DOWN = 5;
    private final boolean[] held = new boolean[6];

    private MoveSession(String path, MoveOrbit orbit, Object level, float health) {
        this.path = path;
        this.orbit = orbit;
        this.level = level;
        this.lastHealth = health;
    }

    public static MoveSession current() {
        return current;
    }

    // null when there's nothing to move here: no world, no placement, or it's in another dimension
    public static MoveSession start(String path) {
        // not end(): that would also switch off McSchematics.moving, which is the very thing asking for this one
        MoveSession old = current;
        current = null;
        if (old != null) {
            old.cancelDrag();
            old.releaseKeys();
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        McSchematics schematics = McSchematics.shared();
        Placement p = schematics.placement(path);
        Footprint fp = schematics.footprint(path);
        if (player == null || mc.level == null || p == null || fp == null || !p.inThisDimension()) {
            return null;
        }
        MoveOrbit orbit = MoveOrbit.framing(centerX(fp), centerY(fp), centerZ(fp), fp.sizeX(), fp.sizeY(), fp.sizeZ(),
            player.getX(), player.getZ(), player.getYRot(), leash(mc));
        // the perspective is left alone: the camera mixin lands after third person has done its pushing around, and
        // the hand and crosshair are switched off by their own mixins, so there's nothing to put back afterwards
        MoveSession s = new MoveSession(path, orbit, mc.level, player.getHealth());
        s.placement = p;
        s.footprint = fp;
        s.lastHurt = player.hurtTime;
        current = s;
        return s;
    }

    // every way out ends here, more than once is fine
    public static void end() {
        MoveSession s = current;
        current = null;
        if (s == null) {
            return;
        }
        s.cancelDrag();
        s.releaseKeys();
        Safe.run("move mode end", () -> {
            McSchematics schematics = McSchematics.shared();
            if (s.path.equals(schematics.moving())) {
                schematics.stopMoving();
            }
        });
    }

    // ----- the tick: anything that should end move mode

    // false = finish now. the screen closes itself on that
    public boolean tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (current != this || player == null || mc.level == null || mc.level != level || player.isDeadOrDying()) {
            return false;
        }
        // getting hit while staring at the build from fifty blocks away is how you die, so it hands the camera back
        // hurtTime jumps back up to 10 on a hit even when absorption soaked all of it and health never moved
        float health = player.getHealth();
        if (health < lastHealth || player.hurtTime > lastHurt) {
            return false;
        }
        lastHealth = health;
        lastHurt = player.hurtTime;
        McSchematics schematics = McSchematics.shared();
        if (!path.equals(schematics.moving()) || !path.equals(schematics.ghost())) {
            return false;
        }
        // deleted, or the world went away under it
        Placement p = schematics.placement(path);
        Footprint fp = schematics.footprint(path);
        if (p == null || fp == null || !p.inThisDimension()) {
            return false;
        }
        placement = p;
        footprint = fp;
        return true;
    }

    // ----- the camera

    // the free camera is what the frame is looking through: the hand and the crosshair stay off
    public static boolean cameraOn() {
        MoveSession s = current;
        if (s == null) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        return mc.gui.screen() instanceof MoveScreenMarker && mc.player != null;
    }

    // x y z yaw pitch for the camera mixin, null for "leave the camera alone". the slide from held keys happens
    // here, per frame, so it's smooth at any frame rate
    public static double[] pose() {
        MoveSession s = current;
        if (s == null) {
            return null;
        }
        if (!cameraOn()) {
            return null;
        }
        return s.frame(Minecraft.getInstance());
    }

    private double[] frame(Minecraft mc) {
        long now = System.nanoTime();
        double dt = lastFrame == 0 ? 0 : Math.min(0.1, (now - lastFrame) / 1e9);
        lastFrame = now;
        double speed = (SLIDE + orbit.dist() * SLIDE_PER_DIST) * dt;
        double right = (held[RIGHT] ? 1 : 0) - (held[LEFT] ? 1 : 0);
        double up = (held[UP] ? 1 : 0) - (held[DOWN] ? 1 : 0);
        double forward = (held[FORWARD] ? 1 : 0) - (held[BACK] ? 1 : 0);
        if (right != 0 || up != 0 || forward != 0) {
            orbit.pan(right * speed, up * speed, forward * speed);
        }
        LocalPlayer player = mc.player;
        orbit.leash(new double[]{player.getX(), player.getEyeY(), player.getZ()}, leash(mc));
        eye = orbit.eye();
        return new double[]{eye[0], eye[1], eye[2], orbit.yaw(), orbit.pitch()};
    }

    // McSchematics.LiveGame.moveRange is the same number, so a build it lets in is one the camera can reach
    public static double leash(Minecraft mc) {
        // the effective one: a server's view distance can be under the option, and that's what Camera.update reads
        return Math.min(mc.options.getEffectiveRenderDistance(), LEASH_CHUNKS) * 16;
    }

    // from the world render, the matrix the frame was drawn with. m11 is 1 / tan(fov / 2), m00 that over the aspect
    public void projection(float m00, float m11) {
        if (m00 > 0 && m11 > 0 && Float.isFinite(m00) && Float.isFinite(m11)) {
            tanHalfFov = 1.0 / m11;
            aspect = m11 / m00;
        }
    }

    // ----- input, pixels of the framebuffer

    public void held(int key, boolean down) {
        held[key] = down;
    }

    public void releaseKeys() {
        java.util.Arrays.fill(held, false);
    }

    public void turn(double dxPx, double dyPx) {
        orbit.turn(dxPx, dyPx);
    }

    public void zoom(double notches) {
        orbit.zoom(notches);
    }

    // middle drag: the world sticks to the cursor at the distance of the orbit center
    public void slide(double dxPx, double dyPx, double screenH) {
        orbit.drag(dxPx, dyPx, 2 * tanHalfFov * orbit.dist() / Math.max(1, screenH));
    }

    public void hover(double px, double py, double w, double h) {
        if (dragAxis == NONE) {
            hover = pick(px, py, w, h);
        }
    }

    // true when an arrow was grabbed
    public boolean grab(double px, double py, double w, double h) {
        int axis = pick(px, py, w, h);
        if (axis == NONE || eye == null) {
            return false;
        }
        double[] origin = origin();
        double t = MoveMath.alongAxis(origin, AXES[axis], eye, ray(px, py, w, h));
        if (Double.isNaN(t)) {
            return false;
        }
        dragAxis = axis;
        hover = axis;
        grab = t;
        grabOrigin = origin;
        dragBlocks = 0;
        return true;
    }

    public void drag(double px, double py, double w, double h) {
        if (dragAxis == NONE || eye == null) {
            return;
        }
        double t = MoveMath.alongAxis(grabOrigin, AXES[dragAxis], eye, ray(px, py, w, h));
        // looking down the axis: hold the last good spot instead of flinging the build off to infinity
        if (!Double.isNaN(t)) {
            dragBlocks = MoveMath.blocks(grab, t);
        }
    }

    // one facade.move per drag, so the ghost rescans once. the move is synchronous on the game thread and the ghost
    // just retargets on its next tick, so a second release before the rescan is done simply wins
    public void release() {
        if (dragAxis == NONE) {
            return;
        }
        int axis = dragAxis;
        int n = dragBlocks;
        dragAxis = NONE;
        grab = Double.NaN;
        grabOrigin = null;
        dragBlocks = 0;
        if (n != 0) {
            double[] a = AXES[axis];
            edit(() -> McSchematics.shared().move(path, (int) a[0] * n, (int) a[1] * n, (int) a[2] * n));
        }
    }

    public boolean dragging() {
        return dragAxis != NONE;
    }

    public void cancelDrag() {
        dragAxis = NONE;
        grab = Double.NaN;
        grabOrigin = null;
        dragBlocks = 0;
    }

    // ----- the buttons

    public void rotate(boolean clockwise) {
        edit(() -> McSchematics.shared().rotate(path, clockwise));
    }

    public void mirror() {
        edit(() -> McSchematics.shared().mirror(path));
    }

    // the build comes to the player and the camera goes with it
    public void toMe() {
        edit(() -> McSchematics.shared().placeAtFeet(path));
        if (footprint != null) {
            orbit.lookAt(centerX(footprint), centerY(footprint), centerZ(footprint));
        }
    }

    private void edit(java.util.function.Supplier<Placement> change) {
        cancelDrag();
        Safe.run("move mode edit", () -> {
            Placement p = change.get();
            if (p != null) {
                placement = p;
                Footprint fp = McSchematics.shared().footprint(path);
                if (fp != null) {
                    footprint = fp;
                }
            }
        });
    }

    // ----- the arrows

    // the gizmo sits on the footprint's middle, plus whatever the drag would move it
    public double[] origin() {
        Footprint fp = footprint;
        double[] o = {centerX(fp), centerY(fp), centerZ(fp)};
        if (dragAxis != NONE) {
            for (int i = 0; i < 3; i++) {
                o[i] += AXES[dragAxis][i] * dragBlocks;
            }
        }
        return o;
    }

    public double arrowLength() {
        if (eye == null) {
            return 1;
        }
        double[] d = MoveMath.sub(origin(), eye);
        return Math.max(0.5, Math.sqrt(MoveMath.dot(d, d)) * ARROW);
    }

    // the axis under the pointer, nearest on screen within the tolerance
    int pick(double px, double py, double w, double h) {
        if (eye == null || footprint == null) {
            return NONE;
        }
        double[] o = origin();
        double len = arrowLength();
        double[] a = MoveMath.project(o, eye, orbit.yaw(), orbit.pitch(), tanHalfFov, aspect, w, h);
        double best = Math.max(6, PICK * h);
        int axis = NONE;
        for (int i = 0; i < 3; i++) {
            double[] tip = {o[0] + AXES[i][0] * len, o[1] + AXES[i][1] * len, o[2] + AXES[i][2] * len};
            double[] b = MoveMath.project(tip, eye, orbit.yaw(), orbit.pitch(), tanHalfFov, aspect, w, h);
            if (a == null || b == null) {
                continue;
            }
            double d = MoveMath.segmentDistance(px, py, a[0], a[1], b[0], b[1]);
            if (d < best) {
                best = d;
                axis = i;
            }
        }
        return axis;
    }

    private double[] ray(double px, double py, double w, double h) {
        return MoveMath.ray(orbit.yaw(), orbit.pitch(), tanHalfFov, aspect, MoveMath.ndcX(px, w), MoveMath.ndcY(py, h));
    }

    public int hoverAxis() {
        return dragAxis != NONE ? dragAxis : hover;
    }

    public int dragAxis() {
        return dragAxis;
    }

    // the footprint the box is drawn around, shifted by the drag
    public int[] previewBox() {
        Footprint fp = footprint;
        if (fp == null) {
            return null;
        }
        int dx = 0;
        int dy = 0;
        int dz = 0;
        if (dragAxis != NONE) {
            dx = (int) AXES[dragAxis][0] * dragBlocks;
            dy = (int) AXES[dragAxis][1] * dragBlocks;
            dz = (int) AXES[dragAxis][2] * dragBlocks;
        }
        return new int[]{fp.x() + dx, fp.y() + dy, fp.z() + dz, fp.sizeX(), fp.sizeY(), fp.sizeZ()};
    }

    public Placement placement() {
        if (dragAxis == NONE || placement == null) {
            return placement;
        }
        double[] a = AXES[dragAxis];
        return new Placement(placement.x() + (int) a[0] * dragBlocks, placement.y() + (int) a[1] * dragBlocks,
            placement.z() + (int) a[2] * dragBlocks, placement.turns(), placement.mirrored(), placement.dimension(),
            placement.inThisDimension());
    }

    private static double centerX(Footprint fp) {
        return fp.x() + fp.sizeX() / 2.0;
    }

    private static double centerY(Footprint fp) {
        return fp.y() + fp.sizeY() / 2.0;
    }

    private static double centerZ(Footprint fp) {
        return fp.z() + fp.sizeZ() / 2.0;
    }

    // the move screen, without this package needing the screen's (it lives with the canvases in mc)
    public interface MoveScreenMarker {
    }
}
