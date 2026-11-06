package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherTripRules;
import adris.altoclef.util.helpers.FoodHelper;
import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// the cards GamerHud builds without the engine's tick: the one for a recovery that has the wheel, and the one a won run leaves
public class GamerHudTest {
    private final GamerConfig cfg = new GamerConfig();
    private final RunState state = new RunState();
    private final FakeFacts facts = new FakeFacts();
    private final GamerHud hud = new GamerHud();

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    // the card as the engine draws it (PhaseMachine.foodForCard): the state's phase and wood budget, and the latch left where it is
    private GamerHudState recovering(double seconds, int attempt, String action) {
        WoodBudget wood = WoodBudget.of(facts, cfg.overworld, cfg.end.beds, state.phase);
        FoodPlan food = FoodPlan.of(facts, cfg, state.phase, state.foodRefilling, wood);
        return hud.recovering(state, facts, cfg, food, seconds, attempt, action);
    }

    @Test
    public void aRecoveryCardSaysWhatTheRecoveryIsDoingAndShowsNoKit() {
        state.phase = GamerPhase.IRON;
        GamerHudState card = recovering(312.5, 2, HudRules.recoveryWords(47));
        assertEquals("Getting our stuff back, 47 blocks", card.action());
        assertTrue(card.rows().isEmpty());
        // the coal or gravel detour is a side job of the phase, the phase is not running
        assertNull(card.detour());
        // and the rest of the card is the phase's: its name, its clock held where the recovery stopped it, the attempt
        assertEquals(GamerPhase.IRON, card.phase());
        assertEquals(312.5, card.secondsInPhase(), 1e-9);
        assertEquals(cfg.budgets.minutes(GamerPhase.IRON), card.budgetMinutes(), 1e-9);
        assertEquals(2, card.attempt());
    }

    @Test
    public void theNetherTripReadsItsStageOnTheSameCard() {
        state.phase = GamerPhase.NETHER;
        GamerHudState walk = recovering(10, 1, HudRules.tripWords(NetherTripRules.Stage.WALK, 212));
        assertEquals("Walking to our stuff, 212 blocks", walk.action());
        assertTrue(walk.rows().isEmpty());
        GamerHudState portal = recovering(10, 1, HudRules.tripWords(NetherTripRules.Stage.PORTAL, -1));
        assertEquals("Heading back to the nether", portal.action());
    }

    @Test
    public void furnacesStillCookingStayOnTheCardThroughARecovery() {
        // a batch in a furnace does not care that we died, its clock runs on and the card keeps counting it
        state.phase = GamerPhase.IRON;
        facts.cooking("iron_ingot", 12, 60);
        GamerHudState card = recovering(100, 1, HudRules.recoveryWords(10));
        assertEquals(1, card.furnaces().size());
        assertEquals("Smelting 12 iron", card.furnaces().get(0).words());
        assertEquals(Items.FURNACE, card.furnaces().get(0).icon());
    }

    @Test
    public void theCoalRowFactoryIsTheOreWithNoCap() {
        GamerHudState.DetourRow coal = GamerHudState.DetourRow.coal(4, 100, 30);
        assertEquals(Items.COAL_ORE, coal.icon());
        assertEquals("coal", coal.name());
        assertEquals(0, coal.digCap());
    }

    @Test
    public void aWonRunLeavesACardWithEveryDotLitTheTotalTimeAndNothingElse() {
        // the last card the run drew: some kit, a furnace, a detour, food at 55 of 70
        state.phase = GamerPhase.DRAGON;
        facts.foodUnits = 55;
        facts.cooking("iron_ingot", 3, 10);
        GamerHudState last = new GamerHudState(GamerPhase.DRAGON, 300, 25, 2, "Fighting the dragon",
                List.of(new GamerHudState.KitRow("bed", "Beds", Items.BED.white(), 8, 8, true, false)),
                List.of(new GamerHudState.FurnaceRow("Smelting 3 iron", Items.FURNACE, 0, 200)),
                GamerHudState.DetourRow.coal(12, 0, 30), 55, 70);
        GamerHudState card = GamerHud.won(last, cfg, 41 * 60 + 12);
        assertEquals(GamerPhase.DONE, card.phase());
        assertEquals(41 * 60 + 12, card.secondsInPhase(), 1e-9);
        assertEquals("41:12", HudRules.titleClock(card.phase(), card.secondsInPhase(), card.budgetMinutes()));
        assertEquals("Beat the game", card.action());
        assertTrue(card.rows().isEmpty());
        assertTrue(card.furnaces().isEmpty());
        assertNull(card.detour());
        // the footer is how the run left it, the attempt too
        assertEquals(55, card.foodUnits());
        assertEquals(70, card.foodTarget());
        assertEquals(2, card.attempt());
        for (HudRules.Dot dot : HudRules.dots(card.phase())) {
            assertEquals(HudRules.Dot.DONE, dot);
        }
    }

    @Test
    public void aWinWithNoCardDrawnYetStillMakesOne() {
        // the run ended before its first tick drew anything (a resume that finished at once): the footer takes the config's line
        GamerHudState card = GamerHud.won(null, cfg, 90);
        assertEquals(GamerPhase.DONE, card.phase());
        assertEquals(1, card.attempt());
        assertEquals(0, card.foodUnits());
        assertEquals(cfg.overworld.minFoodUnits, card.foodTarget());
        assertEquals("1:30", HudRules.titleClock(card.phase(), card.secondsInPhase(), card.budgetMinutes()));
    }

    @Test
    public void theFooterFoodIsWhatTheGatesActOnInThatPhase() {
        // three raw porkchop, a smoker and coal: the kit's phases cook them (24), later ones eat them raw (9)
        facts.give(Items.PORKCHOP, 3).give(Items.COAL, 2);
        facts.smokerPlaced = true;
        facts.foodUnits = 3 * FoodHelper.plannedNutrition(Items.PORKCHOP);
        state.phase = GamerPhase.IRON;
        GamerHudState iron = recovering(0, 1, HudRules.recoveryWords(5));
        assertEquals(24, iron.foodUnits());
        assertEquals(cfg.overworld.minFoodUnits, iron.foodTarget());
        state.phase = GamerPhase.LOCATE;
        assertEquals(9, recovering(0, 1, HudRules.recoveryWords(5)).foodUnits());
    }
}
