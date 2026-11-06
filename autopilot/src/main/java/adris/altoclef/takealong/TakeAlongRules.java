package adris.altoclef.takealong;

import adris.altoclef.tasks.speedrun.gamer.DetourRules.Offset;
import adris.altoclef.tasks.speedrun.gamer.config.TakeAlongConfig;
import adris.altoclef.util.helpers.RecipeBill;
import adris.altoclef.util.helpers.WalkCost;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// taking things along on the way: a cluster of blocks the order, the kit or the basics still lack, seen close to the path,
// is worth a short detour when what it brings is worth at least k times what the detour costs, both in seconds. one measure
// for every raw item, not a rule per resource: the caller says what one item is worth and how urgent the need is (the
// tiers), this says whether a cluster in sight pays for the walk. the deciding half, pure: the world half (TakeAlongChain)
// hands in the clusters it sees, the wishes and the player's state, and runs the detour on DetourRules' state machine
public final class TakeAlongRules {
    // the raw items phase 1 knows (overworld blocks), and what one block gives on average: redstone ore drops 4 to 5, copper
    // ore 2 to 5, gravel a flint one time in ten. every log is one raw item, "log"
    public static final String LOG = "log";
    public static final Map<String, Double> YIELD = Map.of("coal", 1.0, "raw_iron", 1.0, "raw_gold", 1.0, "raw_copper", 3.5,
            "diamond", 1.0, "redstone", 4.5, "flint", 0.1, LOG, 1.0);
    // a speed of 0 would make a block take forever, which is what "no tool" says on its own (Part.tool)
    private static final double MIN_SPEED = 0.1;
    private static final double EPS = 1e-9;

    // one raw item wanted. held = in the bag now; needNow = the bag count the gate ahead and the current step come to;
    // needAll = the same with the rest of the order in this dimension (never under needNow); value = the seconds one item
    // saves, what coming back for it later would cost
    public record Wish(String raw, int held, int needNow, int needAll, double value) {
    }

    // one block seen, relative to the player's feet, and its hardness
    public record Spot(int dx, int dy, int dz, double hardness) {
        public double walk() {
            return WalkCost.estimate(dx, dy, dz);
        }
    }

    // a block of a raw item seen this look, before it is grouped
    public record Seen(String raw, Spot spot) {
    }

    // the blocks of one raw item in a cluster. yield = what one block gives on average; tool = the bag holds something that
    // gets the drop; speed = that tool's speed on the block (1 by hand); stacks = the drop goes onto a stack in the bag
    public record Part(String raw, double yield, boolean tool, double speed, boolean stacks, List<Spot> spots) {
    }

    // the blocks of one vein or patch, of one raw item or several (iron ore beside coal is one detour, not two)
    public record Cluster(List<Part> parts) {
    }

    // what the never list asks about the player. wet = in water, lava or fire; nightSky = night with the open sky over the
    // feet; foes = a hostile in sight; hold = the caller's own reason to leave detours alone now (a portal under way, a food
    // run), null when there is none; freeSlots = empty main inventory slots; shareSeconds = detour seconds in the window
    public record State(boolean overworld, float health, boolean wet, boolean nightSky, boolean foes, String hold, int freeSlots,
                        double shareSeconds) {
    }

    // what one part of a cluster brings. count = the items counted (a fraction for flint), target = the bag count to stop at,
    // blocks = the nearest blocks it takes to get them, value and breaking in seconds
    public record Take(String raw, double yield, double count, int target, List<Spot> blocks, double value, double breaking) {
    }

    // the cluster to take, what it brings, its value and cost in seconds, and the walk to its nearest and farthest block
    public record Choice(Cluster cluster, List<Take> takes, double value, double cost, double near, double far) {
        public double margin(double k) {
            return value - k * cost;
        }

        public int blocks() {
            int n = 0;
            for (Take t : takes) {
                n += t.blocks().size();
            }
            return n;
        }

        // the raw items, for the log line and the status ("raw_iron+coal")
        public String items() {
            StringBuilder out = new StringBuilder();
            for (Take t : takes) {
                if (!out.isEmpty()) {
                    out.append('+');
                }
                out.append(t.raw());
            }
            return out.toString();
        }

        // a chance drop (flint) stops at the blocks it was counted for, found or not: the gravel detour's rule, or every
        // patch is dug out. an ore stops on what it holds, 0 = no cap
        public int mineCap() {
            for (Take t : takes) {
                if (t.yield() < 1) {
                    return blocks();
                }
            }
            return 0;
        }
    }

    private static final Map<List<Double>, List<Offset>> TABLES = new ConcurrentHashMap<>();

    private TakeAlongRules() {
    }

    // ---- names

    // a leaf or setting name as one raw item: no namespace, every log one "log"
    public static String raw(String name) {
        String n = name.startsWith("minecraft:") ? name.substring("minecraft:".length()) : name;
        return n.equals(LOG) || n.endsWith("_log") ? LOG : n;
    }

    // the value table by raw item, the highest where two names are one raw item (oak_log and birch_log)
    public static Map<String, Double> values(Map<String, Double> table) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (table != null) {
            table.forEach((name, value) -> {
                if (name != null && value != null && value > 0) {
                    out.merge(raw(name), value, Math::max);
                }
            });
        }
        return out;
    }

    // ---- the wish list

    // the wishes from two bills: `now` over the gate's and the current step's items, `all` over those and the rest of the
    // order. only raw items this phase knows and has a value for, and only those something needs (nothing needed, nothing
    // in reserve)
    public static Map<String, Wish> wishes(RecipeBill.Bill now, RecipeBill.Bill all, Map<String, Double> values) {
        Map<String, Wish> out = new LinkedHashMap<>();
        if (all == null) {
            return out;
        }
        for (RecipeBill.Node node : all.nodes()) {
            if (node.kind() != null) {
                continue;
            }
            String raw = raw(node.name());
            Double value = values.get(raw);
            if (!YIELD.containsKey(raw) || value == null || value <= 0) {
                continue;
            }
            int needNow = now == null ? 0 : now.need(node.name());
            Wish w = new Wish(raw, node.held(), needNow, Math.max(needNow, node.gross()), value);
            out.merge(raw, w, TakeAlongRules::merge);
        }
        out.values().removeIf(w -> w.needAll() <= 0);
        return out;
    }

    // two leaves of one raw item (the generic logs and the main wood's): the needs add up. the generic one's bag count
    // already holds the other's logs, so the larger count is the bag's
    static Wish merge(Wish a, Wish b) {
        return new Wish(a.raw(), Math.max(a.held(), b.held()), a.needNow() + b.needNow(), a.needAll() + b.needAll(),
                Math.max(a.value(), b.value()));
    }

    // ---- drops on the floor (phase 2)

    // what one of every item in the bill is worth, in seconds: a leaf its value from the caller's table (by raw item), a made
    // item what its inputs are worth per item made, a smelt an eighth of a coal on top. a leaf with no value counts nothing,
    // so an item made of nothing valued is worth nothing
    public static Map<String, Double> unitValues(RecipeBill.Bill bill, Map<String, Double> values) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (bill == null) {
            return out;
        }
        for (RecipeBill.Node node : bill.nodes()) {
            unitValue(node.name(), bill, values, out, new java.util.HashSet<>());
        }
        return out;
    }

    private static double unitValue(String name, RecipeBill.Bill bill, Map<String, Double> values, Map<String, Double> memo,
                                    java.util.Set<String> visiting) {
        Double known = memo.get(name);
        if (known != null) {
            return known;
        }
        RecipeBill.Way way = bill.way(name);
        double v;
        if (way == null) {
            v = values.getOrDefault(raw(name), 0.0);
        } else if (!visiting.add(name) || way.out() <= 0) {
            // a cycle the bill should never have, or a way that makes nothing
            return 0;
        } else {
            double in = 0;
            for (Map.Entry<String, Integer> e : way.inputs().entrySet()) {
                in += e.getValue() * unitValue(e.getKey(), bill, values, memo, visiting);
            }
            if (way.kind() == RecipeBill.Kind.SMELT) {
                in += way.inputs().values().stream().mapToInt(Integer::intValue).sum() * values.getOrDefault(RecipeBill.COAL, 0.0) / 8;
            }
            v = in / way.out();
            visiting.remove(name);
        }
        memo.put(name, v);
        return v;
    }

    // the wishes a drop on the floor answers: every item of the bill, made ones too (an iron ingot, a helmet), each worth its
    // unit value, the tiers and the reserve as for the blocks. keyed by raw item (every log is one "log"). a raw item's wish is
    // the same as the block wish's; an item worth nothing is no wish
    public static Map<String, Wish> dropWishes(RecipeBill.Bill now, RecipeBill.Bill all, Map<String, Double> values) {
        Map<String, Wish> out = new LinkedHashMap<>();
        if (all == null) {
            return out;
        }
        Map<String, Double> unit = unitValues(all, values);
        for (RecipeBill.Node node : all.nodes()) {
            double value = unit.getOrDefault(node.name(), 0.0);
            if (value <= 0 || node.gross() <= 0) {
                continue;
            }
            int needNow = now == null ? 0 : now.need(node.name());
            Wish w = new Wish(raw(node.name()), node.held(), needNow, Math.max(needNow, node.gross()), value);
            out.merge(w.raw(), w, TakeAlongRules::merge);
        }
        out.values().removeIf(w -> w.needAll() <= 0);
        return out;
    }

    // one drop seen, by raw item: how many items it holds, where it lies (hardness 0, nothing to break), and whether it goes
    // onto a stack in the bag
    public record Drop(String raw, int count, Spot spot, boolean stacks) {
    }

    // what a group of drops lying together brings and costs: per raw item the nearest drops up to what is still worth anything,
    // the walk there and back, a second for the drops and the switch. null when nothing in it is wanted or has room, or the
    // nearest is past the reach ceiling. the choice's cluster holds the drops' spots per raw item, for the detour's box
    public static Choice evaluateDrops(List<Drop> drops, Map<String, Wish> wishes, State s, TakeAlongConfig cfg) {
        Map<String, List<Drop>> byRaw = new LinkedHashMap<>();
        for (Drop d : drops) {
            if (d.count() > 0) {
                byRaw.computeIfAbsent(d.raw(), k -> new ArrayList<>()).add(d);
            }
        }
        List<Take> takes = new ArrayList<>();
        List<Part> parts = new ArrayList<>();
        for (Map.Entry<String, List<Drop>> e : byRaw.entrySet()) {
            Wish w = wishes.get(e.getKey());
            if (w == null) {
                continue;
            }
            List<Drop> list = new ArrayList<>(e.getValue());
            list.sort(Comparator.comparingDouble(d -> d.spot().walk()));
            boolean stacks = list.stream().anyMatch(Drop::stacks);
            if (s.freeSlots() < cfg.minFreeSlots && !stacks) {
                continue;
            }
            int room = takeable(w, cfg);
            int count = 0;
            List<Spot> chosen = new ArrayList<>();
            for (Drop d : list) {
                if (count >= room) {
                    break;
                }
                count += d.count();
                chosen.add(d.spot());
            }
            double counted = Math.min(count, room);
            if (counted <= 0) {
                continue;
            }
            double value = worth(w, counted, cfg);
            if (value <= 0) {
                continue;
            }
            takes.add(new Take(e.getKey(), 1, counted, w.held() + (int) counted, List.copyOf(chosen), value, 0));
            parts.add(new Part(e.getKey(), 1, true, 1, stacks, List.copyOf(chosen)));
        }
        if (takes.isEmpty()) {
            return null;
        }
        double near = Double.POSITIVE_INFINITY;
        double far = 0;
        double value = 0;
        for (Take t : takes) {
            value += t.value();
            for (Spot b : t.blocks()) {
                near = Math.min(near, b.walk());
                far = Math.max(far, b.walk());
            }
        }
        if (near > cfg.reachCeiling) {
            return null;
        }
        double cost = 2 * near / cfg.walkBlocksPerSecond + cfg.dropSeconds + cfg.switchSeconds;
        return new Choice(new Cluster(List.copyOf(parts)), List.copyOf(takes), value, cost, near, far);
    }

    // the drops seen as groups lying together (within `gap` on every axis), nearest group first
    public static List<List<Drop>> groupDrops(List<Drop> drops, int gap) {
        List<Seen> seen = new ArrayList<>();
        java.util.IdentityHashMap<Spot, Drop> back = new java.util.IdentityHashMap<>();
        for (Drop d : drops) {
            seen.add(new Seen(d.raw(), d.spot()));
            back.put(d.spot(), d);
        }
        List<List<Drop>> out = new ArrayList<>();
        for (List<Seen> g : group(seen, gap)) {
            List<Drop> list = new ArrayList<>();
            for (Seen x : g) {
                list.add(back.get(x.spot()));
            }
            out.add(list);
        }
        return out;
    }

    // of the drop groups, the one worth it with the largest value - k x cost, null for none
    public static Choice chooseDrops(List<List<Drop>> groups, Map<String, Wish> wishes, State s, TakeAlongConfig cfg) {
        if (wishes.isEmpty() || blocked(s, cfg) != null) {
            return null;
        }
        Choice best = null;
        for (List<Drop> g : groups) {
            Choice ch = evaluateDrops(g, wishes, s, cfg);
            if (ch != null && worthIt(ch, cfg) && (best == null || ch.margin(cfg.k) > best.margin(cfg.k))) {
                best = ch;
            }
        }
        return best;
    }

    // ---- animals for food (phase 3)

    // the one wish food is, in points
    public static final String FOOD = "food";
    // what one adult gives on average, in points at its cooked worth: a cow or a pig 1 to 3 meat of 8, a sheep 1 to 2 of 6, a
    // chicken one of 6. no rabbits: one hopping by gives half a meal at most
    public static final Map<String, Double> PREY_POINTS = Map.of("cow", 16.0, "mooshroom", 16.0, "pig", 16.0, "sheep", 9.0,
            "chicken", 6.0);

    // one animal seen: its kind, the points it gives, where it stands
    public record Prey(String kind, double points, Spot spot) {
    }

    // the food wish: the bag's points against the food's tiers
    public static Wish foodWish(double held, int needNow, int needAll, double value) {
        return new Wish(FOOD, (int) Math.floor(held), needNow, Math.max(needNow, needAll), value);
    }

    // what a group of animals standing together brings and costs: the nearest up to what food is still worth anything, the
    // walk there and back, the kills, the drops and the switch. null when food is not wanted, the bag has no room for meat
    // (no free slot beyond the minimum), or the nearest is past the reach ceiling. the cluster's parts are by animal kind
    public static Choice evaluatePrey(List<Prey> prey, Wish food, State s, TakeAlongConfig cfg) {
        if (food == null || prey.isEmpty() || s.freeSlots() < cfg.minFreeSlots) {
            return null;
        }
        int room = takeable(food, cfg);
        if (room <= 0) {
            return null;
        }
        List<Prey> list = new ArrayList<>(prey);
        list.sort(Comparator.comparingDouble(p -> p.spot().walk()));
        double points = 0;
        List<Prey> chosen = new ArrayList<>();
        for (Prey p : list) {
            if (points >= room) {
                break;
            }
            if (p.points() <= 0) {
                continue;
            }
            points += p.points();
            chosen.add(p);
        }
        double counted = Math.min(points, room);
        if (counted <= 0) {
            return null;
        }
        double value = worth(food, counted, cfg);
        if (value <= 0) {
            return null;
        }
        double near = chosen.get(0).spot().walk();
        double far = 0;
        Map<String, List<Spot>> byKind = new LinkedHashMap<>();
        List<Spot> spots = new ArrayList<>();
        for (Prey p : chosen) {
            far = Math.max(far, p.spot().walk());
            byKind.computeIfAbsent(p.kind(), k -> new ArrayList<>()).add(p.spot());
            spots.add(p.spot());
        }
        if (near > cfg.reachCeiling) {
            return null;
        }
        double kills = cfg.killSeconds * chosen.size();
        double cost = 2 * near / cfg.walkBlocksPerSecond + kills + cfg.dropSeconds + cfg.switchSeconds;
        List<Part> parts = new ArrayList<>();
        byKind.forEach((kind, at) -> parts.add(new Part(kind, PREY_POINTS.getOrDefault(kind, 0.0), true, 1, false, List.copyOf(at))));
        Take take = new Take(FOOD, counted / chosen.size(), counted, food.held() + (int) Math.round(counted), List.copyOf(spots), value, kills);
        return new Choice(new Cluster(List.copyOf(parts)), List.of(take), value, cost, near, far);
    }

    // the animals as groups standing together (within `gap` on every axis), nearest group first
    public static List<List<Prey>> groupPrey(List<Prey> prey, int gap) {
        List<Seen> seen = new ArrayList<>();
        java.util.IdentityHashMap<Spot, Prey> back = new java.util.IdentityHashMap<>();
        for (Prey p : prey) {
            seen.add(new Seen(p.kind(), p.spot()));
            back.put(p.spot(), p);
        }
        List<List<Prey>> out = new ArrayList<>();
        for (List<Seen> g : group(seen, gap)) {
            List<Prey> list = new ArrayList<>();
            for (Seen x : g) {
                list.add(back.get(x.spot()));
            }
            out.add(list);
        }
        return out;
    }

    // of the groups, the one worth it with the largest value - k x cost, null for none
    public static Choice choosePrey(List<List<Prey>> groups, Wish food, State s, TakeAlongConfig cfg) {
        if (food == null || blocked(s, cfg) != null) {
            return null;
        }
        Choice best = null;
        for (List<Prey> g : groups) {
            Choice ch = evaluatePrey(g, food, s, cfg);
            if (ch != null && worthIt(ch, cfg) && (best == null || ch.margin(cfg.k) > best.margin(cfg.k))) {
                best = ch;
            }
        }
        return best;
    }

    // the better of two choices by margin, either may be null
    public static Choice better(Choice a, Choice b, TakeAlongConfig cfg) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return b.margin(cfg.k) > a.margin(cfg.k) ? b : a;
    }

    // the reserve on top of a need: reserveShare of it, rounded up. 10 needed is 5 more, 1 needed is 1 more
    public static int reserve(int need, TakeAlongConfig cfg) {
        return need <= 0 ? 0 : (int) Math.ceil(need * cfg.reserveShare - EPS);
    }

    // the urgency of the item that would make the bag count `position` (1 is the first one in the bag): now up to the now
    // need, later up to the whole need, then the reserve's falling factor, then nothing
    public static double urgency(int position, int needNow, int needAll, TakeAlongConfig cfg) {
        if (position <= needNow) {
            return cfg.urgencyNow;
        }
        if (position <= needAll) {
            return cfg.urgencyLater;
        }
        int reserve = reserve(needAll, cfg);
        int k = position - needAll;
        if (k > reserve) {
            return 0;
        }
        return cfg.reserveStart * (1 - (double) (k - 1) / reserve);
    }

    // how many more items are still worth anything: up to the end of the reserve
    public static int takeable(Wish w, TakeAlongConfig cfg) {
        return Math.max(0, w.needAll() + reserve(w.needAll(), cfg) - w.held());
    }

    // what `count` more items are worth, in seconds, each at its own place in the bag. a fraction (flint from a few gravel)
    // counts its share of the next item
    public static double worth(Wish w, double count, TakeAlongConfig cfg) {
        double sum = 0;
        for (int j = 0; j < count; j++) {
            double share = Math.min(1, count - j);
            sum += share * w.value() * urgency(w.held() + j + 1, w.needNow(), w.needAll(), cfg);
        }
        return sum;
    }

    // ---- value and cost of a cluster

    public static double breakSeconds(double hardness, double speed, TakeAlongConfig cfg) {
        return cfg.breakFactor * Math.max(0, hardness) / Math.max(MIN_SPEED, speed);
    }

    // what a part brings: the yield of its blocks seen, never past what is still worth something, from the nearest blocks
    // it takes. null when it brings nothing
    public static Take take(Part p, Wish w, TakeAlongConfig cfg) {
        if (p.spots().isEmpty() || p.yield() <= 0) {
            return null;
        }
        List<Spot> spots = new ArrayList<>(p.spots());
        spots.sort(Comparator.comparingDouble(Spot::walk));
        double count = Math.min(p.yield() * spots.size(), takeable(w, cfg));
        if (count <= 0) {
            return null;
        }
        int blocks = Math.min(spots.size(), (int) Math.ceil(count / p.yield() - EPS));
        List<Spot> chosen = List.copyOf(spots.subList(0, blocks));
        double breaking = 0;
        for (Spot s : chosen) {
            breaking += breakSeconds(s.hardness(), p.speed(), cfg);
        }
        int target = w.held() + (int) Math.ceil(count - EPS);
        return new Take(p.raw(), p.yield(), count, target, chosen, worth(w, count, cfg), breaking);
    }

    // a drop with room in the bag: more than three free slots, or a stack of it to go onto
    public static boolean room(Part p, State s, TakeAlongConfig cfg) {
        return s.freeSlots() >= cfg.minFreeSlots || p.stacks();
    }

    // value = what the parts bring; cost = the walk there and back at walking speed, breaking the blocks, the drops and the
    // switch. null when nothing in it is wanted, no tool or room for it, or its nearest block is past the reach ceiling
    public static Choice evaluate(Cluster c, Map<String, Wish> wishes, State s, TakeAlongConfig cfg) {
        List<Take> takes = new ArrayList<>();
        for (Part p : c.parts()) {
            Wish w = wishes.get(p.raw());
            if (w == null || !p.tool() || !room(p, s, cfg)) {
                continue;
            }
            Take t = take(p, w, cfg);
            if (t != null && t.value() > 0) {
                takes.add(t);
            }
        }
        if (takes.isEmpty()) {
            return null;
        }
        double near = Double.POSITIVE_INFINITY;
        double far = 0;
        double value = 0;
        double breaking = 0;
        for (Take t : takes) {
            value += t.value();
            breaking += t.breaking();
            for (Spot b : t.blocks()) {
                near = Math.min(near, b.walk());
                far = Math.max(far, b.walk());
            }
        }
        if (near > cfg.reachCeiling) {
            return null;
        }
        double cost = 2 * near / cfg.walkBlocksPerSecond + breaking + cfg.dropSeconds + cfg.switchSeconds;
        return new Choice(c, List.copyOf(takes), value, cost, near, far);
    }

    public static boolean worthIt(Choice ch, TakeAlongConfig cfg) {
        return ch.value() >= cfg.k * ch.cost();
    }

    // the cluster to take, null for none: of those worth it, the largest value - k x cost
    public static Choice choose(List<Cluster> clusters, Map<String, Wish> wishes, State s, TakeAlongConfig cfg) {
        if (wishes.isEmpty() || blocked(s, cfg) != null) {
            return null;
        }
        Choice best = null;
        for (Cluster c : clusters) {
            Choice ch = evaluate(c, wishes, s, cfg);
            if (ch != null && worthIt(ch, cfg) && (best == null || ch.margin(cfg.k) > best.margin(cfg.k))) {
                best = ch;
            }
        }
        return best;
    }

    // ---- the never list

    // what ends a detour under way as well as keeping a new one from starting, null when nothing does
    public static String stopping(State s, TakeAlongConfig cfg) {
        if (!s.overworld()) {
            return "not in the overworld";
        }
        if (s.hold() != null) {
            return s.hold();
        }
        if (s.foes()) {
            return "a foe in sight";
        }
        if (s.health() < cfg.minHealth) {
            return "hurt";
        }
        if (s.wet()) {
            return "in water, lava or fire";
        }
        if (s.nightSky()) {
            return "night under the open sky";
        }
        return null;
    }

    // what keeps a new detour from starting: the never list, and the share of the window detours already had (a detour under
    // way finishes its clock)
    public static String blocked(State s, TakeAlongConfig cfg) {
        String why = stopping(s, cfg);
        if (why != null) {
            return why;
        }
        if (s.shareSeconds() >= cfg.shareCeiling * cfg.shareWindowSeconds) {
            return "detours had their share of the last ten minutes";
        }
        return null;
    }

    // ---- what the world half looks at

    // the blocks seen as clusters: blocks within `gap` of each other on every axis are one cluster, whatever their raw item.
    // in the order the first block of each was seen, so the nearest cluster comes first
    public static List<List<Seen>> group(List<Seen> seen, int gap) {
        int n = seen.size();
        int[] root = new int[n];
        for (int i = 0; i < n; i++) {
            root[i] = i;
        }
        for (int i = 0; i < n; i++) {
            Spot a = seen.get(i).spot();
            for (int j = i + 1; j < n; j++) {
                Spot b = seen.get(j).spot();
                if (Math.abs(a.dx() - b.dx()) <= gap && Math.abs(a.dy() - b.dy()) <= gap && Math.abs(a.dz() - b.dz()) <= gap) {
                    int ra = find(root, i);
                    int rb = find(root, j);
                    if (ra != rb) {
                        root[Math.max(ra, rb)] = Math.min(ra, rb);
                    }
                }
            }
        }
        Map<Integer, List<Seen>> byRoot = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            byRoot.computeIfAbsent(find(root, i), k -> new ArrayList<>()).add(seen.get(i));
        }
        return new ArrayList<>(byRoot.values());
    }

    private static int find(int[] root, int i) {
        while (root[i] != i) {
            root[i] = root[root[i]];
            i = root[i];
        }
        return i;
    }

    // the cells around the feet worth reading: within the reach ceiling (WalkCost) and close enough to be in sight from the
    // eyes (the eyes are a block and a half over the feet, hence the 2). nearest walk first, ties to our own height first,
    // then a fixed order so two looks never disagree
    public static List<Offset> offsets(double reach, double sight) {
        return TABLES.computeIfAbsent(List.of(reach, sight), k -> build(reach, sight));
    }

    private static List<Offset> build(double reach, double sight) {
        double around = Math.max(0, sight) + 2;
        int flat = (int) Math.floor(Math.min(Math.max(reach, 0), around));
        int tall = (int) Math.floor(Math.min(Math.max(reach, 0) / WalkCost.VERTICAL_WEIGHT, around));
        List<Offset> cells = new ArrayList<>();
        for (int dy = -tall; dy <= tall; dy++) {
            for (int dx = -flat; dx <= flat; dx++) {
                for (int dz = -flat; dz <= flat; dz++) {
                    if (WalkCost.within(dx, dy, dz, reach) && dx * dx + dy * dy + dz * dz <= around * around) {
                        cells.add(new Offset(dx, dy, dz));
                    }
                }
            }
        }
        cells.sort(Comparator.comparingDouble((Offset o) -> WalkCost.estimate(o.dx(), o.dy(), o.dz()))
                .thenComparingInt(o -> Math.abs(o.dy())).thenComparingInt(Offset::dy).thenComparingInt(Offset::dx)
                .thenComparingInt(Offset::dz));
        return List.copyOf(cells);
    }
}
