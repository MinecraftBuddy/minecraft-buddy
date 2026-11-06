package adris.altoclef.tasks.speedrun.gamer.end;

import adris.altoclef.tasks.speedrun.gamer.FoodPlan;
import adris.altoclef.tasks.speedrun.gamer.GamerPhase;
import adris.altoclef.tasks.speedrun.gamer.Respawn;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.config.EndConfig;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.util.helpers.FoodHelper;
import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class EndGearTest {
    private final EndConfig cfg = new EndConfig();

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    // the gap as END_PREP asks for it: the engine builds the tick's FoodPlan from the same facts, for that phase, and hands it in
    private EndGear.Gap missing(FakeFacts f, RunState s) {
        GamerConfig all = new GamerConfig();
        all.end = cfg;
        return EndGear.missing(f, s, cfg, FoodPlan.of(f, all, GamerPhase.END_PREP, false));
    }

    private FakeFacts ready() {
        FakeFacts f = new FakeFacts().with(Items.BED.white(), cfg.beds).with(Items.IRON_SWORD, 1).with(Items.WATER_BUCKET, 1)
                .with(Items.IRON_PICKAXE, 1);
        f.wear(Items.IRON_HELMET).wear(Items.IRON_CHESTPLATE).wear(Items.IRON_LEGGINGS).wear(Items.IRON_BOOTS);
        f.armorPoints = 15;
        return f;
    }

    private static RunState diedInEndAt(long gameTime) {
        RunState s = new RunState();
        RunState.Death d = new RunState.Death();
        d.dimension = "END";
        d.gameTime = gameTime;
        s.deaths.add(d);
        return s;
    }

    @Test
    public void anEndDeathStillAsksForEveryBed() {
        FakeFacts f = ready().with(Items.BED.white(), 1);
        assertEquals(cfg.beds - 1, missing(f, diedInEndAt(100)).beds());
        assertFalse(EndGear.mayEnter(1, cfg));
    }

    @Test
    public void onlyTheBagGetsUsIn() {
        assertFalse(EndGear.mayEnter(0, cfg));
        assertFalse(EndGear.mayEnter(cfg.beds - 1, cfg));
        assertTrue(EndGear.mayEnter(cfg.beds, cfg));
        assertTrue(EndGear.mayEnter(cfg.beds + 1, cfg));
        // a night bed lost out of a full bag shuts the gate again, nothing remembers it was ever open
        FakeFacts f = ready();
        assertEquals(0, missing(f, new RunState()).beds());
        f.with(Items.BED.white(), cfg.beds - 1);
        assertEquals(1, missing(f, new RunState()).beds());
    }

    @Test
    public void aBedOnTheCursorIsNotInTheBag() {
        FakeFacts f = ready();
        f.bedsOutOfBag = 1;
        assertEquals(1, missing(f, new RunState()).beds());
        assertEquals(1, EndGear.bedShortfall(f, cfg.beds, false));
        assertFalse(EndGear.mayEnter(f.bedsInBag(), cfg));
    }

    @Test
    public void theDefaultIsTheFullStack() {
        assertEquals(9, new EndConfig().beds);
    }

    @Test
    public void theDeathPileNeverCountsAsBeds() {
        FakeFacts f = ready().with(Items.BED.white(), cfg.beds - 3);
        RunState s = diedInEndAt(90_000);
        s.endDrops.put("white_bed", 3);
        f.gameTime = 92_000;
        assertEquals(3, missing(f, s).beds());
        assertEquals(3, EndGear.bedShortfall(f, cfg.beds, false));
    }

    @Test
    public void waterBucketGapOnlyWithAnEmptyBucketToFill() {
        // nothing in hand: no bucket to fill and we do not craft one for this
        assertFalse(EndGear.wantsWater(false, false, false));
        assertTrue(EndGear.wantsWater(false, true, false));
        // held (or lying in the End) water is not a gap
        assertFalse(EndGear.wantsWater(true, true, false));
    }

    @Test
    public void waterBucketGapEndsWhenTheAttemptGivesUp() {
        assertFalse(EndGear.wantsWater(false, true, true));
        assertFalse(EndGear.bucketTimedOut(0));
        assertFalse(EndGear.bucketTimedOut(EndGear.BUCKET_BUDGET_TICKS - 1));
        assertTrue(EndGear.bucketTimedOut(EndGear.BUCKET_BUDGET_TICKS));
    }

    @Test
    public void gapFollowsTheBucketFacts() {
        GamerConfig all = new GamerConfig();
        all.end = cfg;
        FakeFacts noWater = new FakeFacts().with(Items.BED.white(), 8).with(Items.IRON_SWORD, 1).with(Items.IRON_PICKAXE, 1)
                .with(Items.BUCKET, 1);
        noWater.wear(Items.IRON_HELMET).wear(Items.IRON_CHESTPLATE).wear(Items.IRON_LEGGINGS).wear(Items.IRON_BOOTS);
        noWater.armorPoints = 15;
        // an empty bucket and no water one: the gate wants it, and drops it once the attempt gave up (a reset is a fresh false)
        assertTrue(EndGear.missing(noWater, new RunState(), cfg, FoodPlan.of(noWater, all, GamerPhase.END_PREP, false)).waterBucket());
        assertFalse(EndGear.missing(noWater, new RunState(), cfg, FoodPlan.of(noWater, all, GamerPhase.END_PREP, false), true).waterBucket());
        // the water bucket in the bag
        FakeFacts held = ready().with(Items.BUCKET, 1);
        assertFalse(missing(held, new RunState()).waterBucket());
        // water lying in the End counts as owned until it despawns
        RunState died = diedInEndAt(90_000);
        died.endDrops.put("water_bucket", 1);
        noWater.gameTime = 90_000 + 10;
        assertFalse(missing(noWater, died).waterBucket());
        noWater.gameTime = 90_000 + cfg.dropLifetimeTicks;
        assertTrue(missing(noWater, died).waterBucket());
    }

    @Test
    public void fullKitMissesNothing() {
        assertTrue(missing(ready(), new RunState()).none());
    }

    @Test
    public void emptyHandsMissEverything() {
        FakeFacts f = new FakeFacts().with(Items.BUCKET, 1);
        f.blocks = 0;
        f.food = 0;
        EndGear.Gap gap = missing(f, new RunState());
        assertEquals(cfg.beds, gap.beds());
        assertTrue(gap.weapon());
        assertTrue(gap.waterBucket());
        assertTrue(gap.pickaxe());
        assertEquals(cfg.buildBlocks, gap.buildBlocks());
        assertTrue(gap.food());
        assertFalse(gap.none());
    }

    @Test
    public void foodGateIsTheEndsOwnFloorNotTheOverworldMinimum() {
        FakeFacts f = ready();
        // 24 is the End's line and 70 the overworld's: a bag in between is fine to walk in with
        f.food = cfg.minFoodUnits;
        assertFalse(missing(f, new RunState()).food());
        f.food = cfg.minFoodUnits - 1;
        assertTrue(missing(f, new RunState()).food());
        assertTrue(new OverworldConfig().minFoodUnits > cfg.minFoodUnits);
    }

    @Test
    public void rawMeatNothingCanCookIsWorthItsRawValueAtTheGate() {
        // three raw porkchop book as 24 and the food chain eats them at 3 apiece
        FakeFacts f = ready().with(Items.PORKCHOP, 3);
        f.food = 3 * FoodHelper.plannedNutrition(Items.PORKCHOP);
        // the bare bag figure the gate used to read: exactly on the line, so it never fired
        assertEquals(cfg.minFoodUnits, f.food);
        // what there is to eat is 9, and nothing standing here cooks it
        assertTrue(missing(f, new RunState()).food());
        // real cooked food on top of it is another matter
        f.with(Items.COOKED_BEEF, 3);
        f.food += 3 * FoodHelper.plannedNutrition(Items.COOKED_BEEF);
        assertFalse(missing(f, new RunState()).food());
    }

    @Test
    public void aSmokerStandingNextToRawMeatDoesNotMakeItDinnerAtTheGate() {
        // three raw porkchop with a smoker and coal at hand: the kit would cook them, END_PREP never walks back to the smoker
        FakeFacts f = ready().with(Items.PORKCHOP, 3).with(Items.COAL, 2);
        f.smokerPlaced = true;
        f.food = 3 * FoodHelper.plannedNutrition(Items.PORKCHOP);
        assertEquals(cfg.minFoodUnits, f.food);
        assertTrue(missing(f, new RunState()).food());
    }

    @Test
    public void foodStillCookingDoesNotCountAtTheGate() {
        // 4 porkchop in a smoker we left behind: it is held for the kit and the portal, and for the End it is not there yet
        FakeFacts f = ready();
        f.food = 0;
        f.cookingFood("cooked_porkchop", 4, 8);
        assertEquals(32, FoodPlan.of(f, new OverworldConfig(), cfg, true).held());
        assertTrue(missing(f, new RunState()).food());
        assertFalse(missing(f, new RunState()).none());
        // the bag has to carry the floor by itself, the batch is on top and moves nothing
        f.food = cfg.minFoodUnits;
        assertFalse(missing(f, new RunState()).food());
        f.food = cfg.minFoodUnits - 1;
        assertTrue(missing(f, new RunState()).food());
    }

    @Test
    public void rawMeatBehindAStaleBatchIsStillWorthItsRawValueAtTheGate() {
        // three raw beef (24 on paper, 9 to eat) and a batch cooking in a smoker that END_PREP will never go back to: the gate has
        // the 9, not the 24 the old read gave, and not the batch either
        FakeFacts f = ready().with(Items.BEEF, 3);
        f.food = 3 * FoodHelper.plannedNutrition(Items.BEEF);
        f.cookingFood("cooked_porkchop", 4, 8);
        assertEquals(cfg.minFoodUnits, f.food);
        assertTrue(missing(f, new RunState()).food());
        // real cooked food on top clears it, the batch still does not count
        f.with(Items.COOKED_BEEF, 2);
        f.food += 2 * FoodHelper.plannedNutrition(Items.COOKED_BEEF);
        assertFalse(missing(f, new RunState()).food());
    }

    @Test
    public void bedsAreCountedAcrossColours() {
        FakeFacts f = ready().with(Items.BED.white(), cfg.beds - 2).with(Items.BED.red(), 1);
        assertEquals(1, missing(f, new RunState()).beds());
    }

    @Test
    public void noAttemptGoesWithNoBeds() {
        FakeFacts f = ready().with(Items.BED.white(), 0);
        assertFalse(missing(f, new RunState()).none());
        assertEquals(cfg.beds, missing(f, new RunState()).beds());
    }

    @Test
    public void buildBlocksGateIsTheMinimumButTheTargetIsTheFullStack() {
        FakeFacts f = ready();
        f.blocks = cfg.minBuildBlocks;
        assertEquals(0, missing(f, new RunState()).buildBlocks());
        f.blocks = cfg.minBuildBlocks - 1;
        assertEquals(cfg.buildBlocks, missing(f, new RunState()).buildBlocks());
    }

    @Test
    public void gearLeftInTheEndCountsWhileItIsStillThere() {
        FakeFacts f = new FakeFacts().with(Items.IRON_SWORD, 1);
        RunState s = diedInEndAt(90_000);
        s.endDrops.put("iron_pickaxe", 1);
        s.endDrops.put("white_bed", 3);
        f.gameTime = 92_000;
        EndGear.Gap gap = missing(f, s);
        assertFalse(gap.pickaxe());
        // the pickaxe yes, the beds no: those have to be in the bag
        assertEquals(cfg.beds, gap.beds());
    }

    @Test
    public void despawnedDropsAreForgotten() {
        FakeFacts f = new FakeFacts();
        RunState s = diedInEndAt(90_000);
        s.endDrops.put("iron_pickaxe", 1);
        // 6000 ticks is the despawn, margin makes it 5400
        f.gameTime = 90_000 + cfg.dropLifetimeTicks;
        assertTrue(missing(f, s).pickaxe());
        f.gameTime = 90_000 + cfg.dropLifetimeTicks - 1;
        assertFalse(missing(f, s).pickaxe());
    }

    @Test
    public void dropsWithoutADeathInTheEndAreStaleCache() {
        RunState s = new RunState();
        s.endDrops.put("iron_pickaxe", 1);
        assertTrue(missing(new FakeFacts(), s).pickaxe());
        RunState diedElsewhere = new RunState();
        RunState.Death d = new RunState.Death();
        d.dimension = "NETHER";
        d.gameTime = 99_999;
        diedElsewhere.deaths.add(d);
        diedElsewhere.endDrops.put("iron_pickaxe", 1);
        assertTrue(missing(new FakeFacts(), diedElsewhere).pickaxe());
    }

    @Test
    public void diamondToolsSatisfyTheGate() {
        FakeFacts f = ready().with(Items.IRON_SWORD, 0).with(Items.IRON_PICKAXE, 0).with(Items.DIAMOND_SWORD, 1).with(Items.DIAMOND_PICKAXE, 1);
        assertTrue(missing(f, new RunState()).none());
    }

    @Test
    public void armorWeHoldButDoNotWearGetsWorn() {
        FakeFacts f = ready();
        f.worn.remove(Items.IRON_CHESTPLATE);
        f.with(Items.DIAMOND_CHESTPLATE, 1);
        List<Item> wear = EndGear.armorToWear(f);
        assertEquals(List.of(Items.DIAMOND_CHESTPLATE), wear);
    }

    @Test
    public void aSpareDiamondPieceGoesOverWornIronInTheSameSlot() {
        // skip unless what we hold is strictly better than what is on: a diamond helmet is, so it swaps
        FakeFacts f = ready().with(Items.DIAMOND_HELMET, 1);
        assertEquals(List.of(Items.DIAMOND_HELMET), EndGear.armorToWear(f));
    }

    @Test
    public void theSwappedOutIronPieceIsNotWornBack() {
        FakeFacts f = ready().with(Items.DIAMOND_HELMET, 1);
        f.worn.remove(Items.IRON_HELMET);
        f.worn.add(Items.DIAMOND_HELMET);
        // the iron helmet is still in the bag (with(..) counts it), and it ranks below what is on
        assertTrue(EndGear.armorToWear(f).isEmpty());
    }

    @Test
    public void wornDiamondBeatsABagOfIronAndNetheriteBeatsWornDiamond() {
        FakeFacts f = ready().with(Items.DIAMOND_CHESTPLATE, 1);
        f.worn.remove(Items.IRON_CHESTPLATE);
        f.worn.add(Items.DIAMOND_CHESTPLATE);
        assertTrue(EndGear.armorToWear(f).isEmpty());
        f.with(Items.NETHERITE_CHESTPLATE, 1);
        assertEquals(List.of(Items.NETHERITE_CHESTPLATE), EndGear.armorToWear(f));
    }

    @Test
    public void goldenBootsDoNotHoldTheBootsSlot() {
        FakeFacts f = ready();
        f.worn.remove(Items.IRON_BOOTS);
        f.wear(Items.GOLDEN_BOOTS);
        assertEquals(List.of(Items.IRON_BOOTS), EndGear.armorToWear(f));
    }

    @Test
    public void lowArmorIsReportedNotBlocking() {
        FakeFacts f = ready();
        f.armorPoints = 4;
        assertTrue(EndGear.armorLow(f, cfg));
        assertTrue(missing(f, new RunState()).none());
    }

    @Test
    public void spawnBedShortfallAsksForOneMoreAndGoesNegativeWithASpare() {
        FakeFacts f = ready();
        // all held, all required: nothing short, but a spawn bed makes it 1
        assertEquals(0, EndGear.bedShortfall(f, cfg.beds, false));
        assertEquals(1, EndGear.bedShortfall(f, cfg.beds, true));
        // placing the spawn bed takes one out of the inventory and the want goes away with it, no crafting loop
        f.with(Items.BED.white(), cfg.beds + 1);
        assertEquals(0, EndGear.bedShortfall(f, cfg.beds, true));
        f.with(Items.BED.white(), cfg.beds);
        assertEquals(0, EndGear.bedShortfall(f, cfg.beds, false));
    }

    @Test
    public void spawnBedWantFollowsConfigAndState() {
        RunState s = new RunState();
        assertTrue(EndGear.wantSpawnBed(s, cfg));
        assertEquals(cfg.beds + 1, EndGear.bedTarget(cfg, true));
        assertEquals(cfg.beds, EndGear.bedTarget(cfg, false));
        s.respawnPos = new RunState.Pos(1, 1, 1);
        assertFalse(EndGear.wantSpawnBed(s, cfg));
        s.respawnPos = null;
        s.spawnBedGaveUp = true;
        assertFalse(EndGear.wantSpawnBed(s, cfg));
        cfg.placeSpawnNearPortal = false;
        s.spawnBedGaveUp = false;
        assertFalse(EndGear.wantSpawnBed(s, cfg));
    }

    @Test
    public void pickupOrderIsBedsToolsBucketArmor() {
        FakeFacts f = new FakeFacts().with(Items.BED.white(), 2);
        assertEquals(Items.BED.red(), EndGear.nextPickup(f, cfg, item -> item == Items.BED.red() || item == Items.IRON_PICKAXE));
        assertEquals(Items.IRON_PICKAXE, EndGear.nextPickup(f.with(Items.BED.white(), 8), cfg, item -> item == Items.IRON_PICKAXE || item == Items.WATER_BUCKET));
        assertEquals(Items.WATER_BUCKET, EndGear.nextPickup(f.with(Items.IRON_PICKAXE, 1).with(Items.IRON_SWORD, 1), cfg, item -> item == Items.WATER_BUCKET || item == Items.DIAMOND_HELMET));
        assertEquals(Items.DIAMOND_HELMET, EndGear.nextPickup(f.with(Items.WATER_BUCKET, 1), cfg, item -> item == Items.DIAMOND_HELMET));
    }

    @Test
    public void nothingToFetchWhenWeAlreadyHaveIt() {
        FakeFacts f = ready();
        assertNull(EndGear.nextPickup(f, cfg, item -> item == Items.IRON_PICKAXE || item == Items.WATER_BUCKET
                || item == Items.BED.white() || item == Items.IRON_HELMET || item == Items.DIAMOND_HELMET));
    }

    @Test
    public void theGateNamesTheFirstGapInPhaseOrder() {
        assertEquals("none", EndGear.gate(new EndGear.Gap(0, false, false, false, 0, false, List.of())));
        assertEquals("beds", EndGear.gate(new EndGear.Gap(1, true, true, true, 20, true, List.of(Items.IRON_HELMET))));
        assertEquals("weapon", EndGear.gate(new EndGear.Gap(0, true, true, true, 20, true, List.of())));
        assertEquals("pickaxe", EndGear.gate(new EndGear.Gap(0, false, false, true, 20, true, List.of())));
        assertEquals("blocks", EndGear.gate(new EndGear.Gap(0, false, false, false, 20, true, List.of(Items.IRON_HELMET))));
        assertEquals("armor", EndGear.gate(new EndGear.Gap(0, false, false, false, 0, true, List.of(Items.IRON_HELMET))));
        assertEquals("food", EndGear.gate(new EndGear.Gap(0, false, false, false, 0, true, List.of())));
    }

    @Test
    public void theSpawnBedClockRunsFromItsOwnStep() {
        // started at 400 s into the phase: 300 s later, not at the 300 s mark of the phase
        assertFalse(EndGear.spawnClockExpired(500, 400, 300));
        assertFalse(EndGear.spawnClockExpired(700, 400, 300));
        assertTrue(EndGear.spawnClockExpired(701, 400, 300));
        // never started: no clock yet
        assertFalse(EndGear.spawnClockExpired(900, -1, 300));
    }

    @Test
    public void aFarErrandWantsFoodFirst() {
        assertTrue(EndGear.foodFirst(true, 200, 150));
        assertFalse(EndGear.foodFirst(true, 100, 150));
        assertFalse(EndGear.foodFirst(false, 900, 150));
    }

    @Test
    public void foodNeverReplacesTheWalkHomeOrTheSpawnBed() {
        assertFalse(EndGear.foodFirst(true, 900, 150, true));
        assertTrue(EndGear.foodFirst(true, 900, 150, false));
        assertFalse(EndGear.foodFirst(true, 10, 150, false));
    }

    @Test
    public void theTopUpNeedsRoomAndPrey() {
        assertTrue(EndGear.wantsTopUp(true, false, false));
        assertFalse(EndGear.wantsTopUp(false, false, false));
        assertFalse(EndGear.wantsTopUp(true, true, false));
        assertFalse(EndGear.wantsTopUp(true, false, true));
    }

    @Test
    public void aBagOverTheFloorButUnderTheWantIsNotAGapAndDoesNotReplaceAFarErrand() {
        EndConfig cfg = new EndConfig();
        assertEquals(24, cfg.minFoodUnits);
        assertEquals(56, cfg.wantFoodUnits);
        FakeFacts f = new FakeFacts();
        f.food = 30;
        FoodPlan food = FoodPlan.of(f, new GamerConfig(), GamerPhase.END_PREP, false);
        assertTrue(food.shortOfEnd(cfg.wantFoodUnits));
        boolean gap = EndGear.missing(f, new RunState(), cfg, food).food();
        assertFalse(gap);
        // the bed errand from across the map stays the bed errand
        assertFalse(EndGear.foodFirst(gap, 900, cfg.farErrandBlocks, false));
    }

    @Test
    public void theBedPlanNeverComesUpEmpty() {
        for (int wool = 0; wool < 4; wool++) {
            for (boolean known : new boolean[]{true, false}) {
                assertNotNull(EndGear.bedPlan(wool, known));
            }
        }
        assertEquals(EndGear.BedPlan.CRAFT, EndGear.bedPlan(1, true));
        assertEquals(EndGear.BedPlan.CRAFT, EndGear.bedPlan(1, false));
        assertEquals(EndGear.BedPlan.MINE, EndGear.bedPlan(0, true));
        assertEquals(EndGear.BedPlan.WOOL, EndGear.bedPlan(0, false));
    }

    @Test
    public void theSpawnBedGoesDownWhenCoveredOrWhenWeAreThere() {
        // nothing to place
        assertFalse(EndGear.spawnNow(0, 10, 0, 150));
        // by the portal it goes down right away, the gate has it replaced before we walk in
        assertTrue(EndGear.spawnNow(1, 9, 100, 150));
        // across the map it waits for the rest
        assertFalse(EndGear.spawnNow(1, 9, 900, 150));
        assertTrue(EndGear.spawnNow(10, 0, 900, 150));
    }

    @Test
    public void thePortalAndRoomBedsOnlySpendTheSurplus() {
        assertEquals(0, EndGear.surplusBeds(3, cfg.beds));
        assertEquals(0, EndGear.surplusBeds(cfg.beds, cfg.beds));
        assertEquals(1, EndGear.surplusBeds(cfg.beds + 1, cfg.beds));
        RunState s = new RunState();
        s.strongholdStart = new RunState.Pos(0, 70, 0);
        assertFalse(Respawn.wantRoomBed(s, true, EndGear.surplusBeds(cfg.beds, cfg.beds)));
        assertTrue(Respawn.wantRoomBed(s, true, EndGear.surplusBeds(cfg.beds + 1, cfg.beds)));
        assertFalse(Respawn.wantPortalBed(s, EndGear.surplusBeds(cfg.beds + 1, EndGear.bedTarget(cfg, true)), 0, 0));
        assertTrue(Respawn.wantPortalBed(s, EndGear.surplusBeds(cfg.beds + 2, EndGear.bedTarget(cfg, true)), 0, 0));
    }

    @Test
    public void theSpareGoesWhenNoneIsCloseOrTheBagIsFull() {
        int need = cfg.beds;
        assertTrue("none close by", EndGear.spareBedWaived(true, need, need, 0, false, false));
        assertTrue("nowhere to put it", EndGear.spareBedWaived(true, need, need, 0, true, true));
        assertFalse("one close by and room for it", EndGear.spareBedWaived(true, need, need, 0, true, false));
    }

    @Test
    public void theFightsBedsAndWoolAreNeverWaived() {
        int need = cfg.beds;
        assertFalse("short of the fight's beds, the errand has no cap", EndGear.spareBedWaived(true, need - 1, need, 0, false, true));
        assertFalse("wool in the bag still gets crafted", EndGear.spareBedWaived(true, need, need, 1, false, true));
        assertFalse("nothing owed, nothing to waive", EndGear.spareBedWaived(false, need, need, 0, false, true));
    }

    @Test
    public void theSpareReachIsFlatFromTheStart() {
        assertTrue(EndGear.spareBedInReach(0, 0, 100, 0));
        assertFalse(EndGear.spareBedInReach(0, 0, 101, 0));
        assertFalse(EndGear.spareBedInReach(0, 0, 80, 80));
        assertTrue(EndGear.spareBedInReach(500, -500, 460, -440));
    }

    @Test
    public void bedsWayDownLowAreNotForTheTaking() {
        assertTrue(EndGear.bedTooLow(-40));
        assertTrue(EndGear.bedTooLow(29));
        assertFalse(EndGear.bedTooLow(30));
        assertFalse(EndGear.bedTooLow(70));
    }

    @Test
    public void theRoomBedIsOwedOnlyWhileNothingHoldsTheStronghold() {
        RunState s = new RunState();
        s.strongholdStart = new RunState.Pos(0, 70, 0);
        assertTrue(EndGear.roomBedOwed(s, cfg));
        Respawn.set(s, new RunState.Pos(10, 70, 10), "OVERWORLD");
        assertFalse(EndGear.roomBedOwed(s, cfg));
        Respawn.set(s, new RunState.Pos(5000, 70, 10), "OVERWORLD");
        assertTrue(EndGear.roomBedOwed(s, cfg));
        s.spawnBedGaveUp = true;
        assertFalse(EndGear.roomBedOwed(s, cfg));
    }

    @Test
    public void noRunNoReserve() {
        Respawn.unbind();
        assertEquals(0, EndGear.reserveBeds());
    }

    @Test
    public void aLongSearchSaysSoEveryFiveMinutes() {
        assertTrue(EndGear.shortLogDue(-1, 0));
        assertFalse(EndGear.shortLogDue(1000, 1000 + 5999));
        assertTrue(EndGear.shortLogDue(1000, 1000 + 6000));
        assertEquals("endprep: short by 3 beds after 25 min, still looking, at 1 64 -2", EndGear.shortLine(3, 25, 1, 64, -2));
    }

    private GamerConfig armorCfg() {
        GamerConfig all = new GamerConfig();
        all.end = cfg;
        return all;
    }

    @Test
    public void aNakedRespawnMakesTheChestplateFirst() {
        FakeFacts f = new FakeFacts().with(Items.IRON_SWORD, 1).with(Items.IRON_PICKAXE, 1);
        assertEquals(Items.IRON_CHESTPLATE, EndGear.armorFloorPiece(f, armorCfg()));
        // a chestplate in the bag is the wear step's, the next slot is up
        f.with(Items.IRON_CHESTPLATE, 1);
        assertEquals(Items.IRON_LEGGINGS, EndGear.armorFloorPiece(f, armorCfg()));
        // worn diamond counts as covered, leather does not
        f.wear(Items.DIAMOND_LEGGINGS).wear(Items.LEATHER_HELMET);
        f.armorPoints = 7;
        assertEquals(Items.IRON_HELMET, EndGear.armorFloorPiece(f, armorCfg()));
    }

    @Test
    public void theArmorFloorIsQuietAtTheBedLineOrOffTheIronPlan() {
        FakeFacts f = new FakeFacts();
        f.armorPoints = cfg.bedMinArmor;
        assertNull(EndGear.armorFloorPiece(f, armorCfg()));
        f.armorPoints = 0;
        GamerConfig light = armorCfg();
        light.overworld.armorPlan = OverworldConfig.ArmorPlan.CHEST_HELMET;
        assertNull(EndGear.armorFloorPiece(f, light));
        // every slot iron or held and still short: nothing left to make
        FakeFacts covered = new FakeFacts().with(Items.IRON_HELMET, 1).with(Items.IRON_CHESTPLATE, 1).with(Items.IRON_LEGGINGS, 1)
                .with(Items.IRON_BOOTS, 1);
        assertNull(EndGear.armorFloorPiece(covered, armorCfg()));
    }

    @Test
    public void theArmorFloorClockAndItsGate() {
        assertFalse(EndGear.armorFloorTimedOut((int) (cfg.armorFloorSeconds * 20) - 1, cfg));
        assertTrue(EndGear.armorFloorTimedOut((int) (cfg.armorFloorSeconds * 20), cfg));
        // after the pickaxe, before the blocks
        assertEquals("pickaxe", EndGear.gate(new EndGear.Gap(0, false, false, true, 20, true, List.of()), Items.IRON_CHESTPLATE));
        assertEquals("armor_floor", EndGear.gate(new EndGear.Gap(0, false, false, false, 20, true, List.of()), Items.IRON_CHESTPLATE));
        assertEquals("blocks", EndGear.gate(new EndGear.Gap(0, false, false, false, 20, true, List.of()), null));
        // a piece in the bag goes on before the next one is made, the points only count worn armor
        assertEquals("armor", EndGear.gate(new EndGear.Gap(0, false, false, false, 20, true, List.of(Items.IRON_CHESTPLATE)),
                Items.IRON_LEGGINGS));
    }
}
