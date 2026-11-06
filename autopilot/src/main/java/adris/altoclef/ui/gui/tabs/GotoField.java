package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.kit.NumberField;
import adris.altoclef.ui.gui.kit.Theme;

import java.util.function.Function;

// a number field that takes ~ and may be left blank, and treats enter as "go". the +/- nudges commit through the same
// door, so a click inside the field is told apart from the keyboard
final class GotoField extends NumberField {

    private final Function<String, String> rule;
    private final Runnable onEnter;
    private boolean inMouse;

    GotoField(Theme theme, Function<String, String> rule, Runnable onEnter) {
        super(theme, NumberFormat.INT, null);
        this.rule = rule;
        this.onEnter = onEnter;
        setAllowRelative(true);
    }

    // blank is fine (y is optional, x and z are checked together), anything else is the model's call
    @Override
    public String check(String text) {
        return text.isBlank() ? null : rule.apply(text.trim());
    }

    @Override
    public void commit() {
        super.commit();
        if (!inMouse && error() == null) {
            onEnter.run();
        }
    }

    @Override
    public boolean mouseUp(float mx, float my, int button, int mods) {
        inMouse = true;
        try {
            return super.mouseUp(mx, my, button, mods);
        } finally {
            inMouse = false;
        }
    }
}
