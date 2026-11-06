package adris.altoclef.tasks.speedrun.gamer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.tasks.speedrun.gamer.DetourRules.Inputs;
import adris.altoclef.tasks.speedrun.gamer.DetourRules.Limits;
import adris.altoclef.tasks.speedrun.gamer.DetourRules.Step;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import baritone.api.utils.Dimension;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

// gravel for flint: the same rules as coal with its own numbers. the world half (ResourceDetour.gravel) is only compile checked
public class GravelDetourTest {
    private static final OverworldConfig CFG = new OverworldConfig();
    private static final Limits LIM = DetourSpec.GRAVEL.limits(CFG);

    // somewhere gravel may be dug, no pick needed, nothing else going on, `flint` held and `mined` dug so far
    private static Inputs calm(int flint, int mined) {
        return new Inputs(true, true, flint, DetourSpec.flintNeed(flint, false), false, false, false, false, false, mined);
    }

    private static DetourRules.Ore ore(boolean there) {
        return new DetourRules.Ore(() -> there, () -> there, () -> false, () -> false);
    }

    private static DetourRules started(long now) {
        DetourRules rules = new DetourRules(DetourSpec.GRAVEL.cooldownTicks);
        assertEquals(Step.START, rules.tick(now, calm(0, 0), LIM, ore(true)));
        return rules;
    }

    // ---- the need

    @Test
    public void oneFlintIsOwedUntilWeHoldOneOrTheLighterIsMade() {
        assertEquals(1, DetourSpec.flintNeed(0, false));
        assertEquals(0, DetourSpec.flintNeed(1, false));
        assertEquals(0, DetourSpec.flintNeed(5, false));
        // flint and steel (or a fire charge) made: the flint is spent and not wanted again
        assertEquals(0, DetourSpec.flintNeed(0, true));
    }

    @Test
    public void noNeedIsNoDetourEvenWithGravelRightThere() {
        Inputs lit = new Inputs(true, true, 0, DetourSpec.flintNeed(0, true), false, false, false, false, false, 0);
        assertEquals(Step.IDLE, new DetourRules(DetourSpec.GRAVEL.cooldownTicks).tick(1000, lit, LIM, ore(true)));
        assertEquals(Step.IDLE, new DetourRules(DetourSpec.GRAVEL.cooldownTicks).tick(1000, calm(1, 0), LIM, ore(true)));
    }

    @Test
    public void owedAndInSightStartsAndTheFirstFlintEndsIt() {
        DetourRules rules = started(1000);
        assertEquals(Step.KEEP, rules.tick(1001, calm(0, 3), LIM, ore(true)));
        assertEquals(Step.DONE, rules.tick(1002, calm(1, 4), LIM, ore(true)));
        assertFalse(rules.running());
    }

    @Test
    public void theKitOnFlintAlreadyIsNoDetour() {
        Inputs head = new Inputs(true, true, 0, 1, false, false, false, false, true, 0);
        assertTrue(DetourRules.blocked(head));
        assertTrue(DetourSpec.GRAVEL.kitIsOnIt("flint"));
    }

    @Test
    public void theKitOnTheFlintAndSteelIsOnTheFlintToo() {
        assertTrue(DetourSpec.GRAVEL.kitIsOnIt("flint_and_steel"));
        assertFalse(DetourSpec.GRAVEL.kitIsOnIt("iron_ingot"));
        assertFalse(DetourSpec.GRAVEL.kitIsOnIt(null));
        // coal only ever stood aside for coal
        assertTrue(DetourSpec.COAL.kitIsOnIt("coal"));
        assertFalse(DetourSpec.COAL.kitIsOnIt("flint_and_steel"));
        assertFalse(DetourSpec.COAL.kitIsOnIt(null));
    }

    // ---- the cap

    @Test
    public void tenBlocksAndNoFlintEndsIt() {
        assertEquals(10, CFG.gravelSideBlocks);
        assertEquals(10, LIM.mineCap());
        DetourRules rules = started(1000);
        for (int mined = 0; mined < 10; mined++) {
            assertEquals("mined " + mined, Step.KEEP, rules.tick(1001 + mined, calm(0, mined), LIM, ore(true)));
        }
        // the 10th block's drop gets its moment first, then it is over
        assertEquals(Step.SETTLE, rules.tick(1011, calm(0, 10), LIM, ore(true)));
        assertEquals(Step.SETTLE, rules.tick(1011 + DetourRules.DROP_GRACE_TICKS - 1, calm(0, 10), LIM, ore(true)));
        assertEquals(Step.CAPPED, rules.tick(1011 + DetourRules.DROP_GRACE_TICKS, calm(0, 10), LIM, ore(true)));
        assertFalse(rules.running());
        assertEquals("dug its share, no flint", DetourSpec.GRAVEL.ended(Step.CAPPED));
    }

    @Test
    public void theTenthBlockThatDropsTheFlintIsASuccessNotACap() {
        DetourRules rules = started(1000);
        assertEquals(Step.DONE, rules.tick(1001, calm(1, 10), LIM, ore(true)));
    }

    @Test
    public void theCapComesFromTheConfig() {
        OverworldConfig cfg = new OverworldConfig();
        cfg.gravelSideBlocks = 3;
        Limits lim = DetourSpec.GRAVEL.limits(cfg);
        DetourRules rules = new DetourRules(DetourSpec.GRAVEL.cooldownTicks);
        rules.tick(1000, calm(0, 0), lim, ore(true));
        assertEquals(Step.KEEP, rules.tick(1001, calm(0, 2), lim, ore(true)));
        assertEquals(Step.SETTLE, rules.tick(1002, calm(0, 3), lim, ore(true)));
        assertEquals(Step.CAPPED, rules.tick(1002 + DetourRules.DROP_GRACE_TICKS, calm(0, 3), lim, ore(true)));
    }

    @Test
    public void theFlintFromTheTenthBlockStillGetsPickedUp() {
        DetourRules rules = started(1000);
        DetourRules.Ore flintOnTheFloor = new DetourRules.Ore(() -> true, () -> true, () -> true, () -> false);
        // block 10 just went and its flint is lying there: no cap while it is, however long the walk to it takes
        for (long now = 1001; now <= 1001 + 3 * DetourRules.DROP_GRACE_TICKS; now += 20) {
            assertEquals(Step.SETTLE, rules.tick(now, calm(0, 10), LIM, flintOnTheFloor));
        }
        assertEquals(Step.DONE, rules.tick(1002 + 3 * DetourRules.DROP_GRACE_TICKS, calm(1, 10), LIM, flintOnTheFloor));
    }

    @Test
    public void aDropWeNeverReachStillEndsOnTheClock() {
        DetourRules rules = started(1000);
        DetourRules.Ore flintOnTheFloor = new DetourRules.Ore(() -> true, () -> true, () -> true, () -> false);
        long now = 1001;
        assertEquals(Step.SETTLE, rules.tick(now, calm(0, 10), LIM, flintOnTheFloor));
        while (now + 20 <= 1000 + LIM.maxTicks()) {
            now += 20;
            assertEquals(Step.SETTLE, rules.tick(now, calm(0, 10), LIM, flintOnTheFloor));
        }
        assertEquals(Step.CAPPED, rules.tick(now + 20, calm(0, 10), LIM, flintOnTheFloor));
    }

    @Test
    public void coalHasNoBlockCap() {
        Limits coal = DetourSpec.COAL.limits(CFG);
        assertEquals(0, coal.mineCap());
        assertFalse(DetourRules.capped(new Inputs(true, true, 0, 5, false, false, false, false, false, 500), coal));
    }

    @Test
    public void theClockIsThirtySeconds() {
        assertEquals(600, LIM.maxTicks());
        DetourRules rules = started(1000);
        // a tick every 20 so the gap rule stays out of it
        long now = 1000;
        Step last = Step.KEEP;
        while (last == Step.KEEP) {
            now += 20;
            last = rules.tick(now, calm(0, 1), LIM, ore(true));
        }
        assertEquals(Step.TIMEOUT, last);
        assertEquals(1000 + 600 + 20, now);
    }

    // ---- the cooldown

    @Test
    public void twoMinutesBeforeTheNextGravelDetour() {
        assertEquals(2 * 60 * 20, DetourSpec.GRAVEL.cooldownTicks);
        DetourRules rules = started(1000);
        assertEquals(Step.SETTLE, rules.tick(1001, calm(0, 10), LIM, ore(true)));
        long end = 1001 + DetourRules.DROP_GRACE_TICKS;
        assertEquals(Step.CAPPED, rules.tick(end, calm(0, 10), LIM, ore(true)));
        // the next patch is right there and nothing is in the bag, still too soon
        assertEquals(Step.IDLE, rules.tick(end + 1, calm(0, 0), LIM, ore(true)));
        assertEquals(Step.IDLE, rules.tick(end + 2400 - 1, calm(0, 0), LIM, ore(true)));
        assertEquals(Step.START, rules.tick(end + 2400, calm(0, 0), LIM, ore(true)));
    }

    @Test
    public void aPreemptedGravelDetourAlsoWaitsTheTwoMinutes() {
        DetourRules rules = started(1000);
        rules.preempted(1005);
        assertEquals(Step.IDLE, rules.tick(1006, calm(0, 0), LIM, ore(true)));
        assertEquals(Step.START, rules.tick(1005 + 2400, calm(0, 0), LIM, ore(true)));
    }

    @Test
    public void coalKeepsItsFiveSeconds() {
        assertEquals(5 * 20, DetourSpec.COAL.cooldownTicks);
        assertEquals(DetourRules.COOLDOWN_TICKS, DetourSpec.COAL.cooldownTicks);
    }

    // ---- the tool

    @Test
    public void aShovelIsPreferredAndAHandIsEnough() {
        assertEquals("a shovel", DetourSpec.gravelTool(true));
        assertEquals("bare hands", DetourSpec.gravelTool(false));
        // no pick in the bag is not a never rule for gravel: the world half hands in tool = true
        Inputs noPick = calm(0, 0);
        assertFalse(DetourRules.blocked(noPick));
    }

    // ---- the falling column

    @Test
    public void neverUnderAColumnTallerThanTwo() {
        // feet at 0 64 0, so the head is 65 and the first cell over the head is 66
        assertTrue(DetourSpec.underTallColumn(0, 64, 0, 0, 66, 0, 3));
        assertTrue(DetourSpec.underTallColumn(0, 64, 0, 0, 70, 0, 5));
        // two lands on our feet and pushes us out, that one is fine
        assertFalse(DetourSpec.underTallColumn(0, 64, 0, 0, 66, 0, 2));
        assertFalse(DetourSpec.underTallColumn(0, 64, 0, 0, 66, 0, 1));
        // a column next to us falls into its own cell, that is how gravel is dug
        assertFalse(DetourSpec.underTallColumn(0, 64, 0, 1, 66, 0, 5));
        assertFalse(DetourSpec.underTallColumn(0, 64, 0, 0, 66, 1, 5));
        // the floor under us brings nothing down on us
        assertFalse(DetourSpec.underTallColumn(0, 64, 0, 0, 63, 0, 5));
    }

    // ---- where

    @Test
    public void everyOverworldPhaseFromGatherOnAndTheNether() {
        for (GamerPhase p : GamerPhase.values()) {
            boolean live = !p.isTerminal();
            assertEquals(p.name(), live, DetourSpec.gravelPhase(p, Dimension.OVERWORLD));
            assertEquals(p.name(), live, DetourSpec.gravelPhase(p, Dimension.NETHER));
            assertFalse(p.name(), DetourSpec.gravelPhase(p, Dimension.END));
        }
        // right at spawn counts
        assertTrue(DetourSpec.gravelPhase(GamerPhase.GATHER, Dimension.OVERWORLD));
        assertFalse(DetourSpec.gravelPhase(null, Dimension.OVERWORLD));
    }

    @Test
    public void theWrongPlaceIsANeverRule() {
        Inputs end = new Inputs(DetourSpec.gravelPhase(GamerPhase.DRAGON, Dimension.END), true, 0, 1, false, false, false, false, false, 0);
        assertTrue(DetourRules.blocked(end));
    }

    @Test
    public void portalOnlyAsksInTheOverworldBeforeTheGateIsDone() {
        assertTrue(DetourSpec.portalMayDetour(true, false));
        // the pool, the cast or the obsidian are going: hands off
        assertFalse(DetourSpec.portalMayDetour(true, true));
        assertFalse(DetourSpec.portalMayDetour(false, false));
    }

    @Test
    public void netherOnlyAsksInTheNetherAndNeverAtASpawnerATradeOrAHunt() {
        assertTrue(DetourSpec.netherMayDetour(true, false, false, false));
        assertFalse(DetourSpec.netherMayDetour(false, false, false, false));
        assertFalse(DetourSpec.netherMayDetour(true, true, false, false));
        assertFalse(DetourSpec.netherMayDetour(true, false, true, false));
        // walking at an enderman, angry or not
        assertFalse(DetourSpec.netherMayDetour(true, false, false, true));
    }

    // ---- the announce rule is the coal one

    @Test
    public void oneChatLinePerPatch() {
        assertEquals("Getting flint from this gravel", DetourSpec.GRAVEL.chat);
        DetourRules rules = new DetourRules(DetourSpec.GRAVEL.cooldownTicks);
        assertTrue(rules.announce(1000, 0, 64, 0));
        assertFalse(rules.announce(1100, 3, 64, 0));
        assertTrue(rules.announce(1200, 20, 64, 0));
    }

    // ---- counting what we broke

    @Test
    public void aBreakCountsOnceWhenTheBlockIsGone() {
        Set<Long> gravel = new HashSet<>(List.of(1L, 2L));
        DetourRules.Tally t = new DetourRules.Tally();
        t.tick(true, 1L, gravel::contains);
        t.tick(true, 1L, gravel::contains);
        assertEquals(0, t.count());
        gravel.remove(1L);
        // the stale break flag stays up for a bit after the block went, that is not a second break
        t.tick(true, 1L, gravel::contains);
        t.tick(true, 1L, gravel::contains);
        assertEquals(1, t.count());
        t.tick(true, 2L, gravel::contains);
        gravel.remove(2L);
        t.tick(false, 0L, gravel::contains);
        assertEquals(2, t.count());
    }

    @Test
    public void theBlockThatFallsInIsTheNextBreakNotThisOne() {
        Set<Long> gravel = new HashSet<>(List.of(1L));
        DetourRules.Tally t = new DetourRules.Tally();
        t.tick(true, 1L, gravel::contains);
        gravel.remove(1L);
        t.tick(true, 1L, gravel::contains);
        // the one above lands in the cell while the stale flag is still up: watched again, counted when it goes too
        gravel.add(1L);
        t.tick(true, 1L, gravel::contains);
        assertEquals(1, t.count());
        gravel.remove(1L);
        t.tick(true, 1L, gravel::contains);
        assertEquals(2, t.count());
    }

    @Test
    public void aBreakWeGaveUpOnIsNotCounted() {
        Set<Long> gravel = new HashSet<>(List.of(1L));
        DetourRules.Tally t = new DetourRules.Tally();
        t.tick(true, 1L, gravel::contains);
        // walked off halfway, the flag went stale
        t.tick(false, 0L, gravel::contains);
        // and later something else knocked it down
        gravel.remove(1L);
        t.tick(false, 0L, gravel::contains);
        assertEquals(0, t.count());
        t.tick(true, 1L, gravel::contains);
        t.reset();
        assertEquals(0, t.count());
    }

    // ---- the order in IRON

    @Test
    public void gravelSitsRightBehindCoal() {
        assertEquals(IronActivity.Kind.COAL.ordinal() + 1, IronActivity.Kind.GRAVEL.ordinal());
        IronActivity.Scene plain = new IronActivity.Scene(false, false, true, true, false);
        List<IronActivity.Kind> ask = new IronActivity().ask(plain, 0);
        assertTrue(ask.indexOf(IronActivity.Kind.COAL) < ask.indexOf(IronActivity.Kind.GRAVEL));
    }

    @Test
    public void aHeldCoalDetourIsNotCutByGravelAndAHeldGravelDetourIsNotCutByCoal() {
        IronActivity.Scene plain = new IronActivity.Scene(false, false, true, true, false);
        assertFalse(IronActivity.preempts(IronActivity.Kind.GRAVEL, IronActivity.Kind.COAL, false));
        assertFalse(IronActivity.preempts(IronActivity.Kind.COAL, IronActivity.Kind.GRAVEL, false));
        IronActivity a = new IronActivity();
        a.chose(IronActivity.Kind.GRAVEL, 0);
        // the held one is asked before the open choice, so coal only gets a look once gravel hands back null
        List<IronActivity.Kind> ask = a.ask(plain, 1);
        assertTrue(ask.indexOf(IronActivity.Kind.GRAVEL) < ask.indexOf(IronActivity.Kind.COAL));
    }

    @Test
    public void aQuickSmokerStandByCutsInOnGravelToo() {
        assertTrue(IronActivity.preempts(IronActivity.Kind.STAND_BY, IronActivity.Kind.GRAVEL, false));
        IronActivity.Scene standing = new IronActivity.Scene(true, true, true, true, false);
        assertFalse(IronActivity.eligible(IronActivity.Kind.GRAVEL, standing));
    }
}
