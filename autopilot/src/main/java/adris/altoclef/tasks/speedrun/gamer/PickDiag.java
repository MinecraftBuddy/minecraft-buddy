package adris.altoclef.tasks.speedrun.gamer;

import java.util.List;

// the "where did the iron pickaxe go" log lines. the world half (MinecraftFacts scans, IronPhase asks) only feeds numbers in,
// so the edge and the wording are testable without a game. every line starts with "kit: iron pickaxe" to grep for
public final class PickDiag {
    // -1 = no look yet this phase, 0 = looked and the plan did not own one, anything above = it did
    private int lastHave = -1;

    public void reset() {
        lastHave = -1;
    }

    // true on the one tick the plan goes from owning an iron pickaxe to not owning one. the first look only records, so
    // entering the phase without a pick is not an edge, and a pick that stays gone does not say it again
    public boolean lost(int have) {
        boolean edge = lastHave > 0 && have <= 0;
        lastHave = have;
        return edge;
    }

    // "iron_pickaxe 215/250 hotbar#3 worn". slot < 0 for the places that have no number (cursor, offhand)
    public static String stack(String item, int damage, int max, String where, int slot, boolean worn) {
        return item + " " + damage + "/" + max + " " + where + (slot >= 0 ? "#" + slot : "") + (worn ? " worn" : "");
    }

    // picks are the stacks the scan counted. open are pick stacks sitting in the open screen's own slots (a chest, a
    // furnace), which the scan never looks at. screen and menu are class names, "none" when nothing is open
    public static String scan(List<String> picks, List<String> open, String screen, String menu) {
        return "picks=[" + String.join(", ", picks) + "] open=[" + String.join(", ", open) + "] screen=" + screen + " menu=" + menu;
    }

    // the line IronPhase logs on the edge above
    public static String missing(int have, int held, int spent, String scan) {
        return "kit: iron pickaxe missing, have=" + have + " held=" + held + " spent=" + spent + " " + scan;
    }

    // the line MinecraftFacts logs when it first classes an iron pickaxe as worn out
    public static String wornOut(int damage, int max, String where, int slot) {
        return "kit: iron pickaxe worn out, " + stack("iron_pickaxe", damage, max, where, slot, false) + ", counted as gone from here";
    }
}
