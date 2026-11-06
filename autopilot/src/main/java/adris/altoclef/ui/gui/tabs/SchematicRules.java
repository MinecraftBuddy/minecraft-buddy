package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.SchematicsAccess.Building;
import adris.altoclef.ui.gui.facade.SchematicsAccess.Line;
import adris.altoclef.ui.gui.facade.SchematicsAccess.Materials;
import adris.altoclef.ui.gui.facade.SchematicsAccess.Placement;
import adris.altoclef.ui.gui.facade.SchematicsAccess.Row;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// the words and numbers of the schematics tab, kept away from the drawing so a test can read them as strings
final class SchematicRules {

    private SchematicRules() {
    }

    // rows whose path has the query in it, case insensitive. the path and not the name, so "villages" finds the farm
    static List<Row> filter(List<Row> rows, String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return rows;
        }
        List<Row> out = new ArrayList<>();
        for (Row r : rows) {
            if (r.relativePath().toLowerCase(Locale.ROOT).contains(q)) {
                out.add(r);
            }
        }
        return out;
    }

    // "4 schematics", "2 of 4 schematics", and how many of them didn't parse on the end
    static String count(int shown, int total, int broken) {
        String noun = total == 1 ? " schematic" : " schematics";
        String head = shown == total ? total + noun : shown + " of " + total + noun;
        return broken == 0 ? head : head + " · " + broken + " didn't parse";
    }

    static String num(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    // "47 KB", "120 B", "1.2 MB": the file size is a hint about how long a parse takes, not a number to compare
    static String fileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return Math.round(bytes / 1024.0) + " KB";
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
    }

    // the small line under the name in the pane: format, size and the file size, or just what we know of a broken one
    static String meta(Row r) {
        return r.ok() ? r.formatText() + " · " + r.sizeText() + " · " + fileSize(r.fileSize())
                : r.formatText() + " · " + fileSize(r.fileSize());
    }

    // the list row's second line
    static String sub(Row r) {
        return r.ok() ? r.formatText() + " · " + r.sizeText() : "Didn't parse";
    }

    // "3,557 blocks · 7 kinds", the shortfall is drawn separately in red by summaryTail
    static String summaryHead(Materials m) {
        int kinds = m.items().size();
        return num(m.totalNeeded()) + (m.totalNeeded() == 1 ? " block" : " blocks") + " · " + kinds + (kinds == 1 ? " kind" : " kinds");
    }

    // the same count with the unchecked rows gone: what the build will actually ask for. the shortfall and the
    // warning read this one, the head keeps the whole file's numbers. a row at a time, so it's a touch high when one
    // cell costs two items (untick the pot and the poppies in it still count here, the build drops them too). the
    // list only has totals per item, and the tab isn't worth a recount per click
    static Materials checkedOnly(Materials m, Set<String> excluded) {
        if (excluded == null || excluded.isEmpty()) {
            return m;
        }
        List<Line> kept = new ArrayList<>();
        for (Line l : m.items()) {
            if (!excluded.contains(l.itemId())) {
                kept.add(l);
            }
        }
        return new Materials(m.state(), m.relativePath(), List.copyOf(kept), m.cantPlace(), m.notCounted(), m.notes(), m.error());
    }

    // how many ITEM rows are unchecked. counted off the list, not the set, so an id the file no longer has can't
    // make it say "1 left out" over a list where every box is ticked
    static int leftOut(Materials m, Set<String> excluded) {
        if (excluded == null || excluded.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (Line l : m.items()) {
            if (excluded.contains(l.itemId())) {
                n++;
            }
        }
        return n;
    }

    // " · 2 left out" between the head and the tail when any rows are off, so the short number changing has its
    // reason next to it. "" when every box is ticked. drawn in the quiet colour, it is a choice and not a problem
    static String summaryMid(int leftOut) {
        return leftOut > 0 ? " · " + leftOut + " left out" : "";
    }

    // " · 3,490 short", or " · all in your inventory" when there is nothing to fetch. build is the checked rows only,
    // and with rows left out "all" would be a lie, so it says the rest
    static String summaryTail(Materials build, int leftOut) {
        long missing = build.totalMissing();
        if (missing > 0) {
            return " · " + num(missing) + " short";
        }
        return leftOut > 0 ? " · rest in your inventory" : " · all in your inventory";
    }

    // the line next to the build button, "" when there is nothing to warn about. build is the checked rows only
    static String warning(Materials build) {
        long missing = build.totalMissing();
        if (missing > 0) {
            return num(missing) + " short. It pauses when it runs out.";
        }
        if (!build.cantPlace().isEmpty()) {
            return "Some blocks can't be placed, those spots stay empty.";
        }
        return "";
    }

    // the caption over the list. while this file's build is in flight the boxes still work, they just feed the next
    // start, and the caption is where that gets said
    static String checkHint(boolean building) {
        return building ? "Changes apply on the next start, not this build." : "Unchecked items are left out of the build. A block others rest on can leave them floating.";
    }

    // the same in fewer words for a narrow pane
    static String checkHintShort(boolean building) {
        return building ? "Applies on the next start." : "Unchecked items are left out.";
    }

    // the chip on the map: "x 100 y 64 z -40 · 90°", "· mirrored" on the end when it is. dx dz is a drag in progress
    static String coords(Placement p, int dx, int dz) {
        String s = "x " + (p.x() + dx) + " y " + p.y() + " z " + (p.z() + dz) + " · " + (p.turns() & 3) * 90 + "°";
        return p.mirrored() ? s + " · mirrored" : s;
    }

    // "the Nether" for minecraft:the_nether, the id's path with the underscores gone for anything modded
    static String dimensionName(String id) {
        if (id == null) {
            return "another dimension";
        }
        String path = id.substring(id.indexOf(':') + 1);
        return switch (path) {
            case "overworld" -> "the Overworld";
            case "the_nether" -> "the Nether";
            case "the_end" -> "the End";
            default -> path.replace('_', ' ');
        };
    }

    // what the build bar says about the last build request, "" when it isn't saying anything
    static String buildingLine(Building b) {
        return switch (b.state()) {
            case PARSING -> "Reading the file…";
            case STARTED -> "Building it. Stop is in the bar below.";
            case ERROR -> b.message() == null || b.message().isEmpty() ? "The build didn't start" : b.message();
            default -> "";
        };
    }

    // "40 / 212" for an item, just the count for the folded kinds
    static String counts(Line l) {
        return l.kind() == adris.altoclef.ui.gui.facade.SchematicsAccess.Kind.ITEM ? num(l.have()) + " / " + num(l.needed()) : num(l.needed());
    }

    // 0..1 of the stack the player holds
    static float coverage(Line l) {
        if (l.needed() <= 0) {
            return 1f;
        }
        return Math.max(0f, Math.min(1f, l.have() / (float) l.needed()));
    }
}
