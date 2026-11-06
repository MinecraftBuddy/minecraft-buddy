package adris.altoclef.takealong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import adris.altoclef.takealong.TakeAlongRules.Choice;
import adris.altoclef.takealong.TakeAlongRules.Cluster;
import adris.altoclef.takealong.TakeAlongRules.Drop;
import adris.altoclef.takealong.TakeAlongRules.Part;
import adris.altoclef.takealong.TakeAlongRules.Prey;
import adris.altoclef.takealong.TakeAlongRules.Seen;
import adris.altoclef.takealong.TakeAlongRules.Spot;
import adris.altoclef.takealong.TakeAlongRules.State;
import adris.altoclef.takealong.TakeAlongRules.Wish;
import adris.altoclef.tasks.speedrun.gamer.DetourRules.Offset;
import adris.altoclef.tasks.speedrun.gamer.config.TakeAlongConfig;
import adris.altoclef.util.helpers.RecipeBill;
import adris.altoclef.util.helpers.WalkCost;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

// when a cluster in sight is worth taking along, with the plan's own worked examples (stone ore hardness 3, deepslate 4.5,
// gravel 0.6; a stone pick 4, an iron pick 6, a hand 1). the world half (TakeAlongChain) is only compile checked
public class TakeAlongRulesTest {
    private static final TakeAlongConfig CFG = new TakeAlongConfig();
    private static final double EPS = 1e-6;

    // overworld, by day, unhurt, an empty bag's worth of room, nothing holding detours back
    private static State calm() {
        return new State(true, 20, false, false, false, null, 20, 0);
    }

    private static Wish now(String raw, int held, int need, double value) {
        return new Wish(raw, held, need, need, value);
    }

    private static Wish later(String raw, int held, int need, double value) {
        return new Wish(raw, held, 0, need, value);
    }

    // n blocks side by side at dx, dy, starting at dz 0
    private static List<Spot> row(int n, int dx, int dy, double hardness) {
        List<Spot> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new Spot(dx, dy, i, hardness));
        }
        return out;
    }

    private static Cluster one(String raw, double speed, List<Spot> spots) {
        return new Cluster(List.of(new Part(raw, TakeAlongRules.YIELD.get(raw), true, speed, false, spots)));
    }

    private static Choice choose(Cluster c, Wish w) {
        return TakeAlongRules.choose(List.of(c), Map.of(w.raw(), w), calm(), CFG);
    }

    // ---- the reserve and the tiers

    @Test
    public void theReserveIsHalfTheNeedRoundedUp() {
        assertEquals(0, TakeAlongRules.reserve(0, CFG));
        assertEquals(1, TakeAlongRules.reserve(1, CFG));
        assertEquals(2, TakeAlongRules.reserve(3, CFG));
        assertEquals(5, TakeAlongRules.reserve(10, CFG));
    }

    @Test
    public void tenIronNeededMakeTheEleventhHalfAndTheSixteenthNothing() {
        double[] want = {0.5, 0.4, 0.3, 0.2, 0.1, 0};
        for (int i = 0; i < want.length; i++) {
            assertEquals("item " + (11 + i), want[i], TakeAlongRules.urgency(11 + i, 10, 10, CFG), EPS);
        }
    }

    @Test
    public void nowComesBeforeLaterAndLaterBeforeTheReserve() {
        assertEquals(1.0, TakeAlongRules.urgency(4, 4, 6, CFG), EPS);
        assertEquals(0.6, TakeAlongRules.urgency(5, 4, 6, CFG), EPS);
        assertEquals(0.6, TakeAlongRules.urgency(6, 4, 6, CFG), EPS);
        // a need of 6 has a reserve of 3: 0.5, then 1/3 less each
        assertEquals(0.5, TakeAlongRules.urgency(7, 4, 6, CFG), EPS);
        assertEquals(0.5 * 2 / 3, TakeAlongRules.urgency(8, 4, 6, CFG), EPS);
        assertEquals(0, TakeAlongRules.urgency(10, 4, 6, CFG), EPS);
    }

    @Test
    public void nothingNeededIsNothingInReserve() {
        assertEquals(0, TakeAlongRules.urgency(1, 0, 0, CFG), EPS);
        assertEquals(0, TakeAlongRules.takeable(later("coal", 0, 0, 21), CFG));
    }

    @Test
    public void theWorthCountsEachItemAtItsOwnPlaceInTheBag() {
        // 9 held of a need of 10: the 10th at 0.6, the 11th and 12th from the reserve (0.5, 0.4)
        Wish w = later("raw_iron", 9, 10, 31);
        assertEquals(31 * (0.6 + 0.5 + 0.4), TakeAlongRules.worth(w, 3, CFG), EPS);
        // a fraction counts its share of the next item
        assertEquals(0.5 * 31 * 0.6, TakeAlongRules.worth(w, 0.5, CFG), EPS);
    }

    // ---- the plan's worked examples

    @Test
    public void threeIronOreOnACaveWallWithArmourLacking() {
        Choice c = choose(one("raw_iron", 4, row(3, 8, 0, 3)), now("raw_iron", 0, 15, 31));
        assertNotNull(c);
        assertEquals(93, c.value(), EPS);
        // walk 2 x 8 / 4, three blocks at 1.125, the drops and the switch
        assertEquals(4 + 3 * 1.125 + 4, c.cost(), 0.05);
        assertEquals(3, c.blocks());
        assertEquals(0, c.mineCap());
        assertEquals(3, c.takes().get(0).target());
    }

    @Test
    public void fourCoalFourUpIsJustWorthItSixUpIsNot() {
        // 15 across and 4 up is a WalkCost of 31
        Wish coal = later("coal", 0, 6, 21);
        Choice four = choose(one("coal", 4, row(4, 15, 4, 3)), coal);
        assertNotNull(four);
        assertEquals(4 * 21 * 0.6, four.value(), EPS);
        assertEquals(15.5 + 4 * 1.125 + 4, four.cost(), EPS);
        assertTrue(TakeAlongRules.worthIt(four, CFG));
        // 6 up is past the reach ceiling
        assertNull(choose(one("coal", 4, row(4, 15, 6, 3)), coal));
        // and with the ceiling out of the way still not worth it, 50 against 56
        TakeAlongConfig far = new TakeAlongConfig();
        far.reachCeiling = 48;
        Choice six = TakeAlongRules.evaluate(one("coal", 4, row(4, 15, 6, 3)), Map.of("coal", coal), calm(), far);
        assertNotNull(six);
        assertFalse(TakeAlongRules.worthIt(six, far));
        assertEquals(2 * six.cost(), 56, 0.3);
    }

    @Test
    public void theSameCoalWithTwentyInTheBagIsNothing() {
        assertNull(choose(one("coal", 4, row(4, 15, 4, 3)), later("coal", 20, 6, 21)));
    }

    @Test
    public void oneDryDiamondOreForTheSword() {
        // the one with lava beside never reaches the rules (the world half drops it), the dry one is 10 off at deepslate's 4.5
        Choice c = choose(one("diamond", 6, List.of(new Spot(10, 0, 0, 4.5))), now("diamond", 0, 2, 256));
        assertNotNull(c);
        assertEquals(256, c.value(), EPS);
        assertEquals(5 + 1.125 + 4, c.cost(), EPS);
    }

    @Test
    public void twoGoldOreForTheGoldenHelmet() {
        Choice c = choose(one("raw_gold", 6, row(2, 10, 0, 4.5)), now("raw_gold", 0, 5, 86));
        assertNotNull(c);
        assertEquals(172, c.value(), EPS);
        assertTrue(c.cost() < 12);
    }

    @Test
    public void tenGravelForTheFlint() {
        Choice c = choose(one("flint", 1, row(10, 6, 0, 0.6)), later("flint", 0, 1, 106));
        assertNotNull(c);
        // ten gravel are one flint on average, the reserve's second flint would take ten more
        assertEquals(106 * 0.6, c.value(), EPS);
        assertEquals(3 + 9 + 4, c.cost(), EPS);
        assertEquals(10, c.blocks());
        assertEquals(10, c.mineCap());
        assertEquals(1, c.takes().get(0).target());
    }

    @Test
    public void aBigBankCountsTheReserveFlintToo() {
        Choice c = choose(one("flint", 1, row(30, 3, 0, 0.6)), later("flint", 0, 1, 106));
        assertNotNull(c);
        assertEquals(106 * (0.6 + 0.5), c.value(), EPS);
        assertEquals(20, c.blocks());
        assertEquals(2, c.takes().get(0).target());
    }

    // ---- the never list

    @Test
    public void noToolNoDetour() {
        Cluster c = new Cluster(List.of(new Part("diamond", 1, false, 0, false, row(2, 4, 0, 4.5))));
        assertNull(choose(c, now("diamond", 0, 2, 256)));
    }

    @Test
    public void aFullBagOnlyTakesWhatStacks() {
        Wish iron = now("raw_iron", 0, 15, 31);
        State full = new State(true, 20, false, false, false, null, 3, 0);
        Cluster loose = new Cluster(List.of(new Part("raw_iron", 1, true, 4, false, row(3, 4, 0, 3))));
        Cluster stacking = new Cluster(List.of(new Part("raw_iron", 1, true, 4, true, row(3, 4, 0, 3))));
        assertNull(TakeAlongRules.choose(List.of(loose), Map.of("raw_iron", iron), full, CFG));
        assertNotNull(TakeAlongRules.choose(List.of(stacking), Map.of("raw_iron", iron), full, CFG));
    }

    @Test
    public void everyNeverRuleHoldsTheDetourBack() {
        State[] never = {
                new State(false, 20, false, false, false, null, 20, 0),
                new State(true, 11, false, false, false, null, 20, 0),
                new State(true, 20, true, false, false, null, 20, 0),
                new State(true, 20, false, true, false, null, 20, 0),
                new State(true, 20, false, false, true, null, 20, 0),
                new State(true, 20, false, false, false, "a portal under way", 20, 0),
                new State(true, 20, false, false, false, null, 20, 200)
        };
        Cluster c = one("raw_iron", 4, row(3, 4, 0, 3));
        Map<String, Wish> wishes = Map.of("raw_iron", now("raw_iron", 0, 15, 31));
        assertNotNull(TakeAlongRules.choose(List.of(c), wishes, calm(), CFG));
        for (State s : never) {
            assertNotNull(TakeAlongRules.blocked(s, CFG));
            assertNull(TakeAlongRules.choose(List.of(c), wishes, s, CFG));
        }
        assertEquals("a portal under way", TakeAlongRules.blocked(never[5], CFG));
        // the share holds a new detour back, it does not end one under way
        assertNull(TakeAlongRules.stopping(never[6], CFG));
        assertNotNull(TakeAlongRules.stopping(never[4], CFG));
    }

    // ---- several clusters, one detour

    @Test
    public void ofTwoWorthItTheLargerMarginGoesFirst() {
        Map<String, Wish> wishes = Map.of("raw_iron", now("raw_iron", 0, 15, 31), "diamond", now("diamond", 0, 2, 256));
        Cluster iron = one("raw_iron", 6, row(3, 3, 0, 3));
        Cluster diamonds = one("diamond", 6, row(2, 12, 0, 4.5));
        Choice c = TakeAlongRules.choose(List.of(iron, diamonds), wishes, calm(), CFG);
        assertNotNull(c);
        assertEquals("diamond", c.items());
    }

    @Test
    public void aVeinOfTwoOresIsOneDetourWorthBoth() {
        Map<String, Wish> wishes = Map.of("raw_iron", now("raw_iron", 0, 15, 31), "coal", later("coal", 0, 6, 21));
        Cluster mixed = new Cluster(List.of(new Part("raw_iron", 1, true, 4, false, row(2, 6, 0, 3)),
                new Part("coal", 1, true, 4, false, row(2, 7, 0, 3))));
        Choice c = TakeAlongRules.choose(List.of(mixed), wishes, calm(), CFG);
        assertNotNull(c);
        assertEquals(2 * 31 + 2 * 21 * 0.6, c.value(), EPS);
        assertEquals("raw_iron+coal", c.items());
        assertEquals(4, c.blocks());
    }

    @Test
    public void aClusterTakesOnlyTheBlocksStillWorthSomething() {
        // one raw iron short of a need of 1 and its reserve of 1: two of the five ores
        Choice c = choose(one("raw_iron", 4, row(5, 3, 0, 3)), now("raw_iron", 0, 1, 31));
        assertNotNull(c);
        assertEquals(2, c.blocks());
        assertEquals(31 * (1 + 0.5), c.value(), EPS);
    }

    @Test
    public void blocksCloseTogetherAreOneCluster() {
        List<Seen> seen = List.of(new Seen("raw_iron", new Spot(3, 0, 0, 3)), new Seen("coal", new Spot(5, 1, 0, 3)),
                new Seen("raw_iron", new Spot(12, 0, 0, 3)), new Seen("raw_iron", new Spot(4, 0, 1, 3)));
        List<List<Seen>> groups = TakeAlongRules.group(seen, 2);
        assertEquals(2, groups.size());
        assertEquals(3, groups.get(0).size());
        assertEquals(1, groups.get(1).size());
    }

    @Test
    public void theTableStaysInReachAndInSight() {
        List<Offset> cells = TakeAlongRules.offsets(32, 16);
        assertFalse(cells.isEmpty());
        double last = 0;
        for (Offset o : cells) {
            double walk = WalkCost.estimate(o.dx(), o.dy(), o.dz());
            assertTrue(walk <= 32);
            assertTrue(o.dx() * o.dx() + o.dy() * o.dy() + o.dz() * o.dz() <= 18 * 18);
            assertTrue(walk >= last);
            last = walk;
        }
    }

    // ---- the wish list from the bills

    // iron ingots smelted from raw iron, a chestplate of 8 and a bucket of 3, every station at hand
    private static final RecipeBill.Book IRON = new RecipeBill.Book() {
        @Override
        public RecipeBill.Way wayOf(String name) {
            return switch (name) {
                case "iron_chestplate" -> new RecipeBill.Way(RecipeBill.Kind.CRAFT_TABLE, Map.of("iron_ingot", 8), 1);
                case "bucket" -> new RecipeBill.Way(RecipeBill.Kind.CRAFT_TABLE, Map.of("iron_ingot", 3), 1);
                case "iron_ingot" -> new RecipeBill.Way(RecipeBill.Kind.SMELT, Map.of("raw_iron", 1), 1);
                default -> null;
            };
        }

        @Override
        public int gatherTier(String name) {
            return "raw_iron".equals(name) ? 2 : "coal".equals(name) ? 1 : -1;
        }

        @Override
        public int held(String name) {
            return "raw_iron".equals(name) ? 2 : 0;
        }
    };

    @Test
    public void theWishesComeFromTheBillsTierByTier() {
        RecipeBill.World near = new RecipeBill.World(true, true, 0, n -> false);
        RecipeBill.Bill nowBill = RecipeBill.of(Map.of("iron_chestplate", 1), IRON, near);
        RecipeBill.Bill allBill = RecipeBill.of(Map.of("iron_chestplate", 1, "bucket", 1), IRON, near);
        Map<String, Wish> wishes = TakeAlongRules.wishes(nowBill, allBill,
                TakeAlongRules.values(Map.of("minecraft:raw_iron", 31.0, "coal", 21.0, "flint", 106.0)));
        Wish iron = wishes.get("raw_iron");
        assertNotNull(iron);
        assertEquals(2, iron.held());
        assertEquals(8, iron.needNow());
        assertEquals(11, iron.needAll());
        assertEquals(31, iron.value(), EPS);
        // 8 smelts is one coal, 11 are two
        Wish coal = wishes.get("coal");
        assertNotNull(coal);
        assertEquals(1, coal.needNow());
        assertEquals(2, coal.needAll());
        // nothing needs flint, so it is no wish at all
        assertNull(wishes.get("flint"));
    }

    @Test
    public void everyLogIsOneRawItem() {
        assertEquals("log", TakeAlongRules.raw("oak_log"));
        assertEquals("log", TakeAlongRules.raw("minecraft:birch_log"));
        assertEquals("raw_iron", TakeAlongRules.raw("minecraft:raw_iron"));
        Map<String, Double> values = TakeAlongRules.values(Map.of("oak_log", 9.0, "birch_log", 12.0));
        assertEquals(12.0, values.get("log"), EPS);
    }

    // ---- drops on the floor

    private static final Map<String, Double> PRICES = TakeAlongRules.values(Map.of("raw_iron", 31.0, "coal", 21.0, "flint", 106.0));

    @Test
    public void aMadeItemIsWorthItsInputs() {
        RecipeBill.World near = new RecipeBill.World(true, true, 0, n -> false);
        RecipeBill.Bill bill = RecipeBill.of(Map.of("iron_chestplate", 1, "bucket", 1), IRON, near);
        Map<String, Double> unit = TakeAlongRules.unitValues(bill, PRICES);
        // a smelt is the raw iron and an eighth of a coal
        double ingot = 31 + 21.0 / 8;
        assertEquals(ingot, unit.get("iron_ingot"), EPS);
        assertEquals(8 * ingot, unit.get("iron_chestplate"), EPS);
        assertEquals(31, unit.get("raw_iron"), EPS);
    }

    @Test
    public void theDropWishesHoldTheMadeItems() {
        RecipeBill.World near = new RecipeBill.World(true, true, 0, n -> false);
        RecipeBill.Bill nowBill = RecipeBill.of(Map.of("iron_chestplate", 1), IRON, near);
        RecipeBill.Bill allBill = RecipeBill.of(Map.of("iron_chestplate", 1, "bucket", 1), IRON, near);
        Map<String, Wish> wishes = TakeAlongRules.dropWishes(nowBill, allBill, PRICES);
        Wish ingot = wishes.get("iron_ingot");
        assertNotNull(ingot);
        assertEquals(8, ingot.needNow());
        assertEquals(11, ingot.needAll());
        assertNotNull(wishes.get("iron_chestplate"));
        // the raw item's drop wish is its block wish
        assertEquals(TakeAlongRules.wishes(nowBill, allBill, PRICES).get("raw_iron"), wishes.get("raw_iron"));
        assertNull(wishes.get("flint"));
    }

    @Test
    public void aStackOfIronOnTheFloorIsTaken() {
        // three raw iron 6 blocks off, armour lacking: 93 s against 3 + 1 + 3 = 7 s
        List<Drop> drops = List.of(new Drop("raw_iron", 3, new Spot(6, 0, 0, 0), false));
        Choice c = TakeAlongRules.chooseDrops(TakeAlongRules.groupDrops(drops, CFG.clusterGap), Map.of("raw_iron", now("raw_iron", 0, 15, 31)),
                calm(), CFG);
        assertNotNull(c);
        assertEquals(93, c.value(), EPS);
        assertEquals(7, c.cost(), EPS);
        assertEquals(1, c.blocks());
        assertEquals(3, c.takes().get(0).target());
    }

    @Test
    public void aDropPastTheReserveIsLeft() {
        // 15 held of a need of 10: the reserve is full, a sixteenth is worth nothing
        List<Drop> drops = List.of(new Drop("raw_iron", 1, new Spot(2, 0, 0, 0), false));
        assertNull(TakeAlongRules.evaluateDrops(drops, Map.of("raw_iron", now("raw_iron", 15, 10, 31)), calm(), CFG));
        // a stack of 4 with 13 held counts the two left in the reserve, at 0.2 and 0.1
        Choice c = TakeAlongRules.evaluateDrops(List.of(new Drop("raw_iron", 4, new Spot(2, 0, 0, 0), false)),
                Map.of("raw_iron", now("raw_iron", 13, 10, 31)), calm(), CFG);
        assertNotNull(c);
        assertEquals(0.3 * 31, c.value(), EPS);
        assertEquals(15, c.takes().get(0).target());
    }

    @Test
    public void aFullBagTakesOnlyWhatStacks() {
        State full = new State(true, 20, false, false, false, null, 2, 0);
        Map<String, Wish> w = Map.of("coal", later("coal", 3, 10, 21));
        assertNull(TakeAlongRules.evaluateDrops(List.of(new Drop("coal", 2, new Spot(3, 0, 0, 0), false)), w, full, CFG));
        assertNotNull(TakeAlongRules.evaluateDrops(List.of(new Drop("coal", 2, new Spot(3, 0, 0, 0), true)), w, full, CFG));
    }

    @Test
    public void dropsLyingTogetherAreOneDetour() {
        List<Drop> drops = List.of(new Drop("coal", 1, new Spot(5, 0, 0, 0), false), new Drop("raw_iron", 1, new Spot(6, 0, 1, 0), false),
                new Drop("coal", 1, new Spot(-12, 0, 0, 0), false));
        List<List<Drop>> groups = TakeAlongRules.groupDrops(drops, CFG.clusterGap);
        assertEquals(2, groups.size());
        assertEquals(2, groups.get(0).size());
        Choice c = TakeAlongRules.chooseDrops(groups, Map.of("coal", later("coal", 0, 4, 21), "raw_iron", now("raw_iron", 0, 5, 31)), calm(), CFG);
        assertNotNull(c);
        assertEquals("coal+raw_iron", c.items());
        assertEquals(21 * 0.6 + 31, c.value(), EPS);
    }

    @Test
    public void theBetterChoiceHasTheLargerMargin() {
        Choice ore = choose(one("raw_iron", 4, row(1, 8, 0, 3)), now("raw_iron", 0, 5, 31));
        Choice drop = TakeAlongRules.evaluateDrops(List.of(new Drop("raw_iron", 2, new Spot(3, 0, 0, 0), false)),
                Map.of("raw_iron", now("raw_iron", 0, 5, 31)), calm(), CFG);
        assertNotNull(ore);
        assertNotNull(drop);
        assertTrue(drop.margin(CFG.k) > ore.margin(CFG.k));
        assertEquals(drop, TakeAlongRules.better(ore, drop, CFG));
        assertEquals(ore, TakeAlongRules.better(ore, null, CFG));
    }

    // ---- animals for food

    private static Prey cow(int dx, int dz) {
        return new Prey("cow", TakeAlongRules.PREY_POINTS.get("cow"), new Spot(dx, 0, dz, 0));
    }

    @Test
    public void aCowInSightBeforeTheNetherIsTaken() {
        // a nether order: a food run under 120 points, it gathers to 160. 80 in the bag, a cow 10 blocks off:
        // 16 points at 5 s each, all under 120, against 5 + 3 + 1 + 3 = 12 s
        Wish food = TakeAlongRules.foodWish(80, 120, 160, 5);
        Choice c = TakeAlongRules.choosePrey(TakeAlongRules.groupPrey(List.of(cow(10, 0)), CFG.clusterGap + 1), food, calm(), CFG);
        assertNotNull(c);
        assertEquals(80, c.value(), EPS);
        assertEquals(12, c.cost(), EPS);
        assertEquals(96, c.takes().get(0).target());
        assertEquals(TakeAlongRules.FOOD, c.items());
    }

    @Test
    public void aChickenFarOffWithTheRunsFoodInTheBagIsLeft() {
        // 150 of 160: six points at 0.6 are 18 s, a chicken 12 off costs 6 + 3 + 1 + 3 = 13 s
        Wish food = TakeAlongRules.foodWish(150, 120, 160, 5);
        Prey chicken = new Prey("chicken", TakeAlongRules.PREY_POINTS.get("chicken"), new Spot(12, 0, 0, 0));
        assertNull(TakeAlongRules.choosePrey(List.of(List.of(chicken)), food, calm(), CFG));
    }

    @Test
    public void aHerdIsTakenUpToTheEndOfTheReserve() {
        // 230 of a reserve that ends at 240: one cow is enough, the second brings nothing
        Wish food = TakeAlongRules.foodWish(230, 120, 160, 5);
        Choice c = TakeAlongRules.evaluatePrey(List.of(cow(2, 0), cow(3, 1)), food, calm(), CFG);
        assertNotNull(c);
        assertEquals(1, c.blocks());
        assertEquals(240, c.takes().get(0).target());
        // a full reserve takes nothing at all
        assertNull(TakeAlongRules.evaluatePrey(List.of(cow(2, 0)), TakeAlongRules.foodWish(240, 120, 160, 5), calm(), CFG));
    }

    @Test
    public void aFullBagHuntsNothing() {
        State full = new State(true, 20, false, false, false, null, 3, 0);
        assertNull(TakeAlongRules.evaluatePrey(List.of(cow(2, 0)), TakeAlongRules.foodWish(10, 120, 160, 5), full, CFG));
    }

    @Test
    public void cowsStandingTogetherAreOneHunt() {
        List<List<Prey>> groups = TakeAlongRules.groupPrey(List.of(cow(4, 0), cow(6, 2), cow(-10, 0)), CFG.clusterGap + 1);
        assertEquals(2, groups.size());
        Choice c = TakeAlongRules.evaluatePrey(groups.get(0), TakeAlongRules.foodWish(0, 24, 60, 5), calm(), CFG);
        assertNotNull(c);
        // 32 points: the first 24 at 1.0, the next 8 at 0.6; two kills
        assertEquals(24 * 5 + 8 * 5 * 0.6, c.value(), EPS);
        assertEquals(2 * 4 / 4.0 + 2 * 3 + 1 + 3, c.cost(), EPS);
    }
}
