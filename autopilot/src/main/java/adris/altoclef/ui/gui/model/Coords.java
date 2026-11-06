package adris.altoclef.ui.gui.model;

import adris.altoclef.ui.gui.facade.WorldAccess;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// x y z text to blocks. same grammar as baritone's RelativeCoordinate (a number, or ~ / ~n / ~-n against the player's
// block, floored), so what works in the gui works in #goto. decimals and k/m parse too, but the boxes only type whole
// numbers: they arrive by paste and get tidied into whole ones. pure: no kit, no minecraft
public final class Coords {

    private static final String[] NAMES = {"X", "Y", "Z"};
    private static final Pattern TOKEN = Pattern.compile("^(~?)([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)([kKmM]?)|)$");
    private static final Pattern LABEL = Pattern.compile("^[xyzXYZ][:=]");
    private static final Pattern SEPARATORS = Pattern.compile("[\\s,;/]+");
    // nobody is walking past the world border, and an int that big overflows the goal maths anyway
    public static final int LIMIT = 30_000_000;

    private Coords() {
    }

    // x, y, z are null when their box was blank. error and errorField (0 x, 1 y, 2 z) say which box is wrong
    public record Resolved(Integer x, Integer y, Integer z, String error, int errorField) {

        public boolean ok() {
            return error == null;
        }

        // something baritone can path to: x and z (with or without y), or a lone y level
        public boolean complete() {
            return ok() && ((x != null && z != null) || (x == null && z == null && y != null));
        }

        // the box to point at when it is not complete yet, or -1
        public int missingField() {
            if (!ok() || complete()) {
                return -1;
            }
            if (x != null && z == null) {
                return 2;
            }
            if (z != null && x == null) {
                return 0;
            }
            return -1;
        }

        public String missing() {
            if (!ok() || complete()) {
                return null;
            }
            if (x != null && z == null) {
                return "Add a z to go with the x";
            }
            if (z != null && x == null) {
                return "Add an x to go with the z";
            }
            return "Enter x and z, or just a y level";
        }
    }

    // pasted text to its parts: "120 64 -30", "120, 64, -30", "120 -30", "120/64/-30", "(120, 64, -30)", "x: 120 y: 64"
    public static List<String> split(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        String s = text.replaceAll("[()\\[\\]{}]", " ").trim();
        for (String part : SEPARATORS.split(s)) {
            String p = LABEL.matcher(part).replaceFirst("");
            if (!p.isEmpty()) {
                out.add(p);
            }
        }
        return out;
    }

    public static boolean isToken(String text) {
        String t = text == null ? "" : text.trim();
        return !t.isEmpty() && offset(t) != null;
    }

    // what a paste means: the 2 or 3 parts of "120 64 -30", or the same three inside a longer line like an F3+C
    // "/execute ... tp @s 120.50 64.00 -30.50 0 0" (the first run of numbers that follows some words). empty when it
    // is not coordinates, so a paste of "hello world" leaves the boxes alone
    public static List<String> pasteParts(String text) {
        List<String> parts = split(text);
        boolean all = !parts.isEmpty();
        for (String p : parts) {
            all &= isToken(p);
        }
        if (all) {
            return parts.size() == 2 || parts.size() == 3 ? parts : List.of();
        }
        // the first run of numbers that follows a word: "home 120 -30", "Coords: 120 64 -30", an F3 tp line
        for (int i = 1; i < parts.size(); i++) {
            if (isToken(parts.get(i - 1)) || !isToken(parts.get(i))) {
                continue;
            }
            int j = i;
            while (j < parts.size() && isToken(parts.get(j))) {
                j++;
            }
            if (j - i >= 2) {
                return parts.subList(i, i + Math.min(3, j - i));
            }
            i = j;
        }
        return List.of();
    }

    // one pasted part as the box wants it, made only of what the box can type. a number becomes the whole number it
    // floors to, "~1.5" becomes "~1" (the base is a whole block, so the floor is the same block), "1.5k" is 1500
    public static String tidy(String token) {
        String t = token == null ? "" : token.trim();
        Double off = offset(t);
        if (off == null) {
            return t;
        }
        long whole = (long) Math.floor(off);
        if (t.startsWith("~")) {
            return whole == 0 ? "~" : "~" + whole;
        }
        return Long.toString(whole);
    }

    // "-" and "~-" are somebody half way through typing a negative number, not a mistake yet
    public static boolean pending(String text) {
        String t = text == null ? "" : text.trim();
        return !t.isEmpty() && !t.equals("~") && t.matches("~?[+-]?\\.?");
    }

    // null when the box is fine (blank included), otherwise a sentence a player can act on
    public static String fieldError(int field, String text, WorldAccess.Position origin) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            return null;
        }
        String name = NAMES[field];
        Double off = offset(t);
        if (off == null) {
            return name + " needs a number or ~";
        }
        if (t.startsWith("~") && origin == null) {
            return "Join a world to use ~";
        }
        if (Math.abs(off) > LIMIT) {
            return name + " is too far. Stay within " + String.format(Locale.US, "%,d", LIMIT) + ".";
        }
        if (t.startsWith("~") && Math.abs(block(field, origin) + off) > LIMIT) {
            return name + " is too far. Stay within " + String.format(Locale.US, "%,d", LIMIT) + ".";
        }
        return null;
    }

    public static Resolved resolve(String xText, String yText, String zText, WorldAccess.Position origin) {
        String[] texts = {xText, yText, zText};
        Integer[] v = new Integer[3];
        for (int i = 0; i < 3; i++) {
            String t = texts[i] == null ? "" : texts[i].trim();
            String err = fieldError(i, t, origin);
            if (err != null) {
                return new Resolved(null, null, null, err, i);
            }
            if (t.isEmpty()) {
                continue;
            }
            double off = offset(t);
            double val = t.startsWith("~") ? block(i, origin) + off : off;
            v[i] = (int) Math.floor(val);
        }
        return new Resolved(v[0], v[1], v[2], null, -1);
    }

    // the block the player stands in on one axis (0 x, 1 y, 2 z). y is playerFeet's: the 0.1251 keeps a bot on a
    // farmland or path block in the block baritone calls "here", so ~ and "My position" agree with #goto
    public static int block(int field, WorldAccess.Position p) {
        return (int) Math.floor(field == 0 ? p.x() : field == 1 ? p.y() + 0.1251 : p.z());
    }

    // the number a token means (an offset when it starts with ~), or null when it is not a coordinate
    private static Double offset(String t) {
        Matcher m = TOKEN.matcher(t);
        if (!m.matches()) {
            return null;
        }
        String body = m.group(2);
        if (body.isEmpty()) {
            return m.group(1).isEmpty() ? null : 0.0;
        }
        String scale = m.group(3);
        String digits = scale.isEmpty() ? body : body.substring(0, body.length() - 1);
        double v = Double.parseDouble(digits);
        if (scale.isEmpty()) {
            return v;
        }
        return v * (scale.equalsIgnoreCase("k") ? 1000.0 : 1_000_000.0);
    }

    // block count from the player to the target, 2d when there is no y. y only is how far up or down
    public static double distance(Resolved r, WorldAccess.Position p) {
        if (p == null || !r.complete()) {
            return -1;
        }
        double dy = r.y() == null ? 0 : r.y() - p.y();
        if (r.x() == null) {
            return Math.abs(dy);
        }
        double dx = r.x() + 0.5 - p.x();
        double dz = r.z() + 0.5 - p.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // "412 blocks", "1 block", "12,400 blocks"
    public static String blocks(double d) {
        long n = Math.round(Math.max(0, d));
        return String.format(Locale.US, "%,d", n) + (n == 1 ? " block" : " blocks");
    }

    // list column: "412 m" up to a kilometre, then "1.2 km"
    public static String formatDistance(double d) {
        if (d < 0) {
            return "";
        }
        long m = Math.round(d);
        if (m < 1000) {
            return m + " m";
        }
        double km = m / 1000.0;
        return km < 100 ? String.format(Locale.US, "%.1f km", km) : String.format(Locale.US, "%,d km", Math.round(km));
    }

    // "the_nether" reads as "the nether"
    public static String dimensionName(String dimension) {
        return dimension == null ? "" : dimension.replace('_', ' ');
    }
}
