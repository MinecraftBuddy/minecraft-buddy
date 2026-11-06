package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.kit.Keys;
import adris.altoclef.ui.gui.kit.TextField;
import adris.altoclef.ui.gui.kit.Theme;

import java.util.function.Consumer;
import java.util.function.IntConsumer;

// one of the r g b boxes of a colour setting: digits only, narrow, and up/down nudges by 1 (10 with shift). a full
// NumberField would spend half the row on its plus and minus buttons for a number that never goes past 255
final class SettingChannel extends TextField {

    private final IntConsumer nudge;

    SettingChannel(Theme theme, String placeholder, Consumer<String> onCommit, IntConsumer nudge) {
        super(theme, placeholder, onCommit);
        this.nudge = nudge;
        edit.setCharFilter(ch -> ch >= '0' && ch <= '9');
        edit.setMaxLength(3);
    }

    @Override
    protected int leftInset() {
        return theme.px(8);
    }

    @Override
    protected int rightInset() {
        return theme.px(6);
    }

    @Override
    public boolean keyDown(int key, int mods) {
        if (focused && (key == Keys.UP || key == Keys.DOWN)) {
            nudge.accept((key == Keys.UP ? 1 : -1) * (Keys.shift(mods) ? 10 : 1));
            return true;
        }
        return super.keyDown(key, mods);
    }
}
