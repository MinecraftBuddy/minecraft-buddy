package adris.altoclef.ui.gui.mc.move;

import adris.altoclef.ui.gui.kit.Safe;
import baritone.api.IBaritone;
import baritone.api.event.events.RenderEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.utils.IRenderer;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;

import java.awt.Color;

// blender's move arrows on the build: red x, green y, blue z, a shaft and a cone each, through walls so a build
// half underground still has handles. plus the footprint box, which is what follows the drag (the ghost only moves
// on release). drawn after the ghost, it registers after it
public final class MoveGizmo implements AbstractGameEventListener {

    private static final int[] RGB = {0xFF4A4A, 0x5BE05B, 0x4D8DFF};
    private static final int HOT = 0xFFD84A;
    private static final int CONE_SIDES = 10;
    // the cone is the last quarter of the arrow, this wide at the base
    private static final double CONE_LEN = 0.26;
    private static final double CONE_RADIUS = 0.08;

    private MoveGizmo() {
    }

    public static void attach(IBaritone primary) {
        try {
            primary.getGameEventHandler().registerEventListener(new MoveGizmo());
        } catch (Throwable t) {
            System.err.println("the move gizmo could not start, move mode has no arrows this session");
            t.printStackTrace();
        }
    }

    @Override
    public void onRenderPass(RenderEvent event) {
        MoveSession s = MoveSession.current();
        if (s == null || !MoveSession.cameraOn()) {
            return;
        }
        Safe.run("move gizmo", () -> {
            Matrix4f proj = event.getProjectionMatrix();
            if (proj != null) {
                s.projection(proj.m00(), proj.m11());
            }
            draw(s, event.getModelViewStack());
        });
    }

    private static void draw(MoveSession s, PoseStack stack) {
        double cx = IRenderer.renderManager.renderPosX();
        double cy = IRenderer.renderManager.renderPosY();
        double cz = IRenderer.renderManager.renderPosZ();
        int[] box = s.previewBox();
        double[] o = s.origin();
        double len = s.arrowLength();
        int hot = s.hoverAxis();

        BufferBuilder lines = IRenderer.startLines(Color.WHITE, 0.9f);
        try {
            if (box != null) {
                IRenderer.glColor(new Color(s.dragging() ? HOT : 0xFFFFFF), 0.9f);
                emitBox(lines, stack, box[0] - cx, box[1] - cy, box[2] - cz, box[3], box[4], box[5]);
            }
            for (int i = 0; i < 3; i++) {
                IRenderer.glColor(new Color(i == hot ? HOT : RGB[i]), 1f);
                double[] a = MoveSession.AXES[i];
                double shaft = len * (1 - CONE_LEN);
                IRenderer.emitLine(lines, stack, o[0] - cx, o[1] - cy, o[2] - cz,
                    o[0] + a[0] * shaft - cx, o[1] + a[1] * shaft - cy, o[2] + a[2] * shaft - cz, i == hot ? 6f : 4f);
            }
        } finally {
            IRenderer.endLines(lines, true);
        }

        BufferBuilder quads = IRenderer.startQuads();
        try {
            PoseStack.Pose pose = stack.last();
            for (int i = 0; i < 3; i++) {
                int argb = 0xFF000000 | (i == hot ? HOT : RGB[i]);
                cone(quads, pose, o[0] - cx, o[1] - cy, o[2] - cz, MoveSession.AXES[i], len, argb);
            }
        } finally {
            IRenderer.endQuads(quads, true);
        }
    }

    private static void emitBox(BufferBuilder buf, PoseStack stack, double x, double y, double z, int sx, int sy, int sz) {
        double x1 = x + sx;
        double y1 = y + sy;
        double z1 = z + sz;
        IRenderer.emitLine(buf, stack, x, y, z, x1, y, z, 1, 0, 0, 2f);
        IRenderer.emitLine(buf, stack, x1, y, z, x1, y, z1, 0, 0, 1, 2f);
        IRenderer.emitLine(buf, stack, x1, y, z1, x, y, z1, -1, 0, 0, 2f);
        IRenderer.emitLine(buf, stack, x, y, z1, x, y, z, 0, 0, -1, 2f);
        IRenderer.emitLine(buf, stack, x, y1, z, x1, y1, z, 1, 0, 0, 2f);
        IRenderer.emitLine(buf, stack, x1, y1, z, x1, y1, z1, 0, 0, 1, 2f);
        IRenderer.emitLine(buf, stack, x1, y1, z1, x, y1, z1, -1, 0, 0, 2f);
        IRenderer.emitLine(buf, stack, x, y1, z1, x, y1, z, 0, 0, -1, 2f);
        IRenderer.emitLine(buf, stack, x, y, z, x, y1, z, 0, 1, 0, 2f);
        IRenderer.emitLine(buf, stack, x1, y, z, x1, y1, z, 0, 1, 0, 2f);
        IRenderer.emitLine(buf, stack, x1, y, z1, x1, y1, z1, 0, 1, 0, 2f);
        IRenderer.emitLine(buf, stack, x, y, z1, x, y1, z1, 0, 1, 0, 2f);
    }

    // a cone out of quads: the topology is quads, so each triangle is a quad with the tip twice. the base gets the
    // same treatment around its middle so the arrow isn't hollow when you look up its back
    private static void cone(BufferBuilder buf, PoseStack.Pose pose, double ox, double oy, double oz, double[] axis,
                             double len, int argb) {
        double[] u = axis[1] == 0 ? new double[]{0, 1, 0} : new double[]{1, 0, 0};
        double[] v = MoveMath.cross(axis, u);
        double base = len * (1 - CONE_LEN);
        double r = len * CONE_RADIUS;
        float bx = (float) (ox + axis[0] * base);
        float by = (float) (oy + axis[1] * base);
        float bz = (float) (oz + axis[2] * base);
        float tx = (float) (ox + axis[0] * len);
        float ty = (float) (oy + axis[1] * len);
        float tz = (float) (oz + axis[2] * len);
        // the underside a touch darker, so the cone reads as round and not a flat sticker
        int dark = 0xFF000000 | ((argb >> 1) & 0x7F7F7F);
        for (int k = 0; k < CONE_SIDES; k++) {
            double a0 = 2 * Math.PI * k / CONE_SIDES;
            double a1 = 2 * Math.PI * (k + 1) / CONE_SIDES;
            float x0 = (float) (bx + (u[0] * Math.cos(a0) + v[0] * Math.sin(a0)) * r);
            float y0 = (float) (by + (u[1] * Math.cos(a0) + v[1] * Math.sin(a0)) * r);
            float z0 = (float) (bz + (u[2] * Math.cos(a0) + v[2] * Math.sin(a0)) * r);
            float x1 = (float) (bx + (u[0] * Math.cos(a1) + v[0] * Math.sin(a1)) * r);
            float y1 = (float) (by + (u[1] * Math.cos(a1) + v[1] * Math.sin(a1)) * r);
            float z1 = (float) (bz + (u[2] * Math.cos(a1) + v[2] * Math.sin(a1)) * r);
            buf.addVertex(pose, tx, ty, tz).setColor(argb);
            buf.addVertex(pose, tx, ty, tz).setColor(argb);
            buf.addVertex(pose, x0, y0, z0).setColor(argb);
            buf.addVertex(pose, x1, y1, z1).setColor(argb);
            buf.addVertex(pose, bx, by, bz).setColor(dark);
            buf.addVertex(pose, bx, by, bz).setColor(dark);
            buf.addVertex(pose, x1, y1, z1).setColor(dark);
            buf.addVertex(pose, x0, y0, z0).setColor(dark);
        }
    }
}
