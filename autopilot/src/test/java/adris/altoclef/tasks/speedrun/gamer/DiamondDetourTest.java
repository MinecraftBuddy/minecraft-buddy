package adris.altoclef.tasks.speedrun.gamer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.tasks.speedrun.gamer.DetourRules.Inputs;
import adris.altoclef.tasks.speedrun.gamer.DetourRules.Limits;
import adris.altoclef.tasks.speedrun.gamer.DetourRules.Step;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.util.helpers.ItemFixture;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.Test;

// diamonds on the way: coal's machine with the cap as the need, an iron pick as the tool and no lava next to the ore. the world
// half (ResourceDetour.diamond) is only compile checked, so the tool gate is tested as the Inputs bit it feeds
public class DiamondDetourTest {
    private static final OverworldConfig CFG = new OverworldConfig();
    private static final Limits LIM = DetourSpec.DIAMOND.limits(CFG);

    // overworld, iron pick or better, nothing else going on, `held` diamonds in the bag, the need the way ResourceDetour makes it
    private static Inputs calm(int held) {
        return calm(held, true, CFG.diamondSide, CFG.diamondSideCap);
    }

    private static Inputs calm(int held, boolean tool, boolean on, int cap) {
        return new Inputs(true, tool, held, DetourSpec.diamondNeed(on, cap), false, false, false, false, false);
    }

    private static DetourRules.Ore ore(boolean there) {
        return new DetourRules.Ore(() -> there, () -> there, () -> false, () -> false);
    }

    private static DetourRules rules() {
        return new DetourRules(DetourSpec.DIAMOND.cooldownTicks);
    }

    // ---- the defaults

    @Test
    public void theNumbersAreCoalsAndTheCapIsTheNeed() {
        assertTrue(CFG.diamondSide);
        assertEquals(24, CFG.diamondSideCap);
        assertEquals(CFG.coalSideBudget, CFG.diamondSideBudget, 0);
        assertEquals(CFG.coalSideSeconds, CFG.diamondSideSeconds, 0);
        assertEquals(CFG.diamondSideBudget, DetourSpec.DIAMOND.budget(CFG), 0);
        assertEquals(24, LIM.heldCap());
        assertEquals(30 * 20, LIM.maxTicks());
        // no block cap, it stops on what it holds
        assertEquals(0, LIM.mineCap());
        assertEquals(DetourRules.COOLDOWN_TICKS, DetourSpec.DIAMOND.cooldownTicks);
        assertEquals(24, DetourSpec.DIAMOND.mineTarget(CFG));
        assertEquals(24, DetourSpec.diamondNeed(true, 24));
    }

    @Test
    public void theWordsAreDiamondsOwn() {
        assertEquals("diamond", DetourSpec.DIAMOND.name);
        assertEquals("Diamonds! Might as well mine them", DetourSpec.DIAMOND.chat);
        assertEquals("Mining diamonds while they are close", DetourSpec.DIAMOND.hud);
        assertEquals("ore", DetourSpec.DIAMOND.spotWord);
        assertEquals("diamond", DetourSpec.DIAMOND.itemWord);
        assertEquals("cluster mined", DetourSpec.DIAMOND.ended(Step.DONE));
        // and the kit task already on diamonds is no cause for a detour
        assertTrue(DetourSpec.DIAMOND.kitIsOnIt("diamond"));
        assertFalse(DetourSpec.DIAMOND.kitIsOnIt("coal"));
        assertFalse(DetourSpec.DIAMOND.kitIsOnIt(null));
    }

    // ---- the tool

    @Test
    public void noIronPickIsNoDetour() {
        Inputs bare = calm(0, false, true, 24);
        assertTrue(DetourRules.blocked(bare));
        assertEquals(Step.IDLE, rules().tick(1000, bare, LIM, ore(true)));
    }

    @Test
    public void onlyIronDiamondAndNetheritePicksCountAsTheTool() {
        ItemFixture.boot();
        assertFalse(ResourceDetour.hasIronPickaxe(new FakeFacts()));
        // stone gets coal going, a golden or wooden pick would not mine a diamond at all
        for (Item weak : List.of(Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.GOLDEN_PICKAXE, Items.IRON_SHOVEL, Items.DIAMOND)) {
            assertFalse(weak.toString(), ResourceDetour.hasIronPickaxe(new FakeFacts().give(weak, 1)));
        }
        for (Item good : List.of(Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE)) {
            assertTrue(good.toString(), ResourceDetour.hasIronPickaxe(new FakeFacts().give(good, 1)));
        }
    }

    @Test
    public void anIronOrBetterPickStarts() {
        // (which picks count is ResourceDetour.hasIronPickaxe, the rules only see the bit)
        DetourRules rules = rules();
        assertEquals(Step.START, rules.tick(1000, calm(0), LIM, ore(true)));
        assertTrue(rules.running());
    }

    @Test
    public void thePickGoingMidDetourEndsIt() {
        DetourRules rules = rules();
        rules.tick(1000, calm(0), LIM, ore(true));
        assertEquals(Step.BLOCKED, rules.tick(1001, calm(0, false, true, 24), LIM, ore(true)));
        assertFalse(rules.running());
    }

    // ---- the cap

    @Test
    public void justUnderTheCapStartsAndTheCapDoesNot() {
        assertEquals(Step.START, rules().tick(1000, calm(CFG.diamondSideCap - 1), LIM, ore(true)));
        assertEquals(Step.IDLE, rules().tick(1000, calm(CFG.diamondSideCap), LIM, ore(true)));
        assertEquals(Step.IDLE, rules().tick(1000, calm(CFG.diamondSideCap + 7), LIM, ore(true)));
    }

    @Test
    public void reachingTheCapEndsIt() {
        DetourRules rules = rules();
        rules.tick(1000, calm(0), LIM, ore(true));
        assertEquals(Step.KEEP, rules.tick(1001, calm(23), LIM, ore(true)));
        assertEquals(Step.DONE, rules.tick(1002, calm(24), LIM, ore(true)));
        assertFalse(rules.running());
    }

    @Test
    public void theCapComesFromTheConfig() {
        OverworldConfig cfg = new OverworldConfig();
        cfg.diamondSideCap = 5;
        Limits lim = DetourSpec.DIAMOND.limits(cfg);
        assertEquals(5, lim.heldCap());
        assertEquals(Step.IDLE, rules().tick(1000, calm(5, true, true, 5), lim, ore(true)));
        assertEquals(Step.START, rules().tick(1000, calm(4, true, true, 5), lim, ore(true)));
        assertEquals(5, DetourSpec.DIAMOND.mineTarget(cfg));
    }

    @Test
    public void theBudgetAndTheClockComeFromTheConfig() {
        OverworldConfig cfg = new OverworldConfig();
        cfg.diamondSideBudget = 7;
        cfg.diamondSideSeconds = 10;
        assertEquals(7, DetourSpec.DIAMOND.budget(cfg), 0);
        assertEquals(10 * 20, DetourSpec.DIAMOND.limits(cfg).maxTicks());
    }

    @Test
    public void switchedOffIsNoNeedAndNoDetour() {
        assertEquals(0, DetourSpec.diamondNeed(false, 24));
        Inputs off = calm(0, true, false, 24);
        assertEquals(Step.IDLE, rules().tick(1000, off, LIM, ore(true)));
        // flipped off mid detour it is just done
        DetourRules rules = rules();
        rules.tick(1000, calm(0), LIM, ore(true));
        assertEquals(Step.DONE, rules.tick(1001, off, LIM, ore(true)));
        // a typo'd negative cap is no need either
        assertEquals(0, DetourSpec.diamondNeed(true, -3));
    }

    @Test
    public void aDetourWithNoOreInSightDoesNotStart() {
        assertEquals(Step.IDLE, rules().tick(1000, calm(0), LIM, ore(false)));
    }

    // ---- the never rules, the same list coal has

    private static final List<UnaryOperator<Inputs>> NEVER = List.of(
            in -> new Inputs(false, in.tool(), in.held(), in.need(), in.cookStation(), in.furnaceDue(), in.loadInFlight(), in.foodLeads(), in.resourceHead()),
            in -> new Inputs(in.dimension(), in.tool(), in.held(), in.need(), true, in.furnaceDue(), in.loadInFlight(), in.foodLeads(), in.resourceHead()),
            in -> new Inputs(in.dimension(), in.tool(), in.held(), in.need(), in.cookStation(), true, in.loadInFlight(), in.foodLeads(), in.resourceHead()),
            in -> new Inputs(in.dimension(), in.tool(), in.held(), in.need(), in.cookStation(), in.furnaceDue(), true, in.foodLeads(), in.resourceHead()),
            in -> new Inputs(in.dimension(), in.tool(), in.held(), in.need(), in.cookStation(), in.furnaceDue(), in.loadInFlight(), true, in.resourceHead()),
            in -> new Inputs(in.dimension(), in.tool(), in.held(), in.need(), in.cookStation(), in.furnaceDue(), in.loadInFlight(), in.foodLeads(), true));
    private static final List<String> NEVER_NAMES = List.of("not the overworld", "cook has its station", "furnace due", "load in flight",
            "food leads", "diamond is the head");

    @Test
    public void eachNeverRuleKeepsItOffAndEndsOneThatIsGoing() {
        for (int i = 0; i < NEVER.size(); i++) {
            Inputs bad = NEVER.get(i).apply(calm(0));
            assertTrue(NEVER_NAMES.get(i), DetourRules.blocked(bad));
            DetourRules idle = rules();
            assertEquals(NEVER_NAMES.get(i), Step.IDLE, idle.tick(1000, bad, LIM, ore(true)));
            DetourRules going = rules();
            going.tick(1000, calm(0), LIM, ore(true));
            assertEquals(NEVER_NAMES.get(i), Step.BLOCKED, going.tick(1001, bad, LIM, ore(true)));
        }
    }

    @Test
    public void aFinishedDetourRestsForCoalsFiveSeconds() {
        DetourRules rules = rules();
        rules.tick(1000, calm(0), LIM, ore(true));
        assertEquals(Step.DONE, rules.tick(1001, calm(24), LIM, ore(true)));
        assertEquals(Step.IDLE, rules.tick(1002, calm(0), LIM, ore(true)));
        assertEquals(Step.START, rules.tick(1001 + DetourSpec.DIAMOND.cooldownTicks, calm(0), LIM, ore(true)));
    }

    @Test
    public void theClockEndsAStuckDetourAndAsksForTheBan() {
        DetourRules rules = rules();
        rules.tick(1000, calm(0), LIM, ore(true));
        // asked every second or so, a bigger gap than DetourRules.GAP_TICKS would be somebody else having the wheel
        for (long now = 1020; now <= 1000 + LIM.maxTicks(); now += 20) {
            assertEquals(Step.KEEP, rules.tick(now, calm(0), LIM, ore(true)));
        }
        assertEquals(Step.TIMEOUT, rules.tick(1020 + LIM.maxTicks(), calm(0), LIM, ore(true)));
    }

    // ---- lava

    // a world of lava cells, closed over by the rule
    private static DetourSpec.Lava lavaAt(int... xyz) {
        Set<List<Integer>> cells = new HashSet<>();
        for (int i = 0; i < xyz.length; i += 3) {
            cells.add(List.of(xyz[i], xyz[i + 1], xyz[i + 2]));
        }
        return (x, y, z) -> cells.contains(List.of(x, y, z));
    }

    @Test
    public void lavaOnAnyOfTheSixSidesRefusesTheOre() {
        int[][] around = {{11, 5, 5}, {9, 5, 5}, {10, 6, 5}, {10, 4, 5}, {10, 5, 6}, {10, 5, 4}};
        for (int[] cell : around) {
            assertTrue(List.of(cell[0], cell[1], cell[2]).toString(), DetourSpec.lavaTouches(lavaAt(cell), 10, 5, 5));
        }
    }

    @Test
    public void noLavaOrLavaOnlyAtADistanceOrAcrossACornerIsFine() {
        assertFalse(DetourSpec.lavaTouches(lavaAt(), 10, 5, 5));
        // two away, and the four-corner diagonals: a block of stone or a wall between, not touching
        assertFalse(DetourSpec.lavaTouches(lavaAt(12, 5, 5, 10, 7, 5, 10, 3, 5, 10, 5, 7), 10, 5, 5));
        assertFalse(DetourSpec.lavaTouches(lavaAt(11, 6, 5, 9, 4, 5, 11, 5, 6, 9, 5, 4), 10, 5, 5));
        // the ore's own cell is never asked about
        assertFalse(DetourSpec.lavaTouches(lavaAt(10, 5, 5), 10, 5, 5));
    }

    @Test
    public void lavaUnderTheOreCountsEvenThoughBaritoneNeverLooksDown() {
        assertTrue(DetourSpec.lavaTouches(lavaAt(10, 4, 5), 10, 5, 5));
    }

    // ---- the others do not move

    @Test
    public void coalAndGravelKeepTheirOwnNumbersAndWords() {
        assertEquals("coal", DetourSpec.COAL.name);
        assertEquals("coal", DetourSpec.COAL.itemWord);
        assertEquals("ore", DetourSpec.COAL.spotWord);
        assertEquals(CFG.coalSideCap, DetourSpec.COAL.mineTarget(CFG));
        assertEquals(CFG.coalSideCap, DetourSpec.COAL.limits(CFG).heldCap());
        assertEquals("Mining coal since we found it here", DetourSpec.COAL.chat);
        assertEquals("flint", DetourSpec.GRAVEL.itemWord);
        assertEquals("gravel", DetourSpec.GRAVEL.spotWord);
        assertEquals(64, DetourSpec.GRAVEL.mineTarget(CFG));
        assertEquals(1, DetourSpec.GRAVEL.limits(CFG).heldCap());
        assertEquals(10, DetourSpec.GRAVEL.limits(CFG).mineCap());
        // the coal need does not know diamonds exist: 9 smelts is two coal and the margin
        assertEquals(4, DetourSpec.coalNeed(9, 0, 0, 0, 0));
    }
}
