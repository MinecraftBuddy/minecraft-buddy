package adris.altoclef.trace;

import java.util.Locale;

// the one line that says what the bot was in the middle of, printed every few seconds. takes plain values so the
// formatting is testable, the caller does the asking of minecraft
public final class Vitals {

    // what a gamer run adds. head is the working kit row, null item when there is none
    public record Gamer(String phase, int attempt, String headItem, int headCount, int foodUnits, int foodTarget) {
    }

    private Vitals() {
    }

    public static String format(float hp, float absorption, int hunger, float saturation, int armor, String held,
                                String cursorItem, int cursorCount, int x, int y, int z, String dim, String chain,
                                String task, Gamer gamer) {
        return format(hp, absorption, hunger, saturation, armor, held, cursorItem, cursorCount, x, y, z, dim, chain, task,
                gamer, null, 0);
    }

    // path is none, live or calc, stand is the ticks since the feet last moved. they come last so a grep for the old
    // tail of the line keeps working. a null path leaves both off
    public static String format(float hp, float absorption, int hunger, float saturation, int armor, String held,
                                String cursorItem, int cursorCount, int x, int y, int z, String dim, String chain,
                                String task, Gamer gamer, String path, int stand) {
        return format(hp, absorption, hunger, saturation, armor, held, cursorItem, cursorCount, x, y, z, dim, chain, task,
                gamer, path, stand, null);
    }

    // wet is in water or not, so the share of a game spent at sea can be counted. last for the same reason, null leaves it off
    public static String format(float hp, float absorption, int hunger, float saturation, int armor, String held,
                                String cursorItem, int cursorCount, int x, int y, int z, String dim, String chain,
                                String task, Gamer gamer, String path, int stand, Boolean wet) {
        StringBuilder sb = new StringBuilder(160);
        sb.append("vitals hp=").append(one(hp)).append(" abs=").append(one(absorption));
        sb.append(" hunger=").append(hunger).append(" sat=").append(one(saturation));
        sb.append(" armor=").append(armor).append(" held=").append(word(held));
        sb.append(" cursor=");
        if (cursorItem == null || cursorItem.isEmpty() || cursorCount <= 0) {
            sb.append("none");
        } else {
            sb.append(cursorItem).append(" x").append(cursorCount);
        }
        sb.append(" pos=").append(x).append(',').append(y).append(',').append(z);
        sb.append(" dim=").append(word(dim)).append(" chain=").append(word(chain));
        sb.append(" task=\"").append(clean(task)).append('"');
        if (gamer != null) {
            sb.append(" gamer=").append(word(gamer.phase())).append(" att=").append(gamer.attempt());
            sb.append(" head=");
            if (gamer.headItem() == null || gamer.headItem().isEmpty()) {
                sb.append("none");
            } else {
                sb.append(gamer.headItem()).append(" x").append(gamer.headCount());
            }
            sb.append(" food=").append(gamer.foodUnits()).append('/').append(gamer.foodTarget());
        }
        if (path != null) {
            sb.append(" path=").append(word(path)).append(" stand=").append(stand);
        }
        if (wet != null) {
            sb.append(" wet=").append(wet);
        }
        return sb.toString();
    }

    // locale root, a comma for a decimal point would split the key=value on some machines
    private static String one(float v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static String word(String s) {
        return s == null || s.isEmpty() ? "none" : s;
    }

    // the quotes would end the task="..." early, and a newline would end the line
    private static String clean(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\"", "").replace('\n', ' ').replace('\r', ' ');
    }
}
