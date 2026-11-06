package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.util.helpers.FuelPolicy;
import adris.altoclef.util.helpers.ItemFixture;
import adris.altoclef.util.helpers.StationChoice;
import adris.altoclef.util.helpers.StationHook;
import baritone.api.utils.Dimension;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CookGateTest {
    private static final KitNeed ORE = new KitNeed("iron_ingot", 39);
    private static final KitNeed CRAFT = new KitNeed("iron_pickaxe", 1);
    private static final KitNeed COOK = new KitNeed(KitNeed.COOK_FURNACE, CookGate.MIN_RAW);

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
        // no iron owed unless a test says so: while it is, the furnace is the iron's and meat only goes in a smoker
        cfg.ironKit.clear();
        cfg.armorPlan = OverworldConfig.ArmorPlan.NONE;
        f = new FakeFacts();
    }

    private KitNeed need() {
        return CookGate.need(f, cfg, 8);
    }

    private static List<String> names(List<KitNeed> needs) {
        return needs.stream().map(KitNeed::catalogueName).toList();
    }

    @Test
    public void rawMeatAndAFreeFurnaceIsACook() {
        f.give(Items.MUTTON, 6).give(Items.COAL, 2);
        f.furnacePlaced = true;
        assertEquals(COOK, need());
        // fish and the rest count, a raw potato does not
        FakeFacts fish = new FakeFacts().give(Items.SALMON, 3).give(Items.COAL, 2);
        fish.furnacePlaced = true;
        assertEquals(COOK, CookGate.need(fish, cfg, 8));
        FakeFacts spuds = new FakeFacts().give(Items.POTATO, 9).give(Items.COAL, 2);
        spuds.furnacePlaced = true;
        assertNull(CookGate.need(spuds, cfg, 8));
    }

    @Test
    public void aSmokerWinsOverAFurnace() {
        f.give(Items.MUTTON, 6).give(Items.COAL, 2);
        f.furnacePlaced = true;
        f.smokerPlaced = true;
        assertEquals(KitNeed.COOK_SMOKER, need().catalogueName());
        // one in the bag counts too
        FakeFacts bag = new FakeFacts().give(Items.MUTTON, 6).give(Items.COAL, 2).give(Items.SMOKER, 1);
        assertEquals(KitNeed.COOK_SMOKER, CookGate.need(bag, cfg, 8).catalogueName());
    }

    // WorkbenchRules.cookInSmoker: a smoker of ours standing near or in the bag is where the meat goes, whatever furnace is about
    @Test
    public void aSmokerStandingNearBeatsAFreeFurnaceStandingNear() {
        f.give(Items.MUTTON, 6).give(Items.COAL, 2);
        f.furnacePlaced = true;
        f.smokerPlaced = true;
        assertEquals(CookGate.Station.SMOKER, CookGate.station(f, cfg, true));
        assertEquals(KitNeed.COOK_SMOKER, need().catalogueName());
        // and with only a handful, the smoker is still the one standing there
        FakeFacts few = new FakeFacts().give(Items.RABBIT, 2).give(Items.COAL, 2);
        few.furnacePlaced = true;
        few.smokerPlaced = true;
        assertEquals(CookGate.Station.SMOKER, CookGate.station(few, cfg, false));
    }

    @Test
    public void aSmokerInTheBagBeatsAFurnaceStandingNear() {
        f.give(Items.MUTTON, 6).give(Items.COAL, 2).give(Items.SMOKER, 1);
        f.furnacePlaced = true;
        assertEquals(CookGate.Station.SMOKER, CookGate.station(f, cfg, true));
        assertEquals(KitNeed.COOK_SMOKER, need().catalogueName());
        // the furnace in the bag loses to it as well
        FakeFacts both = new FakeFacts().give(Items.MUTTON, 6).give(Items.COAL, 2).give(Items.SMOKER, 1).give(Items.FURNACE, 1);
        assertEquals(CookGate.Station.SMOKER, CookGate.station(both, cfg, true));
    }

    // the smoker in the bag is only worth putting down for a real pile, or when a station is already standing to cook the little
    // there is (the smoker is the faster of the two). with nothing standing and a handful it is no cook at all
    @Test
    public void aSmokerInTheBagWithLittleMeatNeedsAStationAlreadyStanding() {
        FakeFacts few = new FakeFacts().give(Items.RABBIT, 2).give(Items.COAL, 2).give(Items.SMOKER, 1);
        few.furnacePlaced = true;
        assertEquals(CookGate.Station.SMOKER, CookGate.station(few, cfg, false));
        assertEquals(KitNeed.COOK_SMOKER, CookGate.need(few, cfg, 8).catalogueName());
        // nothing standing at all: the bag smoker alone is not a trip for two rabbit
        FakeFacts none = new FakeFacts().give(Items.RABBIT, 2).give(Items.COAL, 2).give(Items.SMOKER, 1);
        assertEquals(CookGate.Station.NONE, CookGate.station(none, cfg, false));
        assertNull(CookGate.need(none, cfg, 8));
        // the same bag with a real pile is a cook
        FakeFacts pile = new FakeFacts().give(Items.MUTTON, 4).give(Items.COAL, 2).give(Items.SMOKER, 1);
        assertEquals(CookGate.Station.SMOKER, CookGate.station(pile, cfg, true));
    }

    // a cook that picked the furnace and is half way through loading it is not talked into a smoker that shows up in the bag or on
    // the ground, the meat is already in the slot
    @Test
    public void aCookAlreadyRunningOnTheFurnaceKeepsItsFurnace() {
        f.give(Items.MUTTON, 6).give(Items.COAL, 2).give(Items.SMOKER, 1);
        f.smokerPlaced = true;
        f.furnacePlaced = true;
        f.cookStation = "furnace";
        assertEquals(CookGate.Station.FURNACE, CookGate.station(f, cfg, true));
        assertEquals(KitNeed.COOK_FURNACE, need().catalogueName());
        // the other way round too
        f.cookStation = "smoker";
        assertEquals(CookGate.Station.SMOKER, CookGate.station(f, cfg, true));
    }

    @Test
    public void aFurnaceInTheBagIsAFurnace() {
        f.give(Items.BEEF, 4).give(Items.COAL, 1).give(Items.FURNACE, 1);
        assertEquals(KitNeed.COOK_FURNACE, need().catalogueName());
    }

    @Test
    public void nothingToCookIsNoNeed() {
        f.give(Items.COAL, 2).give(Items.COOKED_MUTTON, 9);
        f.furnacePlaced = true;
        assertNull(need());
    }

    @Test
    public void aHandfulOnlyCooksWhereAStationAlreadyStands() {
        // two rabbit and nothing built: not worth a smoker
        f.give(Items.RABBIT, 2).give(Items.COAL, 2).give(Items.COBBLESTONE, 40).give(Items.OAK_LOG, 8);
        assertNull(need());
        // with one standing it is the last thing before the nether
        f.furnacePlaced = true;
        assertEquals(COOK, need());
    }

    @Test
    public void aHandfulDoesNotPutTheBagFurnaceDown() {
        // place, cook, walk back, pick up, all for two rabbit
        f.give(Items.RABBIT, 2).give(Items.COAL, 2).give(Items.FURNACE, 1);
        assertNull(need());
        f.give(Items.SMOKER, 1);
        assertNull(need());
        // a smoker standing is free though
        f.smokerPlaced = true;
        assertEquals(KitNeed.COOK_SMOKER, need().catalogueName());
    }

    @Test
    public void aRunningCookKeepsItsStation() {
        // the smoker craft ate the logs and the cobble that picked "smoker": still a smoker, not a furnace or nothing
        f.give(Items.MUTTON, 6).give(Items.COAL, 2);
        f.cookStation = "smoker";
        assertEquals(KitNeed.COOK_SMOKER, need().catalogueName());
        f.cookStation = "furnace";
        assertEquals(KitNeed.COOK_FURNACE, need().catalogueName());
        // but never onto iron, that one input slot is still the iron's
        f.cooking("iron_ingot", 12, 60);
        assertNull(need());
    }

    @Test
    public void theSmokersLogsAreNotFuel() {
        // the whole kit done, so the reserve keeps no wood and every log may burn (1.5 smelts each)
        FakeFacts wood = new FakeFacts();
        for (net.minecraft.world.item.Item i : new net.minecraft.world.item.Item[]{Items.WOODEN_AXE, Items.STONE_PICKAXE, Items.STONE_SWORD,
                Items.IRON_PICKAXE, Items.IRON_SWORD, Items.FLINT_AND_STEEL, Items.SHIELD, Items.SHEARS, Items.FURNACE}) {
            wood.give(i, 1);
        }
        wood.give(Items.BUCKET, 2).give(Items.LADDER, 3).give(Items.OAK_LOG, 8);
        assertEquals(12, CookGate.fuelSmelts(wood, cfg, 0));
        // nine mutton: twelve smelts of logs looks like enough, but five logs (four and the table's) are about to be a smoker,
        // and the three left burn for four
        wood.give(Items.MUTTON, 9).give(Items.COBBLESTONE, 60);
        wood.cooking("iron_ingot", 12, 60);
        assertEquals(CookGate.Station.SMOKER, CookGate.station(wood, cfg, true));
        assertNull(CookGate.need(wood, cfg, 0));
        // with a coal it is fine
        wood.give(Items.COAL, 1);
        assertEquals(KitNeed.COOK_SMOKER, CookGate.need(wood, cfg, 0).catalogueName());
    }

    @Test
    public void aSmokerWithoutATableCostsALogMore() {
        // four logs is the smoker, the table to craft it on is the fifth
        f.give(Items.MUTTON, 6).give(Items.COAL, 2).give(Items.OAK_LOG, 4).give(Items.COBBLESTONE, 60);
        f.cooking("iron_ingot", 12, 60);
        assertNull(need());
        f.tablePlaced = true;
        assertEquals(KitNeed.COOK_SMOKER, need().catalogueName());
    }

    @Test
    public void enoughMeatBuildsAStationButNotOutOfTheStoneFloor() {
        // the stone pick still wants its cobble: the furnace and the tools come first
        f.give(Items.MUTTON, 6).give(Items.COAL, 2).give(Items.OAK_LOG, 8).give(Items.COBBLESTONE, 10);
        assertNull(need());
        // plenty over the floor: a smoker (logs for it, cobble for its furnace)
        f.give(Items.COBBLESTONE, 40);
        assertEquals(KitNeed.COOK_SMOKER, need().catalogueName());
        // no logs for a smoker, so a furnace
        FakeFacts nologs = new FakeFacts().give(Items.MUTTON, 6).give(Items.COAL, 2).give(Items.COBBLESTONE, 50);
        assertEquals(KitNeed.COOK_FURNACE, CookGate.need(nologs, cfg, 8).catalogueName());
    }

    @Test
    public void meatQueuesBehindIronInTheOnlyFurnace() {
        f.give(Items.MUTTON, 6).give(Items.COAL, 2);
        f.furnacePlaced = true;
        f.cooking("iron_ingot", 12, 60);
        // one input slot, loading meat would swap the ore back out
        assertNull(need());
        // a smoker (even one we still have to make) is the way round
        f.give(Items.OAK_LOG, 8).give(Items.COBBLESTONE, 60);
        assertEquals(KitNeed.COOK_SMOKER, need().catalogueName());
        // and a smoker that is already there is free
        FakeFacts standing = new FakeFacts().give(Items.MUTTON, 6).give(Items.COAL, 2);
        standing.furnacePlaced = true;
        standing.smokerPlaced = true;
        standing.cooking("iron_ingot", 12, 60);
        assertEquals(KitNeed.COOK_SMOKER, CookGate.need(standing, cfg, 8).catalogueName());
    }

    @Test
    public void foodAlreadyCookingBlocksTheNextBatch() {
        f.give(Items.MUTTON, 3).give(Items.PORKCHOP, 3).give(Items.COAL, 2);
        f.smokerPlaced = true;
        f.cookingFood("cooked_mutton", 6, 6, 30);
        assertNull(need());
    }

    @Test
    public void noFuelNoCook() {
        f.give(Items.MUTTON, 6);
        f.furnacePlaced = true;
        assertNull(need());
        // coal is 8 smelts, six meat is one
        f.give(Items.COAL, 1);
        assertEquals(COOK, need());
        // wood only burns above what the kit keeps for crafting: a handful of logs do not count while the wooden tools are owed
        FakeFacts wood = new FakeFacts().give(Items.MUTTON, 6).give(Items.OAK_LOG, 1);
        wood.furnacePlaced = true;
        assertNull(CookGate.need(wood, cfg, 8));
    }

    @Test
    public void notInTheNetherAndNotAfterGivingUp() {
        f.give(Items.MUTTON, 6).give(Items.COAL, 2);
        f.furnacePlaced = true;
        f.cookSuspended = true;
        assertNull(need());
        f.cookSuspended = false;
        f.dimension = Dimension.NETHER;
        assertNull(need());
    }

    @Test
    public void theCookIsTheLastNeedOfThePlan() {
        // this is the "never leaves the overworld raw" half: IRON ends when the plan is empty, and it is not
        f.give(Items.MUTTON, 6).give(Items.COAL, 2).give(Items.OAK_LOG, 30);
        f.furnacePlaced = true;
        List<KitNeed> plan = KitPlanner.plan(f, cfg, 8);
        assertEquals(KitNeed.COOK_FURNACE, plan.get(plan.size() - 1).catalogueName());
        assertEquals(1, names(plan).stream().filter(n -> n.startsWith("cook")).count());
        // GATHER does not wait out the cook, IRON starts it on the surface and collects it on the way
        assertTrue(names(KitPlanner.gather(f, cfg, 8)).stream().noneMatch(n -> n.startsWith("cook")));
        // and cooked it is gone from both
        FakeFacts cooked = new FakeFacts().give(Items.COOKED_MUTTON, 6).give(Items.COAL, 2).give(Items.OAK_LOG, 30);
        cooked.furnacePlaced = true;
        assertTrue(names(KitPlanner.plan(cooked, cfg, 8)).stream().noneMatch(n -> n.startsWith("cook")));
    }

    @Test
    public void aFurnaceCookingTheMeatIsNotAReasonToCraftAnother() {
        f.give(Items.OAK_LOG, 30).give(Items.WOODEN_AXE, 1).give(Items.STONE_PICKAXE, 2).give(Items.STONE_SWORD, 1);
        assertTrue(names(KitPlanner.gather(f, cfg, 8)).contains("furnace"));
        // it went down to cook and it is coming back to the bag
        f.furnacePlaced = true;
        assertFalse(names(KitPlanner.gather(f, cfg, 8)).contains("furnace"));
    }

    @Test
    public void aCookIsNotACraftSoItNeverHoldsTheTable() {
        assertTrue(COOK.isGathering());
        assertFalse(COOK.isCraft());
        assertTrue(new KitNeed(KitNeed.COOK_SMOKER, 3).isSpecial());
        // the registry counts a cook as its station being used (the one we cook in is not picked up under us), and a cook never
        // holds the table
        assertFalse(WorkbenchRules.needsStation(StationHook.Kind.TABLE, KitNeed.COOK_FURNACE, true, true));
        assertTrue(WorkbenchRules.needsStation(StationHook.Kind.FURNACE, KitNeed.COOK_FURNACE, true, true));
        assertTrue(WorkbenchRules.needsStation(StationHook.Kind.SMOKER, KitNeed.COOK_SMOKER, true, true));
        assertTrue(WorkbenchRules.neededSoon(StationHook.Kind.FURNACE, List.of("iron_pickaxe", KitNeed.COOK_FURNACE), true, true));
        assertTrue(WorkbenchRules.neededSoon(StationHook.Kind.SMOKER, List.of("iron_pickaxe", KitNeed.COOK_SMOKER), true, true));
    }

    @Test
    public void progressIsTheMeatLeavingTheBag() {
        f.give(Items.MUTTON, 6);
        int before = KitPlanner.progressOf(f, COOK, FoodPlan.ofBeds(f, cfg, 8));
        FakeFacts less = new FakeFacts().give(Items.MUTTON, 2);
        assertTrue(KitPlanner.progressOf(less, COOK, FoodPlan.ofBeds(less, cfg, 8)) > before);
    }

    @Test
    public void earlyStartOnTheSurfaceOrBetweenJobsButNeverInFrontOfOre() {
        List<KitNeed> underMine = List.of(ORE, CRAFT, COOK);
        // standing in a vein: the mine goes first, the cook keeps the end of the plan
        assertFalse(CookGate.leads(underMine, 2, 6, false, false));
        // on the surface it goes first
        assertTrue(CookGate.leads(underMine, 2, 6, true, false));
        // the next job is not ore (a craft at the table): between needs, go
        assertTrue(CookGate.leads(List.of(CRAFT, ORE, COOK), 2, 6, false, false));
        // a start that happened carries on, walking over the heightmap line does not undo it
        assertTrue(CookGate.leads(underMine, 2, 6, false, true));
        // a handful is only the closing need
        assertFalse(CookGate.leads(underMine, 2, 2, true, false));
        assertFalse(CookGate.leads(underMine, 2, 2, true, true));
    }

    @Test
    public void leadingMovesItToTheFrontBehindALeadingFood() {
        KitNeed food = new KitNeed(KitNeed.FOOD, 70);
        assertEquals(List.of(COOK, ORE, CRAFT), CookGate.lead(List.of(ORE, CRAFT, COOK), 2));
        assertEquals(List.of(food, COOK, ORE), CookGate.lead(List.of(food, ORE, COOK), 2));
        assertEquals(2, CookGate.index(List.of(ORE, CRAFT, COOK)));
        assertEquals(-1, CookGate.index(List.of(ORE, CRAFT)));
    }

    @Test
    public void reusingTheFurnaceWeJustEmptiedNeedsEnoughMeatAndFuel() {
        f.give(Items.MUTTON, 4).give(Items.COAL, 1);
        assertTrue(CookGate.reusable(f, cfg, 8, false));
        // a pair of rabbit is not worth holding the furnace for
        assertFalse(CookGate.reusable(new FakeFacts().give(Items.RABBIT, 2).give(Items.COAL, 1), cfg, 8, false));
        assertFalse(CookGate.reusable(new FakeFacts().give(Items.MUTTON, 4), cfg, 8, false));
        f.cookSuspended = true;
        assertFalse(CookGate.reusable(f, cfg, 8, false));
    }

    // ---- the GATHER -> IRON boundary: a bag of raw pork, chicken and mutton, a furnace and no fuel, and the cook
    // never came up. the fuel gate wanted a coal per piece of meat in the whole bag and the gather chops exactly its budget

    private FakeFacts boundary() {
        FakeFacts b = new FakeFacts();
        b.give(Items.PORKCHOP, 4).give(Items.CHICKEN, 3).give(Items.MUTTON, 3).give(Items.FURNACE, 1).give(Items.CRAFTING_TABLE, 1);
        b.give(Items.WOODEN_AXE, 1).give(Items.STONE_PICKAXE, 2).give(Items.STONE_SWORD, 1);
        b.foodUnits = 90;
        return b;
    }

    @Test
    public void theFirstLoadIsTheBiggestPileNotTheWholeBag() {
        // ten raw in three kinds and one coal (8 smelts): the first load is the 4 pork, the rest waits for it to be collected
        FakeFacts b = boundary().give(Items.COAL, 1);
        assertEquals(KitNeed.COOK_FURNACE, CookGate.need(b, cfg, 8).catalogueName());
        assertEquals(4, CookGate.pile(b));
        assertEquals(10, CookGate.raw(b));
        // and with fuel for three smelts (2 logs, no beds owed so they are spare) the pile of 4 does not fit
        FakeFacts less = boundary().give(Items.OAK_LOG, 2);
        assertEquals(3, CookGate.fuelSmelts(less, cfg, 0));
        assertNull(CookGate.need(less, cfg, 0));
        // three logs (4 smelts) fit
        FakeFacts fits = boundary().give(Items.OAK_LOG, 3);
        assertEquals(KitNeed.COOK_FURNACE, CookGate.need(fits, cfg, 0).catalogueName());
    }

    @Test
    public void whileIronIsOwedTheMeatGoesInASmokerNotTheKitFurnace() {
        OverworldConfig full = new OverworldConfig();
        full.netherSpareShields = 0;
        full.netherSpareSwords = 0;
        FakeFacts b = boundary().give(Items.COAL, 2);
        assertTrue(CookGate.ironOwed(b, full));
        // the furnace in the bag is the iron's, and nothing to build a smoker from: no cook yet
        assertNull(CookGate.need(b, full, 8));
        // with the logs for a smoker (and the furnace it is made from, already in the bag) the meat gets its own
        b.give(Items.OAK_LOG, 8);
        assertEquals(KitNeed.COOK_SMOKER, CookGate.need(b, full, 8).catalogueName());
        // a smoker standing is free, same as always
        FakeFacts standing = boundary().give(Items.COAL, 2);
        standing.smokerPlaced = true;
        assertEquals(KitNeed.COOK_SMOKER, CookGate.need(standing, full, 8).catalogueName());
        // and a furnace standing next to us is not a place for meat while the ore is still to come
        FakeFacts placed = boundary().give(Items.COAL, 2);
        placed.furnacePlaced = true;
        assertNull(CookGate.need(placed, full, 8));
    }

    @Test
    public void onceTheIronIsSmeltedTheFurnaceIsFreeForTheMeat() {
        OverworldConfig full = new OverworldConfig();
        full.netherSpareShields = 0;
        full.netherSpareSwords = 0;
        FakeFacts b = boundary().give(Items.COAL, 2);
        // all the ingots the kit wants, in the bag
        b.give(Items.IRON_INGOT, KitPlanner.ingotsNeeded(b, full));
        assertFalse(CookGate.ironOwed(b, full));
        assertEquals(KitNeed.COOK_FURNACE, CookGate.need(b, full, 8).catalogueName());
    }

    @Test
    public void aCookMidLoadKeepsItsNeedWhenTheLastPileLeavesTheBag() {
        // everything is in the smoker's slot, the bag has no raw meat and the job is not recorded yet
        f.give(Items.COAL, 2).give(Items.COOKED_MUTTON, 2);
        f.cookStation = "smoker";
        assertEquals(KitNeed.COOK_SMOKER, need().catalogueName());
        // no cook running and nothing raw is still no need
        f.cookStation = null;
        assertNull(need());
    }

    @Test
    public void aCookMidLoadKeepsItsNeedWhenItsCoalLeavesTheBag() {
        // 23:18:36: beef x3 and the coal went into the smoker, one raw piece is still in the bag and the bag has no fuel left. the
        // fuel gate read that as "cannot afford it", dropped the need, and iron took the wheel with the smoker half loaded
        f.give(Items.BEEF, 1);
        f.cookStation = "smoker";
        assertEquals(KitNeed.COOK_SMOKER, need().catalogueName());
        // a cook that has not started still wants the fuel before it picks a station
        f.cookStation = null;
        f.smokerPlaced = true;
        assertNull(need());
        f.give(Items.COAL, 1);
        assertEquals(KitNeed.COOK_SMOKER, need().catalogueName());
    }

    // 13:11:01: 5 beef went into the smoker, the bag read 0 raw, and `raw < MIN_RAW` handed the head to the iron mid load
    @Test
    public void aCommittedCookLeadsEvenWithTheMeatInTheStation() {
        List<KitNeed> underMine = List.of(ORE, CRAFT, COOK);
        // the bag is empty of raw meat: the old rule said no, whatever the latch and the heightmap thought
        assertFalse(CookGate.leads(underMine, 2, 0, false, true, false, false));
        assertFalse(CookGate.leads(underMine, 2, 0, true, true, false, false));
        // the pin (a running cook with its station) is what keeps it, wherever the bot stands
        assertTrue(CookGate.leads(underMine, 2, 0, false, false, false, true));
        assertTrue(CookGate.leads(underMine, 2, 0, false, true, false, true));
        // and the plan keeps the need itself, no raw meat and no fuel left in the bag, as long as the pin is up
        f.cookStation = "smoker";
        assertEquals(KitNeed.COOK_SMOKER, need().catalogueName());
    }

    // 13:11:00: the gate counted 19 logs as fuel, the smoker's FuelPolicy would not burn them (altoSupportedFuels is coal and
    // charcoal), and the meat was already in the slot when the smelt task found out
    @Test
    public void fuelTheSmeltTaskWillNotBurnIsNoFuelForTheGate() {
        f = kitDone().give(Items.BEEF, 5).give(Items.OAK_LOG, 23).give(Items.COBBLESTONE, 60);
        f.smokerPlaced = true;
        assertEquals(0, KitPlanner.woodNeed(f, cfg, 0));
        assertTrue(CookGate.fuelSmelts(f, cfg, 0) >= 5);
        assertEquals(KitNeed.COOK_SMOKER, CookGate.need(f, cfg, 0).catalogueName());
        // the same bag when logs are not on the list: no fuel, no cook (and no walking to a smoker to find out)
        f.coalOnly();
        assertEquals(0, CookGate.fuelSmelts(f, cfg, 0));
        assertNull(CookGate.need(f, cfg, 0));
        // a coal fixes it, wood or not
        f.give(Items.COAL, 1);
        assertEquals(8, CookGate.fuelSmelts(f, cfg, 0));
        assertEquals(KitNeed.COOK_SMOKER, CookGate.need(f, cfg, 0).catalogueName());
    }

    // the gate and the smelt task each count the bag with their own code, so any drift between them is a cook that starts and
    // then goes for coal. FuelPolicy.usableFuel is what calculateInventoryFuelCount sums; same reserve, same per item numbers
    @Test
    public void theGateAndFuelPolicyAgreeOnWhatCanBurn() {
        List<ItemStack> stacks = List.of(new ItemStack(Items.OAK_LOG, 14), new ItemStack(Items.SPRUCE_LOG, 5),
                new ItemStack(Items.OAK_PLANKS, 6), new ItemStack(Items.COAL, 2), new ItemStack(Items.CHARCOAL, 1));
        // with no beds owed the kit keeps nothing and every log is spare; with eight owed the bed planks are kept, whatever that
        // comes to. same answer from both sides either way
        for (int beds : new int[]{0, 8}) {
            FakeFacts bag = kitDone().give(Items.OAK_LOG, 14).give(Items.SPRUCE_LOG, 5).give(Items.OAK_PLANKS, 6).give(Items.COAL, 2)
                    .give(Items.CHARCOAL, 1);
            try {
                WoodReserve.Keep keep = WoodBudget.of(bag, cfg, beds).keep();
                FuelPolicy.set(keep.logs(), keep.planks(), true);
                double policy = FuelPolicy.usableFuel(stacks, i -> true, CookGateTest::smelts);
                assertEquals("beds " + beds, (int) policy, CookGate.fuelSmelts(bag, cfg, beds));
                // wood that is not on the list is neither
                bag.coalOnly();
                double coalOnly = FuelPolicy.usableFuel(stacks, i -> i == Items.COAL || i == Items.CHARCOAL, CookGateTest::smelts);
                assertEquals(24, (int) coalOnly);
                assertEquals("beds " + beds, (int) coalOnly, CookGate.fuelSmelts(bag, cfg, beds));
            } finally {
                FuelPolicy.clear();
            }
        }
        // and with nothing kept every plank counts for what a log does (it was 0.75 in the gate and 1.5 in the furnace)
        FakeFacts planks = kitDone().give(Items.OAK_PLANKS, 8);
        assertEquals(12, CookGate.fuelSmelts(planks, cfg, 0));
    }

    // every kit item and no wood owed, so the reserve keeps no wood (endBeds 0)
    private static FakeFacts kitDone() {
        FakeFacts done = new FakeFacts();
        for (net.minecraft.world.item.Item i : new net.minecraft.world.item.Item[]{Items.WOODEN_AXE, Items.STONE_PICKAXE, Items.STONE_SWORD,
                Items.IRON_PICKAXE, Items.IRON_SWORD, Items.FLINT_AND_STEEL, Items.SHIELD, Items.SHEARS, Items.FURNACE, Items.CRAFTING_TABLE}) {
            done.give(i, 1);
        }
        return done.give(Items.BUCKET, 2).give(Items.LADDER, 3);
    }

    private static double smelts(net.minecraft.world.item.Item item) {
        return item == Items.COAL || item == Items.CHARCOAL ? 8 : 1.5;
    }

    // 13:11:01: the beef left in the smoker without a flame
    @Test
    public void meatLeftColdInAStationBlocksTheNextCookUntilItIsCollected() {
        f.give(Items.PORKCHOP, 6).give(Items.COAL, 2);
        f.smokerPlaced = true;
        assertEquals(KitNeed.COOK_SMOKER, need().catalogueName());
        f.cookingFood("cooked_beef", 5, 8, 0);
        f.furnaceJobs().get(0).stranded = true;
        // not pending food (it is not cooking) and not a reason to start another batch on top of it
        assertEquals(0, f.pendingFoodUnits());
        assertTrue(CookGate.stranded(f));
        assertNull(need());
        assertFalse(CookGate.reusable(f, cfg, 8, true));
        // the pickup done, the cook is free again
        f.furnaceJobs().clear();
        assertEquals(KitNeed.COOK_SMOKER, need().catalogueName());
    }

    @Test
    public void takingColdMeatBackPutsTheCookOnTheBackoff() {
        RunState.FurnaceJob meat = new RunState.FurnaceJob(new RunState.Pos(1, 64, 1), "OVERWORLD", "smoker", "beef", 5, "cooked_beef", 0, 0);
        meat.unitsEach = 8;
        // came out because it was cold or never finished: sit out the backoff
        assertTrue(FurnaceJobs.backsOffCook(meat, true));
        // came out because we were leaving the mine: the surface cook is wanted right away
        assertFalse(FurnaceJobs.backsOffCook(meat, false));
        // iron is not the cook's business
        RunState.FurnaceJob iron = new RunState.FurnaceJob(new RunState.Pos(1, 64, 1), "OVERWORLD", "furnace", "raw_iron", 5, "iron_ingot", 0, 0);
        assertFalse(FurnaceJobs.backsOffCook(iron, true));
        // and the backoff really does stop the next cook
        RunState run = new RunState();
        FurnacePlan.cookSuspend(run.cook, 1000);
        assertTrue(FurnacePlan.cookSuspended(run.cook, 1001));
        f.gameTime = 1001;
        f.cookSuspended = FurnacePlan.cookSuspended(run.cook, f.gameTime);
        f.give(Items.PORKCHOP, 6).give(Items.COAL, 2);
        f.smokerPlaced = true;
        assertNull(need());
    }

    @Test
    public void anIronLoadInFlightHoldsTheCookOffEvenWhenItIsLatched() {
        List<KitNeed> underMine = List.of(CRAFT, ORE, COOK);
        // the cook latched while the furnace was still being crafted; the iron was on its way into the slot
        assertTrue(CookGate.leads(underMine, 2, 6, true, true, false));
        assertFalse(CookGate.leads(underMine, 2, 6, true, true, true));
        assertFalse(CookGate.leads(underMine, 2, 6, false, false, true));
        // and the old five argument form is the same as no load
        assertEquals(CookGate.leads(underMine, 2, 6, true, false), CookGate.leads(underMine, 2, 6, true, false, false));
    }

    @Test
    public void gatherChopsFuelForTheCookOnTopOfItsBudget() {
        OverworldConfig full = new OverworldConfig();
        full.netherSpareShields = 0;
        full.netherSpareSwords = 0;
        FakeFacts none = new FakeFacts();
        int base = KitPlanner.woodNeed(none, full, 8);
        assertTrue(base > 0);
        // a fresh gather: its budget and the fuel, in the one log need
        assertEquals(full.cookFuelLogs, KitPlanner.cookFuelLogs(none, full, 8, base));
        KitNeed logs = KitPlanner.gather(none, full, 8).stream().filter(n -> n.catalogueName().equals("log")).reduce((a, b) -> b).orElseThrow();
        assertEquals(base + full.cookFuelLogs, logs.count());
        // the setting off is the old gather
        OverworldConfig off = new OverworldConfig();
        off.cookFuelLogs = 0;
        assertEquals(0, KitPlanner.cookFuelLogs(none, off, 8, base));
    }

    @Test
    public void theFuelLogsStopOnceTheyAreChoppedAndOnceTheFoodIsIn() {
        OverworldConfig full = new OverworldConfig();
        full.netherSpareShields = 0;
        full.netherSpareSwords = 0;
        // the budget met and plenty spare on top: nothing more to chop
        FakeFacts rich = new FakeFacts().give(Items.OAK_LOG, 60);
        assertEquals(0, KitPlanner.woodNeed(rich, full, 8));
        assertEquals(0, KitPlanner.cookFuelLogs(rich, full, 8, 0));
        // food gathered: no logs asked for the cook, whatever the bag has (cooking them must not send the gather back out)
        FakeFacts fed = new FakeFacts();
        fed.foodUnits = 70;
        assertEquals(0, KitPlanner.cookFuelLogs(fed, full, 8, 5));
        // and meat already in the bag means the fuel decision has been made
        FakeFacts meat = new FakeFacts().give(Items.PORKCHOP, 3);
        assertEquals(0, KitPlanner.cookFuelLogs(meat, full, 8, 5));
    }

    @Test
    public void anEmptiedKitFurnaceIsNotReloadedWithMeatWhileIronIsOwed() {
        OverworldConfig full = new OverworldConfig();
        full.netherSpareShields = 0;
        full.netherSpareSwords = 0;
        FakeFacts b = boundary().give(Items.COAL, 2);
        assertFalse(CookGate.reusable(b, full, 8, false));
        // a smoker has no ore to mix it up with
        assertTrue(CookGate.reusable(b, full, 8, true));
    }

    // a far smoker does not pull the meat away from our furnace right here: smoker first is about the one next to us
    @Test
    public void aFarSmokerDoesNotBeatTheFurnaceHere() {
        f.give(Items.MUTTON, 6).give(Items.COAL, 2);
        f.furnacePlaced = true;
        f.smokerPlaced = WorkbenchRules.plannerSmoker(false, true, true);
        assertFalse(f.smokerPlaced);
        assertEquals(CookGate.Station.FURNACE, CookGate.station(f, cfg, true));
        // no furnace at hand: the far smoker is the walk worth making, and the cook is a smoker cook
        FakeFacts away = new FakeFacts().give(Items.MUTTON, 6).give(Items.COAL, 2);
        away.smokerPlaced = WorkbenchRules.plannerSmoker(false, false, true);
        assertTrue(away.smokerPlaced);
        assertEquals(CookGate.Station.SMOKER, CookGate.station(away, cfg, true));
        // and one right here is smoker first, as always
        assertTrue(WorkbenchRules.plannerSmoker(true, true, false));
    }

    // the visit that empties a smoker asks the cook gate "can this smoker take the meat in the bag" (FurnaceWatch.afterVisit). the wood
    // budget built while the smoker's job was still booked holds back the cook's fuel claim (waiting() wants no pending food), so it
    // undercounts the fuel: 10 to 17 logs read "no fuel" with the job booked and "fine" once it is gone. afterVisit asks again
    @Test
    public void aBookedSmokerJobHidesTheFuelTheEmptiedSmokerCouldBurn() {
        OverworldConfig full = new OverworldConfig();
        full.netherSpareShields = 0;
        full.netherSpareSwords = 0;
        FakeFacts bag = new FakeFacts().give(Items.PORKCHOP, 8).give(Items.OAK_LOG, 12).give(Items.STONE_PICKAXE, 1)
                .give(Items.STONE_SWORD, 1).give(Items.WOODEN_AXE, 1);
        bag.smokerPlaced = true;
        bag.cookingFood("cooked_beef", 9, 8, 0);
        WoodBudget booked = WoodBudget.of(bag, full, 8, GamerPhase.IRON);
        bag.jobs.clear();
        assertFalse(CookGate.reusable(bag, full, 8, true, booked));
        assertTrue(CookGate.reusable(bag, full, 8, true, WoodBudget.of(bag, full, 8, GamerPhase.IRON)));
    }

    // what MinecraftFacts says for a furnace of ours 60 blocks off (past WALK_BACK, so the cobble decides): held only while the smelt
    // task would walk back to it (DoStuffInContainerTask.bagCanMake: the cobble the stone tools still eat is not furnace material)
    private boolean walkBackFlag(FakeFacts f) {
        int free = f.count(Items.COBBLESTONE) - KitPlanner.toolCobble(f, cfg);
        boolean canMake = StationChoice.canMakeFrom(StationHook.Kind.FURNACE, free, 0, 0, f.count(Items.FURNACE));
        return !f.has(Items.FURNACE) && 60 <= StationChoice.oursReach(StationHook.Kind.FURNACE, canMake);
    }

    // mining for the stone tools walks the cobble up past 8: that is the tools' cobble, not a furnace. the far furnace stays held the
    // whole way, so the plan never grows a furnace and its 8 cobble halfway through
    @Test
    public void cobbleForTheToolsDoesNotAbandonTheFarFurnace() {
        f.give(Items.OAK_LOG, 15).give(Items.WOODEN_AXE, 1).give(Items.WOODEN_PICKAXE, 1);
        int owed = KitPlanner.toolCobble(f, cfg);
        assertTrue("the test needs tools that eat at least 8", owed >= 8);
        f.furnacePlaced = walkBackFlag(f);
        int target = KitPlanner.stoneNeed(f, cfg);
        for (int held = 1; held <= owed + 2; held++) {
            f.give(Items.COBBLESTONE, 1);
            f.furnacePlaced = walkBackFlag(f);
            assertTrue("cobble " + held, f.furnacePlaced);
            assertFalse("cobble " + held, names(KitPlanner.gather(f, cfg, 8)).contains("furnace"));
            // what is left to mine only ever goes down
            assertEquals(Math.max(0, target - held), KitPlanner.stoneNeed(f, cfg));
        }
    }

    // one flag, every reader: with no cobble the furnace 40 blocks off is ours to walk back to, so there is no furnace to craft, no
    // 8 cobble mined for one, and the meat cooks in it. with 8 cobble StationChoice makes one here, and the plan budgets it
    @Test
    public void aFurnaceTheSmeltWalksBackToIsTheSameFurnaceForEveryReader() {
        f.give(Items.MUTTON, 6).give(Items.COAL, 2);
        FakeFacts none = new FakeFacts().give(Items.MUTTON, 6).give(Items.COAL, 2);
        f.furnacePlaced = walkBackFlag(f);
        assertTrue(f.furnacePlaced);
        assertTrue(names(KitPlanner.gather(none, cfg, 8)).contains("furnace"));
        assertFalse(names(KitPlanner.gather(f, cfg, 8)).contains("furnace"));
        assertEquals(KitPlanner.stoneNeed(none, cfg) - 8, KitPlanner.stoneNeed(f, cfg));
        assertEquals(KitPlanner.stoneFloor(none, cfg) - 8, KitPlanner.stoneFloor(f, cfg));
        assertEquals(CookGate.Station.FURNACE, CookGate.station(f, cfg, true));
        assertEquals(COOK, need());
        assertTrue(CookGate.cookFeasible(f, cfg, 8));
        // 8 cobble over what the tools eat: making one wins in StationChoice, and the planner lets go of the far one with it
        f.give(Items.COBBLESTONE, KitPlanner.toolCobble(f, cfg) + 7);
        assertTrue(walkBackFlag(f));
        f.give(Items.COBBLESTONE, 1);
        f.furnacePlaced = walkBackFlag(f);
        assertFalse(f.furnacePlaced);
        assertTrue(names(KitPlanner.gather(f, cfg, 8)).contains("furnace"));
    }
}
