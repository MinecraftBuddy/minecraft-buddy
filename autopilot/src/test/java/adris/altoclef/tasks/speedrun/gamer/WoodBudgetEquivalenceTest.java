package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.config.EndConfig;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.util.helpers.FuelPolicy;
import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.utils.Dimension;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

// the golden for the wood budget: a few thousand seeded bags, and every wood answer the gamer gives about each of them, once with
// the budget the gather builds and once with the iron phase's. the first file was recorded before the views were folded into one,
// and it only moves when a behaviour round says it should (the commit says which answers, and in which bags). the second file is
// the same bags asked as the IRON phase.
// WOOD_GOLDEN_WRITE=<dir> rewrites both files into <dir> instead of checking them (only ever do that from code that is known to
// be right, a rewrite from a broken change just blesses the break). WOOD_GOLDEN_FULL=<dir> dumps every answer in full, for
// diffing two versions of the code state by state
public class WoodBudgetEquivalenceTest {
    private static final int STATES = 5000;
    // the first lines are stored whole so a failure says which answer moved, the rest are one hash each
    private static final int FULL_LINES = 200;
    private static final String GATHER_FILE = "wood-budget-golden.txt";
    private static final String IRON_FILE = "wood-budget-iron-golden.txt";
    private static final String RESOURCES = "/adris/altoclef/tasks/speedrun/gamer/";

    // the item tables below need the registries up before the class even finishes loading
    static {
        ItemFixture.boot();
    }

    private static final Item[] LOGS = {Items.OAK_LOG, Items.BIRCH_LOG, Items.SPRUCE_LOG, Items.DARK_OAK_LOG, Items.CRIMSON_STEM};
    private static final Item[] PLANKS = {Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS, Items.CRIMSON_PLANKS};
    private static final Item[] MEAT = {Items.BEEF, Items.PORKCHOP, Items.MUTTON, Items.CHICKEN, Items.COD};
    private static final int[] END_BEDS = {0, 3, 8};

    // a loop in the wood arithmetic should fail this, not hang the build
    @Rule
    public Timeout timeout = Timeout.seconds(120);

    @After
    public void clearFuel() {
        FuelPolicy.clear();
    }

    @Test
    public void everyWoodAnswerIsStillTheSameAsItWas() throws IOException {
        List<String> lines = lines(GamerPhase.GATHER);
        int shortOfBudget = 0;
        int spareWood = 0;
        int smokerOwed = 0;
        int cooking = 0;
        for (String line : lines) {
            // a tally of what the generator reaches, so a generator that quietly stops varying something fails loudly
            if (line.startsWith("need=0;")) {
                spareWood++;
            } else {
                shortOfBudget++;
            }
            if (line.contains(";cook=cook_in_smoker")) {
                smokerOwed++;
            }
            if (line.contains(";cook=cook_in_")) {
                cooking++;
            }
        }
        assertTrue("generator never ran short of wood: " + shortOfBudget, shortOfBudget > 500);
        assertTrue("generator never had the budget covered: " + spareWood, spareWood > 500);
        assertTrue("generator never asked for a smoker cook: " + smokerOwed, smokerOwed > 100);
        assertTrue("generator never asked for a cook: " + cooking, cooking > 300);
        check(lines, GATHER_FILE, "gather-full.txt");
    }

    // the same bags asked as the iron phase: the claims the IRON budget makes (WoodBudget) show up here and nowhere in the file above
    @Test
    public void everyIronPhaseWoodAnswerIsStillTheSameAsItWas() throws IOException {
        check(lines(GamerPhase.IRON), IRON_FILE, "iron-full.txt");
    }

    // what the bag of each state looked like, for the full dump (the diff of two versions is read against it)
    private static List<String> notes = List.of();

    private static List<String> lines(GamerPhase phase) {
        List<String> lines = new ArrayList<>();
        List<String> bags = new ArrayList<>();
        Random r = new Random(0x600D5EEDL);
        for (int i = 0; i < STATES; i++) {
            State s = State.generate(r);
            lines.add(answers(s, phase));
            bags.add(note(s));
        }
        notes = bags;
        return lines;
    }

    private static String note(State s) {
        FakeFacts f = s.f;
        return "logs=" + f.count(adris.altoclef.util.helpers.ItemHelper.LOG) + ",planks=" + f.count(adris.altoclef.util.helpers.ItemHelper.PLANKS)
                + ",raw=" + CookGate.raw(f) + ",pile=" + CookGate.pile(f) + ",coal=" + f.count(Items.COAL, Items.CHARCOAL)
                + ",cookStation=" + f.cookStation + ",suspended=" + f.cookSuspended + ",smoker=" + (f.count(Items.SMOKER) > 0 || f.smokerPlaced)
                + ",furnace=" + (f.count(Items.FURNACE) > 0 || f.furnacePlaced) + ",near=" + f.nearSurface + ",dim=" + f.dimension
                + ",smeltsAhead=" + (Math.max(0, KitPlanner.ingotsNeeded(f, s.cfg) - f.count(Items.IRON_INGOT)) + CookGate.raw(f))
                + ",jobs=" + f.jobs.size() + ",beds=" + s.endBeds + ",burn=" + f.unburnable.size();
    }

    private static void check(List<String> lines, String file, String fullFile) throws IOException {
        String full = System.getenv("WOOD_GOLDEN_FULL");
        if (full != null && !full.isEmpty()) {
            List<String> dump = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                dump.add(lines.get(i) + "#" + notes.get(i));
            }
            Files.write(Path.of(full, fullFile), dump, StandardCharsets.UTF_8);
        }
        String write = System.getenv("WOOD_GOLDEN_WRITE");
        if (write != null && !write.isEmpty()) {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                out.add(i < FULL_LINES ? hash(lines.get(i)) + "|" + lines.get(i) : hash(lines.get(i)));
            }
            Files.write(Path.of(write, file), out, StandardCharsets.UTF_8);
            return;
        }
        List<String> golden = readGolden(file);
        assertEquals("states in the golden", STATES, golden.size());
        int bad = 0;
        StringBuilder why = new StringBuilder();
        for (int i = 0; i < STATES; i++) {
            String[] parts = golden.get(i).split("\\|", 2);
            String now = hash(lines.get(i));
            if (parts[0].equals(now)) {
                continue;
            }
            bad++;
            if (why.length() < 6000) {
                why.append("state ").append(i).append(" moved\n");
                if (parts.length == 2) {
                    why.append("  was ").append(parts[1]).append("\n  now ").append(lines.get(i)).append("\n");
                }
            }
        }
        assertEquals(file + ": " + bad + " of " + STATES + " states moved\n" + why, 0, bad);
    }

    private static List<String> readGolden(String file) throws IOException {
        try (InputStream in = WoodBudgetEquivalenceTest.class.getResourceAsStream(RESOURCES + file)) {
            assertNotNull("missing " + file, in);
            return List.of(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R"));
        }
    }

    private static String hash(String line) {
        try {
            byte[] sum = MessageDigest.getInstance("SHA-256").digest(line.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                // two hex digits a byte, a colon between: a run of digits or letters in a hash could spell what the
                // repository's privacy check refuses (a number, a name), and the line number is the line's place
                if (i > 0) sb.append(':');
                sb.append(String.format("%02x", sum[i]));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- every answer for one bag

    private static String answers(State s, GamerPhase phase) {
        FakeFacts f = s.f;
        OverworldConfig cfg = s.cfg;
        int beds = s.endBeds;
        EndConfig end = new EndConfig();
        end.beds = beds;
        WoodBudget w = WoodBudget.of(f, cfg, beds, phase);
        FoodPlan food = FoodPlan.of(f, cfg, end, true, s.refilling, w);
        FoodPlan atMinimum = food.atMinimum();
        // the planners' own plan, with no trip under way
        FoodPlan calm = FoodPlan.of(f, cfg, end, true, false, w);
        StringBuilder sb = new StringBuilder();

        int need = w.gatherLogsOwed();
        sb.append("need=").append(need).append(';');
        sb.append("ironNeed=").append(w.ironLogsOwed()).append(';');
        sb.append("first=").append(w.firstBatchLogs()).append(';');
        WoodReserve.Keep keep = w.keep();
        sb.append("keep=").append(keep.logs()).append('/').append(keep.planks()).append(';');
        sb.append("fuel=").append(fuelAfterApply(w)).append(';');
        sb.append("cookFuel=").append(w.cookFuelLogs(food, 0)).append('/')
                .append(w.cookFuelLogs(food, need)).append('/')
                .append(w.cookFuelLogs(food, 5)).append('/')
                .append(w.cookFuelLogs(calm, need)).append(';');
        sb.append("food=").append(food.held()).append('/').append(food.wantsRefill()).append(';');

        List<KitNeed> gather = KitPlanner.gather(f, cfg, beds, food, w);
        List<KitNeed> plan = KitPlanner.plan(f, cfg, beds, food, w);
        sb.append("gatherLogs=").append(logs(gather)).append(';');
        sb.append("planLogs=").append(logs(plan)).append(';');
        sb.append("gather=").append(names(gather)).append(';');
        sb.append("plan=").append(names(plan)).append(';');
        sb.append("gather3=").append(names(KitPlanner.gather(f, cfg, beds, calm, w))).append(';');
        sb.append("plan3=").append(names(KitPlanner.plan(f, cfg, beds, calm, w))).append(';');
        sb.append("gatherDone=").append(names(KitPlanner.gather(f, cfg, beds, atMinimum, w))).append(';');
        sb.append("planDone=").append(names(KitPlanner.plan(f, cfg, beds, atMinimum, w))).append(';');

        KitNeed cook = CookGate.need(f, cfg, beds, w);
        sb.append("cook=").append(cook == null ? "-" : cook.catalogueName()).append(';');
        for (boolean mayCraft : new boolean[]{false, true}) {
            CookGate.Station station = CookGate.station(f, cfg, mayCraft, w);
            sb.append("station").append(mayCraft ? "Y=" : "N=").append(station).append(';');
            sb.append("toMake").append(mayCraft ? "Y=" : "N=").append(CookGate.smokerToMake(f, station)).append(';');
        }
        sb.append("smokerLogs=").append(CookGate.smokerLogs(f)).append(';');
        sb.append("feasible=").append(CookGate.cookFeasible(f, cfg, beds, w)).append(';');
        sb.append("reusable=").append(CookGate.reusable(f, cfg, beds, true, w)).append('/').append(CookGate.reusable(f, cfg, beds, false, w))
                .append(';');
        sb.append("woodSmelts=").append(w.burnableWoodSmelts(0));
        for (int spoken : new int[]{0, 4, 5}) {
            sb.append('/').append(w.burnableWoodSmelts(spoken));
        }
        sb.append(';');
        sb.append("fuelSmelts=").append(CookGate.fuelSmelts(f, w, 0));
        for (int spoken : new int[]{0, 4, 5}) {
            sb.append('/').append(CookGate.fuelSmelts(f, w, spoken));
        }
        sb.append(';');

        sb.append("stock=");
        for (int wanted : new int[]{4, 8, 16}) {
            sb.append(w.stockUpLogs(wanted, true)).append('/').append(w.stockUpLogs(wanted, false)).append(',');
        }
        sb.append(';');
        for (boolean near : new boolean[]{false, true}) {
            SmeltFiller.Schedule schedule = SmeltFiller.schedule(f, cfg, beds, near, food, w);
            sb.append("sched").append(near ? "Y=" : "N=").append(names(schedule.runnable())).append('|').append(names(schedule.blocked()))
                    .append('|').append(names(schedule.stockUps())).append(';');
        }
        sb.append("gatherRunnable=").append(names(SmeltFiller.gatherRunnable(f, cfg, beds, food, w))).append(';');

        StubContext ctx = new StubContext() {
            @Override
            public GamerFacts facts() {
                return f;
            }
        };
        ctx.cfg.overworld = cfg;
        ctx.cfg.end.beds = beds;
        ctx.state.phase = phase;
        sb.append("coal=").append(ResourceDetour.coalNeed(ctx)).append(';');
        return sb.toString();
    }

    private static String names(List<KitNeed> needs) {
        StringBuilder sb = new StringBuilder("[");
        for (KitNeed n : needs) {
            sb.append(n.catalogueName()).append(':').append(n.count()).append(',');
        }
        return sb.append(']').toString();
    }

    private static String logs(List<KitNeed> needs) {
        for (KitNeed n : needs) {
            if (n.catalogueName().equals("log")) {
                return String.valueOf(n.count());
            }
        }
        return "-";
    }

    // what the furnaces are told to keep, read back the only way FuelPolicy lets us: how much of a big stack may burn
    private static String fuelAfterApply(WoodBudget w) {
        FuelPolicy.clear();
        WoodReserve.apply(w);
        List<ItemStack> stacks = List.of(new ItemStack(Items.OAK_LOG, 1000), new ItemStack(Items.OAK_PLANKS, 1000));
        int[] usable = FuelPolicy.usable(stacks);
        String out = (1000 - usable[0]) + "/" + (1000 - usable[1]);
        FuelPolicy.clear();
        return out;
    }

    // ---- one random bag

    private record State(FakeFacts f, OverworldConfig cfg, int endBeds, boolean refilling) {
        static State generate(Random r) {
            FakeFacts f = new FakeFacts();
            // a third of the bags are wood rich with the tools made, that is where the spare wood and the reserve live
            boolean rich = r.nextInt(3) == 0;
            put(f, LOGS, r, rich ? 8 + r.nextInt(13) : r.nextInt(21), r.nextInt(3) + 1);
            put(f, PLANKS, r, r.nextInt(3) == 0 ? 0 : r.nextInt(41), r.nextInt(2) + 1);
            f.give(Items.STICK, r.nextInt(3) == 0 ? r.nextInt(30) : r.nextInt(8));

            tool(f, r, Items.WOODEN_PICKAXE, rich ? 70 : 25, 1);
            tool(f, r, Items.WOODEN_AXE, rich ? 80 : 25, 1);
            tool(f, r, Items.STONE_PICKAXE, rich ? 80 : 20, 1 + r.nextInt(2));
            tool(f, r, Items.STONE_AXE, rich ? 70 : 15, 1);
            tool(f, r, Items.STONE_SWORD, 8, 1);
            tool(f, r, Items.WOODEN_SHOVEL, 4, 1);
            tool(f, r, Items.WOODEN_HOE, 4, 1);
            tool(f, r, Items.IRON_PICKAXE, rich ? 40 : 12, 1);
            tool(f, r, Items.IRON_AXE, rich ? 30 : 8, 1);
            tool(f, r, Items.IRON_SWORD, 5, 1);
            tool(f, r, Items.DIAMOND_PICKAXE, 3, 1);
            tool(f, r, Items.CRAFTING_TABLE, rich ? 50 : 25, 1);
            tool(f, r, Items.FURNACE, rich ? 50 : 25, 1);
            tool(f, r, Items.SMOKER, 10, 1);
            tool(f, r, Items.BLAST_FURNACE, 2, 1);
            tool(f, r, Items.SHIELD, rich ? 60 : 15, 1);
            tool(f, r, Items.LADDER, rich ? 70 : 25, 1 + r.nextInt(3));
            tool(f, r, Items.BUCKET, rich ? 70 : 25, 1 + r.nextInt(2));
            tool(f, r, Items.SHEARS, rich ? 60 : 20, 1);
            tool(f, r, Items.FLINT_AND_STEEL, rich ? 60 : 20, 1);
            tool(f, r, Items.IRON_HELMET, 10, 1);
            tool(f, r, Items.IRON_CHESTPLATE, 10, 1);
            if (r.nextInt(12) == 0) {
                f.spent.put(Items.WOODEN_PICKAXE, 1);
            }
            if (r.nextInt(12) == 0) {
                f.spent.put(Items.STONE_PICKAXE, 1);
            }
            int bedCount = r.nextInt(3) == 0 ? r.nextInt(9) : 0;
            f.give(Items.BED.white(), bedCount);
            f.give(Items.WOOL.white(), r.nextInt(3) == 0 ? r.nextInt(25) : 0);

            f.give(Items.COBBLESTONE, r.nextInt(3) == 0 ? 0 : r.nextInt(31));
            f.give(Items.COAL, r.nextInt(5));
            f.give(Items.CHARCOAL, r.nextInt(6) == 0 ? 1 + r.nextInt(2) : 0);
            f.give(Items.IRON_INGOT, r.nextInt(3) == 0 ? r.nextInt(13) : 0);
            f.give(Items.RAW_IRON, r.nextInt(4) == 0 ? 1 + r.nextInt(8) : 0);
            f.buildBlocks = r.nextInt(50);

            // raw meat: none, a little or a pile, one kind or two
            int meat = r.nextInt(10);
            if (meat >= 4) {
                f.give(MEAT[r.nextInt(MEAT.length)], meat == 9 ? 6 + r.nextInt(4) : 1 + r.nextInt(5));
                if (r.nextInt(4) == 0) {
                    f.give(MEAT[r.nextInt(MEAT.length)], 1 + r.nextInt(4));
                }
            }
            f.foodUnits = 20 + r.nextInt(131);
            f.tablePlaced = r.nextInt(5) == 0;
            f.furnacePlaced = r.nextInt(5) == 0;
            f.smokerPlaced = r.nextInt(7) == 0;
            f.cookSuspended = r.nextInt(10) == 0;
            int station = r.nextInt(10);
            f.cookStation = station == 0 ? "smoker" : station == 1 ? "furnace" : null;
            f.nearSurface = r.nextBoolean();
            f.bagFull = r.nextInt(8) == 0;
            f.earlyLoad = r.nextInt(20) == 0;
            f.earlyIronPick = r.nextInt(6) != 0;
            int dim = r.nextInt(25);
            f.dimension = dim == 0 ? Dimension.END : dim < 4 ? Dimension.NETHER : Dimension.OVERWORLD;
            f.gameTime = 1000 + r.nextInt(5000);
            int job = r.nextInt(12);
            if (job == 0) {
                f.cooking("iron_ingot", 1 + r.nextInt(12), 30 + r.nextInt(60));
            } else if (job == 1) {
                f.cookingFood("cooked_beef", 1 + r.nextInt(8), 8, 20 + r.nextInt(40));
            } else if (job == 2) {
                f.cookingFood("cooked_beef", 1 + r.nextInt(8), 8, 20 + r.nextInt(40));
                f.jobs.get(0).stranded = true;
            }
            int burn = r.nextInt(20);
            if (burn == 0) {
                f.coalOnly();
            } else if (burn == 1) {
                f.unburnable.addAll(List.of(PLANKS));
            } else if (burn == 2) {
                f.unburnable.add(Items.OAK_LOG);
            } else if (burn == 3) {
                f.unburnable.add(Items.CRIMSON_STEM);
                f.unburnable.add(Items.CRIMSON_PLANKS);
            }

            OverworldConfig cfg = new OverworldConfig();
            cfg.netherSpareShields = 0;
            cfg.netherSpareSwords = 0;
            int variant = r.nextInt(10);
            if (variant == 0) {
                // no iron owed: the furnace is the meat's
                cfg.ironKit.clear();
                cfg.armorPlan = OverworldConfig.ArmorPlan.NONE;
            } else if (variant == 1) {
                cfg.cookFuelLogs = 0;
            } else if (variant == 2) {
                cfg.cookFuelLogs = 5;
            } else if (variant == 3) {
                cfg.armorPlan = OverworldConfig.ArmorPlan.CHEST_HELMET;
            }
            return new State(f, cfg, END_BEDS[r.nextInt(END_BEDS.length)], r.nextInt(4) == 0);
        }

        // `total` of a group, spread over up to `kinds` of its species
        private static void put(FakeFacts f, Item[] group, Random r, int total, int kinds) {
            int left = total;
            for (int k = 0; k < kinds && left > 0; k++) {
                int n = k == kinds - 1 ? left : r.nextInt(left + 1);
                f.give(group[r.nextInt(group.length)], n);
                left -= n;
            }
        }

        private static void tool(FakeFacts f, Random r, Item item, int percent, int count) {
            if (r.nextInt(100) < percent) {
                f.give(item, count);
            }
        }
    }
}
