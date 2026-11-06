package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.PortalPlanner.Method;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.utils.Dimension;
import net.minecraft.world.item.Items;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PortalPlannerTest {
    private OverworldConfig cfg;

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    @Before
    public void setUp() {
        cfg = new OverworldConfig();
        cfg.netherSpareShields = 0;
        cfg.netherSpareSwords = 0;
        // the gate tests below pin the list without the nether picks, those have their own tests at the bottom
        cfg.netherSparePicks = 0;
    }

    @Test
    public void poolGoesFirstUntilItGivesUp() {
        assertTrue(PortalPlanner.usePool(true, false, Method.CAST));
        assertTrue("a failed pool never comes back", !PortalPlanner.usePool(true, true, Method.CAST));
        assertTrue("switched off means the cast like before", !PortalPlanner.usePool(false, false, Method.CAST));
        assertTrue("obsidian is the end of the lava ways", !PortalPlanner.usePool(true, false, Method.OBSIDIAN));
    }

    @Test
    public void castsFirst() {
        assertEquals(Method.CAST, PortalPlanner.decide(Method.CAST, 0, 0, cfg, false, false));
        assertEquals(Method.CAST, PortalPlanner.decide(Method.CAST, 300, 300, cfg, true, true));
        assertEquals("the cast keeps most of the 14 minute portal budget", 7.0, cfg.castGiveUpMinutes, 0);
    }

    @Test
    public void givesUpTheCastOnTheClock() {
        double limit = cfg.castGiveUpMinutes * 60;
        assertEquals(Method.CAST, PortalPlanner.decide(Method.CAST, limit - 1, limit - 1, cfg, true, false));
        assertEquals(Method.OBSIDIAN, PortalPlanner.decide(Method.CAST, limit, limit, cfg, true, true));
    }

    @Test
    public void theClockAloneNeverPicksObsidianWithoutADiamondPickaxe() {
        double limit = cfg.castGiveUpMinutes * 60;
        assertEquals(Method.CAST, PortalPlanner.decide(Method.CAST, limit, limit, cfg, true, false));
        assertEquals(Method.CAST, PortalPlanner.decide(Method.CAST, limit * 10, limit * 10, cfg, false, false));
    }

    @Test
    public void aTimeoutDuringThePoolHandsOverToTheCastFirst() {
        assertEquals(Method.CAST, PortalPlanner.afterTimeout(Method.CAST, true, true));
        assertEquals(Method.CAST, PortalPlanner.afterTimeout(Method.CAST, true, false));
    }

    // maxAttempts 2: the pool stall is paid for, the cast still gets both of its tries
    @Test
    public void aPoolStallDoesNotUseUpTheCastsAttempt() {
        PortalPlanner.TimeoutPlan stall = PortalPlanner.onTimeout(Method.CAST, 1, 2, true, false, true);
        assertTrue(stall.retry());
        assertEquals(Method.CAST, stall.method());
        assertTrue(stall.poolTimedOut());
        // engine attempt 2 is the cast's first try: its timeout is still a retry, and may now go obsidian
        PortalPlanner.TimeoutPlan cast1 = PortalPlanner.onTimeout(Method.CAST, 2, 2, false, stall.poolTimedOut(), true);
        assertTrue(cast1.retry());
        assertEquals(Method.OBSIDIAN, cast1.method());
        // attempt 3 is the second cast try, after that the existing end of the line
        assertTrue(!PortalPlanner.onTimeout(Method.OBSIDIAN, 3, 2, false, true, true).retry());
        // no diamond pickaxe: the retry casts again
        assertEquals(Method.CAST, PortalPlanner.onTimeout(Method.CAST, 2, 2, false, true, false).method());
    }

    // the cast was landing obsidian when the clock ran out: the retry casts again instead of walking off to natural obsidian
    @Test
    public void aTimeoutWithCastProgressRetriesTheCast() {
        PortalPlanner.TimeoutPlan rose = PortalPlanner.onTimeout(Method.CAST, 1, 2, false, false, true, true);
        assertTrue(rose.retry());
        assertEquals(Method.CAST, rose.method());
        assertEquals(Method.OBSIDIAN, PortalPlanner.onTimeout(Method.CAST, 1, 2, false, false, true, false).method());
        assertEquals(Method.OBSIDIAN, PortalPlanner.afterTimeout(Method.OBSIDIAN, false, true, true));
    }

    @Test
    public void withoutAPoolStallTheAttemptsAreTheOldTwo() {
        assertTrue(PortalPlanner.onTimeout(Method.CAST, 1, 2, false, false, true).retry());
        assertTrue(!PortalPlanner.onTimeout(Method.OBSIDIAN, 2, 2, false, false, true).retry());
    }

    @Test
    public void aTimeoutOutsideThePoolGoesObsidianOnlyWithThePickaxe() {
        assertEquals(Method.OBSIDIAN, PortalPlanner.afterTimeout(Method.CAST, false, true));
        assertEquals(Method.CAST, PortalPlanner.afterTimeout(Method.CAST, false, false));
        assertEquals(Method.OBSIDIAN, PortalPlanner.afterTimeout(Method.OBSIDIAN, true, false));
    }

    @Test
    public void noLavaFlipsEarlyOnlyWithThePickaxe() {
        assertEquals(Method.CAST, PortalPlanner.decide(Method.CAST, 149, 149, cfg, false, true));
        assertEquals(Method.OBSIDIAN, PortalPlanner.decide(Method.CAST, 150, 150, cfg, false, true));
        // no pickaxe: keep wandering for a lake until the clock says stop
        assertEquals(Method.CAST, PortalPlanner.decide(Method.CAST, 400, 400, cfg, false, false));
        // lava seen: the cast has a chance
        assertEquals(Method.CAST, PortalPlanner.decide(Method.CAST, 400, 400, cfg, true, true));
    }

    @Test
    public void aCastStillLandingCellsOutlivesTheClock() {
        double limit = cfg.castGiveUpMinutes * 60;
        assertEquals(180.0, cfg.castStallSeconds, 0);
        // a cell every 20 s, well past 7 minutes: keep casting
        for (double t = limit; t < limit + 300; t += 20) {
            assertEquals(Method.CAST, PortalPlanner.decide(Method.CAST, t, 20, cfg, true, true));
        }
        // then it stalls: just under the stall window stays, the window itself gives up
        assertEquals(Method.CAST, PortalPlanner.decide(Method.CAST, limit + 400, cfg.castStallSeconds - 1, cfg, true, true));
        assertEquals(Method.OBSIDIAN, PortalPlanner.decide(Method.CAST, limit + 400, cfg.castStallSeconds, cfg, true, true));
    }

    @Test
    public void aStallBeforeTheClockIsNotEnoughOnItsOwn() {
        double limit = cfg.castGiveUpMinutes * 60;
        assertEquals(Method.CAST, PortalPlanner.decide(Method.CAST, limit - 1, cfg.castStallSeconds * 2, cfg, true, true));
    }

    @Test
    public void noProgressEverIsTheOldClock() {
        double limit = cfg.castGiveUpMinutes * 60;
        // sinceProgress == castSeconds when nothing landed, and the clock is way past the stall window by then
        assertEquals(Method.CAST, PortalPlanner.decide(Method.CAST, limit - 1, limit - 1, cfg, true, true));
        assertEquals(Method.OBSIDIAN, PortalPlanner.decide(Method.CAST, limit, limit, cfg, true, true));
    }

    @Test
    public void noLavaWaitsForTheLastCellNotTheStart() {
        // a cell landed 10 s ago: lava not in sight right now is no reason to drop the frame
        assertEquals(Method.CAST, PortalPlanner.decide(Method.CAST, 400, 10, cfg, false, true));
        assertEquals(Method.CAST, PortalPlanner.decide(Method.CAST, 400, cfg.noLavaSeconds - 1, cfg, false, true));
        assertEquals(Method.OBSIDIAN, PortalPlanner.decide(Method.CAST, 400, cfg.noLavaSeconds, cfg, false, true));
    }

    @Test
    public void aStalledCastWithoutADiamondPickaxeKeepsCasting() {
        double limit = cfg.castGiveUpMinutes * 60;
        assertEquals(Method.CAST, PortalPlanner.decide(Method.CAST, limit * 2, limit * 2, cfg, false, false));
        assertEquals(Method.CAST, PortalPlanner.decide(Method.CAST, limit * 2, cfg.castStallSeconds, cfg, true, false));
    }

    @Test
    public void obsidianIsSticky() {
        assertEquals(Method.OBSIDIAN, PortalPlanner.decide(Method.OBSIDIAN, 0, 0, cfg, true, false));
    }

    @Test
    public void parsesMethodNamesLeniently() {
        assertEquals(Method.CAST, PortalPlanner.parse(null));
        assertEquals(Method.CAST, PortalPlanner.parse("nonsense"));
        assertEquals(Method.OBSIDIAN, PortalPlanner.parse("OBSIDIAN"));
        assertEquals(Method.OBSIDIAN, PortalPlanner.parse("obsidian"));
        assertEquals(Method.CAST, PortalPlanner.parse(new RunState().portalMethod));
    }

    @Test
    public void gateOnABareInventoryAsksForEverythingInOrder() {
        FakeFacts f = new FakeFacts();
        List<String> names = PortalPlanner.gate(f, cfg, 10).stream().map(KitNeed::catalogueName).toList();
        assertEquals(List.of("food", "bucket", "water_bucket", "flint_and_steel", "crafting_table", "build_blocks"), names);
    }

    @Test
    public void portalGateFillsToTheEntryStackNotTheSmallBand() {
        FakeFacts f = new FakeFacts().give(Items.BUCKET, 1).give(Items.WATER_BUCKET, 1).give(Items.FLINT_AND_STEEL, 1).give(Items.CRAFTING_TABLE, 1);
        f.foodUnits = cfg.netherEntryFoodUnits;
        f.buildBlocks = 32;
        List<KitNeed> out = PortalPlanner.gate(f, cfg, 10);
        assertEquals(List.of(new KitNeed(KitNeed.BUILD_BLOCKS, cfg.netherEntryBlocks)), out);
        assertEquals(56, cfg.netherEntryBlocks);
        f.buildBlocks = cfg.netherEntryBlocksLow;
        assertTrue("a near full stack is not worth a trip to the stone", PortalPlanner.gate(f, cfg, 10).isEmpty());
    }

    // KitRunner tells the gate's stack from the stock-up by the count alone, the two landing on one number would bill the stock-up
    // to the base's refills
    @Test
    public void theEntryStackIsNotTheStockUpsNumber() {
        int extra = cfg.smeltExtras.stream().filter(k -> k.item.equals(KitNeed.BUILD_BLOCKS)).findFirst().orElseThrow().count;
        assertTrue(cfg.portalBuildBlocks + extra != cfg.netherEntryBlocks);
    }

    @Test
    public void gateIsEmptyWhenReady() {
        FakeFacts f = new FakeFacts().give(Items.BUCKET, 1).give(Items.WATER_BUCKET, 1).give(Items.FLINT_AND_STEEL, 1).give(Items.CRAFTING_TABLE, 1);
        f.foodUnits = cfg.netherEntryFoodUnits;
        f.buildBlocks = cfg.netherEntryBlocks;
        assertTrue(PortalPlanner.gate(f, cfg, 10).isEmpty());
    }

    @Test
    public void gateFillsTheFoodToTheNetherEntryLineNotTheMinimum() {
        FakeFacts f = readyToGo();
        f.foodUnits = 90;
        assertEquals(210, cfg.netherEntryFoodUnits);
        assertEquals(List.of(new KitNeed(KitNeed.FOOD, cfg.netherEntryFoodUnits)), PortalPlanner.gate(f, cfg, 10));
        // the trip stop is for every other trip, the gate does not move it
        assertEquals(130, cfg.refillStopFoodUnits);
        f.foodUnits = cfg.refillStopFoodUnits;
        assertEquals(List.of(new KitNeed(KitNeed.FOOD, cfg.netherEntryFoodUnits)), PortalPlanner.gate(f, cfg, 10));
        f.foodUnits = cfg.netherEntryFoodUnits;
        assertTrue(PortalPlanner.gate(f, cfg, 10).isEmpty());
    }

    @Test
    public void gateAsksForTheEntryLineOnATripToo() {
        FakeFacts f = readyToGo();
        f.foodUnits = 20;
        assertEquals(List.of(new KitNeed(KitNeed.FOOD, cfg.netherEntryFoodUnits)), PortalPlanner.gate(f, cfg, 10));
    }

    @Test
    public void gateEntryLineNeverGoesUnderTheTripStop() {
        cfg.netherEntryFoodUnits = 100;
        FakeFacts f = readyToGo();
        f.foodUnits = 90;
        assertEquals(List.of(new KitNeed(KitNeed.FOOD, cfg.refillStopFoodUnits)), PortalPlanner.gate(f, cfg, 10));
    }

    @Test
    public void aFullBagStillCapsTheEntryFillAtTheMinimum() {
        FakeFacts f = readyToGo();
        f.bagFull = true;
        f.foodUnits = 60;
        assertEquals(List.of(new KitNeed(KitNeed.FOOD, cfg.minFoodUnits)), PortalPlanner.gate(f, cfg, 10));
        f.foodUnits = cfg.minFoodUnits;
        assertTrue(PortalPlanner.gate(f, cfg, 10).isEmpty());
        // and under the floor, on a trip, it asks for the old stop and not a pile
        f.foodUnits = 20;
        assertEquals(List.of(new KitNeed(KitNeed.FOOD, cfg.refillStopFoodUnits)), PortalPlanner.gate(f, cfg, 10));
    }

    @Test
    public void gateSettlesForTheMinimumWhenTheFillUpIsOff() {
        FakeFacts f = readyToGo();
        f.foodUnits = 90;
        FoodPlan p = FoodPlan.ofBeds(f, cfg, 10);
        assertTrue(PortalPlanner.gate(f, cfg, 10, p, BuildBlocks.NONE, false).isEmpty());
        f.foodUnits = 60;
        assertTrue(PortalPlanner.gate(f, cfg, 10, FoodPlan.ofBeds(f, cfg, 10), BuildBlocks.NONE, false).isEmpty());
    }

    @Test
    public void gateStopsAtTheMinimumWhenTheBagIsFull() {
        FakeFacts f = readyToGo();
        f.foodUnits = 90;
        f.bagFull = true;
        assertTrue(PortalPlanner.gate(f, cfg, 10).isEmpty());
    }

    @Test
    public void fireChargeIsALightToo() {
        FakeFacts f = new FakeFacts().give(Items.BUCKET, 1).give(Items.WATER_BUCKET, 1).give(Items.FIRE_CHARGE, 1).give(Items.CRAFTING_TABLE, 1);
        f.foodUnits = cfg.netherEntryFoodUnits;
        f.buildBlocks = cfg.netherEntryBlocks;
        assertTrue(PortalPlanner.gate(f, cfg, 10).isEmpty());
    }

    @Test
    public void gateAsksForOneMoreEmptyBucketWhenOnlyTheWaterOneIsHeld() {
        FakeFacts f = new FakeFacts().give(Items.WATER_BUCKET, 1).give(Items.FLINT_AND_STEEL, 1).give(Items.CRAFTING_TABLE, 1);
        f.foodUnits = cfg.netherEntryFoodUnits;
        f.buildBlocks = cfg.netherEntryBlocks;
        assertEquals(List.of(new KitNeed("bucket", 1)), PortalPlanner.gate(f, cfg, 10));
    }

    @Test
    public void gateWantsArmorOn() {
        FakeFacts f = new FakeFacts().give(Items.BUCKET, 1).give(Items.WATER_BUCKET, 1).give(Items.FLINT_AND_STEEL, 1).give(Items.CRAFTING_TABLE, 1)
                .give(Items.IRON_BOOTS, 1);
        f.foodUnits = cfg.netherEntryFoodUnits;
        f.buildBlocks = cfg.netherEntryBlocks;
        assertEquals(List.of(new KitNeed(KitNeed.EQUIP_ARMOR, 1)), PortalPlanner.gate(f, cfg, 10));
        f.worn.add(Items.IRON_BOOTS);
        assertTrue(PortalPlanner.gate(f, cfg, 10).isEmpty());
    }

    private FakeFacts readyToGo() {
        FakeFacts f = new FakeFacts().give(Items.BUCKET, 1).give(Items.WATER_BUCKET, 1).give(Items.FLINT_AND_STEEL, 1).give(Items.CRAFTING_TABLE, 1);
        f.foodUnits = cfg.netherEntryFoodUnits;
        f.buildBlocks = cfg.netherEntryBlocks;
        return f;
    }

    @Test
    public void gateMakesAGoldHelmetFromGoldInTheBagThenWearsIt() {
        FakeFacts f = readyToGo().give(Items.GOLD_INGOT, 5);
        assertEquals(List.of(new KitNeed("golden_helmet", 1)), PortalPlanner.gate(f, cfg, 10));
        f.give(Items.GOLD_INGOT, -5).give(Items.GOLDEN_HELMET, 1);
        assertEquals(List.of(new KitNeed(KitNeed.EQUIP_ARMOR, 1)), PortalPlanner.gate(f, cfg, 10));
        f.worn.add(Items.GOLDEN_HELMET);
        assertTrue(PortalPlanner.gate(f, cfg, 10).isEmpty());
    }

    @Test
    public void gateSettlesForGoldBootsAndThenForNothing() {
        assertEquals(List.of(new KitNeed("golden_boots", 1)), PortalPlanner.gate(readyToGo().give(Items.GOLD_INGOT, 4), cfg, 10));
        // no mining detour: three ingots are not a piece of armor and the gate lets us go
        assertTrue(PortalPlanner.gate(readyToGo().give(Items.GOLD_INGOT, 3), cfg, 10).isEmpty());
        assertTrue(PortalPlanner.gate(readyToGo(), cfg, 10).isEmpty());
    }

    @Test
    public void gateLeavesGoldAloneWhenAPieceIsAlreadyOn() {
        FakeFacts f = readyToGo().give(Items.GOLD_INGOT, 9).give(Items.GOLDEN_BOOTS, 1);
        f.worn.add(Items.GOLDEN_BOOTS);
        assertTrue(PortalPlanner.gate(f, cfg, 10).isEmpty());
    }

    // ---- the cook before the nether

    // while iron is owed the furnace is the iron's and meat only goes in a smoker (CookGateTest sets up the same way)
    private void freeTheFurnace() {
        cfg.ironKit.clear();
        cfg.armorPlan = OverworldConfig.ArmorPlan.NONE;
    }

    @Test
    public void gateEndsWithTheCookWhenRawMeatCanBeCooked() {
        FakeFacts f = readyToGo().give(Items.PORKCHOP, 6).give(Items.COAL, 4);
        // plenty over the line so the food need stays out of the way of what is under test
        f.foodUnits = 300;
        f.furnacePlaced = true;
        freeTheFurnace();
        List<KitNeed> out = PortalPlanner.gate(f, cfg, 10);
        assertEquals(List.of(new KitNeed(KitNeed.COOK_FURNACE, CookGate.MIN_RAW)), out);
        // last, after the build blocks it would otherwise have asked for
        f.buildBlocks = 0;
        List<KitNeed> names = PortalPlanner.gate(f, cfg, 10);
        assertEquals(KitNeed.BUILD_BLOCKS, names.get(0).catalogueName());
        assertTrue(KitNeed.isCookName(names.get(names.size() - 1).catalogueName()));
    }

    @Test
    public void gateHasNoCookWithoutAStationOrOnceTheBatchIsCooking() {
        // no station and nothing to build one from: meat is eaten raw, nothing to ask for
        FakeFacts raw = readyToGo().give(Items.PORKCHOP, 6);
        raw.foodUnits = 300;
        assertTrue(PortalPlanner.gate(raw, cfg, 10).isEmpty());
        // loaded: the meat left the bag and a job exists, so the gate goes empty and the leave visit holds the phase
        FakeFacts f = readyToGo().give(Items.PORKCHOP, 3).give(Items.COAL, 4).cookingFood("cooked_porkchop", 3, 8, 15);
        f.foodUnits = 300;
        f.furnacePlaced = true;
        freeTheFurnace();
        assertTrue(PortalPlanner.gate(f, cfg, 10).isEmpty());
        // and mid load the cook keeps its place, the meat in the slot is not in the bag any more
        FakeFacts loading = readyToGo().give(Items.COAL, 4);
        loading.foodUnits = 300;
        loading.furnacePlaced = true;
        freeTheFurnace();
        loading.cookStation = "furnace";
        assertEquals(1, PortalPlanner.gate(loading, cfg, 10).size());
    }

    // ---- the spare picks for the nether

    private List<KitNeed> picksGate(FakeFacts f) {
        cfg.netherSparePicks = 3;
        return PortalPlanner.gate(f, cfg, 10);
    }

    @Test
    public void gateAsksForThreeSparesOnTopOfTheIronPick() {
        FakeFacts f = readyToGo().give(Items.IRON_PICKAXE, 1);
        // the iron pick is the one we dig with, three stone ones are the spares
        assertEquals(List.of(new KitNeed("stone_pickaxe", 3)), picksGate(f));
    }

    @Test
    public void gateCountsEveryTierAndKeepsTheTargetAboveTheStoneOnesHeld() {
        FakeFacts f = readyToGo().give(Items.IRON_PICKAXE, 2).give(Items.STONE_PICKAXE, 1);
        // three usable picks of four: one more, and the target is exact stone picks so it is 1 held + 1
        assertEquals(List.of(new KitNeed("stone_pickaxe", 2)), picksGate(f));
        f.give(Items.STONE_PICKAXE, 1);
        assertTrue(picksGate(f).isEmpty());
    }

    @Test
    public void aWornPickIsSpentAndDoesNotCountAsASpare() {
        FakeFacts f = readyToGo().give(Items.IRON_PICKAXE, 1).give(Items.STONE_PICKAXE, 2);
        f.spent.put(Items.STONE_PICKAXE, 1);
        // 3 usable of 4, the worn one is still in the bag so the catalogue's count starts above it: 3 stone held + 1
        assertEquals(List.of(new KitNeed("stone_pickaxe", 4)), picksGate(f));
    }

    @Test
    public void gateWithNoPickAtAllWantsFourAndPutsThemBeforeTheBuildBlocks() {
        FakeFacts f = new FakeFacts();
        cfg.netherSparePicks = 3;
        List<String> names = PortalPlanner.gate(f, cfg, 10).stream().map(KitNeed::catalogueName).toList();
        assertEquals(List.of("food", "bucket", "water_bucket", "flint_and_steel", "crafting_table", "stone_pickaxe", "build_blocks"), names);
        assertEquals(4, PortalPlanner.gate(f, cfg, 10).get(5).count());
    }

    @Test
    public void aWornStonePickAndAnIronOneStillLeaveTheTargetAboveWhatTheCatalogueSees() {
        // iron + 2 good stone = 3 usable of 4 wanted, one more. the catalogue counts the worn stone one too (3 held), so it stops at 4
        FakeFacts f = readyToGo().give(Items.IRON_PICKAXE, 1).give(Items.STONE_PICKAXE, 2);
        f.spent.put(Items.STONE_PICKAXE, 1);
        assertEquals(List.of(new KitNeed("stone_pickaxe", 4)), picksGate(f));
    }

    @Test
    public void aDiamondPickWalksInWithNoStoneSpares() {
        assertTrue(picksGate(readyToGo().give(Items.IRON_PICKAXE, 1).give(Items.DIAMOND_PICKAXE, 1)).isEmpty());
        assertTrue("no iron one next to it either", picksGate(readyToGo().give(Items.DIAMOND_PICKAXE, 1)).isEmpty());
        assertTrue("netherite is a diamond pick too", picksGate(readyToGo().give(Items.NETHERITE_PICKAXE, 1)).isEmpty());
        // and the iron one alone still gets its three, like before
        assertEquals(List.of(new KitNeed("stone_pickaxe", 3)), picksGate(readyToGo().give(Items.IRON_PICKAXE, 1)));
    }

    @Test
    public void aDiamondPickInHandDoesNotChangeTheFirstPortalChoice() {
        // the dig before the portal means most runs hold one now. the first answer is still the pool, then the cast
        assertTrue(PortalPlanner.usePool(true, false, Method.CAST));
        for (boolean lava : new boolean[]{true, false}) {
            assertEquals(Method.CAST, PortalPlanner.decide(Method.CAST, 0, 0, cfg, lava, true));
            assertEquals(PortalPlanner.decide(Method.CAST, 0, 0, cfg, lava, false), PortalPlanner.decide(Method.CAST, 0, 0, cfg, lava, true));
        }
        assertEquals("a timeout while the pool ran still casts next", Method.CAST, PortalPlanner.afterTimeout(Method.CAST, true, true));
    }

    @Test
    public void theDefaultConfigAsksForThreeSpares() {
        // setUp turns them off for the old gate tests, a fresh config is what a run really gets
        OverworldConfig fresh = new OverworldConfig();
        assertEquals(3, fresh.netherSparePicks);
        assertEquals(List.of(new KitNeed("stone_pickaxe", 3)),
                PortalPlanner.gate(readyToGo().give(Items.IRON_PICKAXE, 1), fresh, 10));
    }

    @Test
    public void zeroSparesSwitchesTheNeedOff() {
        cfg.netherSparePicks = 0;
        assertTrue(PortalPlanner.gate(readyToGo().give(Items.STONE_PICKAXE, 1), cfg, 10).isEmpty());
        assertTrue("not even for a bag with no pick", PortalPlanner.gate(readyToGo(), cfg, 10).isEmpty());
    }

    @Test
    public void recordsTheNetherEndOnArrivalOnce() {
        RunState state = new RunState();
        FakeFacts f = new FakeFacts();
        f.x = 10;
        f.y = 70;
        f.z = -4;
        PortalPlanner.recordArrival(state, f);
        assertNull(state.netherPortal);
        f.dimension = Dimension.NETHER;
        PortalPlanner.recordArrival(state, f);
        assertNotNull(state.netherPortal);
        assertEquals(new RunState.Pos(10, 70, -4), state.netherPortal);
        f.x = 99;
        PortalPlanner.recordArrival(state, f);
        assertEquals(new RunState.Pos(10, 70, -4), state.netherPortal);
    }

    // a cast whose fetches loop is slow, not over: the clock and the no lava line are the way out, not a guess at a loop
    @Test
    public void aCastThatKeepsFetchingIsNotSwitchedOnThatAlone() {
        assertEquals(Method.CAST, PortalPlanner.decide(Method.CAST, 30, 90, cfg, true, true));
    }
}
