package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.config.EndConfig;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.util.helpers.ItemFixture;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// the list and world half of the IRON food gate. the numbers and the verdicts are FoodPlanTest's
public class FoodGateTest {
    // the refill need asks for refillStopFoodUnits
    private static final KitNeed FOOD = new KitNeed(KitNeed.FOOD, 130);
    private static final KitNeed ORE = new KitNeed("iron_ingot", 39);
    private static final KitNeed CRAFT = new KitNeed("iron_pickaxe", 1);

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

    private FoodPlan plan(FakeFacts f) {
        return FoodPlan.of(f, cfg, end, true);
    }

    @Test
    public void onlyTheKitsOwnMinimumFoodIsTheGatedOne() {
        List<KitNeed> plan = List.of(CRAFT, FOOD, ORE, new KitNeed(KitNeed.FOOD, 100), new KitNeed(KitNeed.FOOD, 130));
        FoodPlan food = plan(new FakeFacts());
        assertEquals(1, FoodGate.index(plan, food));
        // the target and the stock-up are not it, and neither is a plan with no food at all
        assertEquals(-1, FoodGate.index(List.of(ORE, new KitNeed(KitNeed.FOOD, 100)), food));
        assertEquals(-1, FoodGate.index(List.of(), food));
        // with no trip due there is no refill need, a stock-up asking for the same 130 is a filler's
        FakeFacts fed = new FakeFacts();
        fed.foodUnits = 110;
        assertEquals(-1, FoodGate.index(List.of(ORE, new KitNeed(KitNeed.FOOD, 130)), plan(fed)));
    }

    @Test
    public void theFloorBiteThatLeadsTheToolsIsTheGatedHeadToo() {
        // an empty bag: the floor bite, the tools, then the whole refill. the gate has to see the bite or the IRON phase
        // would think nothing food-ish leads and drop the furnace-trip and detour manners that keep a starving bot eating
        cfg.cookFuelLogs = 0;
        FakeFacts empty = new FakeFacts();
        FoodPlan food = plan(empty);
        List<KitNeed> needs = KitPlanner.plan(empty, cfg, 8, food);
        assertEquals(new KitNeed(KitNeed.FOOD, food.floor()), needs.get(0));
        assertEquals(0, FoodGate.index(needs, food));
        assertTrue(FoodGate.refillHead(needs, food));
        // starving stays the bag's word, not the list's, and the gate keeps the head in under the floor
        assertTrue(food.starving(null));
        assertTrue(food.leads(false, false, false));
        // the full refill further back is not what the gate looks for first
        assertEquals(1, needs.stream().filter(n -> n.equals(new KitNeed(KitNeed.FOOD, food.refillStop()))).count());
        // over the floor there is no bite, the old way of finding the refill still works
        FakeFacts fed = new FakeFacts();
        fed.foodUnits = 40;
        FoodPlan fedFood = plan(fed);
        List<KitNeed> fedNeeds = KitPlanner.plan(fed, cfg, 8, fedFood);
        assertTrue(FoodGate.index(fedNeeds, fedFood) > 0);
        assertFalse(food.isRefillNeed(new KitNeed("iron_ingot", food.floor())));
        // and a bite-sized need is only the refill while we are under the floor
        assertFalse(fedFood.isRefillNeed(new KitNeed(KitNeed.FOOD, fedFood.floor())));
    }

    @Test
    public void theHeadIsOreMeansTheNextJobIsTheMine() {
        assertTrue(FoodGate.headIsOre(List.of(FOOD, ORE), 0));
        assertFalse(FoodGate.headIsOre(List.of(FOOD, CRAFT, ORE), 0));
        assertFalse(FoodGate.headIsOre(List.of(FOOD), 0));
        // food further back: what runs first without it is whatever is in front
        assertFalse(FoodGate.headIsOre(List.of(CRAFT, FOOD, ORE), 1));
    }

    @Test
    public void aCookStationOrAnotherLoadHoldsTheSoftTopUpBack() {
        assertTrue(FoodGate.cookBusy(true, false, false));
        assertTrue(FoodGate.cookBusy(true, true, true));
        // someone else's screen: the food need did not lead a tick ago, so it is not ours
        assertTrue(FoodGate.cookBusy(false, true, false));
        assertFalse(FoodGate.cookBusy(false, false, false));
        assertFalse(FoodGate.cookBusy(false, false, true));
    }

    @Test
    public void withoutDropsExactlyThatNeed() {
        List<KitNeed> plan = List.of(CRAFT, FOOD, ORE);
        assertEquals(List.of(CRAFT, ORE), FoodGate.without(plan, 1));
        assertEquals(3, plan.size());
    }

    @Test
    public void aHungryKitFoodHeadFeedsOnAFarSmokerAndAFoodStockUpDoesNot() {
        FakeFacts f = new FakeFacts().cooking("iron_ingot", 10, 60);
        f.foodUnits = 5;
        FoodPlan food = plan(f);
        assertEquals(0, food.band());
        SmeltFiller.Schedule s = SmeltFiller.schedule(f, cfg, 8);
        int at = FoodGate.index(s.runnable(), food);
        assertTrue(at >= 0);
        // the list as it is once the refill is the head (the gate keeps it where the planner put it, the vein goes first)
        List<KitNeed> headed = s.runnable().subList(at, s.runnable().size());
        KitNeed own = headed.get(0);
        assertTrue(FoodGate.refillHead(headed, food));
        RunState.FurnaceJob meat = new RunState.FurnaceJob(new RunState.Pos(200, 64, 0), "OVERWORLD", "smoker", "mutton", 8, "cooked_mutton", 0, 100);
        meat.unitsEach = 6;
        // what IronPhase.moment hands feeds: a stock-up only when the head is not the gate's own refill, even if the records match
        assertTrue(FurnacePlan.feeds(own, meat, true, s.isStockUp(own) && !FoodGate.refillHead(headed, food)));
        // no refill going (fed, over the line) and a food stock-up at the head: that one keeps hunting out here
        FakeFacts fed = new FakeFacts().cooking("iron_ingot", 10, 60);
        fed.foodUnits = cfg.minFoodUnits + 5;
        FoodPlan full = plan(fed);
        SmeltFiller.Schedule s2 = SmeltFiller.schedule(fed, cfg, 8);
        KitNeed extra = s2.stockUps().stream().filter(n -> KitNeed.FOOD.equals(n.catalogueName())).findFirst().orElseThrow();
        List<KitNeed> stocking = List.of(extra);
        assertFalse(FoodGate.refillHead(stocking, full));
        assertFalse(FurnacePlan.feeds(extra, meat, true, s2.isStockUp(extra) && !FoodGate.refillHead(stocking, full)));
    }

    @Test
    public void thePlannerStillListsTheFoodAtTheFullAmountOnlyWhenShort() {
        // the gate works on the planner's list: 70 and over has no minimum entry, so there is nothing to gate
        FakeFacts f = new FakeFacts();
        f.foodUnits = 40;
        assertTrue(FoodGate.index(KitPlanner.plan(f, cfg, 8, plan(f)), plan(f)) >= 0);
        f.foodUnits = 70;
        assertEquals(-1, FoodGate.index(KitPlanner.plan(f, cfg, 8, plan(f)), plan(f)));
    }

    @Test
    public void anEmptyBagWithNoPickHasTheFoodAtTheHeadAfterTheGate() {
        // the whole IRON list for a bag with nothing in it: food leads (the gate says so under the floor, mine or no mine) and
        // stays the head, so the furnace's call about the head need is a food call
        FakeFacts f = new FakeFacts();
        FoodPlan food = plan(f);
        assertTrue(food.leads(false, false, false));
        List<KitNeed> needs = KitPlanner.plan(f, cfg, 8, food);
        assertTrue(FoodGate.refillHead(needs, food));
        assertEquals(KitNeed.FOOD, needs.get(0).catalogueName());
    }

    @Test
    public void shallowIsTheSameLineAsGoingUp() {
        assertTrue(SmeltSurface.shallow(SmeltSurface.GO_UP_DEPTH));
        assertFalse(SmeltSurface.shallow(SmeltSurface.GO_UP_DEPTH + 1));
    }
}
