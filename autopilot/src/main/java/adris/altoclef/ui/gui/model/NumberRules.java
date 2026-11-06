package adris.altoclef.ui.gui.model;

import java.math.BigDecimal;

// what a number setting will accept, as sentences. the field only lets digits in, this catches the rest (empty, a lone
// minus, too big for the type, a fraction in a whole number box). null means fine
public final class NumberRules {

    public enum Type { INT, LONG, FLOAT, DOUBLE }

    private NumberRules() {
    }

    public static String check(Type type, String text) {
        String t = text == null ? "" : text.trim();
        boolean decimal = type == Type.FLOAT || type == Type.DOUBLE;
        BigDecimal v = parse(t);
        if (v == null) {
            return "Not a number. Try " + (decimal ? "1.5" : "64") + ".";
        }
        if (!decimal && v.stripTrailingZeros().scale() > 0) {
            return "Whole numbers only. Try 64.";
        }
        switch (type) {
            case INT -> {
                if (v.compareTo(BigDecimal.valueOf(Integer.MIN_VALUE)) < 0 || v.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
                    return "Too big. Stay within " + Integer.MIN_VALUE + " to " + Integer.MAX_VALUE + ".";
                }
            }
            case LONG -> {
                if (v.compareTo(BigDecimal.valueOf(Long.MIN_VALUE)) < 0 || v.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) {
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
        return null;
    }

    // one colour channel: a whole number from 0 to 255
    public static String checkChannel(String text) {
        String t = text == null ? "" : text.trim();
        BigDecimal v = parse(t);
        if (v == null || v.stripTrailingZeros().scale() > 0) {
            return "Use a whole number from 0 to 255.";
        }
        if (v.signum() < 0 || v.compareTo(BigDecimal.valueOf(255)) > 0) {
            return "Colours go from 0 to 255.";
        }
        return null;
    }

    // "r,g,b" as three ints, or null when it is not that. #set prints colours this way
    public static int[] parseRgb(String text) {
        if (text == null) {
            return null;
        }
        String[] parts = text.split(",", -1);
        if (parts.length != 3) {
            return null;
        }
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            if (checkChannel(parts[i]) != null) {
                return null;
            }
            out[i] = new BigDecimal(parts[i].trim()).intValue();
        }
        return out;
    }

    public static String formatRgb(int r, int g, int b) {
        return clamp(r) + "," + clamp(g) + "," + clamp(b);
    }

    public static int clamp(int channel) {
        return Math.max(0, Math.min(255, channel));
    }

    private static BigDecimal parse(String t) {
        if (t.isEmpty() || t.equals("-") || t.equals(".") || t.equals("-.")) {
            return null;
        }
        try {
            return new BigDecimal(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
