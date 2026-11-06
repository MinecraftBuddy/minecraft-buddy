package adris.altoclef.ui.gui.kit;

import adris.altoclef.ui.gui.facade.KeyNames;
import adris.altoclef.ui.gui.model.KeyBind;

import java.util.function.IntConsumer;

// shows a key's name, and when clicked waits for the next key and binds it. the rules (esc cancels, backspace clears,
// a plain shift is a fine bind) live in KeyBind, this is the button around it. while waiting it swallows every key so
// the shell cannot mistake the new bind for the old close key
public class KeyCapture extends Control implements FocusOnClick {

    private final KeyBind bind;
    private final KeyNames names;
    private final IntConsumer onBound;
    private final ShellContext ctx;
    private final Anim hoverA = new Anim(0f);
    private final Anim ringA = new Anim(0f);

    public KeyCapture(Theme theme, int currentKey, KeyNames names, IntConsumer onBound, ShellContext ctx) {
        this(theme, new KeyBind(currentKey), names, onBound, ctx);
    }

    // for when the shell and the control should look at the same bind, so a rebind lands everywhere at once
    public KeyCapture(Theme theme, KeyBind bind, KeyNames names, IntConsumer onBound, ShellContext ctx) {
        super(theme);
        this.bind = bind;
        this.names = names;
        this.onBound = onBound;
        this.ctx = ctx;
    }

    public KeyBind bind() {
        return bind;
    }

    public boolean capturing() {
        return bind.capturing;
    }

    // for code (the other capture control changed it): no callback
    public void setKey(int key) {
        bind.key = key;
    }

    @Override
    public int preferredWidth() {
        return theme.px(96);
    }

    // how wide the box has to be to show the whole key name (same 12 the ellipsis in draw keeps clear), so a sidebar
    // can decide what to drop first
    public int neededWidth() {
        return (int) Math.ceil(theme.measurer.measure(shown(), theme.label)) + theme.px(12);
    }

    private String shown() {
        if (bind.capturing) {
            return "Press a key";
        }
        if (bind.key < 0) {
            return "Unbound";
        }
        String n = null;
        try {
            n = names == null ? null : names.name(bind.key);
        } catch (Throwable e) {
            Safe.report("key name", e);
        }
        return n == null || n.isEmpty() ? "Key " + bind.key : n;
    }

    @Override
    public int preferredHeight() {
        return theme.px(Theme.CONTROL_H);
    }

    @Override
    public boolean focusable() {
        return visible;
    }

    @Override
    public void setFocused(boolean f) {
        super.setFocused(f);
        if (!f) {
            bind.cancelCapture();
        }
    }

    @Override
    public void draw(Canvas c, Frame f) {
        stamp(f);
        hoverTo(hoverA, hovered);
        ringA.set(bind.capturing ? 1f : 0f, now, Theme.ANIM_FAST);
        float r = theme.pxf(Theme.CONTROL_RADIUS);
        float ring = ringA.value(now);
        // a secondary button that happens to show a key name. waiting for a key is the accent ring, same as focus
        c.fillRoundRect(x, y, w, h, r, Theme.lerp(theme.color(Theme.RAISED), theme.color(Theme.HOVER), hoverA.value(now) * 0.8f));
        c.strokeRoundRect(x, y, w, h, r, Theme.fade(theme.color(Theme.HAIRLINE_STRONG), 0.6f));
        if (ring > 0.01f || focused) {
            focusRing(c, r, Math.max(ring, focused ? 1f : 0f));
        }
        String text = shown();
        int col = theme.color(bind.capturing || bind.key >= 0 ? Theme.TEXT : Theme.TEXT_3);
        text = theme.measurer.ellipsize(text, theme.label, Math.max(0, w - theme.px(12)));
        float tw = c.measure(text, theme.label);
        c.text(text, x + (w - tw) / 2f, baseline(c, theme.label, y, h), theme.label, col);
    }

    private void start() {
        // focus first: taking focus from ourselves would cancel the capture we are about to start
        ctx.focus(this);
        bind.startCapture();
    }

    @Override
    public boolean mouseDown(float mx, float my, int button, int mods) {
        if (!contains(mx, my) || button != 0) {
            return false;
        }
        if (bind.capturing) {
            bind.cancelCapture();
        } else {
            start();
        }
        return true;
    }

    @Override
    public boolean keyDown(int key, int mods) {
        if (!focused) {
            return false;
        }
        if (!bind.capturing) {
            if (Keys.isEnter(key) || key == Keys.SPACE) {
                start();
                return true;
            }
            return false;
        }
        KeyBind.Outcome out = bind.press(key);
        switch (out) {
            case BOUND -> Safe.run("key bound", () -> onBound.accept(bind.key));
            case CLEARED -> Safe.run("key cleared", () -> onBound.accept(-1));
            default -> {
            }
        }
        // every key is spent while waiting, esc included: it cancels the capture, it does not close the gui
        return true;
    }
}
