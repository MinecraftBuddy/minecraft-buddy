package adris.altoclef.tasks.speedrun.gamer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.util.helpers.FuelPolicy;
import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.utils.Dimension;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

// the per-tick wood budget: what its fields say about a bag, and that the engine hands every reader the same one
public class WoodBudgetTest {
    private static final int BEDS = 8;
    private final OverworldConfig cfg = new OverworldConfig();
    // the spare shields have their own test (KitPlannerTest), the budgets here are the kit as it was
    {
        cfg.netherSpareShields = 0;
        cfg.netherSpareSwords = 0;
    }

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    @After
    public void clearFuel() {
        FuelPolicy.clear();
    }

    private static class TestHost implements PhaseMachine.Host {
        final RunState state = new RunState();
        final GamerConfig cfg = new GamerConfig();
        final FakeFacts facts = new FakeFacts();

        @Override
        public GamerConfig cfg() {
            return cfg;
        }

        @Override
        public RunState state() {
            return state;
        }

        @Override
        public GamerFacts facts() {
            return facts;
        }

        @Override
        public void say(String line) {
        }

        @Override
        public void save() {
        }

        @Override
        public void walkOnEndPortal(boolean on) {
        }
    }

    @Test
    public void aBareBagOwesTheWholeKit() {
        WoodBudget w = WoodBudget.of(new FakeFacts(), cfg, BEDS);
        assertEquals(15, w.gatherLogsOwed());
        assertEquals(KitPlanner.woodNeed(new FakeFacts(), cfg, BEDS), w.gatherLogsOwed());
        assertEquals(KitPlanner.woodNeed(new FakeFacts(), cfg, BEDS, false), w.ironLogsOwed());
        assertEquals(3 * BEDS, w.bedPlanksOwed());
        // short of the budget every piece is spoken for, there is nothing spare
        assertEquals(0, w.spareLogs());
        assertEquals(0, w.burnableWoodSmelts(0));
    }

    @Test
    public void aGivenUpWoolChaseStillOwesThePlanksForEveryBed() {
        FakeFacts f = new FakeFacts();
        f.woolHuntGaveUp = true;
        assertEquals(3 * BEDS, WoodBudget.bedPlanksOwed(f, BEDS));
        // the spawn bed still to place wants its planks too, a bed in the bag has them already
        f.spawnBedOwed = true;
        assertEquals(3 * (BEDS + 1), WoodBudget.bedPlanksOwed(f, BEDS));
        f.give(Items.BED.white(), 1);
        assertEquals(3 * BEDS, WoodBudget.bedPlanksOwed(f, BEDS));
    }

    @Test
    public void spareWoodIsWhatIsHeldBeyondTheKeep() {
        // 14 logs is exactly the budget with the two planks in hand, 6 more are fuel: a log and a plank are 1.5 smelts apiece
        FakeFacts f = new FakeFacts().give(Items.OAK_LOG, 20).give(Items.OAK_PLANKS, 2);
        WoodBudget w = WoodBudget.of(f, cfg, BEDS);
        assertEquals(new WoodReserve.Keep(14, 2), w.keep());
        assertEquals(6, w.spareLogs());
        assertEquals(0, w.sparePlanks());
        assertEquals(9, w.burnableWoodSmelts(0));
        // logs about to go into a smoker come off the spare ones first
        assertEquals(1, w.burnableWoodSmelts(5));
        assertEquals(0, w.burnableWoodSmelts(9));
    }

    @Test
    public void aSmokerOnlyEatsLogsWhenOneStillHasToBeMade() {
        FakeFacts f = new FakeFacts();
        assertEquals(5, WoodBudget.of(f, cfg, BEDS).smokerLogs());
        assertTrue(WoodBudget.of(f, cfg, BEDS).smokerOwed());
        assertEquals(5, WoodBudget.of(f, cfg, BEDS).logsSpokenFor(true));
        assertEquals(0, WoodBudget.of(f, cfg, BEDS).logsSpokenFor(false));
        f.give(Items.CRAFTING_TABLE, 1);
        assertEquals(4, WoodBudget.of(f, cfg, BEDS).smokerLogs());
        f.smokerPlaced = true;
        assertFalse(WoodBudget.of(f, cfg, BEDS).smokerOwed());
        assertEquals(0, WoodBudget.of(f, cfg, BEDS).logsSpokenFor(true));
    }

    @Test
    public void theFuelReserveIsEverythingOutsideTheOverworld() {
        FakeFacts f = new FakeFacts().give(Items.OAK_LOG, 20).give(Items.OAK_PLANKS, 6);
        // the planks count towards the budget too: 13 logs and 6 planks are what the kit still wants
        assertEquals(new WoodReserve.Keep(13, 6), WoodBudget.of(f, cfg, BEDS).fuelKeep());
        f.dimension = Dimension.NETHER;
        WoodBudget nether = WoodBudget.of(f, cfg, BEDS);
        assertEquals(new WoodReserve.Keep(20, 6), nether.fuelKeep());
        // the planner's own keep does not care where we are
        assertEquals(new WoodReserve.Keep(13, 6), nether.keep());
    }

    @Test
    public void applyingTheBudgetTellsTheFurnacesItsFuelKeep() {
        FakeFacts f = new FakeFacts().give(Items.OAK_LOG, 20).give(Items.OAK_PLANKS, 6);
        WoodReserve.apply(WoodBudget.of(f, cfg, BEDS));
        int[] viaApply = burnable();
        // 13 logs and 6 planks kept
        assertEquals(7, viaApply[0]);
        assertEquals(0, viaApply[1]);
    }

    private static int[] burnable() {
        return FuelPolicy.usable(List.of(new ItemStack(Items.OAK_LOG, 20), new ItemStack(Items.OAK_PLANKS, 6)));
    }

    @Test
    public void theContextBuildsOneFromItsFacts() {
        StubContext ctx = new StubContext();
        ctx.facts.give(Items.OAK_LOG, 20);
        assertEquals(WoodBudget.of(ctx.facts, ctx.cfg.overworld, ctx.cfg.end.beds), ctx.wood());
    }

    @Test
    public void theMachineHandsOutOnePerTickAndRebuildsOnTheNext() {
        TestHost host = new TestHost();
        PhaseMachine machine = new PhaseMachine(new ArrayList<PhaseHandler>(FakeHandler.full()), host);
        machine.begin(null);
        host.facts.give(Items.OAK_LOG, 3);
        WoodBudget first = machine.wood();
        assertSame(first, machine.wood());
        assertEquals(3, first.logs());
        host.facts.give(Items.OAK_LOG, 4);
        machine.tick(null);
        WoodBudget second = machine.wood();
        assertNotSame(first, second);
        assertEquals(7, second.logs());
    }

    @Test
    public void aBudgetTheEngineGaveSurvivesExactlyTheTickItWasGivenFor() {
        TestHost host = new TestHost();
        PhaseMachine machine = new PhaseMachine(new ArrayList<PhaseHandler>(FakeHandler.full()), host);
        machine.begin(null);
        host.facts.give(Items.OAK_LOG, 3);
        WoodBudget given = WoodBudget.of(host.facts, host.cfg.overworld, host.cfg.end.beds);
        machine.giveWood(given);
        assertSame(given, machine.wood());
        machine.tick(null);
        assertSame(given, machine.wood());
        // and the tick after that, with nothing given, is a fresh one
        host.facts.give(Items.OAK_LOG, 1);
        machine.tick(null);
        assertNotSame(given, machine.wood());
        assertEquals(4, machine.wood().logs());
    }

    @Test
    public void aPhaseMoveInsideTheTickGetsThatPhasesBudget() {
        TestHost host = new TestHost();
        PhaseMachine machine = new PhaseMachine(new ArrayList<PhaseHandler>(FakeHandler.full()), host);
        machine.begin(null);
        host.state.phase = GamerPhase.GATHER;
        WoodBudget gather = machine.wood();
        assertEquals(GamerPhase.GATHER, gather.phase());
        host.state.phase = GamerPhase.IRON;
        WoodBudget iron = machine.wood();
        assertEquals(GamerPhase.IRON, iron.phase());
        assertSame(iron, machine.wood());
    }

    @Test
    public void theRecoveryCardsFoodPlanLeavesTheRefillLatchAlone() {
        TestHost host = new TestHost();
        PhaseMachine machine = new PhaseMachine(new ArrayList<PhaseHandler>(FakeHandler.full()), host);
        machine.begin(null);
        host.state.phase = GamerPhase.IRON;
        // an empty bag after a death: the latch would start a trip on it if the card moved it
        host.facts.foodUnits = 0;
        assertFalse(host.state.foodRefilling);
        assertEquals(0, machine.foodForCard().held());
        assertFalse(host.state.foodRefilling);
    }

    @Test
    public void theIronChopFollowsTheEarlyLoadLatchEvenWhenItStartsAfterTheBudget() {
        // a bare bag owes iron-phase wood, and the latch can start mid tick (EarlyIronPick.track runs after the plan)
        FakeFacts f = new FakeFacts();
        WoodBudget w = WoodBudget.of(f, cfg, BEDS);
        assertTrue(w.ironLogsOwed() > 0);
        assertEquals(w.ironLogsOwed(), w.ironChopLogs(f));
        f.earlyLoad = true;
        assertEquals(0, w.ironChopLogs(f));
    }

    // ---- the sticks a diamond pick or sword is made with (DiamondSpend), hand written: the golden generators hold no raw diamonds

    // every kit item held and a table standing, so the only wood owed is what a diamond craft adds
    private static FakeFacts kitBag() {
        FakeFacts f = new FakeFacts();
        for (net.minecraft.world.item.Item i : new net.minecraft.world.item.Item[]{Items.STONE_PICKAXE, Items.STONE_SWORD, Items.FURNACE,
                Items.IRON_PICKAXE, Items.IRON_SWORD, Items.FLINT_AND_STEEL, Items.SHIELD, Items.SHEARS}) {
            f.give(i, 1);
        }
        f.give(Items.BUCKET, 2).give(Items.LADDER, 3);
        f.tablePlaced = true;
        return f;
    }

    @Test
    public void noDiamondsOweNoStickWood() {
        FakeFacts f = kitBag();
        assertEquals(0, KitPlanner.woodNeed(f, cfg, BEDS, false));
        assertEquals(0, KitPlanner.woodNeed(f.give(Items.DIAMOND, 2), cfg, BEDS, false));
    }

    @Test
    public void aDiamondPickUpgradeOnTopOfAnIronOneOwesItsTwoSticks() {
        FakeFacts f = kitBag().give(Items.DIAMOND, 3);
        // two sticks are two planks are one log
        assertEquals(1, KitPlanner.woodNeed(f, cfg, BEDS, false));
        // sticks in the bag settle it
        assertEquals(0, KitPlanner.woodNeed(f.give(Items.STICK, 2), cfg, BEDS, false));
    }

    @Test
    public void theDiamondPickPaysTheSticksTheIronOneWould() {
        // no iron pick: its two sticks are the diamond one's two sticks, the wood owed is the same with and without the diamonds
        FakeFacts without = kitBag();
        without.items.remove(Items.IRON_PICKAXE);
        FakeFacts with = kitBag().give(Items.DIAMOND, 3);
        with.items.remove(Items.IRON_PICKAXE);
        assertEquals(KitPlanner.woodNeed(without, cfg, BEDS, false), KitPlanner.woodNeed(with, cfg, BEDS, false));
        assertEquals(1, KitPlanner.woodNeed(with, cfg, BEDS, false));
        // and in the gather's view too
        assertEquals(KitPlanner.woodNeed(without, cfg, BEDS, true), KitPlanner.woodNeed(with, cfg, BEDS, true));
    }

    @Test
    public void aDiamondSwordStandingInForTheIronOneOwesNoMore() {
        FakeFacts without = kitBag();
        without.items.remove(Items.IRON_PICKAXE);
        without.items.remove(Items.IRON_SWORD);
        FakeFacts with = kitBag().give(Items.DIAMOND, 6);
        with.items.remove(Items.IRON_PICKAXE);
        with.items.remove(Items.IRON_SWORD);
        assertEquals(List.of("diamond_pickaxe", "diamond_sword"), DiamondSpend.choose(with, cfg));
        assertEquals(KitPlanner.woodNeed(without, cfg, BEDS, false), KitPlanner.woodNeed(with, cfg, BEDS, false));
    }

    @Test
    public void armorCostsNoWoodAndTheSettingOffOwesNone() {
        FakeFacts f = kitBag().give(Items.DIAMOND_PICKAXE, 1).give(Items.DIAMOND, 8);
        assertEquals(List.of("diamond_chestplate"), DiamondSpend.choose(f, cfg));
        assertEquals(0, KitPlanner.woodNeed(f, cfg, BEDS, false));
        FakeFacts off = kitBag().give(Items.DIAMOND, 3);
        cfg.diamondSpend = false;
        assertEquals(0, KitPlanner.woodNeed(off, cfg, BEDS, false));
    }
}
