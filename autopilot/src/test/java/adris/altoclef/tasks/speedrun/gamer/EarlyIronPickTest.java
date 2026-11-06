package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class EarlyIronPickTest {
    private static final int BEDS = 8;
    // the default kit's 39 ingots minus the shears' 2, the shield's 1 and the armor's 24, the kit these tests run with (setUp)
    private static final int TOTAL = 12;
    // the default kit, all four early items owed: pick 3, chestplate 8, shield 1, shears 2
    private static final int FULL = 39;
    // both stages together, and the first one on its own (chestplate and shield)
    private static final int EARLY = 14;
    private static final int STAGE1 = 9;
    private OverworldConfig cfg;

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    @Before
    public void setUp() {
        // the pick on its own: the chestplate, shield and shears riding along are their own tests further down (full)
        cfg = new OverworldConfig();
        cfg.netherSpareShields = 0;
        cfg.netherSpareSwords = 0;
        cfg.ironKit.removeIf(k -> k.item.equals("shears") || k.item.equals("shield"));
        cfg.armorPlan = OverworldConfig.ArmorPlan.NONE;
    }

    private static OverworldConfig full() {
        OverworldConfig c = new OverworldConfig();
        c.netherSpareShields = 0;
        c.netherSpareSwords = 0;
        return c;
    }

    private static List<String> names(List<KitNeed> needs) {
        return needs.stream().map(KitNeed::catalogueName).toList();
    }

    // underground with the starter kit and the food, nothing iron yet
    private static FakeFacts mining() {
        FakeFacts f = new FakeFacts();
        f.give(Items.STONE_PICKAXE, 1).give(Items.STONE_SWORD, 1).give(Items.FURNACE, 1).give(Items.LADDER, 3);
        f.foodUnits = 70;
        return f;
    }

    private boolean due(FakeFacts f) {
        return EarlyIronPick.due(f, cfg, KitPlanner.ingotsNeeded(f, cfg));
    }

    @Test
    public void threeRawIronAndNoPickIsTheTrigger() {
        FakeFacts f = mining().give(Items.RAW_IRON, 3);
        assertTrue(due(f));
        List<KitNeed> plan = KitPlanner.plan(f, cfg, BEDS);
        // just the three first, the whole kit's worth right behind it
        assertEquals(new KitNeed("iron_ingot", 3), plan.get(0));
        assertEquals(new KitNeed("iron_ingot", TOTAL), plan.get(1));
    }

    @Test
    public void twoRawIronIsNotEnoughYet() {
        FakeFacts f = mining().give(Items.RAW_IRON, 2);
        assertFalse(due(f));
        assertEquals(new KitNeed("iron_ingot", TOTAL), KitPlanner.plan(f, cfg, BEDS).get(0));
    }

    @Test
    public void anyIronOrBetterPickMeansNoEarlyBatch() {
        FakeFacts iron = mining().give(Items.RAW_IRON, 3).give(Items.IRON_PICKAXE, 1);
        assertFalse(due(iron));
        FakeFacts diamond = mining().give(Items.RAW_IRON, 3).give(Items.DIAMOND_PICKAXE, 1);
        assertFalse(due(diamond));
    }

    @Test
    public void doesNotFireAgainWhileTheFirstThreeCook() {
        FakeFacts f = mining().give(Items.RAW_IRON, 3).cooking("iron_ingot", 3, 30);
        assertFalse(due(f));
        assertFalse(KitPlanner.plan(f, cfg, BEDS).contains(new KitNeed("iron_ingot", 3)));
    }

    @Test
    public void doesNotFireAgainOnceTheIngotsAreInTheBagOrTheCraftIsDone() {
        assertFalse(due(mining().give(Items.RAW_IRON, 3).give(Items.IRON_INGOT, 3)));
        assertFalse(due(mining().give(Items.RAW_IRON, 3).give(Items.IRON_PICKAXE, 1)));
    }

    @Test
    public void theEarlyJobsPendingIngotsCountAsHeld() {
        // three cooking: the pickaxe is paid for, the rest of the kit (36) still needs mining, and it is asked for as the
        // 39 it always was, held and pending ingots come off it (KitPlanner.iron), so the planner does not double plan
        FakeFacts f = mining().cooking("iron_ingot", 3, 30);
        assertEquals(3, f.pendingOutput(Items.IRON_INGOT));
        List<KitNeed> plan = KitPlanner.plan(f, cfg, BEDS);
        assertEquals(new KitNeed("iron_ingot", TOTAL), plan.get(0));
        assertEquals(1, plan.stream().filter(n -> n.catalogueName().equals("iron_ingot")).count());
        // and with the whole 39 cooking there is nothing left to mine
        assertNull(plan.stream().filter(n -> n.catalogueName().equals("iron_ingot") && n.count() == 3).findFirst().orElse(null));
        assertFalse(names(KitPlanner.plan(mining().cooking("iron_ingot", 39, 400), cfg, BEDS)).contains("iron_ingot"));
    }

    @Test
    public void threeIngotsInTheBagMakeThePickTheHeadNeed() {
        FakeFacts f = mining().give(Items.IRON_INGOT, 3);
        assertEquals(List.of("iron_pickaxe"), EarlyIronPick.front(f, cfg));
        List<KitNeed> plan = KitPlanner.plan(f, cfg, BEDS);
        assertEquals(new KitNeed("iron_pickaxe", 1), plan.get(0));
        // the mining need is still there behind it, minus nothing: the pick eats the three it was sized with
        assertEquals("iron_ingot", plan.get(1).catalogueName());
        assertEquals(1, names(plan).stream().filter(n -> n.equals("iron_pickaxe")).count());
        // two is not three
        assertTrue(EarlyIronPick.front(mining().give(Items.IRON_INGOT, 2), cfg).isEmpty());
        assertEquals("iron_ingot", KitPlanner.plan(mining().give(Items.IRON_INGOT, 2), cfg, BEDS).get(0).catalogueName());
    }

    @Test
    public void allTheOreAlreadyMinedIsOneBigSmeltNotTwo() {
        FakeFacts f = mining().give(Items.RAW_IRON, 40);
        assertFalse(due(f));
        assertEquals(new KitNeed("iron_ingot", TOTAL), KitPlanner.plan(f, cfg, BEDS).get(0));
    }

    @Test
    public void theSettingOffIsTheOldPlan() {
        FakeFacts f = mining().give(Items.RAW_IRON, 3);
        f.earlyIronPick = false;
        assertFalse(due(f));
        assertEquals(new KitNeed("iron_ingot", TOTAL), KitPlanner.plan(f, cfg, BEDS).get(0));
        f.give(Items.IRON_INGOT, 3);
        assertTrue(EarlyIronPick.front(f, cfg).isEmpty());
        assertEquals("iron_ingot", KitPlanner.plan(f, cfg, BEDS).get(0).catalogueName());
    }

    @Test
    public void aKitWithoutAnIronPickHasNothingToRush() {
        cfg.ironKit.removeIf(k -> k.item.equals("iron_pickaxe"));
        assertFalse(due(mining().give(Items.RAW_IRON, 3)));
    }

    @Test
    public void theEarlyBatchIsRecognisedForSmeltSurface() {
        FakeFacts f = mining().give(Items.RAW_IRON, 3);
        assertTrue(EarlyIronPick.isEarlyBatch(new KitNeed("iron_ingot", 3), f, cfg));
        // the big batch keeps the go up first rule
        assertFalse(EarlyIronPick.isEarlyBatch(new KitNeed("iron_ingot", TOTAL), f, cfg));
        assertFalse(EarlyIronPick.isEarlyBatch(new KitNeed("iron_pickaxe", 1), f, cfg));
        assertFalse(EarlyIronPick.isEarlyBatch(null, f, cfg));
        // only the pickaxe left to pay for: the ore is all in, that is the big batch
        assertFalse(EarlyIronPick.isEarlyBatch(new KitNeed("iron_ingot", 3), complete().give(Items.RAW_IRON, 3), cfg));
    }

    // everything except the iron pickaxe
    private FakeFacts complete() {
        FakeFacts full = mining();
        for (Item i : new Item[]{Items.IRON_SWORD, Items.FLINT_AND_STEEL, Items.SHIELD, Items.SHEARS,
                Items.IRON_CHESTPLATE, Items.IRON_HELMET, Items.IRON_LEGGINGS, Items.IRON_BOOTS}) {
            full.give(i, 1);
        }
        full.give(Items.BUCKET, 2).give(Items.WOOL.white(), 24);
        full.worn.addAll(List.of(Items.IRON_CHESTPLATE, Items.IRON_HELMET, Items.IRON_LEGGINGS, Items.IRON_BOOTS));
        return full;
    }

    @Test
    public void theFurnaceTripIsForcedOnlyWhileThePickIsStillOwed() {
        FakeFacts cooking = mining().cooking("iron_ingot", 3, 30);
        assertTrue(EarlyIronPick.collectNow(cooking, cfg));
        // already holding them (or the pick): nothing special, the normal boundaries apply
        assertFalse(EarlyIronPick.collectNow(mining().give(Items.IRON_INGOT, 3).cooking("iron_ingot", 3, 30), cfg));
        assertFalse(EarlyIronPick.collectNow(mining().give(Items.IRON_PICKAXE, 1).cooking("iron_ingot", 3, 30), cfg));
        // nothing cooking, nothing to go back for
        assertFalse(EarlyIronPick.collectNow(mining(), cfg));
        FakeFacts off = mining().cooking("iron_ingot", 3, 30);
        off.earlyIronPick = false;
        assertFalse(EarlyIronPick.collectNow(off, cfg));
    }

    @Test
    public void thePickWaitingOnItsIngotsPullsTheBotOutOfTheMineTheMomentTheyAreDone() {
        FakeFacts f = mining().cooking("iron_ingot", 3, 30);
        SmeltFiller.Schedule s = SmeltFiller.schedule(f, cfg, BEDS);
        // the mining need leads and is runnable, collectNow is what says the pick is worth the detour
        assertEquals("iron_ingot", s.runnable().get(0).catalogueName());
        boolean interrupt = EarlyIronPick.collectNow(f, cfg);
        assertTrue(interrupt);
        // mid mining, nothing done yet: keep digging
        FurnacePlan.Moment mid = new FurnacePlan.Moment(true, false, interrupt, false, true);
        assertEquals(FurnacePlan.Call.LEAVE, FurnacePlan.plan(f.furnaceJobs(), mid, f.gameTime(), null).verdicts().get(0).call());
        // done: go, whatever the mining need is up to
        FurnacePlan.Verdict go = FurnacePlan.plan(f.furnaceJobs(), mid, f.seconds(31).gameTime(), null).verdicts().get(0);
        assertEquals(FurnacePlan.Call.COLLECT_NOW, go.call());
        assertEquals(FurnacePlan.Why.INTERRUPT, go.why());
        // without the early pick rule the same moment waits for the end of the need
        FurnacePlan.Moment plain = new FurnacePlan.Moment(true, false, false, false, true);
        assertEquals(FurnacePlan.Call.LEAVE, FurnacePlan.plan(f.furnaceJobs(), plain, f.gameTime(), null).verdicts().get(0).call());
    }

    @Test
    public void theCookingScheduleKeepsMiningAndBlocksThePickUntilTheIngotsAreBack() {
        FakeFacts f = mining().cooking("iron_ingot", 3, 30);
        SmeltFiller.Schedule s = SmeltFiller.schedule(f, cfg, BEDS);
        assertEquals("iron_ingot", s.runnable().get(0).catalogueName());
        assertTrue(names(s.blocked()).contains("iron_pickaxe"));
        // 31 s on, the job is due
        assertTrue(FurnacePlan.anyDue(f.furnaceJobs(), f.seconds(31).gameTime()));
    }

    // the 16:08 run: the first raw iron went into the furnace, the bag stopped showing three, the early need vanished and the
    // 39 need took the head (and then pulled the ore out of the furnace again)
    @Test
    public void aLoadInFlightKeepsTheEarlyNeedWithTheOreOutOfTheBag() {
        FakeFacts f = mining();
        f.earlyLoad = true;
        assertTrue(due(f));
        List<KitNeed> plan = KitPlanner.plan(f, cfg, BEDS);
        assertEquals(new KitNeed("iron_ingot", 3), plan.get(0));
        assertEquals(new KitNeed("iron_ingot", TOTAL), plan.get(1));
        // even with a single ore still in the bag (the move goes one at a time)
        f.give(Items.RAW_IRON, 1);
        assertEquals(new KitNeed("iron_ingot", 3), KitPlanner.plan(f, cfg, BEDS).get(0));
        // and the same bag without the flag is the old plan
        FakeFacts without = mining().give(Items.RAW_IRON, 1);
        assertEquals(new KitNeed("iron_ingot", TOTAL), KitPlanner.plan(without, cfg, BEDS).get(0));
    }

    @Test
    public void aLoadInFlightEndsWhenItIsCookingOrDone() {
        FakeFacts cooking = mining().cooking("iron_ingot", 3, 30);
        cooking.earlyLoad = true;
        assertFalse(due(cooking));
        FakeFacts ingots = mining().give(Items.IRON_INGOT, 3);
        ingots.earlyLoad = true;
        assertFalse(due(ingots));
        FakeFacts pick = mining().give(Items.IRON_PICKAXE, 1);
        pick.earlyLoad = true;
        assertFalse(due(pick));
        FakeFacts off = mining();
        off.earlyLoad = true;
        off.earlyIronPick = false;
        assertFalse(due(off));
    }

    @Test
    public void aLoadInFlightStillCountsAsTheEarlyBatchForSmeltSurface() {
        FakeFacts f = mining();
        f.earlyLoad = true;
        assertTrue(EarlyIronPick.isEarlyBatch(new KitNeed("iron_ingot", 3), f, cfg));
        assertFalse(EarlyIronPick.isEarlyBatch(new KitNeed("iron_ingot", TOTAL), f, cfg));
    }

    @Test
    public void theFlightWindowHasAnEnd() {
        assertFalse(FurnacePlan.earlyLoadInFlight(-1, 100));
        assertTrue(FurnacePlan.earlyLoadInFlight(100, 100));
        assertTrue(FurnacePlan.earlyLoadInFlight(100, 100 + FurnacePlan.EARLY_LOAD_TICKS - 1));
        assertFalse(FurnacePlan.earlyLoadInFlight(100, 100 + FurnacePlan.EARLY_LOAD_TICKS));
        // a clock that went backwards (a relog, another world) is not a flight
        assertFalse(FurnacePlan.earlyLoadInFlight(5000, 10));
    }

    @Test
    public void trackStartsTheFlightWhenTheEarlyBatchLeadsAndDoesNotRestartIt() {
        RunState state = new RunState();
        FakeFacts f = mining().give(Items.RAW_IRON, 3);
        f.gameTime = 400;
        KitNeed early = new KitNeed("iron_ingot", 3);
        EarlyIronPick.track(state, early, f, cfg);
        assertEquals(400, state.earlyLoadTick);
        // the facts see the flight now (MinecraftFacts reads the state), later ticks leave the stamp alone
        f.earlyLoad = true;
        f.gameTime = 460;
        EarlyIronPick.track(state, early, f, cfg);
        assertEquals(400, state.earlyLoadTick);
        // a head that is something else for a moment (food) does not end it either
        EarlyIronPick.track(state, new KitNeed(KitNeed.FOOD, 70), f, cfg);
        assertEquals(400, state.earlyLoadTick);
    }

    @Test
    public void trackLetsGoOnceTheEarlyNeedIsNoLongerDue() {
        RunState state = new RunState();
        state.earlyLoadTick = 400;
        FakeFacts f = mining().give(Items.IRON_INGOT, 3);
        f.earlyLoad = true;
        EarlyIronPick.track(state, new KitNeed("iron_pickaxe", 1), f, cfg);
        assertEquals(-1, state.earlyLoadTick);
        // and a run that never started one stays at none
        EarlyIronPick.track(state, null, mining(), cfg);
        assertEquals(-1, state.earlyLoadTick);
    }

    // ---- the whole early kit: the chestplate and shield out of the first 9 ore, the pick and shears out of the next 5

    @Test
    public void aFreshRunsEarlyBatchIsTheArmorStage() {
        OverworldConfig kit = full();
        assertEquals(STAGE1, EarlyIronPick.batch(mining(), kit));
        assertFalse(EarlyIronPick.due(mining().give(Items.RAW_IRON, STAGE1 - 1), kit, FULL));
        FakeFacts f = mining().give(Items.RAW_IRON, STAGE1);
        assertTrue(EarlyIronPick.due(f, kit, KitPlanner.ingotsNeeded(f, kit)));
        List<KitNeed> plan = KitPlanner.plan(f, kit, BEDS);
        assertEquals(new KitNeed("iron_ingot", STAGE1), plan.get(0));
        // the shared need still sees the whole kit, nothing counted twice
        assertEquals(new KitNeed("iron_ingot", FULL), plan.get(1));
        assertTrue(EarlyIronPick.isEarlyBatch(plan.get(0), f, kit));
        assertFalse(EarlyIronPick.isEarlyBatch(new KitNeed("iron_ingot", 3), f, kit));
    }

    @Test
    public void theSecondStageFiresOnItsOwnOnceTheArmorIsCrafted() {
        OverworldConfig kit = full();
        // chestplate made, the shield is waiting on wood and its ingot is still in the bag: the pick and shears are 5 more raw,
        // not stuck behind the shield
        FakeFacts f = mining().give(Items.IRON_CHESTPLATE, 1).give(Items.IRON_INGOT, 1);
        assertEquals(List.of("shield", "iron_pickaxe", "shears"), EarlyIronPick.owed(f, kit));
        assertTrue(EarlyIronPick.front(f, kit).isEmpty());
        assertEquals(6, EarlyIronPick.batch(f, kit));
        assertFalse(EarlyIronPick.due(f.give(Items.RAW_IRON, 4), kit, KitPlanner.ingotsNeeded(f, kit)));
        f.give(Items.RAW_IRON, 1);
        assertTrue(EarlyIronPick.due(f, kit, KitPlanner.ingotsNeeded(f, kit)));
        assertEquals(new KitNeed("iron_ingot", 6), KitPlanner.plan(f, kit, BEDS).get(0));
        // both armor pieces made, the bag empty: stage two on its own is 5
        FakeFacts armored = mining().give(Items.IRON_CHESTPLATE, 1).give(Items.SHIELD, 1);
        assertEquals(5, EarlyIronPick.batch(armored, kit));
        assertFalse(EarlyIronPick.due(armored.give(Items.RAW_IRON, 4), kit, KitPlanner.ingotsNeeded(armored, kit)));
        armored.give(Items.RAW_IRON, 1);
        assertTrue(EarlyIronPick.due(armored, kit, KitPlanner.ingotsNeeded(armored, kit)));
    }

    @Test
    public void aRespawnedBotThatKeptItsPickStillGetsTheArmorStage() {
        OverworldConfig kit = full();
        FakeFacts f = mining().give(Items.IRON_PICKAXE, 1).give(Items.SHEARS, 1);
        assertEquals(STAGE1, EarlyIronPick.batch(f, kit));
        assertFalse(EarlyIronPick.due(f.give(Items.RAW_IRON, STAGE1 - 1), kit, KitPlanner.ingotsNeeded(f, kit)));
        f.give(Items.RAW_IRON, 1);
        assertTrue(EarlyIronPick.due(f, kit, KitPlanner.ingotsNeeded(f, kit)));
        assertEquals(new KitNeed("iron_ingot", STAGE1), KitPlanner.plan(f, kit, BEDS).get(0));
    }

    @Test
    public void anyMissingEarlyItemFiresItAgain() {
        OverworldConfig kit = full();
        // a death took the armor and the shield and left the pick and the shears: the chestplate and the shield are the batch
        FakeFacts lost = mining().give(Items.IRON_PICKAXE, 1).give(Items.SHEARS, 1);
        assertEquals(9, EarlyIronPick.batch(lost, kit));
        assertFalse(EarlyIronPick.due(lost.give(Items.RAW_IRON, 8), kit, KitPlanner.ingotsNeeded(lost, kit)));
        lost.give(Items.RAW_IRON, 1);
        assertTrue(EarlyIronPick.due(lost, kit, KitPlanner.ingotsNeeded(lost, kit)));
        assertEquals(new KitNeed("iron_ingot", 9), KitPlanner.plan(lost, kit, BEDS).get(0));
        // only the shield missing is a batch of one
        FakeFacts shield = complete().give(Items.IRON_PICKAXE, 1);
        shield.items.remove(Items.SHIELD);
        assertEquals(1, EarlyIronPick.batch(shield, kit));
        // with the buckets still to make too it is worth its own load
        shield.items.remove(Items.BUCKET);
        shield.give(Items.RAW_IRON, 1);
        assertTrue(EarlyIronPick.due(shield, kit, KitPlanner.ingotsNeeded(shield, kit)));
        // everything held: nothing early left
        assertEquals(0, EarlyIronPick.batch(complete().give(Items.IRON_PICKAXE, 1), kit));
        assertFalse(EarlyIronPick.wanted(complete().give(Items.IRON_PICKAXE, 1), kit));
    }

    @Test
    public void theEarlyBatchDoesNotFireWhenItWouldBeTheWholeBatch() {
        OverworldConfig kit = full();
        assertFalse(EarlyIronPick.due(mining().give(Items.RAW_IRON, FULL), kit, FULL));
        // only the shield left to pay for: its one ore is all the iron there is, that is the big batch
        FakeFacts shield = complete().give(Items.IRON_PICKAXE, 1).give(Items.RAW_IRON, 1);
        shield.items.remove(Items.SHIELD);
        assertEquals(1, KitPlanner.ingotsNeeded(shield, kit));
        assertFalse(EarlyIronPick.due(shield, kit, KitPlanner.ingotsNeeded(shield, kit)));
    }

    @Test
    public void aChestplateTheDiamondsReplaceIsNotSmeltedFor() {
        OverworldConfig kit = full();
        // 8 diamonds and a diamond pick held: DiamondSpend takes the chestplate, the early batch is the shield and the shears
        FakeFacts f = mining().give(Items.DIAMOND_PICKAXE, 1).give(Items.DIAMOND, 8);
        assertEquals(List.of("diamond_chestplate"), DiamondSpend.choose(f, kit));
        assertEquals(List.of("shield", "shears"), EarlyIronPick.owed(f, kit));
        // the shield is stage one, the shears ride behind it
        assertEquals(1, EarlyIronPick.batch(f, kit));
        // 11 is the pick and the chestplate: no early pick either (takesThePick, same rule)
        FakeFacts rich = mining().give(Items.DIAMOND, 11);
        assertEquals(List.of("shield", "shears"), EarlyIronPick.owed(rich, kit));
    }

    @Test
    public void aBigEarlyBatchIsAJobOnceItIsLitNotALatch() {
        OverworldConfig kit = full();
        // the load and walk off records the job and that is the end of due: the cook is the job's business, however long it is
        FakeFacts cooking = mining().cooking("iron_ingot", EARLY, 140);
        cooking.earlyLoad = true;
        assertFalse(EarlyIronPick.due(cooking, kit, KitPlanner.ingotsNeeded(cooking, kit)));
        assertTrue(EarlyIronPick.collectNow(cooking, kit));
        // half of it out: still going back for the rest
        assertTrue(EarlyIronPick.collectNow(mining().give(Items.IRON_INGOT, 7).cooking("iron_ingot", 7, 70), kit));
        assertFalse(EarlyIronPick.collectNow(mining().give(Items.IRON_INGOT, EARLY).cooking("iron_ingot", 2, 20), kit));
        List<KitNeed> during = KitPlanner.plan(mining().cooking("iron_ingot", EARLY, 140), kit, BEDS);
        assertEquals(new KitNeed("iron_ingot", FULL), during.get(0));
        assertEquals(1, during.stream().filter(n -> n.catalogueName().equals("iron_ingot")).count());
    }

    @Test
    public void theEarlyCraftsGoFirstChestplateThenShieldThenPickThenShears() {
        OverworldConfig kit = full();
        FakeFacts out = mining().give(Items.IRON_INGOT, EARLY).give(Items.OAK_PLANKS, 6);
        assertEquals(List.of("iron_chestplate", "shield", "iron_pickaxe", "shears"), EarlyIronPick.front(out, kit));
        List<KitNeed> plan = KitPlanner.plan(out, kit, BEDS);
        assertEquals(List.of("iron_chestplate", "shield", "iron_pickaxe", "shears", "iron_ingot"), names(plan).subList(0, 5));
        // a log is four planks, two of them are the shield
        FakeFacts logs = mining().give(Items.IRON_INGOT, EARLY).give(Items.OAK_LOG, 2);
        assertEquals(List.of("iron_chestplate", "shield", "iron_pickaxe", "shears"), EarlyIronPick.front(logs, kit));
    }

    @Test
    public void noPlanksMeansTheShieldWaitsAndTheRestGoAhead() {
        OverworldConfig kit = full();
        FakeFacts out = mining().give(Items.IRON_INGOT, EARLY);
        assertEquals(List.of("iron_chestplate", "iron_pickaxe", "shears"), EarlyIronPick.front(out, kit));
        List<KitNeed> plan = KitPlanner.plan(out, kit, BEDS);
        assertEquals(List.of("iron_chestplate", "iron_pickaxe", "shears", "iron_ingot"), names(plan).subList(0, 4));
        // the shield is still in the plan, behind the mining like any other craft
        assertTrue(names(plan).indexOf("shield") > names(plan).indexOf("iron_ingot"));
    }

    @Test
    public void aShortBagNeverSpendsAnEarlierItemsIngots() {
        OverworldConfig kit = full();
        // chestplate owed, two ingots: those are the chestplate's, not the shears'
        assertTrue(EarlyIronPick.front(mining().give(Items.IRON_INGOT, 2), kit).isEmpty());
        // three: the chestplate owes eight and goes first, nothing behind it jumps the queue
        assertTrue(EarlyIronPick.front(mining().give(Items.IRON_INGOT, 3).give(Items.OAK_PLANKS, 6), kit).isEmpty());
        // the chestplate held, the shield and pick owed: the shield takes its one, the pick waits for its three
        assertEquals(List.of("shield"), EarlyIronPick.front(
                mining().give(Items.IRON_CHESTPLATE, 1).give(Items.IRON_INGOT, 2).give(Items.OAK_PLANKS, 6), kit));
        // the pick held, the chestplate owed, two ingots: nothing jumps the chestplate
        assertTrue(EarlyIronPick.front(mining().give(Items.IRON_PICKAXE, 1).give(Items.IRON_INGOT, 2), kit).isEmpty());
        // the chestplate held too: the shears get their two
        FakeFacts armored = mining().give(Items.IRON_PICKAXE, 1).give(Items.IRON_CHESTPLATE, 1).give(Items.SHIELD, 1)
                .give(Items.IRON_INGOT, 2);
        assertEquals(List.of("shears"), EarlyIronPick.front(armored, kit));
        // setting off: no early crafts at all
        FakeFacts off = mining().give(Items.IRON_INGOT, EARLY);
        off.earlyIronPick = false;
        assertTrue(EarlyIronPick.front(off, kit).isEmpty());
        assertEquals(0, EarlyIronPick.batch(off, kit));
    }

    @Test
    public void armorOutOfTheEarlyCraftsGoesOnBeforeTheMining() {
        OverworldConfig kit = full();
        FakeFacts f = mining().give(Items.IRON_PICKAXE, 1).give(Items.IRON_CHESTPLATE, 1).give(Items.IRON_INGOT, 3);
        List<KitNeed> plan = KitPlanner.plan(f, kit, BEDS);
        // the plan head stays the work (the shears out of the ingots in hand), the runner's list leads with armor on
        assertEquals(new KitNeed("shears", 1), plan.get(0));
        assertEquals(new KitNeed(KitNeed.EQUIP_ARMOR, 1), HeadLatch.armorFirst(plan).get(0));
        // worn, it is gone from the plan
        f.worn.add(Items.IRON_CHESTPLATE);
        assertFalse(names(KitPlanner.plan(f, kit, BEDS)).contains(KitNeed.EQUIP_ARMOR));
    }

    private static RunState.FurnaceJob job(String output) {
        RunState.FurnaceJob job = new RunState.FurnaceJob();
        job.output = output;
        return job;
    }

    @Test
    public void onlyTheIronJobEndsTheEarlyLoad() {
        // the meat landing in a smoker mid load is not the iron cooking
        assertFalse(EarlyIronPick.endsLoad(List.of(job("cooked_beef"))));
        assertFalse(EarlyIronPick.endsLoad(List.of()));
        assertTrue(EarlyIronPick.endsLoad(List.of(job("iron_ingot"))));
        assertTrue(EarlyIronPick.endsLoad(List.of(job("cooked_porkchop"), job("iron_ingot"))));
    }

    // the pick's ingots never pay for spares: one shield early, the rest come with the normal crafts
    @Test
    public void theEarlyBatchMakesOneShieldWhateverTheSparesAre() {
        OverworldConfig kit = full();
        kit.netherSpareShields = 2;
        FakeFacts out = mining().give(Items.IRON_INGOT, EARLY).give(Items.OAK_PLANKS, 6);
        assertEquals(List.of("iron_chestplate", "shield", "iron_pickaxe", "shears"), EarlyIronPick.front(out, kit));
        assertEquals(EARLY, EarlyIronPick.batch(out, kit));
        List<KitNeed> plan = KitPlanner.plan(out, kit, BEDS);
        assertEquals(new KitNeed("shield", 1), plan.get(1));
        // the spares wait until the first one exists
        assertEquals(1, plan.stream().filter(n -> n.catalogueName().equals("shield")).count());
        // one shield in hand and the early shield is done, the spares are not early work
        FakeFacts one = mining().give(Items.IRON_INGOT, EARLY).give(Items.SHIELD, 1);
        assertFalse(EarlyIronPick.owed(one, kit).contains("shield"));
    }
}
