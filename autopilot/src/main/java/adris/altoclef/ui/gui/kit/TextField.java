package adris.altoclef.ui.gui.kit;

import java.util.function.Consumer;

// a single line text box on top of TextEdit. caret blink, selection, horizontal scroll, mouse caret placement by
// measuring prefixes. text metrics are cached per edit so a frame costs a handful of measure calls, not a pile
public class TextField extends Control implements FocusOnClick {

    private static final long DOUBLE_CLICK_NANOS = Anim.ms(400);
    private static final long BLINK_NANOS = Anim.ms(530);
    private static final long STEADY_NANOS = Anim.ms(500);

    protected final TextEdit edit = new TextEdit();
    private String placeholder;
    private final Consumer<String> onCommit;
    private Consumer<String> onChange;
    private final Anim focusA = new Anim(0f);
    private float scrollX;
    private boolean dragging;
    private boolean invalid;
    private boolean editFlag = true;
    private long lastEditNanos;
    private long lastClickNanos = Long.MIN_VALUE / 2;
    private float lastClickX;

    private String cachedText;
    private int cachedCaret = -1;
    private int cachedAnchor = -1;
    private float caretPx;
    private float selFromPx;
    private float selToPx;
    private float totalPx;

    public TextField(Theme theme, String placeholder, Consumer<String> onCommit) {
        super(theme);
        this.placeholder = placeholder == null ? "" : placeholder;
        this.onCommit = onCommit;
    }

    public TextEdit edit() {
        return edit;
    }

    public String text() {
        return edit.text();
    }

    // for code: no onChange, caret to the end
    public void setText(String s) {
        edit.setText(s);
        scrollX = 0f;
        editFlag = true;
        committedText = edit.text();
    }

    // what escape goes back to: the last text that was committed or set by code
    private String committedText = "";

    protected String revertText() {
        return committedText;
    }

    // true when the box holds an edit nobody has committed yet
    public boolean dirty() {
        return !edit.text().equals(revertText());
    }

    public void setPlaceholder(String p) {
        placeholder = p == null ? "" : p;
    }

    public void setOnChange(Consumer<String> l) {
        onChange = l;
    }

    public void setInvalid(boolean v) {
        invalid = v;
    }

    public boolean invalid() {
        return invalid;
    }

    // room taken by decorations inside the box, subclasses with icons override these
    protected int leftInset() {
        return theme.px(10);
    }

    protected int rightInset() {
        return theme.px(10);
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
            dragging = false;
        }
        editFlag = true;
    }

    public void commit() {
        String t = edit.text();
        committedText = t;
        if (onCommit != null) {
            Safe.run("text field commit", () -> onCommit.accept(t));
        }
    }

    protected final void edited(boolean textChanged) {
        editFlag = true;
        if (textChanged) {
            onTextChanged();
        }
    }

    // NumberField hooks in here to clear its error as soon as you type again
    protected void onTextChanged() {
        if (onChange != null) {
            String t = edit.text();
            Safe.run("text field change", () -> onChange.accept(t));
        }
    }

    private void metrics() {
        String t = edit.text();
        if (t == cachedText && edit.caret() == cachedCaret && edit.anchor() == cachedAnchor) {
            return;
        }
        cachedText = t;
        cachedCaret = edit.caret();
        cachedAnchor = edit.anchor();
        TextMeasurer m = theme.measurer;
        TextStyle s = theme.body;
        caretPx = cachedCaret == 0 ? 0f : m.measure(t.substring(0, cachedCaret), s);
        if (cachedCaret == cachedAnchor) {
            selFromPx = caretPx;
            selToPx = caretPx;
        } else {
            float a = cachedAnchor == 0 ? 0f : m.measure(t.substring(0, cachedAnchor), s);
            selFromPx = Math.min(a, caretPx);
            selToPx = Math.max(a, caretPx);
        }
        totalPx = t.isEmpty() ? 0f : m.measure(t, s);
    }

    private float textAreaW() {
        return Math.max(1, w - leftInset() - rightInset());
    }

    // keep the caret inside the visible slice of text
    private void followCaret() {
        float avail = textAreaW();
        if (totalPx + 1 <= avail) {
            scrollX = 0f;
            return;
        }
        if (caretPx - scrollX > avail - 1) {
            scrollX = caretPx - avail + 1;
        }
        if (caretPx - scrollX < 0f) {
            scrollX = caretPx;
        }
        scrollX = Math.max(0f, Math.min(scrollX, totalPx + 1 - avail));
    }

    public int indexAt(float mx) {
        float rel = mx - (x + leftInset()) + scrollX;
        String t = edit.text();
        if (rel <= 0f || t.isEmpty()) {
            return 0;
        }
        TextMeasurer m = theme.measurer;
        int lo = 0;
        int hi = t.length();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (m.measure(t.substring(0, mid), theme.body) < rel) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        if (lo == 0) {
            return 0;
        }
        float m1 = m.measure(t.substring(0, lo), theme.body);
        float m0 = m.measure(t.substring(0, lo - 1), theme.body);
        return rel - m0 < m1 - rel ? lo - 1 : lo;
    }

    @Override
    public void draw(Canvas c, Frame f) {
        stamp(f);
        if (editFlag) {
            editFlag = false;
            lastEditNanos = now;
        }
        metrics();
        followCaret();
        focusA.set(focused ? 1f : 0f, now, Theme.ANIM_FAST);
        float r = theme.pxf(Theme.CONTROL_RADIUS);
        int border = invalid ? theme.color(Theme.DANGER) : Theme.lerp(theme.color(Theme.HAIRLINE), theme.color(Theme.ACCENT), focusA.value(now));
        c.fillRoundRect(x, y, w, h, r, theme.color(Theme.FIELD));
        c.strokeRoundRect(x, y, w, h, r, border);

        float tx = x + leftInset();
        float avail = textAreaW();
        float base = baseline(c, theme.body, y, h);
        c.pushClip(tx, y + 1, avail, h - 2);
        if (edit.text().isEmpty()) {
            if (!placeholder.isEmpty()) {
                c.text(placeholder, tx, base, theme.body, theme.color(Theme.TEXT_3));
            }
        } else {
            if (selToPx > selFromPx) {
                float inset = theme.px(6);
                c.fillRoundRect(tx + selFromPx - scrollX, y + inset, selToPx - selFromPx, h - 2 * inset, 2,
                        theme.color(Theme.ACCENT_SOFT));
            }
            c.text(edit.text(), tx - scrollX, base, theme.body, theme.color(Theme.TEXT));
        }
        if (focused && caretOn()) {
            float inset = theme.px(7);
            c.fillRect(Math.round(tx + caretPx - scrollX), y + inset, 1, h - 2 * inset, theme.color(Theme.TEXT));
        }
        c.popClip();
        drawAdornments(c, f);
    }

    // steady for half a second after you touch anything, then 530 on 530 off
    private boolean caretOn() {
        long since = now - lastEditNanos;
        if (since < STEADY_NANOS) {
            return true;
        }
        return ((since - STEADY_NANOS) / BLINK_NANOS) % 2 == 0;
    }

    // icons and buttons that live inside the box, drawn on top
    protected void drawAdornments(Canvas c, Frame f) {
    }

    @Override
    public boolean keyDown(int key, int mods) {
        if (!focused) {
            return false;
        }
        // altgr arrives as ctrl+alt on windows, and ctrl+alt+a must type its accented letter, not select everything
        boolean ctrl = Keys.ctrl(mods) && !Keys.alt(mods);
        boolean shift = Keys.shift(mods);
        switch (key) {
            case Keys.ENTER, Keys.KP_ENTER -> {
                editFlag = true;
                commit();
                return true;
            }
            // escape on a half typed value puts the last good one back; on a clean box it is not ours and the
            // shell goes on to blur or close
            case Keys.ESCAPE -> {
                if (!dirty()) {
                    return false;
                }
                edit.setText(revertText());
                scrollX = 0f;
                editFlag = true;
                onTextChanged();
                return true;
            }
            case Keys.BACKSPACE -> {
                edited(edit.backspace(ctrl));
                return true;
            }
            case Keys.DELETE -> {
                edited(edit.delete(ctrl));
                return true;
            }
            case Keys.LEFT -> {
                edit.left(ctrl, shift);
                edited(false);
                return true;
            }
            case Keys.RIGHT -> {
                edit.right(ctrl, shift);
                edited(false);
                return true;
            }
            case Keys.HOME -> {
                edit.home(shift);
                edited(false);
                return true;
            }
            case Keys.END -> {
                edit.end(shift);
                edited(false);
                return true;
            }
            default -> {
            }
        }
        if (ctrl) {
            Clipboard clip = clipboard();
            switch (key) {
                case Keys.A -> {
                    edit.selectAll();
                    edited(false);
                    return true;
                }
                case Keys.C -> {
                    if (clip != null) {
                        edit.copy(clip);
                    }
                    return true;
                }
                case Keys.X -> {
                    if (clip != null) {
                        edited(edit.cut(clip));
                    }
                    return true;
                }
                case Keys.V -> {
                    if (clip != null) {
                        edited(edit.paste(clip));
                    }
                    return true;
                }
                default -> {
                }
            }
        }
        return false;
    }

    private Clipboard clip;

    // the shell sets this when it is built so every field in every tab can copy and paste without being wired up one
    // by one. a field can still carry its own
    public static volatile Clipboard defaultClipboard;

    public TextField withClipboard(Clipboard c) {
        clip = c;
        return this;
    }

    protected Clipboard clipboard() {
        return clip != null ? clip : defaultClipboard;
    }

    @Override
    public boolean charTyped(char c) {
        if (!focused) {
            return false;
        }
        edited(edit.insert(String.valueOf(c)));
        return true;
    }

    @Override
    public boolean mouseDown(float mx, float my, int button, int mods) {
        if (!contains(mx, my) || button != 0) {
            return false;
        }
        int idx = indexAt(mx);
        boolean dbl = now - lastClickNanos < DOUBLE_CLICK_NANOS && Math.abs(mx - lastClickX) < theme.px(4);
        if (dbl) {
            edit.selectWordAt(idx);
            lastClickNanos = Long.MIN_VALUE / 2;
            dragging = false;
        } else {
            edit.setCaret(idx, Keys.shift(mods));
            lastClickNanos = now;
            lastClickX = mx;
            dragging = true;
        }
        editFlag = true;
        return true;
    }

    @Override
    public boolean mouseDrag(float mx, float my, int button, float dx, float dy) {
        if (!dragging) {
            return false;
        }
        edit.setCaret(indexAt(mx), true);
        editFlag = true;
        return true;
    }

    @Override
    public boolean mouseUp(float mx, float my, int button, int mods) {
        boolean was = dragging;
        dragging = false;
        return was;
    }
}
