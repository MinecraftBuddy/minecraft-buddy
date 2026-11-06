package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.KeyNames;
import adris.altoclef.ui.gui.facade.Result;
import adris.altoclef.ui.gui.facade.SettingsAccess.Kind;
import adris.altoclef.ui.gui.facade.SettingsAccess.SettingView;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Dropdown;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.IconButton;
import adris.altoclef.ui.gui.kit.KeyCapture;
import adris.altoclef.ui.gui.kit.NumberField;
import adris.altoclef.ui.gui.kit.NumberField.NumberFormat;
import adris.altoclef.ui.gui.kit.RowWidgets;
import adris.altoclef.ui.gui.kit.ShellContext;
import adris.altoclef.ui.gui.kit.TextField;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.Toggle;
import adris.altoclef.ui.gui.kit.Widget;
import adris.altoclef.ui.gui.model.NumberRules;
import adris.altoclef.ui.gui.model.Pinned;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// one setting: name and description on the left, the editor for its type on the right, a reset button that shows up
// when the value is not the default. rows are pooled, so every editor for every kind is built once up front and bind()
// only picks which ones are live. draw only reads state that bind and the edit callbacks already worked out
final class SettingRow extends RowWidgets.Row<SettingView> {

    private final SettingHost host;
    private final ShellContext ctx;
    private final Toggle toggle;
    private final NumberField intField;
    private final NumberField longField;
    private final NumberField floatField;
    private final NumberField doubleField;
    private final TextField text;
    private final TextField other;
    private final Dropdown dropdown;
    private final KeyCapture key;
    private final SettingChannel[] rgb = new SettingChannel[3];
    private final IconButton pin;
    private final IconButton reset;
    private final List<Widget> kids = new ArrayList<>();
    private final boolean[] wasFocused = new boolean[3];
    private static final TextField[] NO_BOXES = new TextField[0];
    private TextField[] boxes = NO_BOXES;

    private SettingView item;
    private int index;
    private int count;
    private NumberField number;
    // what the three colour boxes say right now, or null when they do not parse. the swatch follows typing
    private int[] liveRgb;
    private int[] savedRgb;
    // the last text sent for this setting, so a rejected value is not sent again by every blur and flush
    private String attempted;
    private String desc = "";
    private String error;
    private boolean confirmingReset;
    private long confirmingAtNanos;
    private int textLeft;
    private int textRight;
    private int swatchX;
    private int swatchY;
    private int swatchS;

    private String nameShown = "";
    private float nameShownFor = -1;
    private String lineKey;
    private String lineShown = "";
    private float lineShownFor = -1;

    SettingRow(Theme theme, ShellContext ctx, KeyNames keyNames, SettingHost host) {
        super(theme);
        this.host = host;
        this.ctx = ctx;
        toggle = new Toggle(theme, false, on -> submit(String.valueOf(on)));
        intField = numberField(NumberFormat.INT, NumberRules.Type.INT);
        longField = numberField(NumberFormat.LONG, NumberRules.Type.LONG);
        floatField = numberField(NumberFormat.FLOAT, NumberRules.Type.FLOAT);
        doubleField = numberField(NumberFormat.DOUBLE, NumberRules.Type.DOUBLE);
        text = new TextField(theme, "Empty", this::submit);
        other = new TextField(theme, "Empty", this::submit);
        text.setOnChange(s -> clearTextError(text));
        other.setOnChange(s -> clearTextError(other));
        dropdown = new Dropdown(theme, List.of(""), 0, i -> {
            List<String> opts = item == null ? List.of() : item.options();
            if (i >= 0 && i < opts.size()) {
                submit(opts.get(i));
            }
        }, ctx);
        key = new KeyCapture(theme, -1, keyNames, k -> submit(String.valueOf(k)), ctx);
        String[] ph = {"R", "G", "B"};
        for (int i = 0; i < 3; i++) {
            int idx = i;
            rgb[i] = new SettingChannel(theme, ph[i], s -> commitColour(), d -> nudgeChannel(idx, d));
            rgb[i].setOnChange(s -> {
                rgb[idx].setInvalid(false);
                error = null;
                readColour();
            });
        }
        pin = new IconButton(theme, Icon.PIN, "Pin this setting", this::pinClicked);
        pin.setFocusable(false);
        reset = new IconButton(theme, Icon.RESET, "Reset to default", this::resetClicked);
    }

    private NumberField numberField(NumberFormat format, NumberRules.Type type) {
        NumberField f = new NumberField(theme, format, this::submit);
        f.setValidate(t -> NumberRules.check(type, t));
        // typing again drops a "the game said no" line, same as it drops the field's own error
        f.field().setOnChange(s -> error = null);
        return f;
    }

    private void clearTextError(TextField f) {
        f.setInvalid(false);
        error = null;
    }

    // ----- data

    @Override
    public void bind(SettingView v, int index0) {
        // a popup opened for the last setting would pick into this one
        if (dropdown.isOpen()) {
            ctx.closeOverlay();
        }
        item = v;
        index = index0;
        desc = host.description(v.name());
        error = null;
        confirmingReset = false;
        Arrays.fill(wasFocused, false);
        nameShownFor = -1;
        lineKey = null;
        pick(v.kind());
        sync(v, false);
        if (w > 0) {
            layout();
        }
    }

    // the tab changed this setting (this row, the pinned key card, a reset). keep whatever the person is typing
    void update(SettingView v) {
        if (item != null && item.name().equals(v.name())) {
            item = v;
            sync(v, true);
        }
    }

    String name() {
        return item == null ? null : item.name();
    }

    // the browser says how long the list is, so the first and last row can follow the corners of the card
    void setCount(int n) {
        count = n;
    }

    // the sentence replacing the description right now, or null
    String errorText() {
        return number != null && number.error() != null ? number.error() : error;
    }

    Toggle toggle() {
        return toggle;
    }

    NumberField number() {
        return number;
    }

    TextField text() {
        return text;
    }

    TextField other() {
        return other;
    }

    SettingChannel channel(int i) {
        return rgb[i];
    }

    Dropdown dropdown() {
        return dropdown;
    }

    KeyCapture key() {
        return key;
    }

    IconButton resetButton() {
        return reset;
    }

    private void pick(Kind kind) {
        kids.clear();
        number = null;
        switch (kind) {
            case BOOLEAN -> kids.add(toggle);
            case INTEGER -> kids.add(number = intField);
            case LONG -> kids.add(number = longField);
            case FLOAT -> kids.add(number = floatField);
            case DOUBLE -> kids.add(number = doubleField);
            case STRING -> kids.add(text);
            case COLOR -> kids.addAll(Arrays.asList(rgb));
            case ENUM -> kids.add(dropdown);
            case KEY -> kids.add(key);
            default -> kids.add(other);
        }
        kids.add(pin);
        kids.add(reset);
        if (number != null) {
            boxes = new TextField[] {number.field()};
        } else {
            boxes = switch (kind) {
                case STRING -> new TextField[] {text};
                case COLOR -> rgb;
                case OTHER -> new TextField[] {other};
                default -> NO_BOXES;
            };
        }
    }

    private void sync(SettingView v, boolean keepTyping) {
        attempted = null;
        String value = v.value() == null ? "" : v.value();
        switch (v.kind()) {
            case BOOLEAN -> toggle.setOn(Boolean.parseBoolean(value));
            case INTEGER, LONG, FLOAT, DOUBLE -> {
                if (!(keepTyping && number.field().isFocused())) {
                    number.setValue(value);
                }
            }
            case STRING -> {
                if (!(keepTyping && text.isFocused())) {
                    showFromStart(text, value);
                    text.setInvalid(false);
                }
            }
            case COLOR -> {
                int[] c = NumberRules.parseRgb(value);
                savedRgb = c;
                for (int i = 0; i < 3; i++) {
                    if (!(keepTyping && rgb[i].isFocused())) {
                        rgb[i].setText(c == null ? "" : String.valueOf(c[i]));
                        rgb[i].setInvalid(false);
                    }
                }
                readColour();
            }
            case ENUM -> dropdown.setOptions(v.options().isEmpty() ? List.of(value) : v.options(), Math.max(0, v.options().indexOf(value)));
            case KEY -> key.setKey(parseKey(value));
            default -> {
                if (!(keepTyping && other.isFocused())) {
                    showFromStart(other, value);
                    other.setInvalid(false);
                }
            }
        }
    }

    // setText parks the caret at the end, which scrolls a long value to its tail. at rest you want to read the start
    private static void showFromStart(TextField f, String value) {
        f.setText(value);
        f.edit().setCaret(0, false);
    }

    private static int parseKey(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ----- edits

    private void submit(String value) {
        if (item == null) {
            return;
        }
        error = null;
        attempted = value;
        Result r = host.set(item.name(), value);
        if (r.ok()) {
            return;
        }
        error = r.message();
        // a switch, a menu and a key cannot hold a wrong value on screen, so they snap back. text stays put and red
        // so the person can fix it instead of retyping
        switch (item.kind()) {
            case BOOLEAN, ENUM, KEY -> sync(item, false);
            case STRING -> text.setInvalid(true);
            case OTHER -> other.setInvalid(true);
            case INTEGER, LONG, FLOAT, DOUBLE -> number.field().setInvalid(true);
            default -> {
            }
        }
    }

    // commits whatever is half typed. the tab calls this before anything that would rebind or hide the row (a click
    // on the column, a scroll, leaving the tab), because by the time the row is drawn again it is somebody else's.
    // focus does not matter: the shell blurs before it tells the tab it is hidden, and a blurred box keeps its text.
    // a click inside a box is you moving the caret, not leaving, so that box is skipped (mx, my is the click)
    void flush(float mx, float my) {
        if (item == null) {
            return;
        }
        if (item.kind() == Kind.COLOR) {
            if (!(rgb[0].contains(mx, my) || rgb[1].contains(mx, my) || rgb[2].contains(mx, my)) && colourDirty()) {
                commitColour();
            }
            return;
        }
        for (int i = 0; i < boxes.length; i++) {
            if (!boxes[i].contains(mx, my) && dirty(i)) {
                commitBox(i);
            }
        }
    }

    private boolean rgbFocused() {
        return rgb[0].isFocused() || rgb[1].isFocused() || rgb[2].isFocused();
    }

    // the three boxes differ from the saved colour and that exact text has not been sent already. a colour that does
    // not parse counts, committing it is what puts the error on screen
    private boolean colourDirty() {
        boolean differs = savedRgb == null;
        for (int i = 0; i < 3 && !differs; i++) {
            differs = !rgb[i].text().trim().equals(String.valueOf(savedRgb[i]));
        }
        if (!differs) {
            return false;
        }
        return liveRgb == null || !NumberRules.formatRgb(liveRgb[0], liveRgb[1], liveRgb[2]).equals(attempted);
    }

    private void readColour() {
        int[] v = new int[3];
        for (int i = 0; i < 3; i++) {
            if (NumberRules.checkChannel(rgb[i].text()) != null) {
                liveRgb = null;
                return;
            }
            v[i] = Integer.parseInt(rgb[i].text().trim());
        }
        liveRgb = v;
    }

    private void pinClicked() {
        if (item == null) {
            return;
        }
        Pinned.toggleSetting(item.name());
    }

    // a reset throws away whatever was there, so it gets a second click to be sure. the first arms it, anything that
    // would rebind the row disarms it, and a stale arm lapses on its own in draw
    private void resetClicked() {
        if (item == null) {
            return;
        }
        if (!confirmingReset) {
            confirmingReset = true;
            confirmingAtNanos = System.nanoTime();
            return;
        }
        confirmingReset = false;
        error = null;
        Result r = host.reset(item.name());
        if (!r.ok()) {
            error = r.message();
        }
    }

    private void commitColour() {
        int[] v = new int[3];
        for (int i = 0; i < 3; i++) {
            String problem = NumberRules.checkChannel(rgb[i].text());
            if (problem != null) {
                rgb[i].setInvalid(true);
                error = problem;
                return;
            }
            v[i] = Integer.parseInt(rgb[i].text().trim());
        }
        submit(NumberRules.formatRgb(v[0], v[1], v[2]));
    }

    private void nudgeChannel(int i, int delta) {
        int cur = NumberRules.checkChannel(rgb[i].text()) == null ? Integer.parseInt(rgb[i].text().trim()) : 0;
        rgb[i].setText(String.valueOf(NumberRules.clamp(cur + delta)));
        readColour();
        commitColour();
    }

    // leaving a box with an edit in it is a commit, same as enter. the colour boxes are one group: moving from r to g
    // is not leaving, so a three channel change is one save and one toast
    private void blurCommits() {
        if (item.kind() == Kind.COLOR) {
            boolean now = rgbFocused();
            if (wasFocused[0] && !now && colourDirty()) {
                commitColour();
            }
            wasFocused[0] = now;
            return;
        }
        for (int i = 0; i < boxes.length; i++) {
            boolean now = boxes[i].isFocused();
            if (wasFocused[i] && !now && dirty(i)) {
                commitBox(i);
            }
            wasFocused[i] = now;
        }
    }

    private boolean dirty(int i) {
        if (number != null) {
            return !number.text().trim().equals(number.committed());
        }
        return switch (item.kind()) {
            case STRING -> !text.text().equals(item.value()) && !text.text().equals(attempted);
            case OTHER -> !other.text().equals(item.value()) && !other.text().equals(attempted);
            default -> false;
        };
    }

    private void commitBox(int i) {
        if (number != null) {
            number.commit();
            return;
        }
        switch (item.kind()) {
            case STRING -> submit(text.text());
            case OTHER -> submit(other.text());
            case COLOR -> commitColour();
            default -> {
            }
        }
    }

    // ----- layout

    private int editorWidth(Kind kind) {
        return theme.px(switch (kind) {
            case BOOLEAN -> 34;
            case INTEGER, LONG, FLOAT, DOUBLE -> 132;
            case STRING -> 200;
            case COLOR -> 176;
            case ENUM -> 160;
            case KEY -> 112;
            default -> 220;
        });
    }

    @Override
    protected void layout() {
        if (item == null || w <= 0) {
            return;
        }
        int rs = theme.px(28);
        int rightEdge = x + w - theme.px(Theme.PAD);
        reset.setBounds(rightEdge - rs, y + (h - rs) / 2, rs, rs);
        pin.setBounds(rightEdge - rs - theme.px(2) - rs, y + (h - rs) / 2, rs, rs);
        int right = pin.x() - theme.px(Theme.GAP);
        int ch = theme.px(Theme.CONTROL_H);
        int ey = y + (h - ch) / 2;
        int ew = Math.min(editorWidth(item.kind()), Math.round(w * 0.5f));
        int left = right - ew;
        switch (item.kind()) {
            case BOOLEAN -> {
                int tw = toggle.preferredWidth();
                int th = toggle.preferredHeight();
                toggle.setBounds(right - tw, y + (h - th) / 2, tw, th);
                left = right - tw;
            }
            case COLOR -> {
                swatchS = theme.px(22);
                swatchX = left;
                swatchY = y + (h - swatchS) / 2;
                int gap = theme.px(Theme.SPACE_1);
                int boxX = left + swatchS + theme.px(Theme.GAP);
                int bw = (right - boxX - 2 * gap) / 3;
                for (int i = 0; i < 3; i++) {
                    rgb[i].setBounds(boxX + i * (bw + gap), ey, bw, ch);
                }
            }
            case INTEGER, LONG, FLOAT, DOUBLE -> number.setBounds(left, ey, ew, ch);
            case STRING -> text.setBounds(left, ey, ew, ch);
            case ENUM -> dropdown.setBounds(left, ey, ew, ch);
            case KEY -> key.setBounds(left, ey, ew, ch);
            default -> other.setBounds(left, ey, ew, ch);
        }
        // room for the pin (16 wide, 12 in) before the name starts
        textLeft = x + theme.px(Theme.PAD + Theme.ICON);
        textRight = left - theme.px(Theme.PAD);
        nameShownFor = -1;
        lineKey = null;
    }

    @Override
    public List<Widget> children() {
        return kids;
    }

    // ----- drawing

    @Override
    public void draw(Canvas c, Frame f) {
        if (item == null) {
            return;
        }
        boolean focusedIn = anyFocus();
        boolean active = hovered || focusedIn;
        // the row under the mouse owns the detail strip. a row you are typing in only gets it while the mouse is off the list
        if (hovered || (focusedIn && !host.mouseOverRows(f.mouseX(), f.mouseY()))) {
            host.hover(item);
        }
        blurCommits();
        if (hovered) {
            drawHover(c);
        }
        // the hairline between two rows starts at the dot column and runs out to the card edge, like a grouped list
        if (index > 0) {
            c.fillRect(x + theme.px(Theme.PAD), y, w - theme.px(Theme.PAD) - 1, 1, theme.color(Theme.HAIRLINE));
        }
        reset.setVisible(item.modified() || active);
        pin.setVisible(active || Pinned.isSetting(item.name()));
        // armed: the reset button turns into a "confirm" check, and the arm lapses if nothing happens soon
        if (confirmingReset && f.nowNanos() - confirmingAtNanos > 4_000_000_000L) {
            confirmingReset = false;
        }
        reset.setIcon(confirmingReset ? Icon.CHECK : Icon.RESET);

        float avail = Math.max(0, textRight - textLeft);
        float lh1 = c.ascent(theme.label) + c.descent(theme.label);
        float lh2 = c.ascent(theme.small) + c.descent(theme.small);
        float gap = theme.px(Theme.SPACE_1);
        float top = y + (h - (lh1 + gap + lh2)) / 2f;
        float base1 = top + c.ascent(theme.label);
        float base2 = top + lh1 + gap + c.ascent(theme.small);

        if (Pinned.isSetting(item.name())) {
            int is = theme.px(Theme.ICON);
            c.icon(Icon.PIN, x + theme.px(Theme.SPACE_3), y + (h - is) / 2f, is, theme.color(Theme.ACCENT));
        } else if (item.modified()) {
            float d = theme.px(Theme.SPACE_1);
            c.fillRoundRect(x + theme.px(Theme.PAD), top + lh1 / 2f - d / 2f, d, d, d / 2f, theme.color(Theme.ACCENT));
        }
        if (nameShownFor != avail) {
            nameShownFor = avail;
            nameShown = theme.measurer.ellipsize(item.name(), theme.label, avail);
        }
        c.text(nameShown, textLeft, base1, theme.label, theme.color(Theme.TEXT));

        String err = errorText();
        String line = confirmingReset
                ? "Reset to its default? Click the check to confirm."
                : (err != null ? err : (desc.isEmpty() ? "No description." : desc));
        int lineCol = confirmingReset ? theme.color(Theme.TEXT) : (err != null ? theme.color(Theme.DANGER) : (desc.isEmpty() ? theme.color(Theme.TEXT_3) : theme.color(Theme.TEXT_2)));
        if (!line.equals(lineKey) || lineShownFor != avail) {
            lineKey = line;
            lineShownFor = avail;
            lineShown = theme.measurer.ellipsize(line, theme.small, avail);
        }
        c.text(lineShown, textLeft, base2, theme.small, lineCol);

        if (item.kind() == Kind.COLOR) {
            drawSwatch(c);
        }
        for (Widget k : kids) {
            if (k.visible()) {
                k.draw(c, f);
            }
        }
    }

    // the fill stays inside the card's stroke, and the first and last row take the card's corners. a round rect taller
    // than the row, clipped to the row, gives rounded corners on one side and square on the other
    private void drawHover(Canvas c) {
        int col = theme.color(Theme.HOVER);
        boolean first = index == 0;
        boolean last = index == count - 1;
        float left = x + 1;
        float width = w - 2;
        if (!first && !last) {
            c.fillRect(left, y, width, h, col);
            return;
        }
        float top = first ? y + 1 : y;
        float bottom = last ? y + h - 1 : y + h;
        c.pushClip(left, top, width, bottom - top);
        try {
            c.fillRoundRect(left, first ? top : y - h, width, (last ? bottom : y + 2 * h) - (first ? top : y - h),
                    theme.pxf(Theme.CARD_RADIUS) - 1, col);
        } finally {
            c.popClip();
        }
    }

    private void drawSwatch(Canvas c) {
        int[] col = liveRgb != null ? liveRgb : savedRgb;
        float r = theme.px(4);
        if (col != null) {
            c.fillRoundRect(swatchX, swatchY, swatchS, swatchS, r, 0xFF000000 | (col[0] << 16) | (col[1] << 8) | col[2]);
        }
        c.strokeRoundRect(swatchX, swatchY, swatchS, swatchS, r, theme.color(Theme.HAIRLINE_STRONG));
    }

    private boolean anyFocus() {
        for (Widget k : kids) {
            if (focusedDeep(k)) {
                return true;
            }
        }
        return false;
    }

    private static boolean focusedDeep(Widget k) {
        if (k.isFocused()) {
            return true;
        }
        for (Widget c : k.children()) {
            if (focusedDeep(c)) {
                return true;
            }
        }
        return false;
    }
}
