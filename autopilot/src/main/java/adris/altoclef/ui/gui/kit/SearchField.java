package adris.altoclef.ui.gui.kit;

import java.util.List;
import java.util.function.Consumer;

// a text field with a magnifier on the left and an x on the right once there is something to clear. esc clears
// first and only closes the gui when there is nothing left to clear
public class SearchField extends TextField {

    private final IconButton clear;
    private final List<Widget> kids;

    public SearchField(Theme theme, String placeholder, Consumer<String> onChange) {
        super(theme, placeholder, null);
        setOnChange(onChange);
        clear = new IconButton(theme, Icon.CLOSE, "Clear", this::clearAndNotify);
        clear.setFocusable(false);
        kids = List.of(clear);
    }

    private boolean hasText() {
        return !edit.text().isEmpty();
    }

    // programmatic reset that still tells the listener, unlike setText
    public void clearAndNotify() {
        if (!hasText()) {
            return;
        }
        edit.setText("");
        edited(true);
    }

    @Override
    protected int leftInset() {
        return theme.px(10) + theme.px(Theme.ICON) + theme.px(8);
    }

    @Override
    protected int rightInset() {
        return hasText() ? theme.px(30) : theme.px(10);
    }

    @Override
    public List<Widget> children() {
        return hasText() ? kids : List.of();
    }

    private void placeClear() {
        int s = theme.px(24);
        clear.setBounds(x + w - s - theme.px(3), y + (h - s) / 2, s, s);
    }

    @Override
    protected void drawAdornments(Canvas c, Frame f) {
        int is = theme.px(Theme.ICON);
        c.icon(Icon.SEARCH, x + theme.px(10), y + (h - is) / 2f, is, focused ? theme.color(Theme.TEXT_2) : theme.color(Theme.TEXT_3));
        if (hasText()) {
            placeClear();
            clear.draw(c, f);
        }
    }

    @Override
    public boolean keyDown(int key, int mods) {
        if (key == Keys.ESCAPE) {
            if (hasText()) {
                clearAndNotify();
                return true;
            }
            return false;
        }
        return super.keyDown(key, mods);
    }

    @Override
    public boolean mouseDown(float mx, float my, int button, int mods) {
        if (hasText() && clear.mouseDown(mx, my, button, mods)) {
            return true;
        }
        return super.mouseDown(mx, my, button, mods);
    }

    @Override
    public boolean mouseUp(float mx, float my, int button, int mods) {
        if (hasText() && clear.mouseUp(mx, my, button, mods)) {
            return true;
        }
        return super.mouseUp(mx, my, button, mods);
    }
}
