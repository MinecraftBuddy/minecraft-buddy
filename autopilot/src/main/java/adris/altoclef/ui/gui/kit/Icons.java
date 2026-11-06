package adris.altoclef.ui.gui.kit;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

// every icon as a handful of strokes on a 24 unit grid, white, drawn at whatever pixel size the atlas asks for. no
// pngs to ship and no blurry upscales when somebody runs a 4k monitor. one family: a two unit stroke with round caps
// and joins, the art inside 3..21 so every glyph has the same optical size and sits on the same centre, outlined
// unless the meaning is a fill (a stop square, a busy dot, the toes of a paw). the small marks (x, chevrons, a tick)
// are drawn a bit smaller on purpose, like any symbol set does
public final class Icons {

    private static final float GRID_LO = 2f;
    private static final float GRID_SPAN = 20f;
    private static final float STROKE_UNITS = 2f;

    private Icons() {
    }

    // stroke width in pixels for a box this big: two grid units, so it scales with the glyph instead of slamming
    // into a floor that makes 12px icons heavier than 24px ones. the floor is 1.25 and not 1: a one pixel line
    // that lands on a pixel boundary is two rows of 50% grey, and a 12px plus made of those is barely there
    public static float strokePx(int size) {
        return Math.max(1.25f, size * STROKE_UNITS / 24f);
    }

    // where grid coordinate g lands in pixels inside a size box. the canvas uses this for the go to path dots so they
    // sit exactly where the path ends, at every size
    public static float toPx(int size, float g) {
        float sw = strokePx(size);
        float lo = 0.5f + sw * 0.5f;
        float scale = (size - 2f * lo) / GRID_SPAN;
        return lo + (g - GRID_LO) * scale;
    }

    public static void draw(Icon icon, Graphics2D g, int size) {
        // under about 4px the grid maths flips the art inside out, and there is nothing to see anyway
        if (size < 4) {
            return;
        }
        AffineTransform savedT = g.getTransform();
        Stroke savedS = g.getStroke();
        Object savedAa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        Object savedSc = g.getRenderingHint(RenderingHints.KEY_STROKE_CONTROL);
        Color savedC = g.getColor();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setColor(Color.WHITE);

        float sw = strokePx(size);
        // the stroke's outer edge sits half a pixel inside the box, so nothing gets sliced by the atlas edge
        float lo = 0.5f + sw * 0.5f;
        float scale = (size - 2f * lo) / GRID_SPAN;
        g.translate(lo, lo);
        g.scale(scale, scale);
        g.translate(-GRID_LO, -GRID_LO);
        float w = sw / scale;
        g.setStroke(new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        art(icon, g, w);

        g.setTransform(savedT);
        g.setStroke(savedS);
        g.setColor(savedC);
        restore(g, RenderingHints.KEY_ANTIALIASING, savedAa);
        restore(g, RenderingHints.KEY_STROKE_CONTROL, savedSc);
    }

    private static void restore(Graphics2D g, RenderingHints.Key key, Object value) {
        if (value != null) {
            g.setRenderingHint(key, value);
        }
    }

    // w is the stroke width in grid units, for the things that are just a dot
    private static void art(Icon icon, Graphics2D g, float w) {
        switch (icon) {
            case GET -> {
                // a parcel: the cube with the tape stripe across the lid, which is what tells it apart from BLOCK
                cube(g);
                line(g, 7.75f, 5.25f, 16.25f, 9.75f);
            }
            case GOTO -> {
                // a baritone path seen from above: two straight runs with rounded corners, from where you stand
                // (lower left) to the goal (upper right). the green and red dots are not in the mask, the canvas
                // paints them on top in their own colours (see TexturedCanvas.icon, PATH_START / PATH_END)
                Path2D.Float path = new Path2D.Float();
                path.moveTo(5, 19);
                path.lineTo(9, 19);
                path.quadTo(12, 19, 12, 16);
                path.lineTo(12, 8);
                path.quadTo(12, 5, 15, 5);
                path.lineTo(19, 5);
                g.draw(path);
            }
            case FOLLOW -> {
                // a person and an arrow leading off to the right
                g.draw(new Ellipse2D.Float(6, 3.5f, 7, 7));
                Path2D.Float body = new Path2D.Float();
                body.moveTo(3, 20.5f);
                body.lineTo(3, 18.5f);
                body.quadTo(3, 14.5f, 7, 14.5f);
                body.lineTo(12, 14.5f);
                body.quadTo(16, 14.5f, 16, 18.5f);
                body.lineTo(16, 20.5f);
                g.draw(body);
                line(g, 15.5f, 9.5f, 21, 9.5f);
                poly(g, false, 18.5f, 7, 21, 9.5f, 18.5f, 12);
            }
            case WAYPOINTS -> {
                line(g, 6, 3, 6, 21);
                poly(g, true, 6, 4, 18, 4, 15, 8.5f, 18, 13, 6, 13);
            }
            case HELP -> {
                g.draw(new Ellipse2D.Float(3, 3, 18, 18));
                Path2D.Float q = new Path2D.Float();
                q.append(new Arc2D.Float(9, 6.5f, 6, 6, 200, -230, Arc2D.OPEN), false);
                q.lineTo(12, 12.6f);
                q.lineTo(12, 14.2f);
                g.draw(q);
                dot(g, 12, 17.6f, w);
            }
            case SETTINGS -> {
                // two sliders, knobs centred on their tracks
                line(g, 3, 8, 10, 8);
                g.draw(new Ellipse2D.Float(11, 5, 6, 6));
                line(g, 18, 8, 21, 8);
                line(g, 3, 16, 6, 16);
                g.draw(new Ellipse2D.Float(7, 13, 6, 6));
                line(g, 14, 16, 21, 16);
            }
            case STATS -> {
                line(g, 5, 20, 5, 15);
                line(g, 12, 20, 12, 9);
                line(g, 19, 20, 19, 4);
            }
            case SCHEMATICS -> {
                // two courses of bricks with the joints offset, so it is a wall and not a window
                g.draw(new RoundRectangle2D.Float(3, 6, 18, 12, 2, 2));
                line(g, 3, 12, 21, 12);
                line(g, 12, 6, 12, 12);
                line(g, 7.5f, 12, 7.5f, 18);
                line(g, 16.5f, 12, 16.5f, 18);
            }
            case WARNING -> {
                poly(g, true, 12, 3.5f, 21, 19.5f, 3, 19.5f);
                line(g, 12, 9, 12, 13.5f);
                dot(g, 12, 16.5f, w);
            }
            case SEARCH -> {
                g.draw(new Ellipse2D.Float(3.5f, 3.5f, 14, 14));
                line(g, 15.5f, 15.5f, 20.5f, 20.5f);
            }
            case CLOSE -> {
                line(g, 7, 7, 17, 17);
                line(g, 17, 7, 7, 17);
            }
            case PLUS -> {
                line(g, 12, 5, 12, 19);
                line(g, 5, 12, 19, 12);
            }
            case MINUS -> line(g, 5, 12, 19, 12);
            case CHEVRON_DOWN -> poly(g, false, 6, 9, 12, 15, 18, 9);
            case CHEVRON_RIGHT -> poly(g, false, 9, 6, 15, 12, 9, 18);
            case CHECK -> poly(g, false, 5, 12, 10, 17, 19, 7);
            case COPY -> {
                g.draw(new RoundRectangle2D.Float(9, 9, 12, 12, 4, 4));
                Path2D.Float back = new Path2D.Float();
                back.moveTo(9, 15);
                back.lineTo(5.5f, 15);
                back.quadTo(3, 15, 3, 12.5f);
                back.lineTo(3, 5.5f);
                back.quadTo(3, 3, 5.5f, 3);
                back.lineTo(12.5f, 3);
                back.quadTo(15, 3, 15, 5.5f);
                back.lineTo(15, 9);
                g.draw(back);
            }
            case RESET -> {
                // open on the upper right, arrow on the loose end, goes round the wrong way on purpose
                float cx = 12f;
                float cy = 12f;
                float r = 7.5f;
                g.draw(new Arc2D.Float(cx - r, cy - r, 2 * r, 2 * r, 100, 270, Arc2D.OPEN));
                double th = Math.toRadians(10);
                float px = cx + r * (float) Math.cos(th);
                float py = cy - r * (float) Math.sin(th);
                head(g, px, py, (float) -Math.sin(th), (float) -Math.cos(th), 3.5f, 2.75f);
            }
            case STOP -> g.fill(new RoundRectangle2D.Float(6, 6, 12, 12, 3, 3));
            case KEY -> {
                g.draw(new RoundRectangle2D.Float(3, 5.5f, 18, 13, 5, 5));
                line(g, 8, 14.5f, 16, 14.5f);
            }
            case DOT -> g.fill(new Ellipse2D.Float(6, 6, 12, 12));
            case RING -> g.draw(new Ellipse2D.Float(6, 6, 12, 12));
            case TRASH -> {
                line(g, 4, 6.5f, 20, 6.5f);
                poly(g, false, 9, 6.5f, 9, 4, 15, 4, 15, 6.5f);
                Path2D.Float bin = new Path2D.Float();
                bin.moveTo(6, 6.5f);
                bin.lineTo(6.9f, 19.3f);
                bin.quadTo(7, 21, 8.7f, 21);
                bin.lineTo(15.3f, 21);
                bin.quadTo(17, 21, 17.1f, 19.3f);
                bin.lineTo(18, 6.5f);
                g.draw(bin);
                line(g, 10, 10.5f, 10, 17);
                line(g, 14, 10.5f, 14, 17);
            }
            case PIN -> {
                Path2D.Float pin = new Path2D.Float();
                pin.moveTo(19.5f, 10);
                pin.curveTo(19.5f, 15.5f, 12, 21, 12, 21);
                pin.curveTo(12, 21, 4.5f, 15.5f, 4.5f, 10);
                pin.append(new Arc2D.Float(4.5f, 2.5f, 15, 15, 180, -180, Arc2D.OPEN), true);
                g.draw(pin);
                g.draw(new Ellipse2D.Float(9, 7, 6, 6));
            }
            case PLAYER -> {
                g.draw(new Ellipse2D.Float(7.5f, 3.5f, 9, 9));
                Path2D.Float body = new Path2D.Float();
                body.moveTo(4, 21);
                body.lineTo(4, 19.5f);
                body.quadTo(4, 15, 8.5f, 15);
                body.lineTo(15.5f, 15);
                body.quadTo(20, 15, 20, 19.5f);
                body.lineTo(20, 21);
                g.draw(body);
            }
            case ENTITY -> {
                // four filled toes because an outlined toe at 16px is a smudge, the pad outlined like everything else
                g.fill(new Ellipse2D.Float(3, 8, 4, 4));
                g.fill(new Ellipse2D.Float(7.5f, 3, 4, 4));
                g.fill(new Ellipse2D.Float(12.5f, 3, 4, 4));
                g.fill(new Ellipse2D.Float(17, 8, 4, 4));
                Path2D.Float pad = new Path2D.Float();
                pad.moveTo(12, 11);
                pad.curveTo(9, 11, 6.5f, 14, 6.5f, 17);
                pad.curveTo(6.5f, 19.5f, 8.2f, 21, 10, 21);
                pad.curveTo(11, 21, 11.4f, 20.5f, 12, 20.5f);
                pad.curveTo(12.6f, 20.5f, 13, 21, 14, 21);
                pad.curveTo(15.8f, 21, 17.5f, 19.5f, 17.5f, 17);
                pad.curveTo(17.5f, 14, 15, 11, 12, 11);
                pad.closePath();
                g.draw(pad);
            }
            case BOAT -> {
                // sail bottom and hull top are 3.5 apart so there is a visible strip of air between them at 24px,
                // any closer and the two strokes melt into one smudge
                poly(g, true, 3, 14, 6, 20, 18, 20, 21, 14);
                line(g, 12, 3, 12, 14);
                poly(g, true, 12, 3, 19, 10.5f, 12, 10.5f);
            }
            case PARKOUR -> {
                // a jump from a low ledge up to a high one
                poly(g, false, 3, 20, 3, 15, 8, 15);
                poly(g, false, 16, 20, 16, 13, 21, 13);
                Path2D.Float arc = new Path2D.Float();
                arc.moveTo(5.5f, 12);
                arc.quadTo(12, 1, 18.5f, 10);
                g.draw(arc);
                head(g, 18.5f, 10, 0.585f, 0.81f, 3, 2.4f);
            }
            case BOLT -> poly(g, true, 13, 3, 5, 13.5f, 11, 13.5f, 10.5f, 21, 19, 10.5f, 13, 10.5f);
            case RUN -> {
                g.fill(new Ellipse2D.Float(13.5f, 3, 4, 4));
                poly(g, false, 5, 11.5f, 9, 7.5f, 13.5f, 8.5f, 17, 12.5f, 21, 12.5f);
                poly(g, false, 13.5f, 8.5f, 10, 15, 14.5f, 17, 16.5f, 21);
                poly(g, false, 10, 15, 6.5f, 19.5f, 3, 19.5f);
            }
            case PICKAXE -> {
                // the inventory pose: handle up and to the right, crescent head across the top right corner. the
                // head is two curves off the same tips so it has some mass, and the handle stops at the inner curve
                // instead of poking through it. a lone arc with a stick reads as an umbrella
                Path2D.Float head = new Path2D.Float();
                head.moveTo(6, 4);
                head.quadTo(20, 4, 20, 18);
                head.quadTo(15, 9, 6, 4);
                g.draw(head);
                line(g, 4, 20, 14, 10);
            }
            case BLOCK -> cube(g);
            case WRENCH -> poly(g, true, 15, 3.5f, 13, 8, 16, 11, 20.5f, 9, 19.5f, 13.5f, 15, 15.5f, 7.5f, 21, 3.5f, 17.5f, 10, 10.5f, 10, 5);
            case BACKPACK -> {
                // body, grab handle, front pocket. no shoulder straps: beside the body they merge with its sides
                // into one fat smear, pushed out far enough to clear it they float like ears
                g.draw(new RoundRectangle2D.Float(5, 6, 14, 15, 6, 6));
                g.draw(new Arc2D.Float(8.5f, 3.5f, 7, 6, 0, 180, Arc2D.OPEN));
                g.draw(new RoundRectangle2D.Float(8.5f, 13, 7, 5, 2, 2));
            }
            case WATER -> {
                Path2D.Float drop = new Path2D.Float();
                drop.moveTo(12, 3);
                drop.curveTo(8, 8.5f, 5, 11.5f, 5, 15.5f);
                drop.curveTo(5, 22.5f, 19, 22.5f, 19, 15.5f);
                drop.curveTo(19, 11.5f, 16, 8.5f, 12, 3);
                g.draw(drop);
            }
            case LADDER -> {
                line(g, 6, 3, 6, 21);
                line(g, 18, 3, 18, 21);
                line(g, 6, 7, 18, 7);
                line(g, 6, 12, 18, 12);
                line(g, 6, 17, 18, 17);
            }
            case EYE -> {
                Path2D.Float eye = new Path2D.Float();
                eye.moveTo(3, 12);
                eye.quadTo(12, 1, 21, 12);
                eye.quadTo(12, 23, 3, 12);
                g.draw(eye);
                g.draw(new Ellipse2D.Float(8.5f, 8.5f, 7, 7));
            }
            case CHEVRON_UP -> poly(g, false, 6, 15, 12, 9, 18, 15);
            case CHEVRON_LEFT -> poly(g, false, 15, 6, 9, 12, 15, 18);
            case ROTATE_LEFT -> turn(g);
            case ROTATE_RIGHT -> {
                // the same turn seen in a mirror
                AffineTransform t = g.getTransform();
                g.translate(24, 0);
                g.scale(-1, 1);
                turn(g);
                g.setTransform(t);
            }
            case FLIP -> {
                line(g, 12, 4, 12, 7);
                line(g, 12, 10.5f, 12, 13.5f);
                line(g, 12, 17, 12, 20);
                poly(g, true, 9, 6, 9, 18, 3.5f, 12);
                poly(g, true, 15, 6, 15, 18, 20.5f, 12);
            }
            case LEVEL_UP -> {
                poly(g, false, 6, 13, 12, 7, 18, 13);
                line(g, 6, 18, 18, 18);
            }
            case LEVEL_DOWN -> {
                line(g, 6, 6, 18, 6);
                poly(g, false, 6, 11, 12, 17, 18, 11);
            }
            case MOVE -> {
                line(g, 12, 4, 12, 20);
                line(g, 4, 12, 20, 12);
                poly(g, false, 9.5f, 6.5f, 12, 4, 14.5f, 6.5f);
                poly(g, false, 9.5f, 17.5f, 12, 20, 14.5f, 17.5f);
                poly(g, false, 6.5f, 9.5f, 4, 12, 6.5f, 14.5f);
                poly(g, false, 17.5f, 9.5f, 20, 12, 17.5f, 14.5f);
            }
            // the title screen set, ported call for call from gui-mock/icons.js so the mock can't drift
            case GEAR -> gear(g);
            case GLOBE -> {
                g.draw(new Ellipse2D.Float(3, 3, 18, 18));
                g.draw(new Ellipse2D.Float(8, 3, 8, 18));
                line(g, 3, 12, 21, 12);
            }
            case PEOPLE -> {
                // the full body for the one in front, the one behind only gets a head and a shoulder
                g.draw(new Ellipse2D.Float(5.5f, 5, 7, 7));
                Path2D.Float front = new Path2D.Float();
                front.moveTo(2.5f, 20.5f);
                front.lineTo(2.5f, 19);
                front.quadTo(2.5f, 15, 6.5f, 15);
                front.lineTo(11.5f, 15);
                front.quadTo(15.5f, 15, 15.5f, 19);
                front.lineTo(15.5f, 20.5f);
                g.draw(front);
                g.draw(new Ellipse2D.Float(14, 4.5f, 6, 6));
                Path2D.Float back = new Path2D.Float();
                back.moveTo(17, 13.5f);
                back.quadTo(21.5f, 13.5f, 21.5f, 18);
                back.lineTo(21.5f, 20.5f);
                g.draw(back);
            }
            case CLOUD -> {
                Path2D.Float c = new Path2D.Float();
                c.moveTo(7, 18.5f);
                c.lineTo(17.5f, 18.5f);
                c.curveTo(19.5f, 18.5f, 21, 17, 21, 15);
                c.curveTo(21, 13, 19.5f, 11.5f, 17.5f, 11.5f);
                c.curveTo(17, 8, 14.5f, 5.5f, 11.5f, 5.5f);
                c.curveTo(8.5f, 5.5f, 6.2f, 7.8f, 6, 10.7f);
                c.curveTo(4.2f, 11.2f, 3, 12.8f, 3, 14.7f);
                c.curveTo(3, 16.8f, 4.8f, 18.5f, 7, 18.5f);
                c.closePath();
                g.draw(c);
            }
            case PUZZLE -> {
                // a square piece with a knob on top and one on the right, the knobs are 270 degree arcs
                Path2D.Float p = new Path2D.Float();
                p.moveTo(3.5f, 8.5f);
                p.lineTo(8.4f, 8.5f);
                p.append(new Arc2D.Float(8.2f, 3.5f, 4.6f, 4.6f, 225, -270, Arc2D.OPEN), true);
                p.lineTo(12.6f, 8.5f);
                p.lineTo(17, 8.5f);
                p.lineTo(17, 12.4f);
                p.append(new Arc2D.Float(16.8f, 11.9f, 4.6f, 4.6f, 135, -270, Arc2D.OPEN), true);
                p.lineTo(17, 16);
                p.lineTo(17, 20.5f);
                p.lineTo(3.5f, 20.5f);
                p.closePath();
                g.draw(p);
            }
            case POWER -> {
                // the gap is centred on the top, 60 degrees wide, the stem drops into it
                g.draw(new Arc2D.Float(4.5f, 5.5f, 15, 15, 60, -300, Arc2D.OPEN));
                line(g, 12, 3.5f, 12, 12);
            }
            case LANGUAGE -> {
                // globe in the top left, an A in the bottom right, the A's left leg just clears the globe
                g.draw(new Ellipse2D.Float(3, 3, 12, 12));
                g.draw(new Ellipse2D.Float(6.5f, 3, 5, 12));
                line(g, 3, 9, 15, 9);
                poly(g, false, 13, 21, 17, 11.5f, 21, 21);
                line(g, 14.4f, 17.7f, 19.6f, 17.7f);
            }
            case ACCESSIBILITY -> {
                g.draw(new Ellipse2D.Float(3, 3, 18, 18));
                g.fill(new Ellipse2D.Float(10.5f, 5.6f, 3, 3));
                line(g, 7.5f, 10, 16.5f, 10);
                line(g, 12, 10, 12, 14);
                poly(g, false, 9, 18.5f, 12, 14, 15, 18.5f);
            }
            case SPEAKER -> {
                // two rings round one point just right of the cone, so the waves read as coming out of it
                speaker(g);
                g.draw(new Arc2D.Float(10, 8.5f, 7, 7, -50, 100, Arc2D.OPEN));
                g.draw(new Arc2D.Float(6.5f, 5, 14, 14, -50, 100, Arc2D.OPEN));
            }
            case SPEAKER_OFF -> {
                speaker(g);
                line(g, 15.5f, 9.5f, 20.5f, 14.5f);
                line(g, 20.5f, 9.5f, 15.5f, 14.5f);
            }
        }
    }

    // a box and a cone, flat side left. the art is shifted left of centre so the waves have room on the right
    private static void speaker(Graphics2D g) {
        poly(g, true, 3.5f, 9.5f, 7, 9.5f, 12, 5, 12, 19, 7, 14.5f, 3.5f, 14.5f);
    }

    // eight teeth off a circle: inner radius 7, outer 9.5, the valleys are arcs so it reads as a gear and not a stop
    // sign at 30px. tooth k ends at a+13 on the inner circle, the valley runs to the next tooth's a+45-13
    private static void gear(Graphics2D g) {
        float cx = 12f;
        float cy = 12f;
        float ri = 7f;
        float ro = 9.5f;
        Path2D.Float p = new Path2D.Float();
        for (int k = 0; k < 8; k++) {
            int a = k * 45;
            double a0 = Math.toRadians(a - 13);
            double a1 = Math.toRadians(a - 8);
            double a2 = Math.toRadians(a + 8);
            float x0 = cx + ri * (float) Math.cos(a0);
            float y0 = cy - ri * (float) Math.sin(a0);
            if (k == 0) {
                p.moveTo(x0, y0);
            } else {
                p.lineTo(x0, y0);
            }
            p.lineTo(cx + ro * (float) Math.cos(a1), cy - ro * (float) Math.sin(a1));
            p.lineTo(cx + ro * (float) Math.cos(a2), cy - ro * (float) Math.sin(a2));
            p.append(new Arc2D.Float(cx - ri, cy - ri, 2 * ri, 2 * ri, a + 13, 19, Arc2D.OPEN), true);
        }
        p.closePath();
        g.draw(p);
        g.draw(new Ellipse2D.Float(9, 9, 6, 6));
    }

    // three quarters of a circle open at the top, the arrow on the loose end going counter clockwise, and a dot for
    // the pivot. same arc trick as RESET, the dot is the difference
    private static void turn(Graphics2D g) {
        float cx = 12f;
        float cy = 12f;
        float r = 7f;
        g.draw(new Arc2D.Float(cx - r, cy - r, 2 * r, 2 * r, 135, 270, Arc2D.OPEN));
        double th = Math.toRadians(45);
        float px = cx + r * (float) Math.cos(th);
        float py = cy - r * (float) Math.sin(th);
        head(g, px, py, (float) -Math.sin(th), (float) -Math.cos(th), 3.5f, 2.75f);
        g.fill(new Ellipse2D.Float(cx - 1.5f, cy - 1.5f, 3, 3));
    }

    // an isometric cube seen from a corner, 18 units tall so it matches the rest of the family
    private static void cube(Graphics2D g) {
        poly(g, true, 12, 3, 20.5f, 7.5f, 20.5f, 16.5f, 12, 21, 3.5f, 16.5f, 3.5f, 7.5f);
        poly(g, false, 3.5f, 7.5f, 12, 12, 20.5f, 7.5f);
        line(g, 12, 12, 12, 21);
    }

    private static void line(Graphics2D g, float x1, float y1, float x2, float y2) {
        g.draw(new Line2D.Float(x1, y1, x2, y2));
    }

    private static void poly(Graphics2D g, boolean close, float... xy) {
        Path2D.Float p = new Path2D.Float();
        p.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) {
            p.lineTo(xy[i], xy[i + 1]);
        }
        if (close) {
            p.closePath();
        }
        g.draw(p);
    }

    // a stroke wide dot, which is what a round capped zero length line would be if awt drew those
    private static void dot(Graphics2D g, float x, float y, float w) {
        g.fill(new Ellipse2D.Float(x - w * 0.5f, y - w * 0.5f, w, w));
    }

    // open v arrowhead whose tip is at (x, y) and which points along the unit vector (tx, ty)
    private static void head(Graphics2D g, float x, float y, float tx, float ty, float len, float spread) {
        float bx = x - tx * len;
        float by = y - ty * len;
        float nx = -ty;
        float ny = tx;
        poly(g, false, bx + nx * spread, by + ny * spread, x, y, bx - nx * spread, by - ny * spread);
    }
}
