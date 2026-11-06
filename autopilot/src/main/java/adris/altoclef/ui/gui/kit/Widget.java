package adris.altoclef.ui.gui.kit;

import java.util.Collections;
import java.util.List;

// a retained tree. bounds are device pixels set by the parent, then layout() places the children. input walks the
// children last-drawn-first so overlays win. nothing in here may throw out of draw or an input handler: a widget that
// has a bad day is a widget that draws nothing, not a crashed screen (the Shell wraps the root as a last resort)
public abstract class Widget {

    protected final Theme theme;
    protected int x;
    protected int y;
    protected int w;
    protected int h;
    protected boolean hovered;
    protected boolean focused;
    protected boolean visible = true;

    protected Widget(Theme theme) {
        this.theme = theme;
    }

    public final void setBounds(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        layout();
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int w() {
        return w;
    }

    public int h() {
        return h;
    }

    public boolean visible() {
        return visible;
    }

    public void setVisible(boolean v) {
        visible = v;
    }

    // -1 means "whatever the parent gives me"
    public int preferredWidth() {
        return -1;
    }

    public int preferredHeight() {
        return -1;
    }

    protected void layout() {
    }

    public abstract void draw(Canvas c, Frame f);

    public List<Widget> children() {
        return Collections.emptyList();
    }

    public boolean contains(float px, float py) {
        return visible && px >= x && py >= y && px < x + w && py < y + h;
    }

    // hover state for this widget and the subtree. returns the deepest hovered widget or null
    public Widget mouseMove(float mx, float my) {
        hovered = contains(mx, my);
        Widget deepest = hovered ? this : null;
        List<Widget> kids = children();
        for (int i = kids.size() - 1; i >= 0; i--) {
            Widget k = kids.get(i);
            Widget d = k.mouseMove(mx, my);
            if (d != null && deepest == this) {
                deepest = d;
            }
        }
        return deepest;
    }

    public boolean mouseDown(float mx, float my, int button, int mods) {
        if (!contains(mx, my)) {
            return false;
        }
        List<Widget> kids = children();
        for (int i = kids.size() - 1; i >= 0; i--) {
            if (kids.get(i).mouseDown(mx, my, button, mods)) {
                return true;
            }
        }
        return false;
    }

    public boolean mouseUp(float mx, float my, int button, int mods) {
        List<Widget> kids = children();
        for (int i = kids.size() - 1; i >= 0; i--) {
            if (kids.get(i).mouseUp(mx, my, button, mods)) {
                return true;
            }
        }
        return false;
    }

    public boolean mouseDrag(float mx, float my, int button, float dx, float dy) {
        List<Widget> kids = children();
        for (int i = kids.size() - 1; i >= 0; i--) {
            if (kids.get(i).mouseDrag(mx, my, button, dx, dy)) {
                return true;
            }
        }
        return false;
    }

    // dy in notches, positive means the wheel went up (content should move down)
    public boolean scroll(float mx, float my, float dy) {
        if (!contains(mx, my)) {
            return false;
        }
        List<Widget> kids = children();
        for (int i = kids.size() - 1; i >= 0; i--) {
            if (kids.get(i).scroll(mx, my, dy)) {
                return true;
            }
        }
        return false;
    }

    // keys go to the focused widget only (the Shell routes them), so the default is "not mine"
    public boolean keyDown(int key, int mods) {
        return false;
    }

    public boolean charTyped(char c) {
        return false;
    }

    public boolean focusable() {
        return false;
    }

    public boolean isFocused() {
        return focused;
    }

    public void setFocused(boolean f) {
        focused = f;
    }

    // every focusable widget in layout order, for tab cycling
    public void collectFocusable(List<Widget> out) {
        if (!visible) {
            return;
        }
        if (focusable()) {
            out.add(this);
        }
        for (Widget k : children()) {
            k.collectFocusable(out);
        }
    }

    // 20 hz from the game's tick, for live lists. frames are for drawing, ticks are for data
    public void tick() {
        for (Widget k : children()) {
            k.tick();
        }
    }
}
