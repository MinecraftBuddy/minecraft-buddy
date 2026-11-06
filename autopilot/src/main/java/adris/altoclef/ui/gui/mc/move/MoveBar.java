package adris.altoclef.ui.gui.mc.move;

import adris.altoclef.ui.gui.facade.SchematicsAccess.Placement;
import adris.altoclef.ui.gui.kit.Button;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.IconButton;
import adris.altoclef.ui.gui.kit.TextStyle;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.Widget;

import java.util.List;

// the strip along the bottom of the move screen: turn, mirror, move to me and done, where the build is now, and one
// line saying how the mouse works. a floating card like the toasts, so it reads as the click gui's even with the
// world showing all around it
public final class MoveBar extends Widget {

    public interface Actions {
        void rotate(boolean clockwise);

        void mirror();

        void toMe();

        void done();
    }

    static final String HINT = "drag arrows · right-drag to look · middle-drag to pan · scroll to zoom · wasd, space, shift to slide"
        + " · esc to finish";

    private final IconButton turnLeft;
    private final IconButton turnRight;
    private final Button mirror;
    private final Button feet;
    private final Button done;
    private final List<Widget> kids;
    private String where = "";
    private float cardX;
    private float cardY;
    private float cardW;
    private float cardH;
    private float labelX;
    private float labelW;

    public MoveBar(Theme theme, Actions actions) {
        super(theme);
        turnLeft = new IconButton(theme, Icon.ROTATE_LEFT, "Turn left", () -> actions.rotate(false));
        turnRight = new IconButton(theme, Icon.ROTATE_RIGHT, "Turn right", () -> actions.rotate(true));
        mirror = new Button(theme, "Mirror", Button.Kind.GHOST, Icon.FLIP, actions::mirror);
        feet = new Button(theme, "Move to me", Button.Kind.GHOST, Icon.PIN, actions::toMe);
        done = new Button(theme, "Done", Button.Kind.PRIMARY, Icon.CHECK, actions::done);
        kids = List.of(turnLeft, turnRight, mirror, feet, done);
    }

    // "x 100  y 64  z -40 · turned 90° · mirrored", the turn and flip only when there is one
    public static String label(Placement p) {
        if (p == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("x ").append(p.x()).append("  y ").append(p.y()).append("  z ").append(p.z());
        if (p.turns() != 0) {
            sb.append(" · turned ").append(p.turns() * 90).append('°');
        }
        if (p.mirrored()) {
            sb.append(" · mirrored");
        }
        return sb.toString();
    }

    public void show(Placement p) {
        String next = label(p);
        if (!next.equals(where)) {
            where = next;
            layout();
        }
    }

    // true when the point is on the card, so a click there never grabs an arrow or turns the camera
    public boolean covers(float mx, float my) {
        return mx >= cardX && my >= cardY && mx < cardX + cardW && my < cardY + cardH;
    }

    @Override
    protected void layout() {
        int pad = theme.px(Theme.GAP);
        int gap = theme.px(Theme.SPACE_1);
        int ch = theme.px(Theme.CONTROL_H);
        mirror.setText("Mirror");
        feet.setText("Move to me");
        float labelWanted = theme.measurer.measure(where, theme.small.asTabular()) + 2 * theme.px(Theme.GAP);
        // a narrow window drops the words first, then the label shrinks to whatever is left
        float room = w - 2 * theme.px(Theme.PAD);
        if (buttons(ch, gap) + labelWanted + 2 * pad > room) {
            mirror.setText("");
            feet.setText("");
        }
        float buttons = buttons(ch, gap);
        labelW = Math.max(0, Math.min(labelWanted, room - buttons - 2 * pad));
        cardW = buttons + labelW + 2 * pad;
        cardH = ch + 2 * pad;
        cardX = x + (w - cardW) / 2f;
        float hintH = theme.measurer.lineHeight(theme.small) + theme.px(Theme.SPACE_1);
        cardY = y + h - theme.px(Theme.PAD) - cardH - hintH;
        int bx = Math.round(cardX + pad);
        int by = Math.round(cardY + pad);
        turnLeft.setBounds(bx, by, ch, ch);
        bx += ch;
        turnRight.setBounds(bx, by, ch, ch);
        bx += ch + gap;
        mirror.setBounds(bx, by, mirror.preferredWidth(), ch);
        bx += mirror.preferredWidth() + gap;
        feet.setBounds(bx, by, feet.preferredWidth(), ch);
        bx += feet.preferredWidth();
        labelX = bx;
        int dw = done.preferredWidth();
        done.setBounds(Math.round(cardX + cardW - pad - dw), by, dw, ch);
    }

    private float buttons(int ch, int gap) {
        return 2 * ch + gap + mirror.preferredWidth() + gap + feet.preferredWidth() + done.preferredWidth();
    }

    @Override
    public List<Widget> children() {
        return kids;
    }

    @Override
    public boolean contains(float px, float py) {
        return visible && covers(px, py);
    }

    @Override
    public void draw(Canvas c, Frame f) {
        theme.floating(c, cardX, cardY, cardW, cardH, theme.pxf(Theme.CARD_RADIUS));
        for (Widget k : kids) {
            k.draw(c, f);
        }
        TextStyle s = theme.small.asTabular();
        if (labelW > theme.px(24)) {
            String text = c.ellipsize(where, s, labelW - 2 * theme.px(Theme.GAP));
            float tw = c.measure(text, s);
            c.text(text, labelX + (labelW - tw) / 2f, mid(c, s), s, theme.color(Theme.TEXT_2));
        }
        // the hint sits under the card on a soft backing, the world behind it can be any colour at all
        String hint = c.ellipsize(HINT, theme.small, w - 2 * theme.px(Theme.PAD));
        float hw = c.measure(hint, theme.small);
        float hx = x + (w - hw) / 2f;
        float hy = cardY + cardH + theme.px(Theme.SPACE_1);
        float lh = c.lineHeight(theme.small);
        c.fillRoundRect(hx - theme.px(6), hy - theme.px(1), hw + theme.px(12), lh + theme.px(2), theme.pxf(4),
            Theme.fade(theme.color(Theme.SCRIM), 1f));
        c.text(hint, hx, hy + c.ascent(theme.small), theme.small, 0xFFFFFFFF);
    }

    private float mid(Canvas c, TextStyle s) {
        return done.y() + done.h() / 2f + (c.ascent(s) - c.descent(s)) / 2f;
    }

    // test hooks
    Button control(String name) {
        return switch (name) {
            case "turnLeft" -> turnLeft;
            case "turnRight" -> turnRight;
            case "mirror" -> mirror;
            case "feet" -> feet;
            default -> done;
        };
    }

    String where() {
        return where;
    }
}
