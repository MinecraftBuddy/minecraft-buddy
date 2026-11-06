package adris.altoclef.ui.gui.kit;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

// a text field that only lets numbers in, with minus and plus nudges on the right. enter commits if the text parses
// and passes the validator, otherwise it stays put, goes red, and the old committed value is one revert() away. the
// error line itself is drawn by whoever owns the field, error() just hands over the words
public class NumberField extends Widget {

    public enum NumberFormat { INT, LONG, FLOAT, DOUBLE }

    private final NumberFormat kind;
    private final Consumer<String> onCommit;
    private final TextField field;
    private final IconButton minus;
    private final IconButton plus;
    private final List<Widget> kids;
    private boolean allowRelative;
    private Function<String, String> validate;
    private String committed = "";
    private String error;

    public NumberField(Theme theme, NumberFormat kind, Consumer<String> onCommit) {
        super(theme);
        this.kind = kind;
        this.onCommit = onCommit;
        field = new TextField(theme, "", s -> commit()) {
            // the nudge buttons sit inside the box on the right, text must stop before them
            @Override
            protected int rightInset() {
                return fieldRight;
            }

            @Override
            protected void onTextChanged() {
                error = null;
                setInvalid(false);
                super.onTextChanged();
            }

            // escape goes back to the last value that parsed, not to the last thing enter was pressed on
            @Override
            protected String revertText() {
                return committed;
            }
        };
        field.edit().setCharFilter(this::allowed);
        minus = new IconButton(theme, Icon.MINUS, "Less", () -> nudgeButton(-1));
        plus = new IconButton(theme, Icon.PLUS, "More", () -> nudgeButton(1));
        minus.setFocusable(false);
        plus.setFocusable(false);
        kids = List.of(field, minus, plus);
    }

    public NumberField setAllowRelative(boolean v) {
        allowRelative = v;
        return this;
    }

    public NumberField setValidate(Function<String, String> v) {
        validate = v;
        return this;
    }

    public TextField field() {
        return field;
    }

    public String text() {
        return field.text();
    }

    // for code: shows the value and counts it as the committed one, no callback
    public void setValue(String text) {
        field.setText(text);
        committed = field.text();
        error = null;
        field.setInvalid(false);
    }

    public String committed() {
        return committed;
    }

    public String error() {
        return error;
    }

    // put the last good value back and drop the error
    public void revert() {
        field.setText(committed);
        error = null;
        field.setInvalid(false);
    }

    private boolean allowed(int ch) {
        if (ch >= '0' && ch <= '9') {
            return true;
        }
        if (ch == '-') {
            return true;
        }
        if (ch == '.') {
            return kind == NumberFormat.FLOAT || kind == NumberFormat.DOUBLE;
        }
        return ch == '~' && allowRelative;
    }

    // null when fine, otherwise a sentence a player can act on
    public String check(String text) {
        String t = text.trim();
        boolean rel = allowRelative && t.startsWith("~");
        String num = rel ? t.substring(1) : t;
        if (rel && num.isEmpty()) {
            return runValidator(t);
        }
        String example = allowRelative ? "64 or ~10" : (isFloat() ? "1.5" : "64");
        if (num.isEmpty() || num.equals("-") || num.equals(".") || num.equals("-.")) {
            return "Not a number. Try " + example + ".";
        }
        BigDecimal v;
        try {
            v = new BigDecimal(num);
        } catch (NumberFormatException e) {
            return "Not a number. Try " + example + ".";
        }
        if (!isFloat() && v.stripTrailingZeros().scale() > 0) {
            return "Whole numbers only. Try " + (allowRelative ? "64 or ~10" : "64") + ".";
        }
        switch (kind) {
            case INT -> {
                if (v.compareTo(BigDecimal.valueOf(Integer.MIN_VALUE)) < 0
                        || v.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
                    return "Too big. Stay within " + Integer.MIN_VALUE + " to " + Integer.MAX_VALUE + ".";
                }
            }
            case LONG -> {
                if (v.compareTo(BigDecimal.valueOf(Long.MIN_VALUE)) < 0
                        || v.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) {
                    return "Too big. Try a smaller number.";
                }
            }
            case FLOAT -> {
                if (Float.isInfinite(v.floatValue())) {
                    return "Too big. Try a smaller number.";
                }
            }
            default -> {
                if (Double.isInfinite(v.doubleValue())) {
                    return "Too big. Try a smaller number.";
                }
            }
        }
        return runValidator(t);
    }

    private String runValidator(String t) {
        if (validate == null) {
            return null;
        }
        try {
            return validate.apply(t);
        } catch (Throwable e) {
            Safe.report("number validate", e);
            return null;
        }
    }

    private boolean isFloat() {
        return kind == NumberFormat.FLOAT || kind == NumberFormat.DOUBLE;
    }

    public void commit() {
        String t = field.text().trim();
        String problem = check(t);
        if (problem != null) {
            error = problem;
            field.setInvalid(true);
            return;
        }
        error = null;
        field.setInvalid(false);
        committed = t;
        if (onCommit != null) {
            Safe.run("number commit", () -> onCommit.accept(t));
        }
    }

    // one notch of the +/- buttons: 1 / 10 / 100 for whole numbers, 0.1 / 1 / 10 for decimals
    private void nudgeButton(int dir) {
        nudge(dir, (dir < 0 ? minus : plus).lastMods());
    }

    // package private so the test can nudge with chosen modifiers
    void nudge(int dir, int mods) {
        int mag = Keys.ctrl(mods) ? 2 : (Keys.shift(mods) ? 1 : 0);
        BigDecimal step = isFloat()
                ? new BigDecimal("0.1").movePointRight(mag)
                : BigDecimal.ONE.movePointRight(mag);
        String t = field.text().trim();
        boolean rel = allowRelative && t.startsWith("~");
        BigDecimal cur = parseOrNull(rel ? t.substring(1) : t);
        if (cur == null) {
            // garbage in the box: nudge from the last good value instead
            String last = committed.startsWith("~") ? committed.substring(1) : committed;
            cur = parseOrNull(last);
        }
        if (cur == null) {
            cur = BigDecimal.ZERO;
        }
        BigDecimal next = cur.add(step.multiply(BigDecimal.valueOf(dir))).stripTrailingZeros();
        String out = next.toPlainString();
        if (rel) {
            out = next.signum() == 0 ? "~" : "~" + out;
        }
        field.setText(out);
        commit();
    }

    private static BigDecimal parseOrNull(String s) {
        if (s.isEmpty()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public int preferredHeight() {
        return theme.px(Theme.CONTROL_H);
    }

    @Override
    protected void layout() {
        int s = theme.px(24);
        int gap = theme.px(2);
        int edge = theme.px(3);
        int by = y + (h - s) / 2;
        plus.setBounds(x + w - edge - s, by, s, s);
        minus.setBounds(x + w - edge - 2 * s - gap, by, s, s);
        field.setBounds(x, y, w, h);
        fieldRight = 2 * s + gap + edge + theme.px(2);
    }

    private int fieldRight = 10;

    @Override
    public List<Widget> children() {
        return kids;
    }

    @Override
    public void draw(Canvas c, Frame f) {
        field.draw(c, f);
        minus.draw(c, f);
        plus.draw(c, f);
    }

}
