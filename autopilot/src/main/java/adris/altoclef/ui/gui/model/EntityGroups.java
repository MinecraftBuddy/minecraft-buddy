package adris.altoclef.ui.gui.model;

import adris.altoclef.ui.gui.facade.WorldAccess.EntityEntry;
import adris.altoclef.ui.gui.facade.WorldAccess.PlayerEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// the follow tab's data shaping, no gui in here: entities folded into one group per type, the flat row list a
// virtual list wants, search, and "is this row the thing we are following"
public final class EntityGroups {

    // memberOnly: the group is here because a member's own name matched the search, not its type, so the matching
    // member should be on screen without a click
    public record Group(String typeId, String typeName, String iconItemId, int count, double nearest,
                        List<EntityEntry> members, boolean memberOnly) {
    }

    // a row is either a group header or one entity under an expanded group. key is the list's identity for the row
    public interface Row {
        String key();
    }

    public record GroupRow(Group group) implements Row {
        @Override
        public String key() {
            return "g:" + group.typeId();
        }
    }

    public record EntityRow(EntityEntry entry) implements Row {
        @Override
        public String key() {
            return "e:" + entry.id();
        }
    }

    private EntityGroups() {
    }

    // "minecraft:zombie" -> "zombie"
    public static String path(String typeId) {
        if (typeId == null) {
            return "";
        }
        int i = typeId.indexOf(':');
        return i >= 0 ? typeId.substring(i + 1) : typeId;
    }

    private static String lower(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static String typeName(EntityEntry e) {
        if (e.typeName() != null && !e.typeName().isBlank()) {
            return e.typeName();
        }
        String p = path(e.typeId()).replace('_', ' ');
        return p.isEmpty() ? p : Character.toUpperCase(p.charAt(0)) + p.substring(1);
    }

    public static boolean named(EntityEntry e) {
        return e.displayName() != null && !e.displayName().isBlank();
    }

    // what a single entity is called in a row: its own name, or "Zombie #104"
    public static String label(EntityEntry e) {
        return named(e) ? e.displayName() : typeName(e) + " #" + e.id();
    }

    private static boolean typeMatches(EntityEntry e, String q) {
        return lower(typeName(e)).contains(q) || lower(path(e.typeId())).contains(q);
    }

    private static boolean nameMatches(EntityEntry e, String q) {
        return named(e) && lower(e.displayName()).contains(q);
    }

    // groups by type, nearest group first, members nearest first. a query keeps a whole group when the type matches,
    // otherwise only the members whose own name matches
    public static List<Group> group(List<EntityEntry> entries, String query) {
        String q = lower(query);
        Map<String, List<EntityEntry>> byType = new LinkedHashMap<>();
        for (EntityEntry e : entries == null ? List.<EntityEntry>of() : entries) {
            if (e == null || e.typeId() == null) {
                continue;
            }
            if (!q.isEmpty() && !typeMatches(e, q) && !nameMatches(e, q)) {
                continue;
            }
            byType.computeIfAbsent(e.typeId(), k -> new ArrayList<>()).add(e);
        }
        List<Group> out = new ArrayList<>();
        for (Map.Entry<String, List<EntityEntry>> en : byType.entrySet()) {
            List<EntityEntry> members = en.getValue();
            members.sort(Comparator.comparingDouble(EntityEntry::distance).thenComparingInt(EntityEntry::id));
            String icon = null;
            for (EntityEntry m : members) {
                if (m.iconItemId() != null) {
                    icon = m.iconItemId();
                    break;
                }
            }
            EntityEntry first = members.get(0);
            boolean memberOnly = !q.isEmpty() && !typeMatches(first, q);
            out.add(new Group(en.getKey(), typeName(first), icon, members.size(), first.distance(), List.copyOf(members), memberOnly));
        }
        out.sort(Comparator.comparingDouble(Group::nearest).thenComparing(Group::typeId));
        return out;
    }

    // the flat list: each group header, then its members when the group is expanded
    public static List<Row> rows(List<Group> groups, Set<String> expanded) {
        List<Row> out = new ArrayList<>();
        for (Group g : groups) {
            out.add(new GroupRow(g));
            if (expanded.contains(g.typeId())) {
                for (EntityEntry m : g.members()) {
                    out.add(new EntityRow(m));
                }
            }
        }
        return out;
    }

    // people in the loaded area first (you at the top, you are at distance 0), then the ones who are only on the tab list
    public static List<PlayerEntry> players(List<PlayerEntry> entries, String query) {
        String q = lower(query);
        List<PlayerEntry> out = new ArrayList<>();
        for (PlayerEntry p : entries == null ? List.<PlayerEntry>of() : entries) {
            if (p != null && p.name() != null && (q.isEmpty() || lower(p.name()).contains(q))) {
                out.add(p);
            }
        }
        out.sort(Comparator.comparing((PlayerEntry p) -> p.distance() < 0)
                .thenComparing(p -> !p.self())
                .thenComparingDouble(PlayerEntry::distance)
                .thenComparing(p -> lower(p.name())));
        return out;
    }

    public static int nearbyPlayers(List<PlayerEntry> entries) {
        int n = 0;
        for (PlayerEntry p : entries == null ? List.<PlayerEntry>of() : entries) {
            if (p != null && !p.self() && p.distance() >= 0) {
                n++;
            }
        }
        return n;
    }

    // "12 m". one decimal under 10 would be false precision for a thing that is walking around
    public static String meters(double d) {
        return Math.max(0, Math.round(d)) + " m";
    }

    // ----- following, matched loosely because the text is whatever the command layer called the target

    public static boolean followsPlayer(String following, String name) {
        return !lower(following).isEmpty() && lower(following).equals(lower(name));
    }

    // "every zombie" (also "every zombies", the s is not worth being strict about)
    public static boolean followsGroup(String following, Group g) {
        String f = lower(following);
        if (!f.startsWith("every ")) {
            return false;
        }
        String rest = f.substring(6).trim();
        if (rest.endsWith("s") && rest.length() > 1) {
            String singular = rest.substring(0, rest.length() - 1);
            if (matchesType(singular, g)) {
                return true;
            }
        }
        return matchesType(rest, g);
    }

    private static boolean matchesType(String s, Group g) {
        return s.equals(lower(g.typeName())) || s.equals(lower(path(g.typeId())).replace('_', ' '))
                || s.equals(lower(path(g.typeId())));
    }

    // following() is only the mob's own name for a single entity ("Zombie"), which cannot say which one. the group
    // can at least say "one of these"
    public static boolean followsOneOf(String following, Group g) {
        String f = lower(following);
        if (f.isEmpty()) {
            return false;
        }
        if (f.equals(lower(g.typeName())) || f.equals(lower(path(g.typeId())).replace('_', ' '))) {
            return true;
        }
        for (EntityEntry m : g.members()) {
            if (named(m) && f.equals(lower(m.displayName()))) {
                return true;
            }
        }
        return false;
    }

    public static boolean followsEntity(String following, EntityEntry e) {
        String f = lower(following);
        if (f.isEmpty()) {
            return false;
        }
        return f.equals(lower(label(e))) || (named(e) && f.equals(lower(e.displayName())));
    }
}
