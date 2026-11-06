package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Dropdown;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.ShellContext;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.Toggle;
import adris.altoclef.ui.gui.kit.Widget;
import adris.altoclef.ui.gui.model.SettingsFilter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

// categories are a submenu now. the two common filters stay beside it instead of claiming a second sidebar
final class SettingsToolbar extends Widget {

    private static final String ADVANCED = "Advanced";
    private static final String MODIFIED = "Modified";

    private final List<String> labels = new ArrayList<>();
    private final Consumer<String> onCategory;
    private final Dropdown categories;
    private final Toggle modifiedToggle;
    private final Toggle advancedToggle;
    private final List<Widget> kids;
    private int selected;
    private int advancedLabelX;
    private int modifiedLabelX;

    SettingsToolbar(Theme theme, ShellContext ctx, Consumer<String> onCategory,
                    Consumer<Boolean> onModifiedOnly, Consumer<Boolean> onAdvanced) {
        super(theme);
        this.onCategory = onCategory;
        labels.add(SettingsFilter.ALL);
        categories = new Dropdown(theme, List.of("All settings"), 0, this::choose, ctx);
        modifiedToggle = new Toggle(theme, false, onModifiedOnly);
        advancedToggle = new Toggle(theme, false, onAdvanced);
        kids = List.of(categories, advancedToggle, modifiedToggle);
    }

    String selectedLabel() {
        return labels.get(selected);
    }

    Dropdown categories() {
        return categories;
    }

    // the leftmost thing on the right-hand side, for anyone checking the dropdown does not run into it
    int togglesLeft() {
        return advancedLabelX;
    }

    Toggle modifiedToggle() {
        return modifiedToggle;
    }

    void setData(Map<String, Integer> perCategory, String selectedCategory, boolean modifiedOnly, boolean showAdvanced) {
        labels.clear();
        labels.add(SettingsFilter.ALL);
        labels.addAll(perCategory.keySet());
        selected = Math.max(0, labels.indexOf(selectedCategory));
        List<String> options = new ArrayList<>();
        int total = perCategory.values().stream().mapToInt(Integer::intValue).sum();
        options.add("All settings  \u00b7  " + total);
        for (var e : perCategory.entrySet()) {
            options.add(e.getKey() + "  \u00b7  " + e.getValue());
        }
        categories.setOptions(options, selected);
        modifiedToggle.setOn(modifiedOnly);
        advancedToggle.setOn(showAdvanced);
    }

    int heightFor(int width) {
        return width < theme.px(340) ? theme.px(2 * Theme.CONTROL_H + Theme.GAP) : theme.px(Theme.CONTROL_H);
    }

    private void choose(int i) {
        selected = i;
        onCategory.accept(labels.get(i));
    }

    @Override
    protected void layout() {
        int ch = theme.px(Theme.CONTROL_H);
        boolean narrow = w < theme.px(340);
        categories.setBounds(x, y, narrow ? w : Math.min(theme.px(260), w - theme.px(240)), ch);
        int fy = narrow ? y + ch + theme.px(Theme.GAP) : y;
        int th = modifiedToggle.preferredHeight();
        int tw = modifiedToggle.preferredWidth();
        int mw = (int) Math.ceil(theme.measurer.measure(MODIFIED, theme.small));
        int aw = (int) Math.ceil(theme.measurer.measure(ADVANCED, theme.small));
        int gap = theme.px(Theme.GAP);
        // label then switch, same order as the open key card. the two pairs hug the right edge
        modifiedToggle.setBounds(x + w - tw, fy + (ch - th) / 2, tw, th);
        modifiedLabelX = modifiedToggle.x() - gap - mw;
        advancedToggle.setBounds(modifiedLabelX - theme.px(Theme.SPACE_3 + Theme.GAP) - tw, fy + (ch - th) / 2, tw, th);
        advancedLabelX = advancedToggle.x() - gap - aw;
    }

    @Override
    public List<Widget> children() {
        return kids;
    }

    @Override
    public void draw(Canvas c, Frame f) {
        categories.draw(c, f);
        label(c, advancedToggle, ADVANCED, advancedLabelX);
        label(c, modifiedToggle, MODIFIED, modifiedLabelX);
        advancedToggle.draw(c, f);
        modifiedToggle.draw(c, f);
    }

    private void label(Canvas c, Toggle toggle, String text, int lx) {
        float base = toggle.y() + toggle.h() / 2f + (c.ascent(theme.small) - c.descent(theme.small)) / 2f;
        c.text(text, lx, base, theme.small, theme.color(Theme.TEXT_2));
    }
}
