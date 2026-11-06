package adris.altoclef.tasks.speedrun.gamer.end;

import adris.altoclef.tasks.speedrun.gamer.GamerPhase;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasks.speedrun.gamer.phases.DragonPhase;
import adris.altoclef.tasks.speedrun.gamer.phases.EndPrepPhase;
import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.utils.Dimension;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// the pure half of the two handlers: isDone and regressTo over fake facts. the tick logic needs a game and is not covered here
public class EndPhasesTest {
    private final GamerConfig cfg = new GamerConfig();
    private final EndPrepPhase prep = new EndPrepPhase();
    private final DragonPhase dragon = new DragonPhase();

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    private static void endDeath(RunState state) {
        RunState.Death d = new RunState.Death();
        d.dimension = "END";
        state.deaths.add(d);
    }

    @Test
    public void aDeathWipesWhatTheVisitRememberedAboutItsErrands() throws Exception {
        poke("_spawnStartedAt", 123.0);
        poke("_bedTarget", new net.minecraft.core.BlockPos(1, 2, 3));
        poke("_spawnGo", true);
        poke("_bedMineTail", 7);
        // the overworld death does not re-enter the phase, so this is the only reset the second life gets
        prep.resetOnDeath(3);
        assertEquals(3, prep.bucketDeaths());
        assertEquals(-1, prep.spawnStartedAt(), 0);
        assertNull(peek("_bedTarget"));
        assertFalse((boolean) peek("_spawnGo"));
        assertEquals(0, (int) peek("_bedMineTail"));
    }

    @Test
    public void shortOnBedsNoClockGetsToUs() throws Exception {
        assertTrue(prep.stallSeconds() > 0);
        assertFalse(prep.holdsBudget());
        poke("_shortOnBeds", true);
        assertEquals(0, prep.stallSeconds(), 0);
        assertTrue(prep.holdsBudget());
        poke("_shortOnBeds", false);
        assertTrue(prep.stallSeconds() > 0);
    }

    @Test
    public void endStoneOnlyCountsRightAroundUs() {
        assertTrue(EndRules.endStoneClose(0, -1, 0));
        assertTrue(EndRules.endStoneClose(12, -6, -12));
        assertFalse(EndRules.endStoneClose(13, -1, 0));
        // a long drop for a stack is how we'd miss the landing
        assertFalse(EndRules.endStoneClose(0, -7, 0));
        assertFalse(EndRules.endStoneClose(0, -44, 0));
    }

    private void poke(String field, Object v) throws Exception {
        var f = prep.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(prep, v);
    }

    private Object peek(String field) throws Exception {
        var f = prep.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return f.get(prep);
    }

    @Test
    public void endPrepIsOnlyDoneOnceWeAreInTheEnd() {
        FakeFacts f = new FakeFacts();
        RunState s = new RunState();
        // a full kit standing next to the portal is not done, walking in is the last step of the phase
        assertFalse(prep.isDone(f, s, cfg));
        f.dimension = Dimension.END;
        assertTrue(prep.isDone(f, s, cfg));
    }

    @Test
    public void endPrepIsDoneWhenTheRunAlreadyKnowsTheDragonIsDeadOrCreditsRolled() {
        FakeFacts f = new FakeFacts();
        RunState s = new RunState();
        s.dragonDead = true;
        assertTrue(prep.isDone(f, s, cfg));
        s.dragonDead = false;
        f.credits = true;
        assertTrue(prep.isDone(f, s, cfg));
    }

    @Test
    public void dragonIsDoneAfterTheDragonDiedAndWeAreHome() {
        FakeFacts f = new FakeFacts();
        RunState s = new RunState();
        f.dimension = Dimension.END;
        s.dragonDead = true;
        // still standing in the End: the exit portal walk is not done yet
        assertFalse(dragon.isDone(f, s, cfg));
        f.dimension = Dimension.OVERWORLD;
        assertTrue(dragon.isDone(f, s, cfg));
    }

    @Test
    public void dragonIsDoneOnCreditsAndNotOnAnAliveDragon() {
        FakeFacts f = new FakeFacts();
        RunState s = new RunState();
        assertFalse(dragon.isDone(f, s, cfg));
        f.credits = true;
        assertTrue(dragon.isDone(f, s, cfg));
    }

    @Test
    public void aDeathSendsUsBackToEndPrepWhileAttemptsLast() {
        FakeFacts f = new FakeFacts();
        RunState s = new RunState();
        endDeath(s);
        assertEquals(Optional.of(GamerPhase.END_PREP), dragon.regressTo(f, s, cfg));
        endDeath(s);
        assertEquals(Optional.of(GamerPhase.END_PREP), dragon.regressTo(f, s, cfg));
        endDeath(s);
        assertEquals(cfg.end.attempts, EndRules.endDeaths(s));
        assertEquals(Optional.empty(), dragon.regressTo(f, s, cfg));
    }

    @Test
    public void noRegressInTheEndOrAfterTheDragonDied() {
        FakeFacts f = new FakeFacts();
        RunState s = new RunState();
        f.dimension = Dimension.END;
        assertEquals(Optional.empty(), dragon.regressTo(f, s, cfg));
        f.dimension = Dimension.OVERWORLD;
        s.dragonDead = true;
        assertEquals(Optional.empty(), dragon.regressTo(f, s, cfg));
    }

    @Test
    public void deathsElsewhereDoNotUseUpDragonAttempts() {
        RunState s = new RunState();
        for (String dim : new String[]{"OVERWORLD", "NETHER", "minecraft:the_nether"}) {
            RunState.Death d = new RunState.Death();
            d.dimension = dim;
            s.deaths.add(d);
        }
        assertEquals(0, EndRules.endDeaths(s));
        for (String dim : new String[]{"END", "the_end", "minecraft:the_end"}) {
            assertTrue(EndRules.isEnd(dim));
        }
    }

    // DragonPhase saves RunState.dragonDead from latch.exitPortalSeen() only. a "dragon gone for 5 s" guess (it leaves tracking
    // range on the far side of its lap) followed by a fall into the void must not look like a finished run
    @Test
    public void aSoftDeadGuessIsNotSavedSoARespawnInTheOverworldIsNotDone() {
        DragonDeadLatch latch = new DragonDeadLatch(cfg.end.dragonGoneSeconds);
        latch.update(false, true, true, 0);
        latch.update(false, false, true, 1);
        assertTrue(latch.update(false, false, true, 6));
        FakeFacts f = new FakeFacts();
        RunState s = new RunState();
        s.dragonDead = latch.exitPortalSeen();
        endDeath(s);
        assertFalse(s.dragonDead);
        assertFalse(dragon.isDone(f, s, cfg));
        assertEquals(Optional.of(GamerPhase.END_PREP), dragon.regressTo(f, s, cfg));
    }

    @Test
    public void aHardDeadFromTheExitPortalSurvivesARelogAndIsDoneInTheOverworld() {
        DragonDeadLatch latch = new DragonDeadLatch(cfg.end.dragonGoneSeconds);
        latch.update(true, false, true, 0);
        RunState s = new RunState();
        s.dragonDead = latch.exitPortalSeen();
        FakeFacts f = new FakeFacts();
        assertTrue(dragon.isDone(f, s, cfg));
        // and a fresh latch after the relog starts from the saved flag
        DragonDeadLatch after = new DragonDeadLatch(5);
        after.markDead();
        assertTrue(after.exitPortalSeen());
    }

    @Test
    public void handlersReportTheirPhaseAndWaitWithoutAStallTimer() {
        assertEquals(GamerPhase.END_PREP, prep.phase());
        assertEquals(GamerPhase.DRAGON, dragon.phase());
        assertEquals(0, dragon.stallSeconds(), 0);
        assertTrue(prep.stallSeconds() > 0);
    }

    @Test
    public void theEndPrepHoldsNeitherTheNightNorTheBudgetUntilItWalksIn() {
        assertFalse(prep.nightHold());
        assertFalse(prep.holdsBudget());
    }
}
