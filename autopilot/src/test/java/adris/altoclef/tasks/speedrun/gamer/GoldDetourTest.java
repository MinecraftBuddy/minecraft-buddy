package adris.altoclef.tasks.speedrun.gamer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.tasks.speedrun.gamer.DetourRules.Inputs;
import adris.altoclef.tasks.speedrun.gamer.DetourRules.Limits;
import adris.altoclef.tasks.speedrun.gamer.DetourRules.Step;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.util.helpers.ItemFixture;
import org.junit.BeforeClass;
import org.junit.Test;

// raw gold on the way, for the piglin piece: diamonds' machine with "the piece is short" as the need. the world half
// (ResourceDetour.gold) is compile checked, the need and the rules are here
public class GoldDetourTest {
    private static final OverworldConfig CFG = new OverworldConfig();
    private static final Limits LIM = DetourSpec.GOLD.limits(CFG);

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    private static Inputs calm(int held, int need) {
        return new Inputs(true, true, held, need, false, false, false, false, false);
    }

    private static DetourRules.Ore ore(boolean there) {
        return new DetourRules.Ore(() -> there, () -> there, () -> false, () -> false);
    }

    private static DetourRules rules() {
        return new DetourRules(DetourSpec.GOLD.cooldownTicks);
    }

    @Test
    public void theNumbersAreDiamondsAndTheCapIsABootsWorth() {
        assertTrue(CFG.goldSide);
        assertEquals(CFG.diamondSideBudget, CFG.goldSideBudget, 0);
        assertEquals(CFG.diamondSideSeconds, CFG.goldSideSeconds, 0);
        assertEquals(PiglinGold.BOOTS_INGOTS, LIM.heldCap());
        assertEquals(PiglinGold.BOOTS_INGOTS, DetourSpec.GOLD.mineTarget(CFG));
        assertEquals(0, LIM.mineCap());
        assertEquals(DetourRules.COOLDOWN_TICKS, DetourSpec.GOLD.cooldownTicks);
        assertEquals("gold", DetourSpec.GOLD.name);
        assertEquals("raw_gold", DetourSpec.GOLD.itemWord);
        assertTrue(DetourSpec.GOLD.kitIsOnIt("raw_gold"));
        assertFalse(DetourSpec.GOLD.kitIsOnIt("diamond"));
    }

    // ---- the need

    @Test
    public void noGoldAtAllWantsABootsWorth() {
        assertEquals(4, DetourSpec.goldNeed(true, false, 0, 0));
    }

    @Test
    public void goldWeCouldSmeltCountsTowardsTheNeed() {
        // 3 ingots in the bag: one more raw gold, and the raw gold we already hold is part of what we want to be holding
        assertEquals(1, DetourSpec.goldNeed(true, false, 3, 0));
        // 2 raw in the bag (available 2): want to hold 4 raw in total
        assertEquals(4, DetourSpec.goldNeed(true, false, 2, 2));
    }

    @Test
    public void enoughGoldIsNoNeed() {
        assertEquals(0, DetourSpec.goldNeed(true, false, 4, 0));
        assertEquals(0, DetourSpec.goldNeed(true, false, 9, 3));
    }

    @Test
    public void aPieceWornOrHeldIsNoNeed() {
        assertEquals(0, DetourSpec.goldNeed(true, true, 0, 0));
    }

    @Test
    public void switchedOffIsNoNeed() {
        assertEquals(0, DetourSpec.goldNeed(false, false, 0, 0));
    }

    // ---- the rules around it

    @Test
    public void shortOfGoldStartsAndStopsOnceTheBootsArePaidFor() {
        DetourRules rules = rules();
        assertEquals(Step.START, rules.tick(1000, calm(0, 4), LIM, ore(true)));
        assertEquals(Step.KEEP, rules.tick(1001, calm(2, 4), LIM, ore(true)));
        assertEquals(Step.DONE, rules.tick(1002, calm(4, 4), LIM, ore(true)));
    }

    @Test
    public void noNeedNeverStarts() {
        assertEquals(Step.IDLE, rules().tick(1000, calm(0, 0), LIM, ore(true)));
    }

    @Test
    public void noIronPickIsNoDetour() {
        Inputs bare = new Inputs(true, false, 0, 4, false, false, false, false, false);
        assertTrue(DetourRules.blocked(bare));
        assertEquals(Step.IDLE, rules().tick(1000, bare, LIM, ore(true)));
    }

    @Test
    public void noOreNoDetour() {
        assertEquals(Step.IDLE, rules().tick(1000, calm(0, 4), LIM, ore(false)));
    }

    @Test
    public void aFurnaceDueEndsIt() {
        DetourRules rules = rules();
        rules.tick(1000, calm(0, 4), LIM, ore(true));
        Inputs due = new Inputs(true, true, 1, 4, false, true, false, false, false);
        assertEquals(Step.BLOCKED, rules.tick(1001, due, LIM, ore(true)));
    }

    @Test
    public void goldIsTheFourthSideJobAndYieldsToAnEmptyBag() {
        assertTrue(IronActivity.yields(IronActivity.Kind.GOLD));
        assertEquals("gold detour", IronActivity.Kind.GOLD.words);
    }
}
