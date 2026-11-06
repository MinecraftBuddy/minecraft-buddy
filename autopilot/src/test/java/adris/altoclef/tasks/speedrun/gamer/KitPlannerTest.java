package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.container.SmeltSplit;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig.ArmorPlan;
import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class KitPlannerTest {
    private OverworldConfig cfg;
    private FakeFacts f;

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    @Before
    public void setUp() {
        cfg = new OverworldConfig();
        cfg.netherSpareShields = 0;
        cfg.netherSpareSwords = 0;
        // these are the wood budget's tests; the fuel logs on top of it for the cook are in CookGateTest
        cfg.cookFuelLogs = 0;
        f = new FakeFacts();
    }

    private static List<String> names(List<KitNeed> needs) {
        return needs.stream().map(KitNeed::catalogueName).toList();
    }

    private static KitNeed find(List<KitNeed> needs, String name) {
        return needs.stream().filter(n -> n.catalogueName().equals(name)).findFirst().orElse(null);
    }

    // a bag one table's worth of planks off the wood budget: the log need is there exactly when the table is not held. walking
    // from the trees (near our table) to the stone 22 blocks off used to cross the latch every trip, and the log need and the
    // cobble traded the head every few seconds without either getting a block. the table counts out to 48 now (the craft walks
    // back to it that far), so the walk changes nothing
    @Test
    public void walkingBetweenTheTreesAndTheStoneDoesNotFlipTheLogNeed() {
        cfg.cookFuelLogs = 14;
        FakeFacts bag = new FakeFacts().give(Items.WOODEN_AXE, 1).give(Items.WOODEN_PICKAXE, 1).give(Items.OAK_LOG, 23)
                .give(Items.OAK_PLANKS, 4).give(Items.STICK, 6);
        bag.tablePlaced = false;
        assertTrue(names(KitPlanner.gather(bag, cfg, 8)).contains("log"));
        bag.tablePlaced = true;
        assertFalse(names(KitPlanner.gather(bag, cfg, 8)).contains("log"));
        // there and back, a quarter block a step, the latch carried along like MinecraftFacts carries it
        boolean far = false;
        for (int step = 0; step <= 200; step++) {
            double d = 5 + (step <= 100 ? step : 200 - step) * 0.25;
            boolean near = d <= WorkbenchRules.returnRadius(far);
            far = !near;
            // what MinecraftFacts says: the latch, or ours within the walk back (the bag can make a table, it has logs)
            bag.tablePlaced = near || d <= adris.altoclef.util.helpers.StationChoice.oursReach(adris.altoclef.util.helpers.StationHook.Kind.TABLE, true);
            assertFalse("d " + d, names(KitPlanner.gather(bag, cfg, 8)).contains("log"));
        }
    }

    // everything the default kit asks for, so a test can take one thing away
    private FakeFacts complete() {
        FakeFacts full = new FakeFacts();
        for (Item i : new Item[]{Items.STONE_PICKAXE, Items.STONE_SWORD, Items.FURNACE, Items.IRON_PICKAXE, Items.IRON_SWORD,
                Items.FLINT_AND_STEEL, Items.SHIELD, Items.SHEARS, Items.IRON_CHESTPLATE, Items.IRON_HELMET,
                Items.IRON_LEGGINGS, Items.IRON_BOOTS}) {
            full.give(i, 1);
        }
        // the axe, and enough logs that the gather has nothing left to chop (beds are 24 planks of it)
        full.give(Items.WOODEN_AXE, 1).give(Items.OAK_LOG, 20);
        full.give(Items.BUCKET, 2).give(Items.LADDER, 3).give(Items.WOOL.white(), 24);
        full.worn.addAll(List.of(Items.IRON_CHESTPLATE, Items.IRON_HELMET, Items.IRON_LEGGINGS, Items.IRON_BOOTS));
        full.foodUnits = 100;
        // the diamond pick dig is written off: it has its own tests, these take one iron thing away and want it back
        full.diamondHuntGaveUp = true;
        return full;
    }

    @Test
    public void freshStartGather() {
        List<KitNeed> gather = KitPlanner.gather(f, cfg, 8);
        // 3 logs is the table and the axe (9 planks), 15 is the whole run, and the axe is made in between
        // 18 cobble is 2 picks 6 + sword 2 + furnace 8 + 2 slack, all of it mined before the first stone craft
        assertEquals(List.of(new KitNeed("log", 3), new KitNeed("wooden_axe", 1), new KitNeed("log", 15),
                new KitNeed("cobblestone", 18), new KitNeed("stone_pickaxe", 2), new KitNeed("stone_sword", 1), new KitNeed("furnace", 1),
                new KitNeed(KitNeed.FOOD, 130)), gather);
    }

    @Test
    public void freshStartFullPlanOrder() {
        // fed enough to be over the floor: a bag under it leads with the refill (anEmptyBagEatsBeforeItBuildsAnything)
        f.foodUnits = 40;
        List<KitNeed> plan = KitPlanner.plan(f, cfg, 8);
        // no axe and no bed planks past the gather, but a plan that starts bare still does its wood before any ore
        assertEquals(List.of("log", "stone_pickaxe", "stone_sword", "food", "iron_ingot", "iron_pickaxe", "iron_sword", "bucket",
                "flint_and_steel", "shield", "shears", "ladder", "iron_chestplate", "iron_helmet", "iron_leggings", "iron_boots",
                "wool", "food"), names(plan));
        // no dig yet: the essentials are not in hand (theDigWaitsForTheEssentials)
    }

    @Test
    public void ironPhaseWithExactlyEnoughPlanksAsksForNoLog() {
        // 25 planks is the whole bare run (picks, shield, a table, the sticks). the old spare log wanted 4 more of them
        f.give(Items.OAK_PLANKS, 25);
        assertNull(find(KitPlanner.plan(f, cfg, 8), "log"));
        // one short is a log, and only one
        FakeFacts shy = new FakeFacts().give(Items.OAK_PLANKS, 24);
        assertEquals(new KitNeed("log", 1), find(KitPlanner.plan(shy, cfg, 8), "log"));
    }

    @Test
    public void ladderIsInTheKitButCostsNoIron() {
        // sticks only: three ladders must not grow the ingot need (that stays pickaxe..armor, see the next test)
        assertEquals(new KitNeed("ladder", 3), find(KitPlanner.plan(f, cfg, 8), "ladder"));
        assertEquals(39, KitPlanner.ingotsNeeded(f, cfg));
        // holding some of them: the need is the total, and full hands ask for nothing
        f.give(Items.LADDER, 1);
        assertEquals(new KitNeed("ladder", 3), find(KitPlanner.plan(f, cfg, 8), "ladder"));
        assertEquals(null, find(KitPlanner.plan(complete(), cfg, 8), "ladder"));
    }

    @Test
    public void oneCombinedIronNeedSizedByEverythingMissing() {
        // pickaxe 3 + sword 2 + 2 buckets 6 + flint and steel 1 + shield 1 + shears 2 + armor 24
        assertEquals(39, find(KitPlanner.plan(f, cfg, 8), "iron_ingot").count());
        assertEquals(39, KitPlanner.ingotsNeeded(f, cfg));
        assertEquals(1, KitPlanner.plan(f, cfg, 8).stream().filter(n -> n.catalogueName().equals("iron_ingot")).count());
    }

    @Test
    public void startedGatherPlanDoesNotAskForStationsAgain() {
        // the furnace gets placed for smelting and then we hold none: that must not send us off to craft another
        assertFalse(names(KitPlanner.plan(f, cfg, 8)).contains("furnace"));
        assertTrue(names(KitPlanner.gather(f, cfg, 8)).contains("furnace"));
    }

    @Test
    public void partialKitOnlyAsksForTheRest() {
        f.give(Items.STONE_PICKAXE, 1).give(Items.STONE_SWORD, 1).give(Items.IRON_PICKAXE, 1).give(Items.BUCKET, 2);
        f.foodUnits = 100;
        List<KitNeed> plan = KitPlanner.plan(f, cfg, 8);
        assertFalse(names(plan).contains("iron_pickaxe"));
        assertFalse(names(plan).contains("bucket"));
        // sword 2 + flint and steel 1 + shield 1 + shears 2 + armor 24
        assertEquals(30, find(plan, "iron_ingot").count());
    }

    @Test
    public void heldIngotsCoverTheCombinedNeed() {
        f.give(Items.IRON_INGOT, 39);
        List<KitNeed> plan = KitPlanner.plan(f, cfg, 8);
        assertEquals(null, find(plan, "iron_ingot"));
        assertTrue(names(plan).contains("iron_pickaxe"));
        f.give(Items.IRON_INGOT, -1);
        assertEquals(39, find(KitPlanner.plan(f, cfg, 8), "iron_ingot").count());
    }

    @Test
    public void waterBucketCountsAsABucketButNotAsAnEmptyOne() {
        f.give(Items.WATER_BUCKET, 1).give(Items.BUCKET, 1);
        assertEquals(null, find(KitPlanner.plan(f, cfg, 8), "bucket"));
        FakeFacts lone = new FakeFacts().give(Items.WATER_BUCKET, 1);
        // one empty bucket short, so ask for exactly one more empty bucket (3 ingots), not two
        assertEquals(new KitNeed("bucket", 1), find(KitPlanner.plan(lone, cfg, 8), "bucket"));
    }

    @Test
    public void betterToolsSatisfyWorseOnes() {
        f.give(Items.DIAMOND_PICKAXE, 1).give(Items.NETHERITE_SWORD, 1);
        List<KitNeed> plan = KitPlanner.plan(f, cfg, 8);
        assertFalse(names(plan).contains("stone_pickaxe"));
        assertFalse(names(plan).contains("iron_pickaxe"));
        assertFalse(names(plan).contains("stone_sword"));
        assertFalse(names(plan).contains("iron_sword"));
    }

    @Test
    public void armorPlans() {
        cfg.armorPlan = ArmorPlan.CHEST_HELMET;
        List<KitNeed> two = KitPlanner.plan(f, cfg, 8);
        assertTrue(names(two).containsAll(List.of("iron_chestplate", "iron_helmet")));
        assertFalse(names(two).contains("iron_leggings"));
        assertEquals(39 - 11, find(two, "iron_ingot").count());

        cfg.armorPlan = ArmorPlan.NONE;
        List<KitNeed> none = KitPlanner.plan(f, cfg, 8);
        assertFalse(names(none).contains("iron_chestplate"));
        assertEquals(15, find(none, "iron_ingot").count());
    }

    @Test
    public void wornArmorCountsAsOwned() {
        f.give(Items.IRON_CHESTPLATE, 1);
        f.worn.add(Items.IRON_CHESTPLATE);
        List<KitNeed> plan = KitPlanner.plan(f, cfg, 8);
        assertFalse(names(plan).contains("iron_chestplate"));
        assertFalse(names(plan).contains(KitNeed.EQUIP_ARMOR));
        assertEquals(39 - 8, find(plan, "iron_ingot").count());
    }

    @Test
    public void armorInTheBackpackGetsEquippedAfterTheCrafts() {
        f.give(Items.IRON_HELMET, 1);
        List<KitNeed> plan = KitPlanner.plan(f, cfg, 8);
        assertEquals(new KitNeed(KitNeed.EQUIP_ARMOR, 1), find(plan, KitNeed.EQUIP_ARMOR));
        assertTrue(names(plan).indexOf(KitNeed.EQUIP_ARMOR) > names(plan).indexOf("iron_boots"));
        assertTrue(names(plan).indexOf(KitNeed.EQUIP_ARMOR) < names(plan).indexOf("wool"));
        // in the plan, that is. the runner puts it on first (HeadLatch.armorFirst)
        assertEquals(new KitNeed(KitNeed.EQUIP_ARMOR, 1), HeadLatch.armorFirst(plan).get(0));
        assertEquals(List.of(Items.IRON_HELMET), KitPlanner.toEquip(f, cfg));
    }

    @Test
    public void aShieldInTheBagIsPutInTheOffhandWithTheArmor() {
        f.give(Items.SHIELD, 1);
        f.offhandShield = false;
        assertEquals(List.of(Items.SHIELD), KitPlanner.toEquip(f, cfg));
        assertEquals(new KitNeed(KitNeed.EQUIP_ARMOR, 1), find(KitPlanner.plan(f, cfg, 8), KitNeed.EQUIP_ARMOR));
        // in the offhand it is done, and a spare in the bag next to it is not a reason to swap
        f.worn.add(Items.SHIELD);
        f.give(Items.SHIELD, 1);
        assertTrue(KitPlanner.toEquip(f, cfg).isEmpty());
    }

    @Test
    public void equipsTheBestTierWeHold() {
        f.give(Items.IRON_HELMET, 1).give(Items.DIAMOND_HELMET, 1);
        assertEquals(List.of(Items.DIAMOND_HELMET), KitPlanner.toEquip(f, cfg));
        f.worn.add(Items.DIAMOND_HELMET);
        assertTrue(KitPlanner.toEquip(f, cfg).isEmpty());
    }

    @Test
    public void shearsComeBeforeWool() {
        List<String> n = names(KitPlanner.plan(f, cfg, 8));
        assertTrue(n.indexOf("shears") < n.indexOf("wool"));
        assertEquals(24, find(KitPlanner.plan(f, cfg, 8), "wool").count());
    }

    @Test
    public void woolIsSizedByBedsAndWoolWeHold() {
        f.give(Items.WOOL.red(), 10);
        // only 9 of the 10 make beds, so the task (which counts every wool) must hold 10 + 15 more
        assertEquals(25, find(KitPlanner.plan(f, cfg, 8), "wool").count());
        FakeFacts beds = new FakeFacts().give(Items.BED.white(), 2);
        // two beds are six wool already, so 18 more, held as 18 wool
        assertEquals(18, find(KitPlanner.plan(beds, cfg, 8), "wool").count());
        assertEquals(null, find(KitPlanner.plan(new FakeFacts().give(Items.BED.white(), 8), cfg, 8), "wool"));
        assertEquals(null, find(KitPlanner.plan(f, cfg, 0), "wool"));
    }

    @Test
    public void bedShortfallIsTheWoolNeedInWholeBeds() {
        assertEquals(24, KitPlanner.woolShortfall(f, 8));
        assertEquals(8, KitPlanner.bedsShort(f, 8));
        // a held bed is three wool, whatever colour
        FakeFacts beds = new FakeFacts().give(Items.BED.red(), 2).give(Items.BED.white(), 1);
        assertEquals(15, KitPlanner.woolShortfall(beds, 8));
        assertEquals(5, KitPlanner.bedsShort(beds, 8));
        // wool in sets of three counts like beds do, scraps do not
        FakeFacts wool = new FakeFacts().give(Items.WOOL.red(), 5).give(Items.WOOL.white(), 2);
        assertEquals(21, KitPlanner.woolShortfall(wool, 8));
        assertEquals(7, KitPlanner.bedsShort(wool, 8));
        // a spare is a ninth bed, and nothing is owed once we hold enough
        assertEquals(1, KitPlanner.bedsShort(new FakeFacts().give(Items.BED.white(), 8), 9));
        assertEquals(0, KitPlanner.bedsShort(new FakeFacts().give(Items.BED.white(), 9), 9));
        assertEquals(0, KitPlanner.bedsShort(f, 0));
        // and the planner asks for exactly the wool this says is missing
        assertEquals(wool.count(Items.WOOL.red()) + wool.count(Items.WOOL.white()) + KitPlanner.woolShortfall(wool, 8),
                find(KitPlanner.plan(wool, cfg, 8), "wool").count());
    }

    @Test
    public void woolCountsPerColourInSetsOfThree() {
        // a bed needs three of ONE colour: 5 red + 4 white is 3 + 3
        f.give(Items.WOOL.red(), 5).give(Items.WOOL.white(), 4);
        assertEquals(6, KitPlanner.usableWool(f));
        assertEquals(9 + 24 - 6, find(KitPlanner.plan(f, cfg, 8), "wool").count());
        // 24 wool in odd leftovers is no beds at all
        FakeFacts scraps = new FakeFacts();
        for (Item wool : adris.altoclef.util.helpers.ItemHelper.WOOL) {
            scraps.give(wool, 2);
        }
        assertEquals(0, KitPlanner.usableWool(scraps));
        assertEquals(32 + 24, find(KitPlanner.plan(scraps, cfg, 8), "wool").count());
        // a full set in one colour is done
        assertEquals(null, find(KitPlanner.plan(new FakeFacts().give(Items.WOOL.white(), 24), cfg, 8), "wool"));
        assertEquals(null, find(KitPlanner.plan(new FakeFacts().give(Items.WOOL.white(), 12).give(Items.WOOL.red(), 12), cfg, 8), "wool"));
    }

    @Test
    public void stoneToolsMetIgnoresFoodAndFurnace() {
        assertFalse(KitPlanner.stoneToolsMet(f));
        f.give(Items.STONE_PICKAXE, 1);
        assertFalse(KitPlanner.stoneToolsMet(f));
        f.give(Items.IRON_SWORD, 1);
        assertTrue(KitPlanner.stoneToolsMet(f));
        // the weapon the kit makes is a sword, an axe out of a chest swings too
        assertTrue(KitPlanner.stoneToolsMet(new FakeFacts().give(Items.STONE_PICKAXE, 1).give(Items.STONE_SWORD, 1)));
        assertTrue(KitPlanner.stoneToolsMet(new FakeFacts().give(Items.STONE_PICKAXE, 1).give(Items.STONE_AXE, 1)));
        assertFalse(KitPlanner.stoneToolsMet(new FakeFacts().give(Items.STONE_PICKAXE, 1).give(Items.WOODEN_AXE, 1)));
    }

    @Test
    public void foodNeedsAreEarlyAndAtTheEnd() {
        f.foodUnits = 80;
        List<KitNeed> plan = KitPlanner.plan(f, cfg, 8);
        assertEquals(new KitNeed(KitNeed.FOOD, 100), plan.get(plan.size() - 1));
        assertEquals(1, plan.stream().filter(n -> n.catalogueName().equals(KitNeed.FOOD)).count());
        f.foodUnits = 100;
        assertEquals(null, find(KitPlanner.plan(f, cfg, 8), KitNeed.FOOD));
        f.foodUnits = 10;
        // under the floor with tools still to make: the floor bite, the whole refill behind the tools, and the target
        assertEquals(3, KitPlanner.plan(f, cfg, 8).stream().filter(n -> n.catalogueName().equals(KitNeed.FOOD)).count());
    }

    @Test
    public void anEmptyBagEatsBeforeItBuildsAnything() {
        // no food, no pick, no wood: the starter chain (logs, the stone pick) has a lot to ask for, and food still leads, but only
        // up to the floor. the rest of the refill waits behind the tools
        FoodPlan food = FoodPlan.ofBeds(f, cfg, 8);
        List<KitNeed> plan = KitPlanner.plan(f, cfg, 8, food);
        assertEquals(new KitNeed(KitNeed.FOOD, food.floor()), plan.get(0));
        assertTrue(names(plan).contains("stone_pickaxe"));
        assertTrue(names(plan).contains("log"));
        assertTrue(names(plan).indexOf("log") > 0);
        // the head the gate cares about is that one
        assertEquals(0, FoodGate.index(plan, food));
        // one under the floor is the same: the floor bite, the tools, the whole refill, and the target closing the plan
        f.foodUnits = 23;
        plan = KitPlanner.plan(f, cfg, 8);
        assertEquals(new KitNeed(KitNeed.FOOD, food.floor()), plan.get(0));
        assertEquals(3, plan.stream().filter(n -> n.catalogueName().equals(KitNeed.FOOD)).count());
    }

    @Test
    public void anEmptyBagEatsThenArmsItselfThenFinishesStockingFood() {
        FoodPlan food = FoodPlan.ofBeds(f, cfg, 8);
        List<KitNeed> plan = KitPlanner.plan(f, cfg, 8, food);
        int bite = 0;
        int tools = names(plan).indexOf("stone_pickaxe");
        int rest = plan.indexOf(new KitNeed(KitNeed.FOOD, food.refillStop()));
        assertEquals(new KitNeed(KitNeed.FOOD, food.floor()), plan.get(bite));
        assertTrue("the pick comes after the first bite", tools > bite);
        assertTrue("and the rest of the food after the pick", rest > tools);
        // the weapon too, not just the pick
        assertTrue(names(plan).indexOf("stone_sword") < rest);
        // and the ore only after all of it
        assertTrue(names(plan).indexOf("iron_ingot") > rest);
        // once the bite is eaten the plan is the fed one, tools first and the refill behind them
        f.foodUnits = food.floor();
        List<KitNeed> fed = KitPlanner.plan(f, cfg, 8);
        assertEquals(plan.subList(1, plan.size()), fed.subList(0, plan.size() - 1));
    }

    @Test
    public void anArmedBagUnderTheFloorStillRefillsInOnePieceUpFront() {
        // nothing in the starter to make, so there is nothing to split the refill around
        FakeFacts armed = complete();
        armed.foodUnits = 5;
        FoodPlan food = FoodPlan.ofBeds(armed, cfg, 8);
        List<KitNeed> plan = KitPlanner.plan(armed, cfg, 8, food);
        assertEquals(new KitNeed(KitNeed.FOOD, food.refillStop()), plan.get(0));
        assertEquals(0, FoodGate.index(plan, food));
    }

    @Test
    public void aBagWithItsToolsThatOnlyOwesLogsKeepsTheOnePieceRefill() {
        // pick and weapon in hand, no wood at all: the starter is not empty but there is nothing to arm
        FakeFacts armed = new FakeFacts().give(Items.STONE_PICKAXE, 1).give(Items.STONE_SWORD, 1);
        armed.foodUnits = 5;
        FoodPlan food = FoodPlan.ofBeds(armed, cfg, 8);
        List<KitNeed> plan = KitPlanner.plan(armed, cfg, 8, food);
        assertEquals(new KitNeed(KitNeed.FOOD, food.refillStop()), plan.get(0));
        assertFalse(plan.contains(new KitNeed(KitNeed.FOOD, food.floor())));
        assertTrue(names(plan).contains("log"));
        assertEquals(0, FoodGate.index(plan, food));
    }

    @Test
    public void aBagAtOrOverTheFloorGetsTheSamePlanAsBefore() {
        // the fed order: starter, refill (only while one is due), iron, the rest. nothing in it asks for the floor bite
        for (int units : new int[] {24, 30, 44, 45, 60, 100, 130}) {
            FakeFacts fed = new FakeFacts();
            fed.foodUnits = units;
            FoodPlan food = FoodPlan.ofBeds(fed, cfg, 8);
            List<KitNeed> plan = KitPlanner.plan(fed, cfg, 8, food);
            assertNotEquals("units " + units, new KitNeed(KitNeed.FOOD, food.floor()), plan.get(0));
            assertFalse("units " + units, plan.contains(new KitNeed(KitNeed.FOOD, food.floor())));
            int first = names(plan).indexOf(KitNeed.FOOD);
            int pick = names(plan).indexOf("stone_pickaxe");
            if (food.wantsRefill()) {
                assertTrue("units " + units, pick < first);
            }
        }
    }

    @Test
    public void atTheFloorTheStarterStillGoesFirst() {
        // the same bag with food at the floor: a refill may be due, but it waits behind the tools like it always did
        f.foodUnits = 24;
        List<KitNeed> plan = KitPlanner.plan(f, cfg, 8);
        assertFalse(KitNeed.FOOD.equals(plan.get(0).catalogueName()));
        assertTrue(names(plan).contains(KitNeed.FOOD));
        f.foodUnits = 40;
        assertFalse(KitNeed.FOOD.equals(KitPlanner.plan(f, cfg, 8).get(0).catalogueName()));
    }

    @Test
    public void completeKitIsEmptyAndStaysEmpty() {
        FakeFacts full = complete();
        assertTrue(KitPlanner.plan(full, cfg, 8).isEmpty());
        assertTrue(KitPlanner.plan(full, cfg, 8).isEmpty());
        assertTrue(KitPlanner.gather(full, cfg, 8).isEmpty());
        assertTrue(KitPlanner.essentialsMet(full, cfg));
    }

    @Test
    public void losingOneThingBringsBackExactlyThatThing() {
        FakeFacts full = complete();
        full.items.put(Items.SHEARS, 0);
        List<KitNeed> plan = KitPlanner.plan(full, cfg, 8);
        assertEquals(List.of("iron_ingot", "shears"), names(plan));
        assertEquals(2, find(plan, "iron_ingot").count());
    }

    @Test
    public void essentialsNeedPickaxeLightAndTwoBuckets() {
        FakeFacts full = complete();
        full.items.put(Items.BUCKET, 1);
        assertFalse(KitPlanner.essentialsMet(full, cfg));
        full.give(Items.LAVA_BUCKET, 1);
        assertTrue(KitPlanner.essentialsMet(full, cfg));
        full.items.put(Items.FLINT_AND_STEEL, 0);
        assertFalse(KitPlanner.essentialsMet(full, cfg));
        full.give(Items.FIRE_CHARGE, 1);
        assertTrue(KitPlanner.essentialsMet(full, cfg));
    }

    @Test
    public void progressOfTracksTheRightNumber() {
        f.foodUnits = 33;
        f.buildBlocks = 7;
        f.give(Items.IRON_INGOT, 4);
        FoodPlan food = FoodPlan.ofBeds(f, cfg, 8);
        assertEquals(33, KitPlanner.progressOf(f, new KitNeed(KitNeed.FOOD, 70), food));
        assertEquals(7, KitPlanner.progressOf(f, new KitNeed(KitNeed.BUILD_BLOCKS, 32), food));
        assertEquals(4, KitPlanner.progressOf(f, new KitNeed("iron_ingot", 40), food));
    }

    @Test
    public void fiveIngotsOfGoldMakeTheHelmetGoldAndSaveFiveIron() {
        f.give(Items.GOLD_INGOT, 5);
        List<KitNeed> plan = KitPlanner.plan(f, cfg, 8);
        assertTrue(names(plan).contains("golden_helmet"));
        assertFalse(names(plan).contains("iron_helmet"));
        // same spot in the order, so nothing else moves
        assertEquals(names(plan).indexOf("iron_chestplate") + 1, names(plan).indexOf("golden_helmet"));
        assertEquals(34, find(plan, "iron_ingot").count());
        assertEquals(34, KitPlanner.ingotsNeeded(f, cfg));
        assertTrue(names(plan).contains("iron_boots"));
    }

    @Test
    public void fourIngotsOfGoldMakeTheBootsGold() {
        f.give(Items.GOLD_INGOT, 4);
        List<KitNeed> plan = KitPlanner.plan(f, cfg, 8);
        assertTrue(names(plan).contains("golden_boots"));
        assertFalse(names(plan).contains("iron_boots"));
        assertTrue(names(plan).contains("iron_helmet"));
        assertEquals(35, find(plan, "iron_ingot").count());
    }

    @Test
    public void noGoldMeansTheSamePlanAsEver() {
        List<KitNeed> plan = KitPlanner.plan(f, cfg, 8);
        assertEquals(39, find(plan, "iron_ingot").count());
        assertFalse(names(plan).contains("golden_helmet"));
        assertFalse(names(plan).contains("golden_boots"));
        f.give(Items.GOLD_INGOT, 3);
        assertEquals(39, KitPlanner.ingotsNeeded(f, cfg));
    }

    @Test
    public void goldInOtherShapesCountsToo() {
        // a block is nine, nine nuggets are one, raw gold gets smelted. none of it is mined for
        assertEquals(34, KitPlanner.ingotsNeeded(new FakeFacts().give(Items.GOLD_BLOCK, 1), cfg));
        assertEquals(34, KitPlanner.ingotsNeeded(new FakeFacts().give(Items.GOLD_NUGGET, 45), cfg));
        assertEquals(34, KitPlanner.ingotsNeeded(new FakeFacts().give(Items.RAW_GOLD, 5), cfg));
        assertEquals(35, KitPlanner.ingotsNeeded(new FakeFacts().give(Items.GOLD_NUGGET, 36), cfg));
        assertEquals(34, KitPlanner.ingotsNeeded(new FakeFacts().give(Items.GOLD_INGOT, 2).give(Items.GOLD_NUGGET, 27), cfg));
        assertEquals(39, KitPlanner.ingotsNeeded(new FakeFacts().give(Items.GOLD_NUGGET, 35), cfg));
    }

    @Test
    public void aHeldGoldenHelmetIsJustEquipped() {
        f.give(Items.GOLDEN_HELMET, 1);
        List<KitNeed> plan = KitPlanner.plan(f, cfg, 8);
        assertFalse(names(plan).contains("golden_helmet"));
        assertFalse(names(plan).contains("iron_helmet"));
        assertEquals(34, find(plan, "iron_ingot").count());
        assertEquals(new KitNeed(KitNeed.EQUIP_ARMOR, 1), find(plan, KitNeed.EQUIP_ARMOR));
        assertEquals(List.of(Items.GOLDEN_HELMET), KitPlanner.toEquip(f, cfg));
        f.worn.add(Items.GOLDEN_HELMET);
        assertEquals(null, find(KitPlanner.plan(f, cfg, 8), KitNeed.EQUIP_ARMOR));
    }

    @Test
    public void goldHeldAsBootsSwapsTheBootsSlot() {
        f.give(Items.GOLDEN_BOOTS, 1).give(Items.GOLD_INGOT, 9);
        List<KitNeed> plan = KitPlanner.plan(f, cfg, 8);
        // the boots are already ours, so the nine ingots do not turn into a helmet as well
        assertFalse(names(plan).contains("golden_helmet"));
        assertFalse(names(plan).contains("iron_boots"));
        assertTrue(names(plan).contains("iron_helmet"));
        assertEquals(List.of(Items.GOLDEN_BOOTS), KitPlanner.toEquip(f, cfg));
    }

    @Test
    public void goldAfterTheIronHelmetWasMadeStillGoesGold() {
        f.give(Items.IRON_HELMET, 1).give(Items.GOLD_INGOT, 5);
        f.worn.add(Items.IRON_HELMET);
        List<KitNeed> plan = KitPlanner.plan(f, cfg, 8);
        assertTrue(names(plan).contains("golden_helmet"));
        assertEquals(34, find(plan, "iron_ingot").count());
        // crafted: the gold one is held, the iron one stays on until the equip step swaps them
        f.items.put(Items.GOLD_INGOT, 0);
        f.give(Items.GOLDEN_HELMET, 1);
        plan = KitPlanner.plan(f, cfg, 8);
        assertFalse(names(plan).contains("golden_helmet"));
        assertEquals(new KitNeed(KitNeed.EQUIP_ARMOR, 1), find(plan, KitNeed.EQUIP_ARMOR));
        assertEquals(List.of(Items.GOLDEN_HELMET), KitPlanner.toEquip(f, cfg));
        // and it sticks: wearing it and having no gold left must not bring the iron helmet back
        f.worn.clear();
        f.worn.add(Items.GOLDEN_HELMET);
        plan = KitPlanner.plan(f, cfg, 8);
        assertFalse(names(plan).contains("golden_helmet"));
        assertFalse(names(plan).contains(KitNeed.EQUIP_ARMOR));
        assertEquals(34, find(plan, "iron_ingot").count());
    }

    @Test
    public void aGoldPieceOutsideThePlanIsStillWorn() {
        cfg.armorPlan = ArmorPlan.CHEST_HELMET;
        f.give(Items.GOLDEN_BOOTS, 1);
        assertEquals(List.of(Items.GOLDEN_BOOTS), KitPlanner.toEquip(f, cfg));
        cfg.armorPlan = ArmorPlan.NONE;
        assertEquals(List.of(Items.GOLDEN_BOOTS), KitPlanner.toEquip(f, cfg));
        f.worn.add(Items.GOLDEN_BOOTS);
        assertTrue(KitPlanner.toEquip(f, cfg).isEmpty());
        // nothing gold to swap in the none plan, so the gold stays a gate matter
        assertEquals(15, find(KitPlanner.plan(new FakeFacts().give(Items.GOLD_INGOT, 9), cfg, 8), "iron_ingot").count());
    }

    @Test
    public void onlyOneGoldPieceIsWornWhenSeveralAreHeld() {
        f.give(Items.GOLDEN_HELMET, 1).give(Items.GOLDEN_BOOTS, 1);
        assertEquals(List.of(Items.GOLDEN_HELMET), KitPlanner.toEquip(f, cfg));
        f.worn.add(Items.GOLDEN_BOOTS);
        // boots on already: the helmet in the bag is not a second wardrobe change
        assertTrue(KitPlanner.toEquip(f, cfg).isEmpty());
    }

    // ---- wood budget ----

    @Test
    public void woodBudgetForAFreshRunIsFifteenLogs() {
        // planks: table x2 8 + axe 3 + wooden pick 3 + shield 6 + 24 for eight beds = 44. sticks: axe 2, two stone picks 4,
        // wooden pick 2, stone sword 1, iron pick 2, iron sword 1, ladders 7 = 19, which is 5 crafts of 2 planks. 54 + a log
        // of slack is 58, and 58 planks are 15 logs (rounded up)
        assertEquals(15, KitPlanner.woodNeed(f, cfg, 8));
    }

    @Test
    public void firstBatchIsTheTableAndTheAxeThenTheWholeBudgetAfterTheAxe() {
        List<KitNeed> gather = KitPlanner.gather(f, cfg, 8);
        assertEquals(List.of("log", "wooden_axe", "log", "cobblestone", "stone_pickaxe", "stone_sword", "furnace", "food"), names(gather));
        // logs in hand but no axe yet: the small batch is done, the axe is next, and the big target does not move
        f.give(Items.OAK_LOG, 3);
        gather = KitPlanner.gather(f, cfg, 8);
        assertEquals(List.of("wooden_axe", "log", "cobblestone", "stone_pickaxe", "stone_sword", "furnace", "food"), names(gather));
        assertEquals(new KitNeed("log", 15), gather.get(1));
        // axe made (the table and some planks went with it), still short: the wood comes before the stone tools
        FakeFacts after = new FakeFacts().give(Items.WOODEN_AXE, 1).give(Items.OAK_LOG, 1).give(Items.OAK_PLANKS, 5);
        after.give(Items.CRAFTING_TABLE, 1);
        assertEquals("log", KitPlanner.gather(after, cfg, 8).get(0).catalogueName());
    }

    @Test
    public void anyAxeIsTheAxe() {
        for (Item axe : new Item[]{Items.WOODEN_AXE, Items.STONE_AXE, Items.IRON_AXE, Items.DIAMOND_AXE}) {
            FakeFacts held = new FakeFacts().give(axe, 1);
            List<String> names = names(KitPlanner.gather(held, cfg, 8));
            assertFalse(axe.toString(), names.contains("wooden_axe"));
            // no axe to wait for means the budget is the first thing asked
            assertEquals("log", names.get(0));
        }
    }

    @Test
    public void theKitMakesSwordsAndTheLogAxeOnly() {
        // a sword is the weapon (WeaponPick), the only axe the kit makes is the wooden one for the logs
        List<String> all = names(KitPlanner.plan(f, cfg, 8));
        assertTrue(all.contains("stone_sword"));
        assertTrue(all.contains("iron_sword"));
        assertFalse(all.contains("stone_axe"));
        assertFalse(all.contains("iron_axe"));
        assertTrue(names(KitPlanner.gather(f, cfg, 8)).contains("stone_sword"));
        // and the first batch is the wooden axe, the stone sword waits for the stone pick
        assertEquals(List.of("log", "wooden_axe"), names(KitPlanner.gather(f, cfg, 8)).subList(0, 2));
    }

    @Test
    public void aBetterSwordCoversTheWorseOnesAndAnAxeCoversNone() {
        assertEquals(1, KitPlanner.have(new FakeFacts().give(Items.IRON_SWORD, 1), "stone_sword"));
        assertEquals(0, KitPlanner.have(new FakeFacts().give(Items.STONE_SWORD, 1), "iron_sword"));
        // an axe in the bag is not a sword in the kit's eyes, the sword is still there to make
        assertEquals(0, KitPlanner.have(new FakeFacts().give(Items.IRON_AXE, 1), "stone_sword"));
        assertEquals(new KitNeed("stone_sword", 1), find(KitPlanner.gather(new FakeFacts().give(Items.OAK_LOG, 15)
                .give(Items.WOODEN_AXE, 1).give(Items.COBBLESTONE, 40).give(Items.STONE_PICKAXE, 2).give(Items.FURNACE, 1)
                .give(Items.IRON_AXE, 1), cfg, 8), "stone_sword"));
    }

    @Test
    public void budgetEndsExactlyWhereTheWoodDoes() {
        FakeFacts enough = new FakeFacts().give(Items.OAK_LOG, 15);
        assertEquals(0, KitPlanner.woodNeed(enough, cfg, 8));
        assertFalse(names(KitPlanner.gather(enough, cfg, 8)).contains("log"));
        assertEquals(1, KitPlanner.woodNeed(new FakeFacts().give(Items.OAK_LOG, 14), cfg, 8));
        // planks and logs are the same currency, four to one
        assertEquals(0, KitPlanner.woodNeed(new FakeFacts().give(Items.SPRUCE_PLANKS, 58), cfg, 8));
        assertEquals(1, KitPlanner.woodNeed(new FakeFacts().give(Items.SPRUCE_PLANKS, 57), cfg, 8));
        assertEquals(0, KitPlanner.woodNeed(new FakeFacts().give(Items.OAK_LOG, 10).give(Items.OAK_PLANKS, 20), cfg, 8));
    }

    @Test
    public void heldSticksTakeOffThePlanksThatWouldMakeThem() {
        // 19 sticks wanted is 10 planks, so 20 sticks in the bag leave 48 planks of budget
        assertEquals(0, KitPlanner.woodNeed(new FakeFacts().give(Items.STICK, 20).give(Items.OAK_PLANKS, 48), cfg, 8));
        assertEquals(1, KitPlanner.woodNeed(new FakeFacts().give(Items.STICK, 20).give(Items.OAK_PLANKS, 47), cfg, 8));
        // 17 of them is one craft short, 14 is five sticks short and a stick over a craft still costs the craft
        assertEquals(0, KitPlanner.woodNeed(new FakeFacts().give(Items.STICK, 17).give(Items.OAK_PLANKS, 50), cfg, 8));
        assertEquals(1, KitPlanner.woodNeed(new FakeFacts().give(Items.STICK, 14).give(Items.OAK_PLANKS, 51), cfg, 8));
    }

    @Test
    public void bedPlanksAreOnlyForBedsWeStillHaveToMake() {
        assertEquals(9, KitPlanner.woodNeed(f, cfg, 0));
        assertEquals(15, KitPlanner.woodNeed(f, cfg, 8));
        // a bed in the bag is its three planks already spent
        assertEquals(9, KitPlanner.woodNeed(new FakeFacts().give(Items.BED.white(), 8), cfg, 8));
    }

    @Test
    public void aTableInTheBagAndToolsAlreadyMadeShrinkTheTableShare() {
        int fresh = KitPlanner.woodNeed(f, cfg, 0);
        // a held table is 8 planks we do not need
        assertEquals(fresh - 2, KitPlanner.woodNeed(new FakeFacts().give(Items.CRAFTING_TABLE, 1), cfg, 0));
        // axe and wooden pick made means the table went down already: one spare is left to budget
        FakeFacts made = new FakeFacts().give(Items.WOODEN_AXE, 1).give(Items.WOODEN_PICKAXE, 1);
        assertTrue(KitPlanner.woodNeed(made, cfg, 0) < fresh);
    }

    // the log: the table went down for the axe, left the bag, and the head need flipped from the axe to log
    @Test
    public void aTablePlacedNextToUsCountsAsHeld() {
        // 4 planks and some sticks in the bag: enough for the axe, not for an axe AND a table
        FakeFacts placed = new FakeFacts().give(Items.OAK_PLANKS, 3).give(Items.STICK, 2);
        assertEquals("log", KitPlanner.gather(placed, cfg, 8).get(0).catalogueName());
        placed.tablePlaced = true;
        assertEquals("wooden_axe", KitPlanner.gather(placed, cfg, 8).get(0).catalogueName());
        // and the same with the table in the bag, it is the same table
        FakeFacts bag = new FakeFacts().give(Items.OAK_PLANKS, 3).give(Items.STICK, 2).give(Items.CRAFTING_TABLE, 1);
        assertEquals(names(KitPlanner.gather(bag, cfg, 8)), names(KitPlanner.gather(placed, cfg, 8)));
    }

    @Test
    public void aPlacedTableTakesTheTablePlanksOutOfTheBudgetToo() {
        int fresh = KitPlanner.woodNeed(f, cfg, 0);
        FakeFacts placed = new FakeFacts();
        placed.tablePlaced = true;
        assertEquals(fresh - 2, KitPlanner.woodNeed(placed, cfg, 0));
        assertEquals(KitPlanner.woodNeed(new FakeFacts().give(Items.CRAFTING_TABLE, 1), cfg, 0), KitPlanner.woodNeed(placed, cfg, 0));
    }

    @Test
    public void ironPhaseDoesNotAskForWoodOnceTheOreIsInTheBag() {
        // the whole point: no cave to surface trip for one more log
        // (fed: an empty stomach puts the refill ahead of the wood, anEmptyBagEatsBeforeItBuildsAnything)
        f.foodUnits = 40;
        assertEquals("log", names(KitPlanner.plan(f, cfg, 8)).get(0));
        FakeFacts raw = new FakeFacts().give(Items.RAW_IRON, 1);
        assertFalse(names(KitPlanner.plan(raw, cfg, 8)).contains("log"));
        FakeFacts ingots = new FakeFacts().give(Items.IRON_INGOT, 3);
        assertFalse(names(KitPlanner.plan(ingots, cfg, 8)).contains("log"));
        // iron cooking in a furnace we loaded is iron too
        FakeFacts cooking = new FakeFacts().cooking("iron_ingot", 8, 60);
        assertFalse(names(KitPlanner.plan(cooking, cfg, 8)).contains("log"));
    }

    @Test
    public void ironPhaseNeverAsksForAnAxeOrTheBedPlanks() {
        assertFalse(names(KitPlanner.plan(f, cfg, 8)).contains("wooden_axe"));
        // a complete kit with no wood at all is still complete: wool in hand is the iron phase's last word on the beds
        FakeFacts full = complete();
        full.items.remove(Items.OAK_LOG);
        full.items.remove(Items.WOODEN_AXE);
        assertTrue(KitPlanner.plan(full, cfg, 8).isEmpty());
    }

    // ---- stone budget ----

    @Test
    public void stoneBudgetForAFreshRunIsEighteen() {
        // two picks 6, sword 2, furnace 8, and 2 of slack
        assertEquals(18, KitPlanner.stoneNeed(f, cfg));
    }

    @Test
    public void heldCobbleTakesOffTheStoneBudgetAndStaysAtZeroThroughTheCrafts() {
        assertEquals(8, KitPlanner.stoneNeed(new FakeFacts().give(Items.COBBLESTONE, 10), cfg));
        assertEquals(0, KitPlanner.stoneNeed(new FakeFacts().give(Items.COBBLESTONE, 18), cfg));
        assertEquals(0, KitPlanner.stoneNeed(new FakeFacts().give(Items.COBBLESTONE, 40), cfg));
        // the picks are crafted: 6 cobble gone and 6 wanted gone, so a need that was met stays met (no second trip)
        FakeFacts crafted = new FakeFacts().give(Items.COBBLESTONE, 12).give(Items.STONE_PICKAXE, 2);
        assertEquals(0, KitPlanner.stoneNeed(crafted, cfg));
        // and the sword too
        crafted.give(Items.COBBLESTONE, -2).give(Items.STONE_SWORD, 1);
        assertEquals(0, KitPlanner.stoneNeed(crafted, cfg));
        // everything made: nothing left to want, not even the slack
        assertEquals(0, KitPlanner.stoneNeed(new FakeFacts().give(Items.STONE_PICKAXE, 2).give(Items.STONE_SWORD, 1)
                .give(Items.FURNACE, 1), cfg));
    }

    @Test
    public void cobbledDeepslateIsNotCobbleToTheRecipes() {
        // the catalogue recipes only take Items.COBBLESTONE, so deepslate in the bag would not reach the crafts
        assertEquals(18, KitPlanner.stoneNeed(new FakeFacts().give(Items.COBBLED_DEEPSLATE, 30), cfg));
    }

    @Test
    public void stoneBudgetShrinksWithWhatIsAlreadyMade() {
        // a furnace in the bag is 8 less
        assertEquals(10, KitPlanner.stoneNeed(new FakeFacts().give(Items.FURNACE, 1), cfg));
        // a blast furnace counts as a furnace
        assertEquals(10, KitPlanner.stoneNeed(new FakeFacts().give(Items.BLAST_FURNACE, 1), cfg));
        // one pick down: one more to make
        assertEquals(15, KitPlanner.stoneNeed(new FakeFacts().give(Items.STONE_PICKAXE, 1), cfg));
    }

    @Test
    public void anIronPickCoversTheStoneBudgetOfTheStonePicks() {
        // sword and furnace only: 10 and 2 of slack
        assertEquals(12, KitPlanner.stoneNeed(new FakeFacts().give(Items.IRON_PICKAXE, 1), cfg));
        assertEquals(12, KitPlanner.stoneNeed(new FakeFacts().give(Items.DIAMOND_PICKAXE, 1), cfg));
    }

    @Test
    public void aWornStonePickIsAFreshOneInTheBudget() {
        f.spent.put(Items.STONE_PICKAXE, 1);
        // the worn one does not count, so both picks are still to make
        assertEquals(18, KitPlanner.stoneNeed(f, cfg));
        f.give(Items.STONE_PICKAXE, 1);
        assertEquals(15, KitPlanner.stoneNeed(f, cfg));
    }

    // ---- stone floor (what the movements must not spend) ----

    @Test
    public void stoneFloorIsTheWholeKitWithoutSlackOrWhatWeHold() {
        // two picks 6, sword 2, furnace 8: the slack is for mining, not for keeping
        assertEquals(16, KitPlanner.stoneFloor(f, cfg));
        // holding cobble does not shrink it, the held cobble IS what it keeps
        assertEquals(16, KitPlanner.stoneFloor(new FakeFacts().give(Items.COBBLESTONE, 18), cfg));
        assertEquals(16, KitPlanner.stoneFloor(new FakeFacts().give(Items.COBBLESTONE, 3), cfg));
        // and deepslate is no cobble but it is not a reason to change the number either
        assertEquals(16, KitPlanner.stoneFloor(new FakeFacts().give(Items.COBBLED_DEEPSLATE, 30), cfg));
    }

    @Test
    public void stoneFloorFallsAsTheStoneItemsGetMade() {
        // the whole log: picks crafted, then the sword, then the furnace
        FakeFacts made = new FakeFacts().give(Items.COBBLESTONE, 12).give(Items.STONE_PICKAXE, 2);
        assertEquals(10, KitPlanner.stoneFloor(made, cfg));
        made.give(Items.STONE_SWORD, 1);
        assertEquals(8, KitPlanner.stoneFloor(made, cfg));
        made.give(Items.FURNACE, 1);
        assertEquals(0, KitPlanner.stoneFloor(made, cfg));
        // one pick down, and an iron pick covers both of them
        assertEquals(13, KitPlanner.stoneFloor(new FakeFacts().give(Items.STONE_PICKAXE, 1), cfg));
        assertEquals(10, KitPlanner.stoneFloor(new FakeFacts().give(Items.IRON_PICKAXE, 1), cfg));
    }

    @Test
    public void aFurnaceOnTheGroundIsHeldForTheStoneFloor() {
        // standing next to us and coming back to the bag: 8 less to keep
        f.furnacePlaced = true;
        assertEquals(8, KitPlanner.stoneFloor(f, cfg));
        // and what is left to mine agrees: the planner skips the furnace need for it, so no 8 cobble for it either
        assertEquals(10, KitPlanner.stoneNeed(f, cfg));
        // a furnace in the bag AND one on the ground is still one furnace
        f.give(Items.FURNACE, 1);
        assertEquals(8, KitPlanner.stoneFloor(f, cfg));
        // the table never cost any cobble, so it changes nothing
        FakeFacts table = new FakeFacts();
        table.tablePlaced = true;
        assertEquals(16, KitPlanner.stoneFloor(table, cfg));
    }

    @Test
    public void stoneIsMinedAfterTheWoodAndBeforeTheCrafts() {
        f.give(Items.OAK_LOG, 15).give(Items.WOODEN_AXE, 1);
        List<KitNeed> gather = KitPlanner.gather(f, cfg, 8);
        assertEquals(List.of("cobblestone", "stone_pickaxe", "stone_sword", "furnace", "food"), names(gather));
        // the total is held plus the shortfall, same shape as the log need
        FakeFacts some = new FakeFacts().give(Items.OAK_LOG, 15).give(Items.WOODEN_AXE, 1).give(Items.COBBLESTONE, 5);
        assertEquals(new KitNeed("cobblestone", 18), find(KitPlanner.gather(some, cfg, 8), "cobblestone"));
        // enough of it: no gather entry, the crafts go straight in
        some.give(Items.COBBLESTONE, 13);
        assertEquals(List.of("stone_pickaxe", "stone_sword", "furnace", "food"), names(KitPlanner.gather(some, cfg, 8)));
        // the wood is still first when it is short
        assertEquals("log", KitPlanner.gather(new FakeFacts().give(Items.COBBLESTONE, 2), cfg, 8).get(0).catalogueName());
    }

    // ---- the split smelt (SmeltSplit)

    // the split's extra furnaces are never in the plan: the mine brings back the spare cobble, the split uses what is there
    @Test
    public void theSplitAsksNoCobble() {
        assertTrue(KitPlanner.ingotsNeeded(f, cfg) >= 27);
        assertEquals(18, KitPlanner.stoneNeed(f, cfg));
        assertEquals(16, KitPlanner.stoneFloor(f, cfg));
        try {
            f.smeltBatch = SmeltSplit.start(37, SmeltSplit.sizes(37, 3));
            assertEquals(18, KitPlanner.stoneNeed(f, cfg));
            assertEquals(16, KitPlanner.stoneFloor(f, cfg));
        } finally {
            SmeltSplit.clear();
        }
    }

    // the coal rounds per load: the loads the owed iron would get, then the loads the batch still has
    @Test
    public void smeltLoadsFollowTheOwedIronThenTheBatch() {
        assertEquals(3, KitPlanner.smeltLoads(f, cfg));
        // a blast furnace takes it all
        assertEquals(1, KitPlanner.smeltLoads(new FakeFacts().give(Items.BLAST_FURNACE, 1), cfg));
        // a leftover is one load, cooking ingots count as held
        int owed = KitPlanner.ingotsNeeded(f, cfg);
        assertEquals(1, KitPlanner.smeltLoads(new FakeFacts().give(Items.IRON_INGOT, owed - 15), cfg));
        assertEquals(1, KitPlanner.smeltLoads(new FakeFacts().cooking("iron_ingot", owed - 10, 60), cfg));
        try {
            SmeltSplit.Batch batch = SmeltSplit.start(37, SmeltSplit.sizes(37, 3));
            f.smeltBatch = batch;
            batch.loaded(0);
            assertEquals(2, KitPlanner.smeltLoads(f, cfg));
            batch.loaded(1);
            assertEquals(1, KitPlanner.smeltLoads(f, cfg));
        } finally {
            SmeltSplit.clear();
        }
    }

    @Test
    public void cobbleIsAGatheringNeedSoItNeverHoldsTheTable() {
        assertTrue(new KitNeed("cobblestone", 18).isGathering());
        assertFalse(new KitNeed("cobblestone", 18).isCraft());
        assertTrue(KitNeed.isCraftName("stone_pickaxe"));
    }

    @Test
    public void theIronPhaseNeverGathersTheStoneKitAgain() {
        // a worn pick in the iron phase is a just in time craft, not a trip
        FakeFacts raw = new FakeFacts().give(Items.RAW_IRON, 1);
        assertFalse(names(KitPlanner.plan(raw, cfg, 8)).contains("cobblestone"));
        assertFalse(names(KitPlanner.plan(f, cfg, 8)).contains("cobblestone"));
        FakeFacts worn = complete();
        worn.items.put(Items.STONE_PICKAXE, 0);
        worn.spent.put(Items.STONE_PICKAXE, 1);
        assertFalse(names(KitPlanner.plan(worn, cfg, 8)).contains("cobblestone"));
    }

    // ---- durable picks ----

    @Test
    public void gatherWantsASparePickAndPlanWantsOne() {
        f.give(Items.STONE_PICKAXE, 1).give(Items.OAK_LOG, 15);
        assertEquals(new KitNeed("stone_pickaxe", 2), find(KitPlanner.gather(f, cfg, 8), "stone_pickaxe"));
        assertEquals(null, find(KitPlanner.plan(f, cfg, 8), "stone_pickaxe"));
    }

    @Test
    public void anIronPickCoversTheWholeStonePickQuantity() {
        f.give(Items.IRON_PICKAXE, 1);
        assertEquals(null, find(KitPlanner.gather(f, cfg, 8), "stone_pickaxe"));
        assertEquals(null, find(KitPlanner.plan(f, cfg, 8), "stone_pickaxe"));
        FakeFacts diamond = new FakeFacts().give(Items.DIAMOND_PICKAXE, 1);
        assertEquals(null, find(KitPlanner.gather(diamond, cfg, 8), "stone_pickaxe"));
        // and the wooden pick it would have been made with is no wood we budget for
        assertTrue(KitPlanner.woodNeed(diamond, cfg, 8) < KitPlanner.woodNeed(new FakeFacts(), cfg, 8));
        // a stone pick is not an iron pick, though, the cover only goes upward
        assertTrue(names(KitPlanner.plan(new FakeFacts().give(Items.STONE_PICKAXE, 2), cfg, 8)).contains("iron_pickaxe"));
    }

    @Test
    public void aWornPickIsMadeAgainAboveTheOneInTheBag() {
        // the planner never sees the worn one in count(), but the catalogue does, so the target starts above it
        f.spent.put(Items.STONE_PICKAXE, 1);
        f.give(Items.OAK_LOG, 15);
        assertEquals(new KitNeed("stone_pickaxe", 3), find(KitPlanner.gather(f, cfg, 8), "stone_pickaxe"));
        assertEquals(new KitNeed("stone_pickaxe", 2), find(KitPlanner.plan(f, cfg, 8), "stone_pickaxe"));
        // one fresh next to it and the plan is happy, the gather still wants its spare
        f.give(Items.STONE_PICKAXE, 1);
        assertEquals(null, find(KitPlanner.plan(f, cfg, 8), "stone_pickaxe"));
        assertEquals(new KitNeed("stone_pickaxe", 3), find(KitPlanner.gather(f, cfg, 8), "stone_pickaxe"));
    }

    @Test
    public void aWornIronPickIsReplacedWithTheIngotsForIt() {
        FakeFacts worn = complete();
        worn.items.put(Items.IRON_PICKAXE, 0);
        worn.spent.put(Items.IRON_PICKAXE, 1);
        List<KitNeed> plan = KitPlanner.plan(worn, cfg, 8);
        assertEquals(List.of("iron_ingot", "iron_pickaxe"), names(plan));
        assertEquals(new KitNeed("iron_pickaxe", 2), find(plan, "iron_pickaxe"));
        assertEquals(3, find(plan, "iron_ingot").count());
    }

    @Test
    public void wearThresholdIsEightyFivePercentAndOnlyForPicks() {
        // stone: 131 uses, 111.35 is the line
        assertFalse(KitPlanner.wornOut(Items.STONE_PICKAXE, 111, 131));
        assertTrue(KitPlanner.wornOut(Items.STONE_PICKAXE, 112, 131));
        // iron: 250 uses, 212.5
        assertFalse(KitPlanner.wornOut(Items.IRON_PICKAXE, 212, 250));
        assertTrue(KitPlanner.wornOut(Items.IRON_PICKAXE, 213, 250));
        // wooden: 59 uses, 50.15
        assertFalse(KitPlanner.wornOut(Items.WOODEN_PICKAXE, 50, 59));
        assertTrue(KitPlanner.wornOut(Items.WOODEN_PICKAXE, 51, 59));
        assertFalse(KitPlanner.wornOut(Items.STONE_PICKAXE, 0, 131));
        // swords and axes wear out of the fight in their own time, and diamond is not ours to second guess
        assertFalse(KitPlanner.wornOut(Items.STONE_SWORD, 130, 131));
        assertFalse(KitPlanner.wornOut(Items.IRON_AXE, 249, 250));
        assertFalse(KitPlanner.wornOut(Items.DIAMOND_PICKAXE, 1500, 1561));
        // something that cannot break is not worn
        assertFalse(KitPlanner.wornOut(Items.STONE_PICKAXE, 5, 0));
    }

    // ---- diamonds in the bag (DiamondSpend)

    @Test
    public void underThreeDiamondsEveryPlanIsTheOneItWas() {
        f.foodUnits = 40;
        List<KitNeed> bare = KitPlanner.plan(f, cfg, 8);
        for (int d = 1; d < DiamondSpend.MIN; d++) {
            FakeFacts rich = new FakeFacts().give(Items.DIAMOND, d);
            rich.foodUnits = 40;
            assertEquals(bare, KitPlanner.plan(rich, cfg, 8));
        }
        assertEquals(List.of(), KitPlanner.plan(complete(), cfg, 8));
        assertEquals(List.of(), KitPlanner.plan(complete().give(Items.DIAMOND, 2), cfg, 8));
    }

    @Test
    public void twoDiamondsBuyTheSwordInPlaceOfTheIronOneOnceThePickIsDiamond() {
        f.foodUnits = 40;
        f.give(Items.DIAMOND, 2).give(Items.DIAMOND_PICKAXE, 1);
        List<String> n = names(KitPlanner.plan(f, cfg, 8));
        assertTrue(n.toString(), n.contains("diamond_sword"));
        assertFalse(n.toString(), n.contains("iron_sword"));
    }

    @Test
    public void switchedOffTheDiamondsSitInTheBag() {
        cfg.diamondSpend = false;
        // the dig for the pick has its own switch and its own tests below, with it on 40 diamonds are one pick craft
        cfg.diamondPick = false;
        f.foodUnits = 40;
        List<KitNeed> bare = KitPlanner.plan(f, cfg, 8);
        FakeFacts rich = new FakeFacts().give(Items.DIAMOND, 40);
        rich.foodUnits = 40;
        assertEquals(bare, KitPlanner.plan(rich, cfg, 8));
        assertEquals(List.of(), KitPlanner.plan(complete().give(Items.DIAMOND, 40), cfg, 8));
    }

    // ---- the diamond pick dig (addDiamondPick)

    @Test
    public void theDigComesAfterTheIronCraftsAndBeforeTheWool() {
        // the essentials in hand (pick, light, two buckets), the rest of the iron kit still owed
        f.give(Items.STONE_PICKAXE, 1).give(Items.STONE_SWORD, 1).give(Items.IRON_PICKAXE, 1).give(Items.FLINT_AND_STEEL, 1)
                .give(Items.BUCKET, 2).give(Items.OAK_LOG, 20);
        f.foodUnits = 40;
        List<KitNeed> plan = KitPlanner.plan(f, cfg, 8);
        List<String> n = names(plan);
        int dig = n.indexOf(KitPlanner.DIAMOND);
        assertEquals(new KitNeed(KitPlanner.DIAMOND, 3), plan.get(dig));
        for (String before : List.of("iron_ingot", "iron_sword", "shield", "shears", "ladder", "iron_chestplate", "iron_boots")) {
            assertTrue(before + " " + n, n.indexOf(before) < dig);
        }
        assertTrue(n.toString(), dig < n.indexOf("wool"));
        // diamondSpend crafts the pick once the three are in, so the plan never names it while we dig
        assertFalse(n.contains("diamond_pickaxe"));
    }

    @Test
    public void theDigWaitsForTheEssentials() {
        FakeFacts bag = complete();
        bag.diamondHuntGaveUp = false;
        assertTrue(names(KitPlanner.plan(bag, cfg, 8)).contains(KitPlanner.DIAMOND));
        // one bucket short: the bucket first, then the dig. same for the light and for the iron pick (nothing else mines a diamond)
        bag.items.put(Items.BUCKET, 1);
        assertEquals(List.of("iron_ingot", "bucket"), names(KitPlanner.plan(bag, cfg, 8)));
        bag.items.put(Items.BUCKET, 2);
        bag.items.remove(Items.FLINT_AND_STEEL);
        assertFalse(names(KitPlanner.plan(bag, cfg, 8)).contains(KitPlanner.DIAMOND));
        bag.give(Items.FLINT_AND_STEEL, 1);
        bag.items.remove(Items.IRON_PICKAXE);
        assertFalse(names(KitPlanner.plan(bag, cfg, 8)).contains(KitPlanner.DIAMOND));
    }

    @Test
    public void aWholeKitStillOwesTheDig() {
        FakeFacts bag = complete();
        bag.diamondHuntGaveUp = false;
        assertEquals(List.of(new KitNeed(KitPlanner.DIAMOND, 3)), KitPlanner.plan(bag, cfg, 8));
        // two in the bag is still "hold three"
        assertEquals(List.of(new KitNeed(KitPlanner.DIAMOND, 3)), KitPlanner.plan(bag.give(Items.DIAMOND, 2), cfg, 8));
        // the wool goes behind it
        bag.items.put(Items.WOOL.white(), 0);
        assertEquals(List.of(KitPlanner.DIAMOND, "wool"), names(KitPlanner.plan(bag, cfg, 8)));
    }

    @Test
    public void withDiamondSpendOffTheDigIsTheDiamondsThenThePick() {
        cfg.diamondSpend = false;
        FakeFacts bag = complete();
        bag.diamondHuntGaveUp = false;
        assertEquals(List.of(new KitNeed(KitPlanner.DIAMOND, 3), new KitNeed("diamond_pickaxe", 1)), KitPlanner.plan(bag, cfg, 8));
        bag.give(Items.DIAMOND, 3);
        assertEquals(List.of(new KitNeed("diamond_pickaxe", 1)), KitPlanner.plan(bag, cfg, 8));
        bag.give(Items.DIAMOND_PICKAXE, 1);
        assertEquals(List.of(), KitPlanner.plan(bag, cfg, 8));
    }

    @Test
    public void threeDiamondsAndNoDiamondPickPutDiamondSpendsPickFirst() {
        FakeFacts bag = complete().give(Items.DIAMOND, 3).give(Items.STICK, 2);
        bag.diamondHuntGaveUp = false;
        bag.items.remove(Items.SHEARS);
        List<KitNeed> plan = KitPlanner.plan(bag, cfg, 8);
        // the pick ahead of the iron the shears still want, and no dig: the three it would dig for are in the bag
        assertEquals(new KitNeed("diamond_pickaxe", 1), plan.get(0));
        assertEquals(List.of("diamond_pickaxe", "iron_ingot", "shears"), names(plan));
    }

    @Test
    public void noDigOnceGivenUpOrWithADiamondPickOrSwitchedOff() {
        FakeFacts bag = complete();
        assertEquals("complete() has it written off", List.of(), KitPlanner.plan(bag, cfg, 8));
        bag.diamondHuntGaveUp = false;
        assertEquals(List.of(new KitNeed(KitPlanner.DIAMOND, 3)), KitPlanner.plan(bag, cfg, 8));
        assertEquals("a diamond pick", List.of(), KitPlanner.plan(complete().give(Items.DIAMOND_PICKAXE, 1), cfg, 8));
        FakeFacts netherite = complete().give(Items.NETHERITE_PICKAXE, 1);
        netherite.diamondHuntGaveUp = false;
        assertEquals("netherite is a diamond pick too", List.of(), KitPlanner.plan(netherite, cfg, 8));
        cfg.diamondPick = false;
        assertEquals("switched off", List.of(), KitPlanner.plan(bag, cfg, 8));
    }

    @Test
    public void theDigIsAMineAndNeverHoldsATable() {
        assertTrue(new KitNeed(KitPlanner.DIAMOND, 3).isGathering());
        assertFalse(new KitNeed(KitPlanner.DIAMOND, 3).isCraft());
    }

    @Test
    public void threeDiamondsAndAnIronPickMakeTheDiamondPickBeforeAnyIronCraft() {
        f.give(Items.IRON_PICKAXE, 1).give(Items.DIAMOND, 3).give(Items.STICK, 2);
        f.foodUnits = 40;
        List<String> n = names(KitPlanner.plan(f, cfg, 8));
        assertTrue(n.toString(), n.indexOf("diamond_pickaxe") >= 0);
        assertTrue(n.indexOf("diamond_pickaxe") < n.indexOf("iron_ingot"));
        assertTrue(n.indexOf("diamond_pickaxe") < n.indexOf("iron_sword"));
        // the iron pick was already in the bag, so the iron kit is what it was
        assertEquals(39 - 3, find(KitPlanner.plan(f, cfg, 8), "iron_ingot").count());
        assertEquals(new KitNeed("diamond_pickaxe", 1), find(KitPlanner.plan(f, cfg, 8), "diamond_pickaxe"));
    }

    @Test
    public void theDiamondPickTakesTheIronPicksPlaceAndItsIngots() {
        f.give(Items.DIAMOND, 3);
        List<KitNeed> plan = KitPlanner.plan(f, cfg, 8);
        assertNull(find(plan, "iron_pickaxe"));
        assertEquals(39 - 3, find(plan, "iron_ingot").count());
        assertEquals(36, KitPlanner.ingotsNeeded(f, cfg));
        // nothing was held back by the swap: the same bag without diamonds still asks for the iron one
        assertEquals(new KitNeed("iron_pickaxe", 1), find(KitPlanner.plan(new FakeFacts(), cfg, 8), "iron_pickaxe"));
    }

    @Test
    public void rawIronForAnEarlyPickIsNotSmeltedForAPickWeAreAboutToOutclass() {
        // the pick drops out of the early batch, the chestplate, shield and shears are still worth smelting early (the first stage is 9)
        FakeFacts raw = new FakeFacts().give(Items.RAW_IRON, 5).give(Items.DIAMOND, 3);
        assertFalse(EarlyIronPick.owed(raw, cfg).contains("iron_pickaxe"));
        assertEquals(9, EarlyIronPick.batch(raw, cfg));
        assertFalse(EarlyIronPick.due(raw, cfg, KitPlanner.ingotsNeeded(raw, cfg)));
        // and without the diamonds it is the same first stage, the pick waits for the second
        assertTrue(EarlyIronPick.due(new FakeFacts().give(Items.RAW_IRON, 9), cfg, 40));
    }

    @Test
    public void aDiamondChestplateIsNotSmeltedFor() {
        // the pick is already diamond, so 8 diamonds are the chestplate
        f.give(Items.DIAMOND_PICKAXE, 1).give(Items.DIAMOND, 8);
        List<KitNeed> plan = KitPlanner.plan(f, cfg, 8);
        assertNull(find(plan, "iron_chestplate"));
        assertEquals(new KitNeed("diamond_chestplate", 1), find(plan, "diamond_chestplate"));
        // pick held, so 3 off already; the chestplate is 8 more off the 39
        assertEquals(39 - 3 - 8, find(plan, "iron_ingot").count());
        assertEquals(39 - 3 - 8, KitPlanner.ingotsNeeded(f, cfg));
        assertEquals(List.of("diamond_chestplate"), names(plan).stream().filter(s -> s.startsWith("diamond_")).toList());
    }

    @Test
    public void diamondCraftsComeBeforeTheIronOnesAndAfterTheStarter() {
        f.give(Items.DIAMOND, 11).give(Items.STICK, 2);
        f.foodUnits = 40;
        List<String> n = names(KitPlanner.plan(f, cfg, 8));
        assertEquals(List.of("diamond_pickaxe", "diamond_chestplate"), n.stream().filter(s -> s.startsWith("diamond_")).toList());
        assertTrue(n.indexOf("stone_sword") < n.indexOf("diamond_pickaxe"));
        assertTrue(n.indexOf("diamond_chestplate") < n.indexOf("iron_ingot"));
        assertEquals(n.indexOf("diamond_pickaxe") + 1, n.indexOf("diamond_chestplate"));
    }

    @Test
    public void aDiamondToolWithNoSticksWaitsBehindTheIronCraftsButArmorDoesNot() {
        // no sticks, planks or logs: the pick would go and chop its own wood from the middle of the mine
        f.give(Items.DIAMOND, 11);
        f.foodUnits = 40;
        List<String> n = names(KitPlanner.plan(f, cfg, 8));
        assertTrue(n.indexOf("diamond_chestplate") < n.indexOf("iron_ingot"));
        assertTrue(n.indexOf("diamond_pickaxe") > n.indexOf("iron_boots"));
        assertTrue(n.toString(), n.indexOf("diamond_pickaxe") < n.indexOf("wool"));
        // a log, two planks or two sticks and it is back at the front
        for (FakeFacts wood : List.of(new FakeFacts().give(Items.OAK_LOG, 1), new FakeFacts().give(Items.OAK_PLANKS, 2),
                new FakeFacts().give(Items.STICK, 2))) {
            wood.give(Items.DIAMOND, 11);
            wood.foodUnits = 40;
            List<String> m = names(KitPlanner.plan(wood, cfg, 8));
            assertTrue(m.toString(), m.indexOf("diamond_pickaxe") < m.indexOf("iron_ingot"));
        }
    }

    @Test
    public void hungerStillLeadsTheDiamonds() {
        f.give(Items.DIAMOND, 11).give(Items.STONE_PICKAXE, 2).give(Items.STONE_SWORD, 1).give(Items.FURNACE, 1);
        f.foodUnits = 5;
        List<KitNeed> plan = KitPlanner.plan(f, cfg, 8);
        assertEquals(KitNeed.FOOD, plan.get(0).catalogueName());
        assertTrue(names(plan).contains("diamond_pickaxe"));
    }

    @Test
    public void ironAlreadyWornIsUpgradedAndTheNewPieceIsWornAfterTheCraft() {
        FakeFacts bag = complete().give(Items.DIAMOND_PICKAXE, 1).give(Items.DIAMOND, 8);
        List<KitNeed> plan = KitPlanner.plan(bag, cfg, 8);
        assertEquals(List.of(new KitNeed("diamond_chestplate", 1)), plan);
        // crafted: the diamonds are gone, the new piece is in the bag and the iron one still on
        bag.items.put(Items.DIAMOND, 0);
        bag.give(Items.DIAMOND_CHESTPLATE, 1);
        assertEquals(List.of(Items.DIAMOND_CHESTPLATE), KitPlanner.toEquip(bag, cfg));
        assertEquals(List.of(new KitNeed(KitNeed.EQUIP_ARMOR, 1)), KitPlanner.plan(bag, cfg, 8));
        // worn: iron sits in the bag below it and asks for nothing, the plan is empty and stays that way
        bag.worn.remove(Items.IRON_CHESTPLATE);
        bag.worn.add(Items.DIAMOND_CHESTPLATE);
        assertEquals(List.of(), KitPlanner.toEquip(bag, cfg));
        assertEquals(List.of(), KitPlanner.plan(bag, cfg, 8));
    }

    @Test
    public void theChoiceHoldsStillThroughACraft() {
        // the pick goes into the grid (MinecraftFacts counts it, so the diamonds never dip), comes out as a pick, and the
        // chestplate that was behind it is the plan with no wobble in between
        FakeFacts bag = complete().give(Items.DIAMOND, 11);
        bag.items.remove(Items.IRON_PICKAXE);
        List<String> before = names(KitPlanner.plan(bag, cfg, 8)).stream().filter(s -> s.startsWith("diamond_")).toList();
        assertEquals(List.of("diamond_pickaxe", "diamond_chestplate"), before);
        bag.items.put(Items.DIAMOND, 8);
        bag.give(Items.DIAMOND_PICKAXE, 1);
        assertEquals(List.of("diamond_chestplate"), names(KitPlanner.plan(bag, cfg, 8)));
    }

    @Test
    public void goldArrivingAfterADiamondHelmetGoesOnTheBootsInstead() {
        FakeFacts bag = complete().give(Items.DIAMOND_HELMET, 1).give(Items.GOLD_INGOT, 5);
        bag.worn.remove(Items.IRON_HELMET);
        bag.worn.add(Items.DIAMOND_HELMET);
        assertEquals(List.of(new KitNeed("golden_boots", 1)), PiglinGold.gate(bag));
        assertTrue(PiglinGold.swap(bag, KitPlanner.armorNames(cfg)).contains("golden_boots"));
        assertTrue(PiglinGold.swap(bag, KitPlanner.armorNames(cfg)).contains("iron_helmet"));
        // iron on the head and the same gold: the helmet, like it always was
        FakeFacts iron = complete().give(Items.GOLD_INGOT, 5);
        assertEquals(List.of(new KitNeed("golden_helmet", 1)), PiglinGold.gate(iron));
        // diamond boots as well: nothing left to protect, the helmet it is
        bag.give(Items.DIAMOND_BOOTS, 1);
        assertEquals(List.of(new KitNeed("golden_helmet", 1)), PiglinGold.gate(bag));
    }

    @Test
    public void goldInTheHelmetSlotKeepsTheDiamondsOffIt() {
        FakeFacts bag = complete().give(Items.DIAMOND_PICKAXE, 1).give(Items.DIAMOND, 5).give(Items.GOLDEN_HELMET, 1);
        bag.items.remove(Items.IRON_HELMET);
        bag.worn.remove(Items.IRON_HELMET);
        bag.worn.add(Items.GOLDEN_HELMET);
        // 5 buys exactly the helmet, and the slot is the piglin piece
        assertFalse(names(KitPlanner.plan(bag, cfg, 8)).contains("diamond_helmet"));
    }

    // ---- toEquip over a tier

    @Test
    public void aBetterPieceInTheBagGoesOverWornIron() {
        f.give(Items.IRON_CHESTPLATE, 1).give(Items.DIAMOND_CHESTPLATE, 1);
        f.worn.add(Items.IRON_CHESTPLATE);
        assertEquals(List.of(Items.DIAMOND_CHESTPLATE), KitPlanner.toEquip(f, cfg));
        // and the iron one now in the bag is not a reason to swap back
        f.worn.clear();
        f.worn.add(Items.DIAMOND_CHESTPLATE);
        assertEquals(List.of(), KitPlanner.toEquip(f, cfg));
    }

    @Test
    public void netherriteGoesOverDiamondAndWornDiamondBeatsABagOfIron() {
        f.give(Items.DIAMOND_BOOTS, 1).give(Items.NETHERITE_BOOTS, 1).give(Items.IRON_BOOTS, 1);
        f.worn.add(Items.DIAMOND_BOOTS);
        assertEquals(List.of(Items.NETHERITE_BOOTS), KitPlanner.toEquip(f, cfg));
        f.worn.clear();
        f.worn.add(Items.NETHERITE_BOOTS);
        assertEquals(List.of(), KitPlanner.toEquip(f, cfg));
        FakeFacts lower = new FakeFacts().give(Items.DIAMOND_LEGGINGS, 1).give(Items.IRON_LEGGINGS, 1);
        lower.worn.add(Items.DIAMOND_LEGGINGS);
        assertEquals(List.of(), KitPlanner.toEquip(lower, cfg));
    }

    @Test
    public void spareShieldsAreIronAndPlanksInTheNormalBudget() {
        int base = KitPlanner.ingotsNeeded(f, cfg);
        int planks = WoodBudget.owed(f, cfg, 8, true);
        cfg.netherSpareShields = 2;
        // 3 shields, an ingot each
        assertEquals(base + 2, KitPlanner.ingotsNeeded(f, cfg));
        // the first one alone until it exists, the spares come after it
        assertEquals(new KitNeed("shield", 1), find(KitPlanner.plan(f, cfg, 8), "shield"));
        assertEquals(new KitNeed("shield", 2), find(KitPlanner.plan(new FakeFacts().give(Items.SHIELD, 1), cfg, 8), "shield"));
        // 12 more planks is three logs
        assertEquals(planks + 3, WoodBudget.owed(f, cfg, 8, true));
        // holding the first one and both spares asks for nothing more of them
        FakeFacts shields = new FakeFacts().give(Items.SHIELD, 3);
        cfg.netherSpareShields = 0;
        cfg.netherSpareSwords = 0;
        int without = KitPlanner.ingotsNeeded(shields, cfg);
        cfg.netherSpareShields = 2;
        assertEquals(without, KitPlanner.ingotsNeeded(shields, cfg));
        assertNull(find(KitPlanner.plan(shields, cfg, 8), "shield"));
    }

    // three shields in one craft want 18 planks and two kinds of wood don't stack, so the need is always just the next shield
    @Test
    public void theShieldTargetIsNeverMoreThanOneOverWhatWeHold() {
        cfg.netherSpareShields = 3;
        for (int held = 0; held < 4; held++) {
            FakeFacts bag = new FakeFacts().give(Items.SHIELD, held);
            KitNeed need = find(KitPlanner.plan(bag, cfg, 8), "shield");
            assertEquals(new KitNeed("shield", held + 1), need);
        }
        assertNull(find(KitPlanner.plan(new FakeFacts().give(Items.SHIELD, 4), cfg, 8), "shield"));
    }

    @Test
    public void aSpareSwordIsIronAndSticksInTheNormalBudget() {
        int base = KitPlanner.ingotsNeeded(f, cfg);
        int wood = WoodBudget.owed(f, cfg, 8, true);
        cfg.netherSpareSwords = 1;
        // 2 swords, 2 ingots each
        assertEquals(base + 2, KitPlanner.ingotsNeeded(f, cfg));
        // the first one alone until it exists, the spare comes after it
        assertEquals(new KitNeed("iron_sword", 1), find(KitPlanner.plan(f, cfg, 8), "iron_sword"));
        assertEquals(new KitNeed("iron_sword", 2), find(KitPlanner.plan(new FakeFacts().give(Items.IRON_SWORD, 1), cfg, 8), "iron_sword"));
        assertTrue(WoodBudget.owed(f, cfg, 8, true) >= wood);
        // holding both asks for nothing more of them
        FakeFacts swords = new FakeFacts().give(Items.IRON_SWORD, 2);
        assertNull(find(KitPlanner.plan(swords, cfg, 8), "iron_sword"));
        // 0 = off, the kit is the one sword again
        cfg.netherSpareSwords = 0;
        assertEquals(base, KitPlanner.ingotsNeeded(f, cfg));
        assertNull(find(KitPlanner.plan(new FakeFacts().give(Items.IRON_SWORD, 1), cfg, 8), "iron_sword"));
    }

    @Test
    public void aDiamondSwordInTheBagOwesNoIronSpare() {
        cfg.netherSpareSwords = 1;
        FakeFacts diamond = new FakeFacts().give(Items.DIAMOND_SWORD, 1);
        assertNull(find(KitPlanner.plan(diamond, cfg, 8), "iron_sword"));
        assertEquals(KitPlanner.ingotsNeeded(diamond, cfg), noSpareIngots(diamond));
        assertEquals(WoodBudget.owed(diamond, cfg, 8, true), noSpareWood(diamond));
    }

    private int noSpareIngots(FakeFacts bag) {
        cfg.netherSpareSwords = 0;
        int n = KitPlanner.ingotsNeeded(bag, cfg);
        cfg.netherSpareSwords = 1;
        return n;
    }

    private int noSpareWood(FakeFacts bag) {
        cfg.netherSpareSwords = 0;
        int n = WoodBudget.owed(bag, cfg, 8, true);
        cfg.netherSpareSwords = 1;
        return n;
    }
}
