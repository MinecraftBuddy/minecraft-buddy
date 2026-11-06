package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.kit.Keys;
import adris.altoclef.ui.gui.kit.TextField;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.model.Basket;

// a text field wearing a number's clothes: digits only, four of them, centred, empty means zero. enter or leaving the
// box commits, esc walks away from the edit. used for a line's count and for the mine limit
final class GetCountField extends TextField {

    interface Host {
        void commit(int n);

        // up/down while the box has focus
        void step(int dir, int mods);

        void done();
    }

    // every count box in the basket (a row's and the mine limit) is this wide, with [-] [+] buttons this big beside it
    static int boxW(Theme theme) {
        return theme.px(56);
    }

    static int stepperPx(Theme theme) {
        return theme.px(Theme.CONTROL_H - 2);
    }

    private final Host host;
    private String boundText = "";
    private String hint = "0";

    GetCountField(Theme theme, Host host) {
        super(theme, "", null);
        this.host = host;
        edit.setCharFilter(ch -> ch >= '0' && ch <= '9');
        edit.setMaxLength(4);
    }

    // shows n as the committed value, zero as an empty box with the hint in it
    void bind(int n, String hintText) {
        hint = hintText;
        setPlaceholder(hintText);
        boundText = n == 0 ? "" : String.valueOf(n);
        setText(boundText);
    }

    // for a box that keeps focus while the number moves under it
    void sync(int n) {
        boundText = n == 0 ? "" : String.valueOf(n);
        setText(boundText);
    }

    @Override
    protected int leftInset() {
        String t = edit.text();
        float tw = theme.measurer.measure(t.isEmpty() ? hint : t, theme.body);
        return Math.max(theme.px(4), Math.round((w - tw) / 2f));
    }

    @Override
    protected int rightInset() {
        return theme.px(4);
    }

    void commitIfDirty() {
        String t = edit.text();
        if (t.equals(boundText)) {
            return;
        }
        int n;
        try {
            n = t.isEmpty() ? 0 : Integer.parseInt(t);
        } catch (NumberFormatException e) {
            n = Basket.MAX;
        }
        boundText = t;
        host.commit(n);
    }

    @Override
    public void setFocused(boolean f) {
        boolean was = isFocused();
        super.setFocused(f);
        if (was && !f) {
            commitIfDirty();
        }
    }

    @Override
    public boolean keyDown(int key, int mods) {
        if (!isFocused()) {
            return false;
        }
        if (Keys.isEnter(key)) {
            commitIfDirty();
            host.done();
            return true;
        }
        if (key == Keys.ESCAPE) {
            // the shell blurs us next, with nothing left to commit
            setText(boundText);
            return false;
        }
        if (key == Keys.UP || key == Keys.DOWN) {
            commitIfDirty();
            host.step(key == Keys.UP ? 1 : -1, mods);
            return true;
        }
        return super.keyDown(key, mods);
    }
}
