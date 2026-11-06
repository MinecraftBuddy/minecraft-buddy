package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.config.EndConfig;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.tasks.speedrun.gamer.end.EndGear;
import adris.altoclef.util.helpers.FoodHelper;
import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FoodPlanTest {
    private static final KitNeed FOOD = new KitNeed(KitNeed.FOOD, 70);

    private OverworldConfig cfg;
    private EndConfig end;

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    @Before
    public void setUp() {
        cfg = new OverworldConfig();
        cfg.netherSpareShields = 0;
        cfg.netherSpareSwords = 0;
        end = new EndConfig();
    }

    // a plan with exactly this held and this much raw meat left out of it, no facts needed. a refill is under way: the soft
    // top-up tests live in one, it is the only place the top-up exists (wantsRefill)
    private FoodPlan held(int held, int rawLeftOut) {
        return new FoodPlan(held + rawLeftOut, 0, rawLeftOut, 0, 0, 0, cfg, end, true);
    }

    // the same, with no trip under way
    private FoodPlan idle(int held) {
        return new FoodPlan(held, 0, 0, 0, 0, 0, cfg, end, false);
    }

    private FoodPlan plan(FakeFacts f, int beds) {
        return FoodPlan.ofBeds(f, cfg, beds);
    }

    // the same bag in the middle of a food trip (RunState.foodRefilling)
    private FoodPlan refill(FakeFacts f, int beds) {
        EndConfig e = new EndConfig();
        e.beds = beds;
        return FoodPlan.of(f, cfg, e, true, true);
    }

    // the kit's phases, where the cook runs
    private FoodPlan plan(FakeFacts f) {
        return FoodPlan.of(f, cfg, end, true);
    }

    // the same bag as a given phase sees it, through the entry the engine uses
    private FoodPlan planIn(FakeFacts f, GamerPhase phase) {
        GamerConfig all = new GamerConfig();
        all.overworld = cfg;
        all.end = end;
        return FoodPlan.of(f, all, phase, false);
    }

    // ---- the valuation

    @Test
    public void rawMeatCountsAtItsCookedValue() {
        // an apple and six raw mutton: raw that is 4 + 12 = 16, cooked it is 4 + 36 = 40
        assertEquals(6, FoodHelper.plannedNutrition(Items.MUTTON));
        assertEquals(6, FoodHelper.plannedNutrition(Items.COOKED_MUTTON));
        assertEquals(4, FoodHelper.plannedNutrition(Items.APPLE));
        assertEquals(4 + 6 * 6, FoodHelper.plannedNutrition(Items.APPLE) + 6 * FoodHelper.plannedNutrition(Items.MUTTON));
        for (Item[] pair : new Item[][]{{Items.PORKCHOP, Items.COOKED_PORKCHOP}, {Items.BEEF, Items.COOKED_BEEF},
                {Items.CHICKEN, Items.COOKED_CHICKEN}, {Items.RABBIT, Items.COOKED_RABBIT}, {Items.COD, Items.COOKED_COD},
                {Items.SALMON, Items.COOKED_SALMON}}) {
            assertEquals(pair[0].toString(), pair[1].components().get(DataComponents.FOOD).nutrition(), FoodHelper.plannedNutrition(pair[0]));
            assertTrue(pair[0].toString(), FoodHelper.plannedNutrition(pair[0]) > pair[0].components().get(DataComponents.FOOD).nutrition());
        }
        // everything else is what it says on the tin, and a stick is not dinner
        assertEquals(Items.BREAD.components().get(DataComponents.FOOD).nutrition(), FoodHelper.plannedNutrition(Items.BREAD));
        assertEquals(0, FoodHelper.plannedNutrition(Items.STICK));
    }

    private static FakeFacts tenRawPork() {
        FakeFacts f = new FakeFacts();
        // what MinecraftFacts books: ten porkchop at the cooked 8 each
        f.give(Items.PORKCHOP, 10);
        f.foodUnits = 80;
        return f;
    }

    @Test
    public void rawGapIsTheCookedValueMinusTheRawOne() {
        assertEquals(10 * (8 - 3), CookGate.rawGap(tenRawPork()));
        assertEquals(0, CookGate.rawGap(new FakeFacts().give(Items.COOKED_PORKCHOP, 10)));
        assertEquals(3, FoodHelper.ownNutrition(Items.PORKCHOP));
        assertEquals(0, FoodHelper.ownNutrition(Items.STICK));
    }

    @Test
    public void rawMeatWithNoWayToCookItCountsAtItsRawValue() {
        // no furnace, no smoker, no fuel, no cobble: ten porkchop get eaten raw, 30 units, and the kit hunts
        FakeFacts f = tenRawPork();
        assertFalse(CookGate.cookFeasible(f, cfg, 10));
        assertEquals(30, plan(f, 10).held());
        assertEquals(50, plan(f, 10).rawLeftOut());
        assertEquals(new KitNeed(KitNeed.FOOD, cfg.refillStopFoodUnits), KitPlanner.gather(f, cfg, 10, plan(f, 10)).stream()
                .filter(n -> n.catalogueName().equals(KitNeed.FOOD)).findFirst().orElse(null));
    }

    @Test
    public void rawMeatWithAStationAndFuelCountsAtItsCookedValue() {
        FakeFacts f = tenRawPork();
        f.smokerPlaced = true;
        f.give(Items.COAL, 4);
        assertTrue(CookGate.cookFeasible(f, cfg, 10));
        assertEquals(80, plan(f, 10).held());
        // a station with no fuel to burn is no way to cook it
        FakeFacts dry = tenRawPork();
        dry.smokerPlaced = true;
        assertEquals(30, plan(dry, 10).held());
        // and a cook that backed off is not one either
        f.cookSuspended = true;
        assertEquals(30, plan(f, 10).held());
    }

    @Test
    public void aCookInProgressKeepsTheCookedValue() {
        // loading right now (the meat is half in the slot) or a smoker already on a batch: the cook is happening
        FakeFacts loading = tenRawPork();
        loading.cookStation = "smoker";
        assertEquals(80, plan(loading, 10).held());
        FakeFacts cooking = tenRawPork();
        cooking.cookingFood("cooked_porkchop", 4, 8, 20);
        assertEquals(80 + 32, plan(cooking, 10).held());
        assertEquals(32, plan(cooking, 10).pending());
    }

    // ---- the phase decides whether a standing smoker makes dinner

    @Test
    public void onlyTheKitsPhasesCook() {
        for (GamerPhase p : GamerPhase.values()) {
            boolean kit = p == GamerPhase.GATHER || p == GamerPhase.IRON || p == GamerPhase.PORTAL;
            assertEquals(p.name(), kit, p.cooks());
        }
    }

    private FakeFacts rawPorkAndASmoker() {
        // three raw porkchop (24 on paper, 9 to eat), a smoker standing and coal to burn: the planner would cook it
        FakeFacts f = new FakeFacts();
        f.give(Items.PORKCHOP, 3).give(Items.COAL, 2);
        f.smokerPlaced = true;
        f.foodUnits = 3 * FoodHelper.plannedNutrition(Items.PORKCHOP);
        assertTrue(CookGate.cookFeasible(f, cfg, end.beds));
        return f;
    }

    @Test
    public void rawMeatIsCookedValueInThePhasesThatCookAndRawValueInTheRest() {
        FakeFacts f = rawPorkAndASmoker();
        for (GamerPhase p : new GamerPhase[]{GamerPhase.GATHER, GamerPhase.IRON, GamerPhase.PORTAL}) {
            assertEquals(p.name(), 24, planIn(f, p).held());
            assertEquals(p.name(), 0, planIn(f, p).rawLeftOut());
        }
        for (GamerPhase p : new GamerPhase[]{GamerPhase.NETHER, GamerPhase.EYES, GamerPhase.RETURN, GamerPhase.LOCATE, GamerPhase.ROOM,
                GamerPhase.OPEN, GamerPhase.END_PREP, GamerPhase.DRAGON}) {
            assertEquals(p.name(), 9, planIn(f, p).held());
            assertEquals(p.name(), 15, planIn(f, p).rawLeftOut());
            // counted is the plain sum in every phase, it is only for "did we get closer"
            assertEquals(p.name(), 24, planIn(f, p).counted());
        }
    }

    @Test
    public void theCookingPhasesReadWhatTheyAlwaysDid() {
        // the same bags through the phase entry and through the old no-phase one: every number the kit acts on is the same
        FakeFacts empty = new FakeFacts();
        FakeFacts noCook = tenRawPork();
        FakeFacts station = tenRawPork();
        station.smokerPlaced = true;
        station.give(Items.COAL, 4);
        FakeFacts dry = tenRawPork();
        dry.smokerPlaced = true;
        FakeFacts loading = tenRawPork();
        loading.cookStation = "smoker";
        FakeFacts cooking = tenRawPork();
        cooking.cookingFood("cooked_porkchop", 4, 8, 20);
        FakeFacts mixed = rawPorkAndASmoker();
        mixed.give(Items.COOKED_BEEF, 5);
        mixed.foodUnits += 40;
        for (FakeFacts f : new FakeFacts[]{empty, noCook, station, dry, loading, cooking, mixed}) {
            for (GamerPhase p : new GamerPhase[]{GamerPhase.GATHER, GamerPhase.IRON, GamerPhase.PORTAL}) {
                FoodPlan now = planIn(f, p);
                FoodPlan before = plan(f);
                assertEquals(p.name(), before.held(), now.held());
                assertEquals(p.name(), before.rawLeftOut(), now.rawLeftOut());
                assertEquals(p.name(), before.counted(), now.counted());
                assertEquals(p.name(), before.covered(), now.covered());
                assertEquals(p.name(), before.band(), now.band());
            }
        }
    }

    @Test
    public void aBatchPendingWhereNothingCollectsItDoesNotSaveTheRawMeat() {
        // nothing goes back to a station after PORTAL and the food task will not load a second batch behind the first, so the
        // ten porkchop in the bag stay raw (30 to eat) with a batch out there or without one
        FakeFacts f = tenRawPork();
        f.cookingFood("cooked_porkchop", 4, 8, 20);
        for (GamerPhase p : new GamerPhase[]{GamerPhase.NETHER, GamerPhase.LOCATE, GamerPhase.END_PREP, GamerPhase.DRAGON}) {
            assertEquals(p.name(), 50, planIn(f, p).rawLeftOut());
            assertEquals(p.name(), 30 + 32, planIn(f, p).held());
            assertEquals(p.name(), 30, planIn(tenRawPork(), p).held());
        }
        // the kit's phases do go back for it, the cook is happening and the bag is cooked-valued
        assertEquals(0, planIn(f, GamerPhase.IRON).rawLeftOut());
        assertEquals(80 + 32, planIn(f, GamerPhase.IRON).held());
    }

    @Test
    public void threeRawBeefBehindAStaleBatchAreWorthNineWhereNothingCooks() {
        // 3 raw beef is 24 on paper, 9 to eat, and a batch of 32 is cooking in a smoker nobody will come back to
        FakeFacts f = new FakeFacts();
        f.give(Items.BEEF, 3);
        f.foodUnits = 3 * FoodHelper.plannedNutrition(Items.BEEF);
        f.cookingFood("cooked_porkchop", 4, 8, 20);
        FoodPlan p = planIn(f, GamerPhase.END_PREP);
        assertEquals(15, p.rawLeftOut());
        assertEquals(32, p.pending());
        // held still counts the batch (the other gates would), what the bag is worth is the 9
        assertEquals(41, p.held());
        assertEquals(9, p.held() - p.pending());
        assertTrue(p.held() - p.pending() < end.minFoodUnits);
        // and the End gate says so: 9 of 24, the batch and the 24 on paper do not get it through
        assertTrue(p.shortOfEndFloor());
        assertTrue(EndGear.missing(f, new RunState(), end, p).food());
        // the hunt asks for the batch on top (the food task counts it as food on the way, the gate does not), and for the raw gap,
        // which the task counts cooked but will not cook while the batch is out
        assertEquals(end.minFoodUnits + FoodPlan.END_MARGIN + 32 + 15, p.endCollect());
    }

    @Test
    public void enoughRawMeatOnPaperBehindAStaleBatchStillSendsTheHuntOut() {
        // 5 raw beef: 40 on paper, 15 to eat. the food task's own count is 40 + the batch's 32 = 72, which used to clear a target of
        // 64, so it sat in its convert branch unable to cook and wandered. the target has to be over what it counts
        FakeFacts f = new FakeFacts();
        f.give(Items.BEEF, 5);
        f.foodUnits = 5 * FoodHelper.plannedNutrition(Items.BEEF);
        f.cookingFood("cooked_porkchop", 4, 8, 20);
        FoodPlan p = planIn(f, GamerPhase.END_PREP);
        assertTrue(p.shortOfEndFloor());
        assertEquals(32 + 32 + 25, p.endCollect());
        assertTrue(f.foodUnits + p.pending() < p.endCollect());
        // with no batch out the task cooks the raw meat itself, so the gap is not asked for
        FakeFacts noBatch = new FakeFacts();
        noBatch.give(Items.BEEF, 5);
        noBatch.foodUnits = 40;
        assertEquals(32, planIn(noBatch, GamerPhase.END_PREP).endCollect());
    }

    @Test
    public void theFoodFloorOfAPhaseThatNeverCooksSeesTheRawValue() {
        // LOCATE with 3 raw porkchop and a smoker standing: the floor used to read 24 and sit comfortably over 24
        FakeFacts f = rawPorkAndASmoker();
        assertFalse(planIn(f, GamerPhase.IRON).floorNext(false));
        FoodPlan locate = planIn(f, GamerPhase.LOCATE);
        assertTrue(locate.held() < cfg.minHeldFoodUnits);
        assertTrue(locate.floorNext(false));
    }

    @Test
    public void theEngineBuildsTheTicksPlanForThePhaseItIsIn() {
        // the default context (what a stub or a test hands a handler) takes the phase from the state
        StubContext ctx = new StubContext();
        ctx.cfg.overworld = cfg;
        ctx.cfg.end = end;
        ctx.facts.give(Items.PORKCHOP, 3).give(Items.COAL, 2);
        ctx.facts.smokerPlaced = true;
        ctx.facts.foodUnits = 3 * FoodHelper.plannedNutrition(Items.PORKCHOP);
        ctx.state.phase = GamerPhase.IRON;
        assertEquals(24, ctx.food().held());
        ctx.state.phase = GamerPhase.END_PREP;
        assertEquals(9, ctx.food().held());
    }

    @Test
    public void foodWithNoRawMeatIsTheSumItAlwaysWas() {
        FakeFacts f = new FakeFacts();
        f.foodUnits = 50;
        assertEquals(50, plan(f, 10).held());
        f.cookingFood("cooked_mutton", 3, 6, 20);
        assertEquals(68, plan(f, 10).held());
        assertEquals(50, plan(f, 10).bag());
    }

    // ---- the lines, and the two things called minFoodUnits

    @Test
    public void theLinesAreTheConfigsAndTheEndFloorIsNotTheOverworldMinimum() {
        FoodPlan p = plan(new FakeFacts());
        assertEquals(cfg.minHeldFoodUnits, p.floor());
        assertEquals(cfg.minFoodUnits, p.overworldMinimum());
        assertEquals(cfg.targetFoodUnits, p.target());
        assertEquals(end.minFoodUnits, p.endFloor());
        // the two configs share a field name and not a number, and neither leaks into the other
        assertEquals(70, p.overworldMinimum());
        assertEquals(24, p.endFloor());
        cfg.minFoodUnits = 90;
        end.minFoodUnits = 50;
        FoodPlan moved = plan(new FakeFacts());
        assertEquals(90, moved.overworldMinimum());
        assertEquals(50, moved.endFloor());
        assertEquals(cfg.minHeldFoodUnits, moved.floor());
    }

    @Test
    public void aBagBetweenTheTwoMinimumsIsShortForOneAndFineForTheOther() {
        FakeFacts f = new FakeFacts();
        f.foodUnits = 40;
        FoodPlan p = plan(f);
        assertTrue(p.shortOfMinimum());
        assertFalse(p.shortOfEndFloor());
        end.minFoodUnits = 50;
        assertTrue(plan(f).shortOfEndFloor());
    }

    @Test
    public void theEndLineCountsLikeTheFloorForAnyNumber() {
        FakeFacts f = new FakeFacts();
        f.foodUnits = 40;
        FoodPlan p = plan(f);
        assertFalse(p.shortOfEndFloor());
        assertFalse(p.shortOfEnd(40));
        assertTrue(p.shortOfEnd(41));
        assertEquals(p.shortOfEndFloor(), p.shortOfEnd(end.minFoodUnits));
    }

    @Test
    public void aBatchInTheSmokerDoesNotCountTowardTheTopUpEither() {
        FakeFacts f = new FakeFacts();
        f.foodUnits = 40;
        f.cookingFood("cooked_porkchop", 4, 8, 20);
        FoodPlan p = planIn(f, GamerPhase.END_PREP);
        assertEquals(32, p.pending());
        assertTrue(p.shortOfEnd(41));
        assertFalse(p.shortOfEndFloor());
    }

    @Test
    public void theHuntToAnyLineIsTheSameSum() {
        FakeFacts f = new FakeFacts();
        f.foodUnits = 10;
        f.junkFoodUnits = 12;
        FoodPlan p = plan(f);
        assertEquals(p.endCollect(), p.endCollect(end.minFoodUnits));
        assertEquals(56 + FoodPlan.END_MARGIN + 12, p.endCollect(56));
        // a batch out goes on top, same as the floor's hunt
        FakeFacts cooking = new FakeFacts();
        cooking.foodUnits = 10;
        cooking.cookingFood("cooked_porkchop", 4, 8, 20);
        FoodPlan c = planIn(cooking, GamerPhase.END_PREP);
        assertEquals(56 + FoodPlan.END_MARGIN + 32, c.endCollect(56));
    }

    @Test
    public void bandsAreTheTwoOverworldLines() {
        assertEquals(0, held(23, 0).band());
        assertEquals(1, held(24, 0).band());
        assertEquals(1, held(69, 0).band());
        assertEquals(2, held(70, 0).band());
    }

    @Test
    public void collectAsksForTheJunkOnTop() {
        FakeFacts f = new FakeFacts();
        f.junkFoodUnits = 12;
        FoodPlan p = plan(f);
        assertEquals(70 + 12, p.collect(70));
        assertEquals(24 + FoodPlan.END_MARGIN + 12, p.endCollect());
        // the End hunt is the same junk rule the kit runner uses, only with its own line and margin
        assertEquals(p.collect(end.minFoodUnits + 8), p.endCollect());
        assertEquals(70 + 12, KitRunner.foodTarget(FOOD, p));
        assertEquals(32, KitRunner.foodTarget(new KitNeed(KitNeed.BUILD_BLOCKS, 32), p));
    }

    // ---- the verdicts

    @Test
    public void miningWithEnoughInTheBagKeepsMining() {
        // 40 units and a vein in front of us: no trip
        assertFalse(held(40, 0).leads(false, false, false));
        assertFalse(held(24, 0).leads(false, false, false));
    }

    @Test
    public void underTheFloorFoodAlwaysGoesFirst() {
        assertTrue(held(23, 0).leads(false, false, false));
        assertTrue(held(0, 0).leads(false, false, false));
    }

    @Test
    public void starvingIsUnderTheFloorWithATripThatIsNotFood() {
        FakeFacts jobs = new FakeFacts().cooking("iron_ingot", 11, 30).cookingFood("cooked_mutton", 8, 6, 30);
        RunState.FurnaceJob iron = jobs.furnaceJobs().get(0);
        RunState.FurnaceJob meat = jobs.furnaceJobs().get(1);
        assertTrue(held(0, 0).starving(iron));
        assertTrue(held(23, 0).starving(iron));
        // no trip at all: the detours still give way
        assertTrue(held(0, 0).starving(null));
        // at the floor and over it the bag is fed
        assertFalse(held(24, 0).starving(iron));
        assertFalse(held(130, 0).starving(iron));
        // a trip for food is the fastest food there is, it keeps the wheel even with a smoker's worth already counted in held
        assertFalse(held(0, 0).starving(meat));
        // and the cold meat a pickup brings back is food too
        meat.stranded = true;
        assertFalse(held(0, 0).starving(meat));
        // but one smoker cooking somewhere does not excuse the iron trip
        assertTrue(held(0, 0).starving(iron));
    }

    @Test
    public void onlyTheSurfaceMakesTheSoftTopUpCheap() {
        assertTrue(held(40, 0).leads(true, false, false));
        // a cook or a craft next in a mine used to count as "between jobs" (a smoker deep down and then a cow hunt through the
        // rock). down there the hunt is a climb whatever the next job is
        assertFalse(held(40, 0).leads(false, false, false));
    }

    @Test
    public void aCookThatIsLoadingIsNotCutShortBySoftTopUp() {
        FoodPlan p = held(40, 0);
        assertFalse(p.leads(true, false, true));
        // not even one that already started, it picks up again when the cook lets go
        assertFalse(p.leads(true, true, true));
        assertTrue(p.leads(true, true, false));
        // but a bag that is really empty is still an emergency
        assertTrue(held(10, 0).leads(false, false, true));
        // and the top-up that was latched does not start from nothing because the cook blinked
        assertFalse(p.nextTopUp(false, p.leads(true, false, true)));
        assertTrue(p.nextTopUp(true, p.leads(true, true, true)));
    }

    @Test
    public void atTheFullAmountItNeverLeads() {
        assertFalse(held(130, 0).leads(true, true, false));
        assertFalse(held(150, 0).leads(true, false, false));
        // the minimum is not the end of a trip any more, it carries on to refillStop
        assertTrue(held(70, 0).leads(true, true, false));
        assertTrue(held(100, 0).leads(false, true, false));
    }

    @Test
    public void aTopUpThatStartedRunsToTheTopWithoutFlipping() {
        int count = 40;
        boolean topUp = false;
        // down the mine: nothing
        boolean lead = held(count, 0).leads(false, topUp, false);
        topUp = held(count, 0).nextTopUp(topUp, lead);
        assertFalse(lead);
        assertFalse(topUp);
        // up on the surface it starts
        lead = held(count, 0).leads(true, topUp, false);
        topUp = held(count, 0).nextTopUp(topUp, lead);
        assertTrue(lead);
        assertTrue(topUp);
        // the hunt takes it under ground or into a ravine, and the count climbs: still leading, every tick
        for (count = 41; count < 130; count += 7) {
            lead = held(count, 0).leads(false, topUp, false);
            topUp = held(count, 0).nextTopUp(topUp, lead);
            assertTrue("at " + count, lead);
            assertTrue(topUp);
        }
        // 130 and it is over, and the next dip starts from scratch
        lead = held(130, 0).leads(false, topUp, false);
        topUp = held(130, 0).nextTopUp(topUp, lead);
        assertFalse(lead);
        assertFalse(topUp);
        assertFalse(held(60, 0).leads(false, topUp, false));
    }

    @Test
    public void aHuntKeepsTheWheelUntilTheFloorIsMetWithoutTheRawMeat() {
        // 14 cooked and 10 of raw that only counts while we are hunting: reads 24, on the floor, and must not let go
        FoodPlan smokerCounts = new FoodPlan(24, 0, 0, 0, 0, 0, cfg, end, false);
        assertFalse(smokerCounts.leads(false, false, false));
        assertTrue(smokerCounts.keepsLead(true, 10));
        // nobody was hunting, so nothing to keep
        assertFalse(smokerCounts.keepsLead(false, 10));
        // met without the raw meat, it can go. the count can't dip back under the floor when the smoker stops counting
        assertFalse(new FoodPlan(34, 0, 0, 0, 0, 0, cfg, end, false).keepsLead(true, 10));
        // a batch in the smoker is food we will get
        assertFalse(new FoodPlan(14, 20, 0, 0, 0, 0, cfg, end, false).keepsLead(true, 10));
    }

    @Test
    public void aKeptLeadDoesNotStartTheSoftTopUp() {
        FoodPlan smokerCounts = new FoodPlan(24, 0, 0, 0, 0, 0, cfg, end, true);
        // what IronPhase hands nextTopUp is leads(), the keep stays out of it
        assertFalse(smokerCounts.nextTopUp(false, smokerCounts.leads(false, false, false)));
    }

    @Test
    public void aForcedTripUnderTheFloorDoesNotLatchTheSoftOne() {
        boolean lead = held(10, 0).leads(false, false, false);
        assertTrue(lead);
        assertFalse(held(10, 0).nextTopUp(false, lead));
        // and once it is back over the floor in the mine, the mine wins again
        assertFalse(held(25, 0).leads(false, false, false));
    }

    @Test
    public void theFoodFloorStartsLowAndRunsToTheFullAmount() {
        assertFalse(held(40, 0).floorNext(false));
        assertTrue(held(cfg.minHeldFoodUnits - 1, 0).floorNext(false));
        // once started it carries on past the floor, up to the minimum
        assertTrue(held(40, 0).floorNext(true));
        assertFalse(held(cfg.minFoodUnits, 0).floorNext(true));
    }

    @Test
    public void theFoodFloorIgnoresWhatTheRawMeatWouldBeWorthCooked() {
        stopAtMinimum();
        // LOCATE and the like never cook, so the part of the raw meat that held leaves out is not coming: it keeps hunting at 66
        // where the IRON phase, which does cook, calls the same bag covered
        assertTrue(held(66, 4).floorNext(true));
        assertTrue(held(66, 4).covered());
        assertFalse(held(66, 4).leads(true, true, false));
    }

    // ---- a small shortfall the raw meat in the bag covers is not worth a trip (one raw chicken, 4 units)

    // covered() is about the line a trip ends on. these cases were written when that was the minimum (70), the rule is the same
    // at refillStop, so they put the stop back where the numbers make sense
    private void stopAtMinimum() {
        cfg.refillStopFoodUnits = cfg.minFoodUnits;
    }

    @Test
    public void theRawMeatCoversTheLastBitOfABigTrip() {
        // the same rule at the default stop: 126 held and a chicken that cooks to the last 4
        assertTrue(held(126, 4).covered());
        assertFalse(held(126, 4).leads(true, true, false));
        assertFalse(held(126, 4).nextTopUp(true, false));
        assertFalse(held(119, 50).covered());
        assertTrue(held(119, 50).leads(true, true, false));
        // at the old line it is no hole at all, the trip carries on
        assertFalse(held(66, 4).covered());
        assertTrue(held(66, 4).leads(true, true, false));
    }

    // 64 units of other food and one raw chicken. foodUnits books the chicken at the cooked 6, so 70 in the sum
    private static FakeFacts chickenBag(boolean smokerNearby) {
        FakeFacts f = new FakeFacts();
        f.foodUnits = 64 + FoodHelper.plannedNutrition(Items.CHICKEN);
        f.give(Items.CHICKEN, 1);
        if (smokerNearby) {
            f.smokerPlaced = true;
            f.give(Items.COAL, 1);
        }
        return f;
    }

    // the soft rule as IronPhase asks it, standing on the surface with nobody else's load going
    private boolean softLeads(FakeFacts f, boolean topUp) {
        return refill(f, 10).leads(true, topUp, false);
    }

    @Test
    public void aSmallGapTheRawMeatCoversDoesNotLead() {
        stopAtMinimum();
        // 66 held, the chicken's 4 not in it: cooked it is 70, so no trip, latched top-up or not
        assertFalse(held(66, 4).leads(true, false, false));
        assertFalse(held(66, 4).leads(true, true, false));
        // and it does not start the latch either
        assertFalse(held(66, 4).nextTopUp(false, held(66, 4).leads(true, false, false)));
        // without the raw meat to cover it the very same 66 is a real top-up
        assertTrue(held(66, 0).leads(true, false, false));
        assertTrue(held(66, 0).nextTopUp(false, held(66, 0).leads(true, false, false)));
    }

    @Test
    public void theSmokerComingAndGoingDoesNotStartOrStopATopUp() {
        // the smoker is picked up, placed again and forgotten while the bag stays the same: the verdict must not follow it
        FakeFacts nearby = chickenBag(true);
        FakeFacts gone = chickenBag(false);
        assertTrue(CookGate.cookFeasible(nearby, cfg, 10));
        assertFalse(CookGate.cookFeasible(gone, cfg, 10));
        assertEquals(70, plan(nearby, 10).held());
        assertEquals(66, plan(gone, 10).held());
        boolean topUp = false;
        for (FakeFacts f : new FakeFacts[]{nearby, gone, nearby, gone, gone, nearby}) {
            FoodPlan p = plan(f, 10);
            boolean lead = p.leads(true, topUp, false);
            topUp = p.nextTopUp(topUp, lead);
            assertFalse(lead);
            assertFalse(topUp);
        }
    }

    @Test
    public void twoRawBeefAndTheSmokerComingAndGoingDoNotChurnEither() {
        stopAtMinimum();
        // under MIN_RAW the cook is feasible only with a smoker standing: that is the biggest swing the smoker can make, 2 x 5
        for (Item meat : new Item[]{Items.BEEF, Items.PORKCHOP}) {
            FakeFacts nearby = new FakeFacts();
            nearby.foodUnits = 54 + 2 * FoodHelper.plannedNutrition(meat);
            nearby.give(meat, 2).give(Items.COAL, 1);
            nearby.smokerPlaced = true;
            FakeFacts gone = new FakeFacts();
            gone.foodUnits = nearby.foodUnits;
            gone.give(meat, 2).give(Items.COAL, 1);
            assertEquals(70, plan(nearby, 10).held());
            assertEquals(60, plan(gone, 10).held());
            assertFalse(softLeads(nearby, false));
            assertFalse(softLeads(gone, false));
        }
        // the pile above is the biggest one under MIN_RAW, a bigger MIN_RAW wants a bigger pile here and a bigger SMALL_GAP
        assertEquals(2, CookGate.MIN_RAW - 1);
    }

    @Test
    public void rawMeatThatIsAlreadyCountedIsNotPaidTwice() {
        // a cook is feasible, so the sum has the chicken at 6 already. 62 other units and the chicken is a real 68: a top-up.
        // adding the chicken's gap on top would read 72 and call it covered
        FakeFacts f = chickenBag(true);
        f.foodUnits = 62 + FoodHelper.plannedNutrition(Items.CHICKEN);
        assertEquals(68, plan(f, 10).held());
        assertEquals(0, plan(f, 10).rawLeftOut());
        assertFalse(plan(f, 10).covered());
        assertTrue(softLeads(f, false));
        // no cook possible: the same chicken is not counted, and left out it is the gap
        f.smokerPlaced = false;
        assertEquals(64, plan(f, 10).held());
        assertEquals(4, plan(f, 10).rawLeftOut());
        // 64 + 4 is 68, still 2 short of 70: the chicken does not close that hole
        assertFalse(held(64, 4).covered());
        assertTrue(softLeads(f, false));
    }

    @Test
    public void aBigGapOfRawMeatStillGetsAHunt() {
        stopAtMinimum();
        // ten raw porkchop and no way to cook them: 30 real units, 50 on paper. nowhere near a steak's worth of hole
        FakeFacts f = tenRawPork();
        assertEquals(30, plan(f, 10).held());
        assertEquals(50, plan(f, 10).rawLeftOut());
        assertTrue(softLeads(f, false));
        // the cap is SMALL_GAP: that far under is covered by any raw meat that fills it, one more unit is not
        int edge = cfg.minFoodUnits - FoodPlan.SMALL_GAP;
        assertTrue(held(edge, 50).covered());
        assertFalse(held(edge - 1, 50).covered());
        assertTrue(held(edge - 1, 50).leads(true, false, false));
        assertFalse(held(edge, 50).leads(true, false, false));
        // and a small hole that the meat only half fills is still a top-up
        assertTrue(held(66, 3).leads(true, false, false));
        assertFalse(held(66, 4).leads(true, false, false));
    }

    @Test
    public void underTheFloorLeadsWhateverTheBagHolds() {
        assertTrue(held(10, 100).leads(false, false, false));
        assertTrue(held(23, 50).leads(false, false, true));
        assertFalse(held(23, 100).covered());
        // the floor is not a small hole even when the config puts the two lines next to each other
        cfg.minHeldFoodUnits = 24;
        cfg.minFoodUnits = 28;
        stopAtMinimum();
        assertTrue(held(23, 100).leads(false, false, false));
        assertFalse(held(24, 100).leads(true, false, false));
    }

    @Test
    public void aTopUpUnderWayEndsWhenTheRawMeatCoversTheRest() {
        stopAtMinimum();
        // the hunt got us to 66 and the bag holds the chicken that fills the last 4: done, the latch is cleared too
        assertFalse(held(66, 4).leads(false, true, false));
        assertFalse(held(66, 4).nextTopUp(true, false));
        // and it stays cleared: with the meat eaten a top-up has to start over, on the surface, not wake up in the mine
        assertFalse(held(66, 0).leads(false, false, false));
        assertTrue(held(66, 0).leads(true, false, false));
        // far from covered, the latch carries on the way it always did
        assertTrue(held(50, 4).nextTopUp(true, true));
        assertTrue(held(50, 4).leads(false, true, false));
    }

    // ---- the top-up's own smoker screen is not somebody else's load

    @Test
    public void theTopUpDoesNotCutItselfOffWhenItsOwnScreenOpens() {
        // a top-up on the surface walks to a smoker for the cooked chicken, and the screen opens under it: that screen is its own
        FoodPlan p = held(40, 0);
        boolean led = false;
        boolean lead = p.leads(true, false, FoodGate.cookBusy(false, false, led));
        assertTrue(lead);
        led = lead;
        for (int tick = 0; tick < 5; tick++) {
            lead = p.leads(true, true, FoodGate.cookBusy(false, true, led));
            assertTrue("tick " + tick, lead);
            led = lead;
        }
        // an iron load that was already in flight when the food need came up still holds it back
        led = false;
        assertFalse(p.leads(true, false, FoodGate.cookBusy(false, true, led)));
    }

    // ---- the furnace screen we have open

    private static RunState.FurnaceJob foodJob(int x, int count) {
        RunState.FurnaceJob job = new RunState.FurnaceJob(new RunState.Pos(x, 64, 0), "OVERWORLD", "smoker", "porkchop", count,
                "cooked_porkchop", 0, 1000);
        job.unitsEach = 8;
        return job;
    }

    @Test
    public void meatInTheOpenStationCountsUntilTheJobIsRecorded() {
        RunState.Pos screen = new RunState.Pos(5, 64, 0);
        // 42 held, 4 pork in the screen (32 planned): the dip that sent the bot after a cow is not a dip
        assertEquals(32, FoodPlan.inStation(32, List.of(), screen, "OVERWORLD"));
        // the job is in the state: the pending sum has it, counting it again would hide a real shortage
        assertEquals(0, FoodPlan.inStation(32, List.of(foodJob(5, 4)), screen, "OVERWORLD"));
        assertEquals(0, FoodPlan.inStation(0, List.of(), screen, "OVERWORLD"));
    }

    @Test
    public void aDifferentStationsJobDoesNotHideTheMeatBeingLoaded() {
        RunState.Pos screen = new RunState.Pos(9, 64, 0);
        // smoker A (x 5) is recorded and furnace B (x 9) is on screen with meat going in: B's meat still counts
        assertEquals(32, FoodPlan.inStation(32, List.of(foodJob(5, 4)), screen, "OVERWORLD"));
        // once B has its own job, it is the pending sum's and not counted twice
        assertEquals(0, FoodPlan.inStation(32, List.of(foodJob(5, 4), foodJob(9, 4)), screen, "OVERWORLD"));
        // same spot in another dimension is another station
        assertEquals(32, FoodPlan.inStation(32, List.of(foodJob(9, 4)), screen, "NETHER"));
        // an iron job on the very block is not food and hides nothing
        RunState.FurnaceJob iron = new RunState.FurnaceJob(new RunState.Pos(9, 64, 0), "OVERWORLD", "furnace", "raw_iron", 3,
                "iron_ingot", 0, 1000);
        assertEquals(32, FoodPlan.inStation(32, List.of(iron), screen, "OVERWORLD"));
        // not knowing which block the screen is falls back to the old rule: any food job hides it
        assertEquals(0, FoodPlan.inStation(32, List.of(foodJob(5, 4)), null, "OVERWORLD"));
        assertEquals(32, FoodPlan.inStation(32, List.of(), null, "OVERWORLD"));
    }

    private static final RunState.Pos SMOKER = new RunState.Pos(5, 64, 0);

    // one visit to a smoker replayed the way MinecraftFacts does it: the leftover is carried from tick to tick and the numbers go
    // through the real planner. iron is cooking in a furnace the whole time, so the stock-up fillers are in the schedule
    private final class Visit {
        final FakeFacts f = new FakeFacts();
        int leftover = -1;

        Visit() {
            f.cooking("iron_ingot", 3, 60);
        }

        void tick(int bag, int station, boolean synced, boolean foodTask) {
            leftover = FoodPlan.leftover(leftover, station, synced, foodTask);
            f.foodUnits = bag + FoodPlan.inStation(station, f.jobs, SMOKER, "OVERWORLD", leftover);
        }

        int held() {
            return plan(f, 8).held();
        }

        // the stock-up filler IronPhase's cooking tick would run (100 and the 30 of smeltExtras)
        boolean fillerOn() {
            return SmeltFiller.schedule(f, cfg, 8, true, plan(f, 8)).isStockUp(new KitNeed(KitNeed.FOOD, cfg.targetFoodUnits + 30));
        }

        // the minimum need, the one that would replace the filler mid load. asked with a trip under way that ends at the minimum,
        // the strict case (a trip to refillStop is on at 100 whatever the screen does, that is not a dip)
        boolean minimumOn() {
            FoodPlan p = refill(f, 8).atMinimum();
            return FoodGate.index(KitPlanner.plan(f, cfg, 8, p), p) >= 0;
        }
    }

    @Test
    public void whatWasInTheScreenWhenTheFoodTaskGotThereStaysOut() {
        // the first look is the baseline, after that it only goes down
        assertEquals(24, FoodPlan.leftover(-1, 24, true, true));
        assertEquals(24, FoodPlan.leftover(24, 40, true, true));
        assertEquals(0, FoodPlan.leftover(24, 0, true, true));
        assertEquals(0, FoodPlan.leftover(0, 40, true, true));
        // no screen, slots not here yet, or somebody else's screen: nothing to leave out
        assertEquals(-1, FoodPlan.leftover(24, 24, false, true));
        assertEquals(-1, FoodPlan.leftover(-1, 0, false, true));
        assertEquals(-1, FoodPlan.leftover(24, 24, true, false));
        assertEquals(0, FoodPlan.inStation(24, List.of(), SMOKER, "OVERWORLD", 24));
        assertEquals(16, FoodPlan.inStation(40, List.of(), SMOKER, "OVERWORLD", 24));
        // -1 is no baseline, not a negative one
        assertEquals(24, FoodPlan.inStation(24, List.of(), SMOKER, "OVERWORLD", -1));
        assertEquals(24, FoodPlan.inStation(24, List.of(), SMOKER, "OVERWORLD"));
        // a recorded job on this block still hides all of it, and not knowing which block it is hides it for any food job
        assertEquals(0, FoodPlan.inStation(40, List.of(foodJob(5, 4)), SMOKER, "OVERWORLD", 24));
        assertEquals(0, FoodPlan.inStation(40, List.of(foodJob(5, 4)), null, "OVERWORLD", 0));
    }

    @Test
    public void somebodyElsesLoadStillCounts() {
        // a cook loading 72 units into the smoker: 72 held the whole way, neither food need wakes up
        Visit cook = new Visit();
        for (int moved = 0; moved <= 72; moved += 8) {
            cook.tick(72 - moved, moved, true, false);
            assertEquals("moved " + moved, 72, cook.held());
            assertFalse("moved " + moved, cook.minimumOn());
        }
        // and once its job is recorded the pending sum has it, not the screen
        cook.f.cookingFood("cooked_beef", 9, 8, 40);
        cook.tick(0, 72, true, false);
        assertEquals(72, cook.held());
    }

    @Test
    public void openingTheSmokerDoesNotEndTheNeedThatWalkedThere() {
        // 110 held, the filler asks for 130, and 24 units of cooked meat sit in a smoker no job knows about
        Visit v = new Visit();
        v.tick(110, 0, false, true);
        assertTrue(v.fillerOn());
        // the slots arrive a tick after the screen: an empty look is not the baseline
        v.tick(110, 0, false, true);
        assertTrue(v.fillerOn());
        v.tick(110, 24, true, true);
        assertEquals(110, v.held());
        assertTrue("the click must not end the need that opened the screen", v.fillerOn());
        v.tick(110, 24, true, true);
        assertTrue(v.fillerOn());
        // the output comes out and into the bag: honestly 134 now, ended, and the screen closing does not bring it back
        v.tick(134, 0, true, true);
        assertFalse(v.fillerOn());
        v.tick(134, 0, false, true);
        assertFalse(v.fillerOn());
        // what the same click did without the leftover rule: 134 on the click, the filler gone, and back on once the screen closed
        Visit old = new Visit();
        old.tick(110, 0, false, false);
        assertTrue(old.fillerOn());
        old.tick(110, 24, true, false);
        assertFalse(old.fillerOn());
        old.tick(110, 0, false, false);
        assertTrue(old.fillerOn());
    }

    @Test
    public void theFoodTaskLoadingItsOwnMeatDoesNotDip() {
        // 100 in the bag, 40 of it raw going into an empty smoker a stack at a time: the same 100 the whole way, so the minimum need
        // never shows up in front of the filler and the task is not swapped out with meat in the slots and no job
        Visit v = new Visit();
        v.tick(100, 0, true, true);
        for (int moved = 0; moved <= 40; moved += 8) {
            v.tick(100 - moved, moved, true, true);
            assertEquals("moved " + moved, 100, v.held());
            assertFalse("moved " + moved, v.minimumOn());
            assertTrue("moved " + moved, v.fillerOn());
        }
    }

    @Test
    public void aLoadByTheFoodTaskEndsCleanlyOnceTheJobIsRecorded() {
        // 100 in the bag, 40 of raw meat already in the smoker's input from an earlier visit nobody wrote down, 40 more going in
        Visit v = new Visit();
        v.tick(100, 40, true, true);
        assertEquals(100, v.held());
        v.tick(60, 80, true, true);
        assertEquals(100, v.held());
        assertTrue(v.fillerOn());
        // Loaded: the job covers the whole input, the leftover included (that meat is cooking for us now)
        v.f.cookingFood("cooked_beef", 10, 8, 50);
        assertEquals(80, v.f.pendingFoodUnits());
        for (boolean open : new boolean[]{true, true, false, false}) {
            v.tick(60, open ? 80 : 0, open, true);
            assertEquals(140, v.held());
            assertFalse(v.fillerOn());
        }
    }

    @Test
    public void loadingBeforeTakingTheOutputOnlyEverUndercounts() {
        // 24 cooked in the output, 16 more raw goes in first and then the output comes out. the baseline follows the station down, so
        // the 16 loaded is briefly not counted: the number never falls, it catches up when the job is recorded
        Visit v = new Visit();
        v.tick(100, 24, true, true);
        assertEquals(100, v.held());
        v.tick(84, 40, true, true);
        assertEquals(100, v.held());
        v.tick(108, 16, true, true);
        assertEquals(108, v.held());
        v.f.cookingFood("cooked_beef", 2, 8, 10);
        v.tick(108, 0, false, true);
        assertEquals(124, v.held());
    }

    // ---- one plan, every gate

    @Test
    public void everyFoodGateReadsTheSameNumberOnTheClick() {
        // all of them go through the one plan, so the click moves none of them. spelled out for the ones that decide something
        Visit v = new Visit();
        v.tick(60, 0, false, true);
        int before = v.held();
        v.tick(60, 24, true, true);
        assertEquals(before, v.held());
        assertEquals(60, KitPlanner.progressOf(v.f, FOOD, plan(v.f, 8)));
        // the min need is in the plan, the floor and the soft top-up read the same held, and the portal's need is the same sum
        assertTrue(v.minimumOn());
        assertTrue(refill(v.f, 8).leads(true, true, FoodGate.cookBusy(false, true, true)));
        assertTrue(PortalPlanner.gate(v.f, cfg, 8, refill(v.f, 8)).stream().anyMatch(n -> KitNeed.FOOD.equals(n.catalogueName())));
    }

    // a bag, and whether each gate says the food is short. every row is one bag read by all of them, each in its own phase: the
    // kit and the portal in the ones that cook, the End gate in END_PREP where nothing does
    private void assertGatesAgree(String bag, FakeFacts f, boolean shortOfMinimum, boolean shortOfEnd) {
        FoodPlan p = plan(f);
        assertEquals(bag + " (plan)", shortOfMinimum, p.shortOfMinimum());
        assertEquals(bag + " (kit)", shortOfMinimum, FoodGate.index(KitPlanner.plan(f, cfg, end.beds, p), p) >= 0);
        assertEquals(bag + " (gather)", shortOfMinimum, KitPlanner.gather(f, cfg, end.beds, p).stream()
                .anyMatch(n -> KitNeed.FOOD.equals(n.catalogueName())));
        // (the portal is the last stop before the nether: it fills to the entry line, so it is short of more than the minimum)
        assertEquals(bag + " (portal)", p.shortOf(p.entryEnd(cfg.netherEntryFoodUnits)), PortalPlanner.gate(f, cfg, end.beds, p).stream()
                .anyMatch(n -> KitNeed.FOOD.equals(n.catalogueName())));
        assertEquals(bag + " (end)", shortOfEnd, EndGear.missing(f, new RunState(), end, planIn(f, GamerPhase.END_PREP)).food());
    }

    @Test
    public void theKitThePortalAndTheEndAllSeeTheSameBag() {
        FakeFacts empty = new FakeFacts();
        assertGatesAgree("empty", empty, true, true);

        FakeFacts mid = new FakeFacts();
        mid.foodUnits = 40;
        assertGatesAgree("40 cooked units", mid, true, false);

        FakeFacts full = new FakeFacts();
        full.foodUnits = 90;
        assertGatesAgree("90 cooked units", full, false, false);

        // five raw porkchop with nothing to cook them on: 40 on paper, 15 to eat. short everywhere, the End included
        FakeFacts rawNoCook = new FakeFacts();
        rawNoCook.give(Items.PORKCHOP, 5);
        rawNoCook.foodUnits = 5 * FoodHelper.plannedNutrition(Items.PORKCHOP);
        assertEquals(15, plan(rawNoCook).held());
        assertGatesAgree("5 raw porkchop, no cook", rawNoCook, true, true);

        // the same five with a smoker standing and coal to burn: the kit's phases cook them, so there they are 40. END_PREP never
        // goes back to the smoker, so the End gate still sees the 15 it can eat
        FakeFacts rawCook = new FakeFacts();
        rawCook.give(Items.PORKCHOP, 5).give(Items.COAL, 2);
        rawCook.smokerPlaced = true;
        rawCook.foodUnits = 5 * FoodHelper.plannedNutrition(Items.PORKCHOP);
        assertEquals(40, plan(rawCook).held());
        assertEquals(15, planIn(rawCook, GamerPhase.END_PREP).held());
        assertGatesAgree("5 raw porkchop, smoker standing", rawCook, true, true);
    }

    // ---- where the gates used to disagree, and what they say now

    @Test
    public void theEndGateNoLongerCountsRawMeatNothingCanCook() {
        // it read the bare bag: three raw porkchop is 24 on paper and the End gate was happy, the food chain eats them at 3 apiece
        FakeFacts f = new FakeFacts();
        f.give(Items.PORKCHOP, 3);
        f.foodUnits = 3 * FoodHelper.plannedNutrition(Items.PORKCHOP);
        assertEquals(24, f.foodUnits);
        assertFalse("the old read", f.foodUnits() < end.minFoodUnits);
        FoodPlan atTheEnd = planIn(f, GamerPhase.END_PREP);
        assertEquals(9, atTheEnd.held());
        assertTrue(EndGear.missing(f, new RunState(), end, atTheEnd).food());
        // with a smoker standing and fuel the kit's phases can cook them, and it is the kit's phases that count them at 24. the
        // End gate is read in END_PREP, which never does, so a smoker that only stands there does not turn 9 into 24
        f.smokerPlaced = true;
        f.give(Items.COAL, 1);
        assertEquals(24, planIn(f, GamerPhase.IRON).held());
        assertEquals(9, planIn(f, GamerPhase.END_PREP).held());
        assertTrue(EndGear.missing(f, new RunState(), end, planIn(f, GamerPhase.END_PREP)).food());
    }

    @Test
    public void theEndGateLeavesOutABatchStillCooking() {
        // empty bag, 4 pork in the smoker (32 units): every other gate calls that held, but nothing in END_PREP goes back to
        // the smoker, so the End gate has an empty bag to go on
        FakeFacts f = new FakeFacts();
        f.cookingFood("cooked_porkchop", 4, 8, 20);
        assertEquals(0, f.foodUnits());
        FoodPlan p = planIn(f, GamerPhase.END_PREP);
        assertEquals(32, p.held());
        assertEquals(32, p.pending());
        assertTrue(p.shortOfEndFloor());
        assertTrue(EndGear.missing(f, new RunState(), end, p).food());
        // the kit and the portal gate still count it, they do go back for it: 32 is under their 70 and over the End's 24
        assertEquals(32, plan(f).held());
        assertTrue(plan(f).shortOfMinimum());
        assertFalse(plan(f).held() < end.minFoodUnits);
        // and the hunt asks for it on top, CollectFoodTask books a batch in a smoker as food on the way
        assertEquals(end.minFoodUnits + FoodPlan.END_MARGIN + 32, p.endCollect());
    }

    @Test
    public void aBatchCookingNextToFoodInTheBagOnlyCountsTheBag() {
        // 30 cooked in the bag and 40 on the way: held is 70, the End gate has the 30 and clears its 24
        FakeFacts f = new FakeFacts();
        f.foodUnits = 30;
        f.cookingFood("cooked_porkchop", 5, 8, 20);
        FoodPlan p = planIn(f, GamerPhase.END_PREP);
        assertEquals(70, p.held());
        assertFalse(p.shortOfEndFloor());
        // 20 in the bag is under the line whatever is cooking
        f.foodUnits = 20;
        assertEquals(60, planIn(f, GamerPhase.END_PREP).held());
        assertTrue(planIn(f, GamerPhase.END_PREP).shortOfEndFloor());
    }

    @Test
    public void theEndHuntAndTheKitHuntAskForTheJunkTheSameWay() {
        // the End phase copied the junk fix instead of sharing it: both go through FoodPlan.collect now
        FakeFacts f = new FakeFacts();
        f.junkFoodUnits = 17;
        FoodPlan p = plan(f);
        assertEquals(KitRunner.foodTarget(new KitNeed(KitNeed.FOOD, end.minFoodUnits + 8), p), p.endCollect());
        f.junkFoodUnits = 0;
        assertEquals(end.minFoodUnits + 8, plan(f).endCollect());
    }

    @Test
    public void progressIsThePlainCountAndDoesNotFollowTheSmoker() {
        // held moves by the chicken's 4 when the smoker comes and goes, progress must not: it would be a stall timer reset for nothing
        FakeFacts nearby = chickenBag(true);
        FakeFacts gone = chickenBag(false);
        assertEquals(70, plan(nearby, 10).held());
        assertEquals(66, plan(gone, 10).held());
        assertEquals(KitPlanner.progressOf(nearby, FOOD, plan(nearby, 10)), KitPlanner.progressOf(gone, FOOD, plan(gone, 10)));
        assertEquals(70, plan(gone, 10).counted());
        // a batch going into the smoker is progress: the pending food is in it
        gone.cookingFood("cooked_mutton", 3, 6, 20);
        assertEquals(70 + 18, KitPlanner.progressOf(gone, FOOD, plan(gone, 10)));
    }

    @Test
    public void theCookFuelGateAndTheMinimumNeedAgreeOnABagWithNoRawMeat() {
        // cookFuelLogs used to add the bag and the pending food itself: with no raw meat that is exactly held
        for (int units : new int[]{0, 40, 69, 70, 120}) {
            FakeFacts f = new FakeFacts();
            f.foodUnits = units;
            FoodPlan p = plan(f, 8);
            assertEquals(units, p.held());
            assertEquals(units + " units", p.wantsRefill() ? cfg.cookFuelLogs : 0, KitPlanner.cookFuelLogs(f, cfg, 8, 0, p));
            FoodPlan trip = refill(f, 8);
            assertEquals(units + " units on a trip", trip.wantsRefill() ? cfg.cookFuelLogs : 0, KitPlanner.cookFuelLogs(f, cfg, 8, 0, trip));
        }
        // 69 with no trip is no hunt, so no logs to cook it with either. on a trip it still wants them
        FakeFacts f = new FakeFacts();
        f.foodUnits = 69;
        assertEquals(0, KitPlanner.cookFuelLogs(f, cfg, 8, 0, plan(f, 8)));
        assertEquals(cfg.cookFuelLogs, KitPlanner.cookFuelLogs(f, cfg, 8, 0, refill(f, 8)));
    }

    // ---- the log line

    @Test
    public void theFoodLineSaysWhatTheNumbersWere() {
        // station 24 of the bag's 134 (110 + 24), nothing pending, nothing left out
        FoodPlan on = new FoodPlan(134, 0, 0, 0, 24, 0, cfg, end);
        assertEquals("food: need off, held 134 of 70 (station 24, pending 0, raw left out 0), running wool", on.line(false, 70, "wool"));
        FoodPlan left = new FoodPlan(116, 0, 6, 0, 0, 24, cfg, end);
        assertEquals("food: need on, held 110 of 130 (station 0, pending 0, raw left out 6), running food, "
                + "24 was already in the open screen, not counted", left.line(true, 130, "food"));
        assertTrue(held(0, 0).line(true, 70, null).endsWith("running nothing"));
    }

    // ---- the refill gap: a trip starts under 45 and runs to 130

    private boolean planHasMinimum(FakeFacts f, FoodPlan p) {
        return FoodGate.index(KitPlanner.plan(f, cfg, 8, p), p) >= 0;
    }

    // the portal gate asks for the nether entry line, not the trip stop
    private boolean hasEntryFood(List<KitNeed> needs) {
        return needs.stream().anyMatch(n -> KitNeed.FOOD.equals(n.catalogueName()) && n.count() == cfg.netherEntryFoodUnits);
    }

    // the refill need, not the kit's target of 100 at the end of the plan
    private boolean hasFood(List<KitNeed> needs) {
        return needs.stream().anyMatch(n -> KitNeed.FOOD.equals(n.catalogueName()) && n.count() == cfg.refillStopFoodUnits);
    }

    @Test
    public void oneBiteUnderTheMinimumIsNotATrip() {
        FakeFacts f = new FakeFacts();
        f.foodUnits = 69;
        FoodPlan p = plan(f, 8);
        assertFalse(p.wantsRefill());
        assertFalse(planHasMinimum(f, p));
        assertFalse(hasFood(KitPlanner.gather(f, cfg, 8, p)));
        // (the portal is the exception: the last stop before the nether fills to the entry line)
        assertTrue(hasEntryFood(PortalPlanner.gate(f, cfg, 8, p)));
        // and the soft top-up does not start on the surface either, it only lives inside a refill
        assertFalse(p.leads(true, false, false));
        assertFalse(p.nextTopUp(false, true));
        assertFalse(idle(46).leads(true, false, false));
        assertFalse(idle(46).leads(true, true, false));
        assertFalse(idle(46).nextTopUp(true, true));
    }

    @Test
    public void underTheStartIsATrip() {
        FakeFacts f = new FakeFacts();
        f.foodUnits = 44;
        FoodPlan p = plan(f, 8);
        assertTrue(p.wantsRefill());
        assertTrue(p.nextRefilling());
        assertTrue(planHasMinimum(f, p));
        assertTrue(hasFood(KitPlanner.gather(f, cfg, 8, p)));
        assertTrue(hasEntryFood(PortalPlanner.gate(f, cfg, 8, p)));
        assertTrue(p.leads(true, false, false));
        // still the soft rule down the mine: it waits for the surface
        assertFalse(p.leads(false, false, false));
        // the edge: 45 itself is not under the start
        assertFalse(idle(45).wantsRefill());
        assertTrue(idle(44).wantsRefill());
    }

    @Test
    public void aTripThatStartedRunsFrom44To130() {
        RunState s = new RunState();
        s.phase = GamerPhase.IRON;
        List<String> log = new java.util.ArrayList<>();
        FakeFacts f = new FakeFacts();
        f.foodUnits = 60;
        assertFalse(latch(f, s, log).wantsRefill());
        assertFalse(s.foodRefilling);
        f.foodUnits = 44;
        FoodPlan p = latch(f, s, log);
        assertTrue(s.foodRefilling);
        assertTrue(p.refilling());
        assertTrue(p.wantsRefill());
        for (int units : new int[]{50, 60, 69, 70, 80, 100, 129}) {
            f.foodUnits = units;
            p = latch(f, s, log);
            assertTrue("at " + units, p.wantsRefill());
            assertTrue("at " + units, planHasMinimum(f, p));
            // and the hunt is asked for the whole trip, not the minimum
            assertTrue("at " + units, KitPlanner.plan(f, cfg, 8, p).contains(new KitNeed(KitNeed.FOOD, 130)));
            // the phase-done checks only want the minimum: past it a phase may end and the trip goes on in the next one
            assertEquals("at " + units, units < 70, p.atMinimum().wantsRefill());
        }
        f.foodUnits = 130;
        p = latch(f, s, log);
        assertFalse(s.foodRefilling);
        assertFalse(p.wantsRefill());
        // the next dip waits for the start again
        f.foodUnits = 60;
        assertFalse(latch(f, s, log).wantsRefill());
        assertEquals(List.of("food: refilling from 44 (under 45), up to 130", "food: refill done at 130"), log);
    }

    @Test
    public void theFloorStillAlwaysLeads() {
        FoodPlan p = idle(23);
        assertTrue(p.wantsRefill());
        assertTrue(p.leads(false, false, false));
        // a floor configured over the start still wins
        cfg.minHeldFoodUnits = 50;
        assertTrue(idle(48).wantsRefill());
        assertTrue(idle(48).leads(false, false, false));
    }

    @Test
    public void theLatchIsAnswerNeutral() {
        // whoever asks before the engine moves the latch reads the same need: wantsRefill with the old latch equals the new one
        for (int units = 0; units <= 150; units++) {
            for (boolean was : new boolean[]{false, true}) {
                FoodPlan before = new FoodPlan(units, 0, 0, 0, 0, 0, cfg, end, was);
                FoodPlan after = new FoodPlan(units, 0, 0, 0, 0, 0, cfg, end, before.nextRefilling());
                assertEquals(units + " " + was, before.wantsRefill(), after.wantsRefill());
            }
        }
    }

    @Test
    public void theLatchDropsOutsideTheCookingPhases() {
        RunState s = new RunState();
        s.foodRefilling = true;
        s.phase = GamerPhase.LOCATE;
        List<String> log = new java.util.ArrayList<>();
        FakeFacts f = new FakeFacts();
        f.foodUnits = 30;
        latch(f, s, log);
        assertFalse(s.foodRefilling);
        assertTrue(log.isEmpty());
    }

    @Test
    public void aStartOverTheMinimumIsTheMinimum() {
        cfg.refillStartFoodUnits = 200;
        assertEquals(70, idle(0).refillStart());
        assertTrue(idle(69).wantsRefill());
        assertFalse(idle(70).wantsRefill());
    }

    // ---- the surface top-up: up top a trip starts under the minimum, in a mine only under the start

    private GamerConfig all() {
        GamerConfig all = new GamerConfig();
        all.overworld = cfg;
        all.end = end;
        return all;
    }

    // the latch as the engine moves it, with the bag held long enough for a due start to count (REFILL_CONFIRM_TICKS)
    private FoodPlan latch(FakeFacts f, RunState s, List<String> log) {
        FoodPlan.latched(f, all(), s, log::add);
        f.gameTime += FoodPlan.REFILL_CONFIRM_TICKS;
        return FoodPlan.latched(f, all(), s, log::add);
    }

    private static RunState inIron() {
        RunState s = new RunState();
        s.phase = GamerPhase.IRON;
        return s;
    }

    @Test
    public void aSurfaceTopUpStartsAt69UpTopButNotInAMine() {
        RunState s = inIron();
        List<String> log = new java.util.ArrayList<>();
        FakeFacts f = new FakeFacts();
        f.foodUnits = 69;
        // down a mine 69 is nothing, and neither is 46
        assertFalse(latch(f, s, log).wantsRefill());
        f.foodUnits = 46;
        assertFalse(latch(f, s, log).wantsRefill());
        assertFalse(s.foodRefilling);
        // up top the same 69 is a trip, and a whole one: to 130
        f.foodUnits = 69;
        f.nearSurface = true;
        FoodPlan p = latch(f, s, log);
        assertTrue(s.foodRefilling);
        assertTrue(p.wantsRefill());
        assertTrue(KitPlanner.plan(f, cfg, 8, p).contains(new KitNeed(KitNeed.FOOD, 130)));
        // latched: the hunt dives into a ravine past the minimum and the trip carries on
        f.nearSurface = false;
        for (int units : new int[]{75, 100, 129}) {
            f.foodUnits = units;
            assertTrue("at " + units, latch(f, s, log).wantsRefill());
        }
        f.foodUnits = 130;
        latch(f, s, log);
        assertFalse(s.foodRefilling);
        assertEquals(List.of("food: refilling from 69 (on the surface, under 70), up to 130", "food: refill done at 130"), log);
        // at the minimum up top there is nothing to top up
        f.nearSurface = true;
        f.foodUnits = 70;
        assertFalse(latch(f, s, log).wantsRefill());
        assertFalse(s.foodRefilling);
    }

    @Test
    public void theFloorStillForcesATripDownAMine() {
        RunState s = inIron();
        List<String> log = new java.util.ArrayList<>();
        FakeFacts f = new FakeFacts();
        f.foodUnits = 23;
        FoodPlan p = latch(f, s, log);
        assertTrue(s.foodRefilling);
        // leads with no surface, no latch, and a cook in the way
        assertTrue(p.leads(false, false, true));
        assertEquals(List.of("food: refilling from 23 (under 45), up to 130"), log);
        // under the start in a mine: a trip, but it waits for the surface to lead
        FoodPlan mine = new FoodPlan(44, 0, 0, 0, 0, 0, cfg, end, true, false);
        assertTrue(mine.wantsRefill());
        assertFalse(mine.leads(false, false, false));
        assertTrue(mine.leads(true, false, false));
    }

    @Test
    public void theSmokerComingAndGoingDoesNotStartASurfaceTopUp() {
        // 66 and a chicken that cooks to the last 4: covered at the minimum, so no trip up top whether the smoker is there or not
        RunState s = inIron();
        List<String> log = new java.util.ArrayList<>();
        for (boolean smoker : new boolean[]{true, false, true, false}) {
            FakeFacts f = chickenBag(smoker);
            f.nearSurface = true;
            assertFalse(latch(f, s, log).wantsRefill());
            assertFalse(s.foodRefilling);
        }
        assertTrue(log.isEmpty());
        // a real hole up top is still a trip: 58 and the chicken, 12 short is more than the chicken fills
        FakeFacts f = chickenBag(false);
        f.foodUnits = 56 + FoodHelper.plannedNutrition(Items.CHICKEN);
        f.nearSurface = true;
        assertTrue(latch(f, s, log).wantsRefill());
        assertTrue(s.foodRefilling);
    }

    @Test
    public void theSurfaceAndBagLatchIsAnswerNeutralToo() {
        for (int units = 0; units <= 150; units++) {
            for (boolean was : new boolean[]{false, true}) {
                for (boolean up : new boolean[]{false, true}) {
                    for (boolean full : new boolean[]{false, true}) {
                        FoodPlan before = new FoodPlan(units, 0, 0, 0, 0, 0, cfg, end, was, up, full);
                        FoodPlan after = new FoodPlan(units, 0, 0, 0, 0, 0, cfg, end, before.nextRefilling(), up, full);
                        assertEquals(units + " " + was + " " + up + " " + full, before.wantsRefill(), after.wantsRefill());
                    }
                }
            }
        }
    }

    @Test
    public void rawMeatWaitingOnFuelIsATripThatOnlyCooks() {
        // a trip ended at 130 with 16 raw porkchop at the cooked value, then a craft ate the fuel: the pile reads raw (16 x 5 under
        // the cooked sum) and held is 50. nothing else asks for fuel, so up top this is a trip on purpose: the food task counts the
        // pile cooked, finds it already has its 130 and goes straight to the smoker, which fetches the fuel. no cows
        RunState s = inIron();
        List<String> log = new java.util.ArrayList<>();
        FakeFacts f = new FakeFacts();
        f.nearSurface = true;
        f.give(Items.PORKCHOP, 16);
        f.foodUnits = 2 + 16 * FoodHelper.plannedNutrition(Items.PORKCHOP);
        FoodPlan p = latch(f, s, log);
        assertEquals(50, p.held());
        assertTrue(p.surfaceTopUp());
        assertTrue(s.foodRefilling);
        assertTrue(KitPlanner.plan(f, cfg, 8, p).contains(new KitNeed(KitNeed.FOOD, 130)));
        assertTrue(p.counted() >= KitRunner.foodTarget(new KitNeed(KitNeed.FOOD, 130), p));
    }

    @Test
    public void huntsToFollowsTheTrip() {
        // on a trip it is the stop, with none on it is the target
        assertEquals(130, held(120, 0).huntsTo());
        assertEquals(100, idle(80).huntsTo());
        // a full bag past the minimum has no trip left, the target is the line again
        FoodPlan full = new FoodPlan(80, 0, 0, 0, 0, 0, cfg, end, true, false, true);
        assertFalse(full.wantsRefill());
        assertEquals(100, full.huntsTo());
        // a due start still sitting out the wait is not a trip yet
        RunState s = inIron();
        FakeFacts f = new FakeFacts();
        f.foodUnits = 44;
        assertEquals(100, FoodPlan.latched(f, all(), s, l -> { }).huntsTo());
    }

    @Test
    public void theCardShowsWhereTheTripEnds() {
        assertEquals(130, GamerHud.footerLine(held(90, 0)));
        assertEquals(70, GamerHud.footerLine(idle(90)));
        // a full bag ends the trip at the minimum, so that is what the card says
        assertEquals(70, GamerHud.footerLine(new FoodPlan(60, 0, 0, 0, 0, 0, cfg, end, true, false, true)));
    }

    // ---- a dip of a second or two is not a trip

    @Test
    public void aCookCutOffMidLoadDoesNotStartASecondTrip() {
        // a trip just ended, and up top with 80 the cook takes its 3 raw porkchop to a smoker. it loses the head halfway: the meat
        // in the smoker belongs to no job for a moment (-24) and the rest of the raw meat reads raw (-15). then the stranded load is
        // adopted as a job and it all comes back
        RunState s = inIron();
        s.foodRefilling = true;
        List<String> log = new java.util.ArrayList<>();
        FakeFacts f = new FakeFacts();
        f.nearSurface = true;
        f.foodUnits = 130;
        FoodPlan.latched(f, all(), s, log::add);
        assertFalse(s.foodRefilling);
        f.foodUnits = 80;
        FoodPlan.latched(f, all(), s, log::add);
        int[] dip = {56, 56, 41, 41, 41, 80, 80};
        for (int units : dip) {
            f.foodUnits = units;
            f.gameTime += 10;
            FoodPlan p = FoodPlan.latched(f, all(), s, log::add);
            assertFalse("at " + units, s.foodRefilling);
            assertFalse("at " + units, p.wantsRefill());
            assertFalse("at " + units, p.leads(true, false, false));
            assertFalse("at " + units, FoodGate.index(KitPlanner.plan(f, cfg, 8, p), p) >= 0);
        }
        assertEquals(List.of("food: refill done at 130", "food: back to 80 inside 5 s, no trip"), log);
    }

    @Test
    public void aHoleThatStaysIsATripAfterTheWait() {
        RunState s = inIron();
        List<String> log = new java.util.ArrayList<>();
        FakeFacts f = new FakeFacts();
        f.foodUnits = 44;
        for (int t = 0; t < FoodPlan.REFILL_CONFIRM_TICKS; t += 20) {
            assertFalse("tick " + t, FoodPlan.latched(f, all(), s, log::add).wantsRefill());
            f.gameTime += 20;
        }
        FoodPlan p = FoodPlan.latched(f, all(), s, log::add);
        assertTrue(s.foodRefilling);
        assertTrue(p.wantsRefill());
        assertEquals(List.of("food: refilling from 44 (under 45), up to 130"), log);
    }

    @Test
    public void theFloorDoesNotWait() {
        RunState s = inIron();
        List<String> log = new java.util.ArrayList<>();
        FakeFacts f = new FakeFacts();
        f.foodUnits = 44;
        FoodPlan.latched(f, all(), s, log::add);
        f.gameTime += 10;
        f.foodUnits = 23;
        assertTrue(FoodPlan.latched(f, all(), s, log::add).wantsRefill());
        assertTrue(s.foodRefilling);
        assertEquals(List.of("food: refilling from 23 (under 45), up to 130"), log);
    }

    @Test
    public void aClockThatJumpsBackRestartsTheWait() {
        RunState s = inIron();
        List<String> log = new java.util.ArrayList<>();
        FakeFacts f = new FakeFacts();
        f.gameTime = 1000;
        f.foodUnits = 44;
        FoodPlan.latched(f, all(), s, log::add);
        f.gameTime = 10;
        assertFalse(FoodPlan.latched(f, all(), s, log::add).wantsRefill());
        f.gameTime = 10 + FoodPlan.REFILL_CONFIRM_TICKS - 1;
        assertFalse(FoodPlan.latched(f, all(), s, log::add).wantsRefill());
        f.gameTime = 10 + FoodPlan.REFILL_CONFIRM_TICKS;
        assertTrue(FoodPlan.latched(f, all(), s, log::add).wantsRefill());
    }

    // ---- no room in the bag

    @Test
    public void aFullBagEndsTheTripEarly() {
        RunState s = inIron();
        List<String> log = new java.util.ArrayList<>();
        FakeFacts f = new FakeFacts();
        f.foodUnits = 44;
        latch(f, s, log);
        assertTrue(s.foodRefilling);
        f.foodUnits = 90;
        assertTrue(latch(f, s, log).wantsRefill());
        // every slot taken and nothing to throw: done at 96, not 130
        f.foodUnits = 96;
        f.bagFull = true;
        FoodPlan p = latch(f, s, log);
        assertFalse(s.foodRefilling);
        assertFalse(p.wantsRefill());
        assertFalse(p.leads(true, true, false));
        assertFalse(KitPlanner.plan(f, cfg, 8, p).contains(new KitNeed(KitNeed.FOOD, 130)));
        assertEquals("food: refill done at 96, the bag is full", log.get(log.size() - 1));
        // and a slot coming free at 96 does not start it again, that is no hole
        f.bagFull = false;
        assertFalse(latch(f, s, log).wantsRefill());
        assertEquals(2, log.size());
    }

    @Test
    public void aFullBagStillHuntsToTheMinimum() {
        RunState s = inIron();
        List<String> log = new java.util.ArrayList<>();
        FakeFacts f = new FakeFacts();
        f.bagFull = true;
        f.foodUnits = 40;
        latch(f, s, log);
        assertTrue(s.foodRefilling);
        // under the minimum the kit needs it, a full bag or not
        f.foodUnits = 69;
        assertTrue(latch(f, s, log).wantsRefill());
        f.foodUnits = 70;
        assertFalse(latch(f, s, log).wantsRefill());
        assertFalse(s.foodRefilling);
        // and up top a full bag does not start the top-up, it would end the moment it got to where it started
        f.nearSurface = true;
        f.foodUnits = 60;
        assertFalse(latch(f, s, log).wantsRefill());
        assertFalse(s.foodRefilling);
    }

    @Test
    public void rawMeatInAColdStationWithNoFuelIsNotFood() {
        assertEquals(0, FoodPlan.stationUnits(170, 0, false, false));
    }

    @Test
    public void rawMeatCountsOnceItIsLitOrFueled() {
        assertEquals(170, FoodPlan.stationUnits(170, 0, true, false));
        assertEquals(170, FoodPlan.stationUnits(170, 0, false, true));
    }

    @Test
    public void cookedOutputAlwaysCounts() {
        assertEquals(40, FoodPlan.stationUnits(170, 40, false, false));
        assertEquals(210, FoodPlan.stationUnits(170, 40, true, true));
    }
}
