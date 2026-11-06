package adris.altoclef.util.helpers;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.function.ToIntFunction;

// the whole job's shopping list, added up before anything gets fetched. the recipe tasks each replan from the bag one level
// at a time, so a lantern mined iron for its nuggets, then the chain noticed it wanted an ingot too and off we went again.
// this pools every target's needs (the bag counted once, shared between everyone who wants the thing), so GatherBillTask can
// fetch it all in one go and then only craft. pure: recipes come in through Book, tests hand it fakes, the game CatalogueBook
public final class RecipeBill {

    public static final String TABLE = "crafting_table";
    public static final String FURNACE = "furnace";
    public static final String COAL = "coal";
    public static final String IRON = "iron_ingot";
    public static final String RAW_IRON = "raw_iron";
    public static final String SMOOTH_STONE = "smooth_stone";
    // CollectIronIngotTask's blast furnace: 5 ingots (smelted the slow way first), 3 smooth stone and a furnace
    static final int BLAST_IRON = 5;
    static final int BLAST_SMOOTH_STONE = 3;
    // a lump of coal is 8 smelts, same in a blast furnace (burns twice as fast, cooks twice as fast)
    static final int SMELTS_PER_COAL = 8;
    // the fixed point below settles in 2 or 3 rounds (stations, then fuel for the stations' smelts). this is just a seatbelt
    private static final int MAX_ROUNDS = 8;

    public static final String[] PICKAXES = {null, "wooden_pickaxe", "stone_pickaxe", "iron_pickaxe", "diamond_pickaxe", "netherite_pickaxe"};
    // the blocks a shovel is for, by catalogue name
    static final Set<String> SHOVEL_STUFF = Set.of("dirt", "sand", "red_sand", "gravel", "clay_ball", "soul_sand", "soul_soil", "snowball", "snow_block");
    static final int AXE_LOGS = 8;
    static final int SHOVEL_BLOCKS = 32;

    private RecipeBill() {
    }

    // CRAFT fits the 2x2, CRAFT_TABLE wants a table, FILL is a bucket dunked in something
    public enum Kind {CRAFT, CRAFT_TABLE, SMELT, FILL}

    // how a thing is made: one run eats inputs (name -> per run) and makes out
    public record Way(Kind kind, Map<String, Integer> inputs, int out) {
    }

    public interface Book {
        // null: not made from anything we know about. it's a leaf
        Way wayOf(String name);

        // the pickaxe tier (MiningRequirement ordinal) a plain mine of it needs, -1 when it isn't fetched up front
        int gatherTier(String name);

        int held(String name);

        // the wood prefixes that have X_planks made from X_log, so generic planks/log demand has somewhere to go
        default List<String> woods() {
            return List.of();
        }
    }

    // the bits that come from the world: stations already standing nearby, fuel in the bag that isn't coal (in smelts), and
    // CollectIronIngotTask.wouldBuildBlast for that many ingots
    public record World(boolean tableNear, boolean furnaceNear, int otherFuelSmelts, IntPredicate buildsBlast) {
    }

    // gross is everything anyone wants of it (an absolute bag count), held what the bag already has, runs how many times
    // its way runs. kind is null for leaves. tier only means something for leaves
    public record Node(String name, Kind kind, int gross, int held, int runs, int level, int tier) {
        public int net() {
            return Math.max(0, gross - held);
        }
    }

    public record Fetch(String name, int target, int tier) {
    }

    public static final class Bill {
        private final Map<String, Node> _nodes;
        private final Map<String, Way> _ways;
        // fetched up front: plain mines with something still missing, lowest pickaxe first
        public final List<Node> leaves;
        // the rest of the leaves, the tasks that make their parents get them like they always did
        public final List<Node> deferred;
        // everything made, lowest level first, smelts first inside a level
        public final List<List<Node>> levels;
        public final int smelts;
        public final boolean table;
        public final boolean furnace;
        public final boolean blast;
        public final int fuelCoal;

        Bill(Map<String, Node> nodes, Map<String, Way> ways, List<Node> leaves, List<Node> deferred, List<List<Node>> levels, int smelts,
             boolean table, boolean furnace, boolean blast, int fuelCoal) {
            _nodes = nodes;
            _ways = ways;
            this.leaves = leaves;
            this.deferred = deferred;
            this.levels = levels;
            this.smelts = smelts;
            this.table = table;
            this.furnace = furnace;
            this.blast = blast;
            this.fuelCoal = fuelCoal;
        }

        public Node node(String name) {
            return _nodes.get(name);
        }

        // gross wanted of name, 0 when the bill doesn't know it
        public int need(String name) {
            Node n = _nodes.get(name);
            return n == null ? 0 : n.gross();
        }

        public int net(String name) {
            Node n = _nodes.get(name);
            return n == null ? 0 : n.net();
        }

        // how name gets made in this bill, null for leaves
        public Way way(String name) {
            return _ways.get(name);
        }

        public java.util.Collection<Node> nodes() {
            return _nodes.values();
        }

        private String _mainWood;
        private Set<String> _anyPlanks = Set.of();
        private Set<String> _anyLog = Set.of();

        // the wood that took the generic share, null when nothing was redirected
        public String mainWood() {
            return _mainWood;
        }

        // made from "any planks" (sticks, the table...). in game that slot takes the biggest planks stack in the bag, whatever
        // the bill said
        public boolean takesAnyPlanks(String name) {
            return _anyPlanks.contains(name);
        }

        // same for "any log" (the smoker)
        public boolean takesAnyLog(String name) {
            return _anyLog.contains(name);
        }
    }

    public static final String PLANKS = "planks";
    public static final String LOG = "log";

    // sticks, the table, wooden tools and chests all take "any planks", and the bill used to keep that as its own node. then the
    // generic planks counted the spruce planks the stairs were waiting on as held, and the craft grabbed whichever log stack
    // was biggest. so the job's main wood takes the generic share: the logs come home as one kind and get netted once.
    // no pooling with other woods in the bag on purpose: an any-planks target reads as met by D's own planks, so spare logs
    // of another kind never get crafted and the sticks eat D's planks anyway
    public static Bill of(Map<String, Integer> targets, Book book, World world) {
        return of(targets, book, world, null);
    }

    // same, but the generic share stays on latched (the wood an earlier bill of this job picked) while the job still wants
    // any of it. two woods a few planks apart would otherwise swap places as the bag fills and strand the old one's planks
    public static Bill of(Map<String, Integer> targets, Book book, World world, String latched) {
        Bill raw = ofRaw(targets, book, world);
        if (raw.need(PLANKS) <= 0 && raw.need(LOG) <= 0) {
            return raw;
        }
        String main = keepsLatch(raw, book, latched) ? latched : mainWood(raw, book);
        if (main == null) {
            return raw;
        }
        Map<String, String> rename = Map.of(PLANKS, main + "_planks", LOG, main + "_log");
        Map<String, Integer> renamed = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> t : targets.entrySet()) {
            renamed.merge(rename.getOrDefault(t.getKey(), t.getKey()), t.getValue(), Integer::sum);
        }
        Renamed redirected = new Renamed(book, rename, new HashSet<>(), new HashSet<>());
        Bill bill = ofRaw(renamed, redirected, world);
        bill._mainWood = main;
        bill._anyPlanks = Collections.unmodifiableSet(redirected.anyPlanks());
        bill._anyLog = Collections.unmodifiableSet(redirected.anyLog());
        return bill;
    }

    // the overworld wood the job wants the most planks of (logs if nobody wants planks), null when the job names no wood.
    // nether stems are a nether trip, so they never take the generic share
    static String mainWood(Bill raw, Book book) {
        String best = null;
        int bestPlanks = 0;
        int bestLogs = 0;
        for (String wood : new java.util.TreeSet<>(book.woods())) {
            if (book.gatherTier(wood + "_log") < 0) continue;
            int planks = raw.need(wood + "_planks");
            int logs = raw.need(wood + "_log");
            if (planks > bestPlanks || (planks == bestPlanks && logs > bestLogs)) {
                best = wood;
                bestPlanks = planks;
                bestLogs = logs;
            }
        }
        return bestPlanks > 0 || bestLogs > 0 ? best : null;
    }

    // the latch only holds while the job still names that wood itself. if the last spruce stair got cut from the job, sending
    // the sticks to spruce would be a whole extra tree for nothing. any spruce_ node counts, not just the planks: crafted
    // stairs in the bag stop asking for planks, so their gross planks go to 0 while the job very much still wants spruce.
    // "dark_oak_door" doesn't start with "oak_", so oak doesn't get dark oak's credit
    private static boolean keepsLatch(Bill raw, Book book, String latched) {
        if (latched == null || !book.woods().contains(latched) || book.gatherTier(latched + "_log") < 0) return false;
        String prefix = latched + "_";
        for (Node n : raw.nodes()) {
            if (n.gross() > 0 && n.name().startsWith(prefix)) return true;
        }
        return false;
    }

    // the book with "planks"/"log" inputs pointed at the main wood, writing down who had them. held and tiers are the real book's
    private record Renamed(Book book, Map<String, String> rename, Set<String> anyPlanks, Set<String> anyLog) implements Book {
        @Override
        public Way wayOf(String name) {
            Way w = book.wayOf(name);
            if (w == null) return null;
            Map<String, Integer> inputs = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> in : w.inputs().entrySet()) {
                if (PLANKS.equals(in.getKey())) anyPlanks.add(name);
                if (LOG.equals(in.getKey())) anyLog.add(name);
                inputs.merge(rename.getOrDefault(in.getKey(), in.getKey()), in.getValue(), Integer::sum);
            }
            return new Way(w.kind(), inputs, w.out());
        }

        @Override
        public int gatherTier(String name) {
            return book.gatherTier(name);
        }

        @Override
        public int held(String name) {
            return book.held(name);
        }

        @Override
        public List<String> woods() {
            return book.woods();
        }
    }

    private static Bill ofRaw(Map<String, Integer> targets, Book book, World world) {
        Map<String, Integer> side = new LinkedHashMap<>();
        boolean blast = false;
        Round round = null;
        for (int i = 0; i < MAX_ROUNDS; i++) {
            round = new Round(book, targets, side, blast);
            boolean blastNow = blastFor(round, world);
            int smelts = round.smelts + (blastNow ? BLAST_IRON : 0);
            boolean tableNeeded = round.anyTableCraft || blastNow;
            Map<String, Integer> nextSide = new LinkedHashMap<>();
            if (tableNeeded && !world.tableNear()) {
                nextSide.merge(TABLE, 1, Integer::sum);
            }
            if (smelts > 0 && !world.furnaceNear()) {
                nextSide.merge(FURNACE, 1, Integer::sum);
            }
            if (blastNow) {
                // the blast furnace eats a furnace on top of the one doing the first 5 ingots
                nextSide.merge(FURNACE, 1, Integer::sum);
                nextSide.merge(RAW_IRON, BLAST_IRON, Integer::sum);
                nextSide.merge(SMOOTH_STONE, BLAST_SMOOTH_STONE, Integer::sum);
            }
            int coal = fuelCoal(smelts, world.otherFuelSmelts());
            if (coal > 0) {
                nextSide.merge(COAL, coal, Integer::sum);
            }
            if (nextSide.equals(side) && blastNow == blast) {
                return round.bill(book, blast, coal, tableNeeded && !world.tableNear(), smelts > 0 && !world.furnaceNear(), smelts);
            }
            side = nextSide;
            blast = blastNow;
        }
        // didn't settle (it always does, but): the last round is still a sane bill, just maybe a lump of coal off
        return round.bill(book, blast, side.getOrDefault(COAL, 0), side.containsKey(TABLE), side.containsKey(FURNACE), round.smelts);
    }

    // the iron run makes a blast furnace when there's real smelting to do and wouldBuildBlast says so for its target
    private static boolean blastFor(Round round, World world) {
        Node iron = round.nodes.get(IRON);
        return iron != null && iron.kind() == Kind.SMELT && iron.runs() > 0 && world.buildsBlast().test(iron.gross());
    }

    // coal for the smelts the other fuel in the bag can't cover
    static int fuelCoal(int smelts, int otherFuelSmelts) {
        int uncovered = smelts - Math.max(0, otherFuelSmelts);
        if (uncovered <= 0) {
            return 0;
        }
        return (uncovered + SMELTS_PER_COAL - 1) / SMELTS_PER_COAL;
    }

    // one pass: discover the graph from the targets, push demand down it in topological order, bag netted once per node
    private static final class Round {
        final Map<String, Way> ways = new LinkedHashMap<>();
        final Set<String> cyclic = new HashSet<>();
        final Map<String, Integer> demand = new LinkedHashMap<>();
        final Map<String, Node> nodes = new LinkedHashMap<>();
        final Map<String, Integer> levels = new HashMap<>();
        final boolean blast;
        final boolean stationTable;
        final boolean stationFurnace;
        int smelts;
        boolean anyTableCraft;

        Round(Book book, Map<String, Integer> targets, Map<String, Integer> side, boolean blast) {
            this.blast = blast;
            stationTable = side.containsKey(TABLE);
            stationFurnace = side.containsKey(FURNACE);
            for (Map.Entry<String, Integer> t : targets.entrySet()) {
                if (t.getValue() > 0) demand.merge(t.getKey(), t.getValue(), Integer::sum);
            }
            for (Map.Entry<String, Integer> t : side.entrySet()) {
                if (t.getValue() > 0) demand.merge(t.getKey(), t.getValue(), Integer::sum);
            }
            for (String root : new ArrayList<>(demand.keySet())) {
                discover(book, root, new HashSet<>());
            }
            push(book);
        }

        Way way(String name) {
            return cyclic.contains(name) ? null : ways.get(name);
        }

        // a child that is already on the path closes a loop (nugget -> ingot -> nugget style). the node that would close it
        // stops being made from anything and turns into a leaf, which is the end of the loop
        private void discover(Book book, String name, Set<String> onPath) {
            if (ways.containsKey(name)) {
                return;
            }
            Way w = book.wayOf(name);
            ways.put(name, w);
            if (w == null) {
                return;
            }
            onPath.add(name);
            for (String child : w.inputs().keySet()) {
                if (onPath.contains(child)) {
                    cyclic.add(name);
                    continue;
                }
                discover(book, child, onPath);
            }
            onPath.remove(name);
        }

        // kahn's: a node is netted and expanded only once every parent has added its share
        private void push(Book book) {
            Map<String, Integer> parents = new HashMap<>();
            for (String name : ways.keySet()) {
                Way w = way(name);
                if (w == null) continue;
                for (String child : w.inputs().keySet()) {
                    parents.merge(child, 1, Integer::sum);
                }
            }
            Deque<String> ready = new ArrayDeque<>();
            for (String name : ways.keySet()) {
                if (parents.getOrDefault(name, 0) == 0) ready.add(name);
            }
            while (!ready.isEmpty()) {
                String name = ready.poll();
                Way w = way(name);
                int gross = demand.getOrDefault(name, 0);
                int held = Math.max(0, book.held(name));
                int net = Math.max(0, gross - held);
                int runs = 0;
                if (w != null && net > 0) {
                    runs = (net + Math.max(1, w.out()) - 1) / Math.max(1, w.out());
                    if (w.kind() == Kind.SMELT) smelts += runs;
                    if (w.kind() == Kind.CRAFT_TABLE) anyTableCraft = true;
                }
                if (w != null) {
                    for (Map.Entry<String, Integer> in : w.inputs().entrySet()) {
                        if (runs > 0) demand.merge(in.getKey(), runs * in.getValue(), Integer::sum);
                        if (parents.merge(in.getKey(), -1, Integer::sum) == 0) ready.add(in.getKey());
                    }
                }
                if (gross > 0) {
                    nodes.put(name, new Node(name, w == null ? null : w.kind(), gross, held, runs, level(name, new HashSet<>()),
                            w == null ? book.gatherTier(name) : -1));
                }
            }
        }

        // 0 for a leaf, else one past its highest input. with a blast furnace coming the ingots also wait for its parts, so
        // the smooth stone and the furnace are made before the iron run goes looking for them
        private int level(String name, Set<String> visiting) {
            Integer known = levels.get(name);
            if (known != null) return known;
            Way w = way(name);
            if (w == null || !visiting.add(name)) return 0;
            int max = 0;
            for (String child : w.inputs().keySet()) {
                max = Math.max(max, level(child, visiting));
            }
            if (blast && IRON.equals(name)) {
                max = Math.max(max, level(SMOOTH_STONE, visiting));
                max = Math.max(max, level(FURNACE, visiting));
            }
            // and a station we're making goes before what needs it, or the first table craft makes its own table out of order
            if (stationTable && w.kind() == Kind.CRAFT_TABLE) {
                max = Math.max(max, level(TABLE, visiting));
            }
            if (stationFurnace && w.kind() == Kind.SMELT) {
                max = Math.max(max, level(FURNACE, visiting));
            }
            visiting.remove(name);
            levels.put(name, max + 1);
            return max + 1;
        }

        Bill bill(Book book, boolean blast, int coal, boolean table, boolean furnace, int smeltCount) {
            List<Node> leaves = new ArrayList<>();
            List<Node> deferred = new ArrayList<>();
            Map<Integer, List<Node>> byLevel = new java.util.TreeMap<>();
            for (Node n : nodes.values()) {
                if (n.kind() == null) {
                    if (n.net() <= 0) continue;
                    (n.tier() >= 0 ? leaves : deferred).add(n);
                } else if (n.runs() > 0) {
                    byLevel.computeIfAbsent(n.level(), k -> new ArrayList<>()).add(n);
                }
            }
            leaves.sort(Comparator.comparingInt(Node::tier).thenComparing(Node::name));
            deferred.sort(Comparator.comparing(Node::name));
            List<List<Node>> levels = new ArrayList<>();
            for (List<Node> level : byLevel.values()) {
                level.sort(Comparator.comparingInt((Node n) -> n.kind() == Kind.SMELT ? 0 : 1).thenComparing(Node::name));
                levels.add(Collections.unmodifiableList(level));
            }
            Map<String, Way> made = new HashMap<>();
            for (String name : nodes.keySet()) {
                Way w = way(name);
                if (w != null) made.put(name, w);
            }
            return new Bill(Collections.unmodifiableMap(nodes), Collections.unmodifiableMap(made), Collections.unmodifiableList(leaves),
                    Collections.unmodifiableList(deferred),
                    Collections.unmodifiableList(levels), smeltCount, table, furnace, blast, coal);
        }
    }

    // what fits in the bag, lowest pickaxe first (the bill's leaf order). the rest is left for the recipe tasks to fetch the
    // way they always did. a leaf that only partly fits gets topped up to what fits and the list stops there
    public static List<Fetch> fitBag(List<Node> leaves, int freeSlots, ToIntFunction<String> stackSize) {
        List<Fetch> out = new ArrayList<>();
        int free = Math.max(0, freeSlots);
        for (Node leaf : leaves) {
            int stack = Math.max(1, stackSize.applyAsInt(leaf.name()));
            int heldStacks = ceilDiv(leaf.held(), stack);
            int extra = ceilDiv(leaf.gross(), stack) - heldStacks;
            if (extra <= free) {
                out.add(new Fetch(leaf.name(), leaf.gross(), leaf.tier()));
                free -= Math.max(0, extra);
                continue;
            }
            // the room left in the stacks we already have, plus whatever empty slots are left
            int room = heldStacks * stack - leaf.held() + free * stack;
            if (room > 0) {
                out.add(new Fetch(leaf.name(), leaf.held() + room, leaf.tier()));
            }
            break;
        }
        return out;
    }

    private static int ceilDiv(int a, int b) {
        return a <= 0 ? 0 : (a + b - 1) / b;
    }

    // what the bag has to dig with. "tier" is 1 wood (gold counts, it's fast enough), 2 stone, 3 iron and up. -1 means none
    // in the bag (a worn one counts as none)
    public interface ToolBag {
        int pickTier();

        int axeTier();

        int shovelTier();
    }

    // the tools to make right now, one pickaxe tier at a time: a wooden pick and a wooden axe first (three logs or so), then
    // every log in one trip, then the cobble, then the stone pick out of that cobble. the old way made the stone pick
    // straight off, which chopped its own few logs, came back, and then went out again for the real pile.
    // the axe is stone only once a stone pick is in the bag and there's still a pile of logs left. a wooden one is 59 logs,
    // when it breaks the bag check in GatherBillTask makes the next one. deferred only counts for the stripped logs, those
    // are chopped too
    public static List<String> tools(List<Node> leaves, List<Node> deferred, ToolBag bag) {
        int logs = 0;
        int shovelStuff = 0;
        for (Node leaf : leaves) {
            if (leaf.net() <= 0) continue;
            if (isLog(leaf.name())) logs += leaf.net();
            if (SHOVEL_STUFF.contains(leaf.name())) shovelStuff += leaf.net();
        }
        for (Node leaf : deferred) {
            if (leaf.net() > 0 && isStrippedLog(leaf.name())) logs += leaf.net();
        }
        List<String> out = new ArrayList<>();
        String step = nextPick(leaves, bag);
        if (step != null) out.add(step);
        int want = bag.pickTier() >= 2 ? 2 : 1;
        if (logs >= AXE_LOGS && bag.axeTier() < want) {
            out.add(want >= 2 ? "stone_axe" : "wooden_axe");
        }
        if (shovelStuff >= SHOVEL_BLOCKS && bag.shovelTier() < want) {
            out.add(want >= 2 ? "stone_shovel" : "wooden_shovel");
        }
        return out;
    }

    // the stone pick and up, made later out of what the leaves bring home. they go on the bill so their cobble, iron and
    // sticks come back with the rest instead of costing their own trip. the wooden one is made first and fetches its own
    public static List<String> laterTools(List<Node> leaves, ToolBag bag) {
        List<String> out = new ArrayList<>();
        int pick = hardestPick(leaves);
        for (int t = Math.max(2, Math.max(0, bag.pickTier()) + 1); t <= pick && t < PICKAXES.length; t++) {
            out.add(PICKAXES[t]);
        }
        return out;
    }

    // the one pickaxe tier above what the bag has, if a leaf needs better. null when the bag's pick does for everything
    public static String nextPick(List<Node> leaves, ToolBag bag) {
        int pick = hardestPick(leaves);
        int have = Math.max(0, bag.pickTier());
        if (pick <= have) return null;
        int step = Math.min(pick, have + 1);
        return step < PICKAXES.length ? PICKAXES[step] : null;
    }

    private static int hardestPick(List<Node> leaves) {
        int pick = 0;
        for (Node leaf : leaves) {
            if (leaf.net() > 0) pick = Math.max(pick, leaf.tier());
        }
        return pick;
    }

    // a tool past ToolWear's line counts as gone, the bill wants a fresh one before it snaps halfway up a tree
    public static int usableTier(int tier, int damage, int maxDamage) {
        return ToolWear.wornOut(damage, maxDamage) ? -1 : tier;
    }

    // the bag at its fullest isn't just the leaves: the logs all turn into planks in one level (a stack of logs is four of
    // planks), and the finals pile up at the end. the leaves turn into the finals though, so only the finals' room past what
    // the leaves take is extra. counting all of it would starve a batch that's sized to fill the bag with finals
    public static int reservedSlots(Bill bill, Map<String, Integer> targets, ToIntFunction<String> stackSize) {
        int finals = 0;
        for (Map.Entry<String, Integer> t : targets.entrySet()) {
            Node n = bill.node(t.getKey());
            if (n == null || n.kind() == null) continue;
            finals += newStacks(t.getValue(), n.held(), stackSize.applyAsInt(n.name()));
        }
        int leaves = 0;
        for (Node leaf : bill.leaves) {
            leaves += newStacks(leaf.gross(), leaf.held(), stackSize.applyAsInt(leaf.name()));
        }
        int growth = 0;
        // every craft straight off the leaves, added up: they mostly sit in the same level and in the bag at the same time
        for (Node n : bill.nodes()) {
            Way w = bill.way(n.name());
            if (w == null || n.runs() <= 0 || !allLeaves(bill, w)) continue;
            int out = ceilDiv(n.runs() * w.out(), Math.max(1, stackSize.applyAsInt(n.name())));
            int in = 0;
            for (Map.Entry<String, Integer> i : w.inputs().entrySet()) {
                in += ceilDiv(n.runs() * i.getValue(), Math.max(1, stackSize.applyAsInt(i.getKey())));
            }
            growth += Math.max(0, out - in);
        }
        // the planks are usually a final too, so their growth is already in finals - leaves. the bigger one is the peak
        return Math.max(Math.max(0, finals - leaves), growth);
    }

    private static int newStacks(int want, int held, int stack) {
        int s = Math.max(1, stack);
        return Math.max(0, ceilDiv(want, s) - ceilDiv(held, s));
    }

    private static boolean allLeaves(Bill bill, Way w) {
        for (String in : w.inputs().keySet()) {
            if (bill.way(in) != null) return false;
        }
        return true;
    }

    // the next bunch of things to make, lowest level first. the bill put the sticks and the table on the main wood, but the
    // craft takes the biggest planks stack in the bag, and right after the planks level a full stack of birch looks as good as
    // spruce. so the other woods' planks wait until the any-planks crafts are done (their stairs make their own planks out of
    // the logs meanwhile), and the any-log crafts (smoker) wait for those, or they'd eat the birch logs instead.
    // if that ever leaves nothing to do with things still unmade, the plain level order goes
    public static List<String> nextBatch(Bill bill, Set<String> made) {
        List<String> fallback = null;
        for (List<Node> level : bill.levels) {
            List<String> unmade = new ArrayList<>();
            List<String> todo = new ArrayList<>();
            for (Node node : level) {
                if (made.contains(node.name())) continue;
                unmade.add(node.name());
                if (!waits(bill, node.name(), made)) todo.add(node.name());
            }
            if (!todo.isEmpty()) return todo;
            if (fallback == null && !unmade.isEmpty()) fallback = unmade;
        }
        return fallback == null ? List.of() : fallback;
    }

    private static boolean waits(Bill bill, String name, Set<String> made) {
        if (bill.mainWood() == null) return false;
        if (otherPlanks(bill, name)) return anyUnmade(bill, made, n -> bill.takesAnyPlanks(n));
        if (bill.takesAnyLog(name)) return anyUnmade(bill, made, n -> otherPlanks(bill, n));
        return false;
    }

    private static boolean otherPlanks(Bill bill, String name) {
        return name.endsWith("_planks") && !name.equals(bill.mainWood() + "_planks");
    }

    private static boolean anyUnmade(Bill bill, Set<String> made, java.util.function.Predicate<String> which) {
        for (List<Node> level : bill.levels) {
            for (Node node : level) {
                if (!made.contains(node.name()) && which.test(node.name())) return true;
            }
        }
        return false;
    }

    // consumers before what they eat. the stairs go before the planks, so a short stairs craft makes its own planks and
    // the planks target gets topped up once at the end, instead of planks, stairs eat 6, planks again, forever
    public static List<String> consumersFirst(Bill bill, List<String> names) {
        List<String> out = new ArrayList<>(names);
        out.sort(Comparator.comparingInt((String n) -> {
            Node node = bill.node(n);
            return node == null ? 0 : node.level();
        }).reversed());
        return out;
    }

    public static boolean isLog(String name) {
        return LOG.equals(name) || name.endsWith("_log");
    }

    public static boolean isShovelStuff(String name) {
        return SHOVEL_STUFF.contains(name);
    }

    // the catalogue calls them spruce_stripped_logs. CollectStrippedLogTask strips standing trees, so these are chopping too
    static boolean isStrippedLog(String name) {
        return name.endsWith("stripped_logs");
    }
}
