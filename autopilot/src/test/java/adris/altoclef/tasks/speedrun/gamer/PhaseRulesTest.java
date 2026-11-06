package adris.altoclef.tasks.speedrun.gamer;

import static org.junit.Assert.assertEquals;

import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

public class PhaseRulesTest {
    private final FakeFacts facts = new FakeFacts();
    private final RunState state = new RunState();
    private final GamerConfig cfg = new GamerConfig();

    private static FakeHandler of(List<FakeHandler> hs, GamerPhase p) {
        return hs.stream().filter(h -> h.phase == p).findFirst().orElseThrow();
    }

    private PhaseRules rules(List<FakeHandler> hs) {
        return new PhaseRules(new ArrayList<PhaseHandler>(hs));
    }

    private PhaseRules.Decision decide(PhaseRules r, GamerPhase p, double now) {
        return r.decide(p, facts, state, cfg, now);
    }

    @Test
    public void staysWhileNotDone() {
        PhaseRules r = rules(FakeHandler.full());
        assertEquals(PhaseRules.Kind.STAY, decide(r, GamerPhase.GATHER, 0).kind());
    }

    @Test
    public void advancesOnePhaseWhenOnlyThisOneIsDone() {
        List<FakeHandler> hs = FakeHandler.full();
        of(hs, GamerPhase.GATHER).alwaysDone();
        PhaseRules.Decision d = decide(rules(hs), GamerPhase.GATHER, 0);
        assertEquals(PhaseRules.Kind.ADVANCE, d.kind());
        assertEquals(GamerPhase.IRON, d.to());
    }

    @Test
    public void aResumedRunSkipsAheadButOnlyFourPhasesPerTick() {
        List<FakeHandler> hs = FakeHandler.full();
        hs.forEach(FakeHandler::alwaysDone);
        PhaseRules r = rules(hs);
        PhaseRules.Decision d = decide(r, GamerPhase.GATHER, 0);
        // GATHER -> IRON -> PORTAL -> NETHER -> EYES, then the next tick carries on
        assertEquals(GamerPhase.EYES, d.to());
        // EYES -> RETURN -> LOCATE -> ROOM -> OPEN
        assertEquals(GamerPhase.OPEN, decide(r, GamerPhase.EYES, 0).to());
    }

    @Test
    public void theCascadeStopsAtTheFirstPhaseThatIsNotDone() {
        List<FakeHandler> hs = FakeHandler.full();
        of(hs, GamerPhase.GATHER).alwaysDone();
        of(hs, GamerPhase.IRON).alwaysDone();
        PhaseRules.Decision d = decide(rules(hs), GamerPhase.GATHER, 0);
        assertEquals(GamerPhase.PORTAL, d.to());
    }

    @Test
    public void doneIsOnlyReachedThroughADoneDragon() {
        List<FakeHandler> hs = FakeHandler.full();
        PhaseRules r = rules(hs);
        // everything before the dragon being done cannot skip the dragon
        hs.stream().filter(h -> h.phase != GamerPhase.DRAGON).forEach(FakeHandler::alwaysDone);
        assertEquals(GamerPhase.DRAGON, decide(r, GamerPhase.END_PREP, 0).to());
        assertEquals(PhaseRules.Kind.STAY, decide(r, GamerPhase.DRAGON, 0).kind());
        of(hs, GamerPhase.DRAGON).alwaysDone();
        PhaseRules.Decision d = decide(r, GamerPhase.DRAGON, 0);
        assertEquals(PhaseRules.Kind.ADVANCE, d.kind());
        assertEquals(GamerPhase.DONE, d.to());
    }

    @Test
    public void aCascadeFromBeforeTheEndCanFinishTheRunInTwoTicks() {
        List<FakeHandler> hs = FakeHandler.full();
        hs.forEach(FakeHandler::alwaysDone);
        PhaseRules r = rules(hs);
        assertEquals(GamerPhase.DONE, decide(r, GamerPhase.OPEN, 0).to());
    }

    @Test
    public void terminalPhasesNeverMove() {
        List<FakeHandler> hs = FakeHandler.full();
        hs.forEach(FakeHandler::alwaysDone);
        of(hs, GamerPhase.DRAGON).regress = Optional.of(GamerPhase.GATHER);
        PhaseRules r = rules(hs);
        assertEquals(PhaseRules.Kind.STAY, decide(r, GamerPhase.DONE, 0).kind());
        assertEquals(PhaseRules.Kind.STAY, decide(r, GamerPhase.STUCK, 0).kind());
    }

    @Test
    public void regressesToAnEarlierPhaseOnlyWhenAsked() {
        List<FakeHandler> hs = FakeHandler.full();
        of(hs, GamerPhase.LOCATE).regress = Optional.of(GamerPhase.NETHER);
        PhaseRules r = rules(hs);
        PhaseRules.Decision d = decide(r, GamerPhase.LOCATE, 0);
        assertEquals(PhaseRules.Kind.REGRESS, d.kind());
        assertEquals(GamerPhase.NETHER, d.to());
        // other phases have nothing to say
        assertEquals(PhaseRules.Kind.STAY, decide(r, GamerPhase.ROOM, 0).kind());
    }

    @Test
    public void aRegressToTheSameOrALaterPhaseOrATerminalOneIsIgnored() {
        List<FakeHandler> hs = FakeHandler.full();
        PhaseRules r = rules(hs);
        for (GamerPhase bad : new GamerPhase[]{GamerPhase.LOCATE, GamerPhase.ROOM, GamerPhase.DONE, GamerPhase.STUCK}) {
            of(hs, GamerPhase.LOCATE).regress = Optional.of(bad);
            assertEquals(bad.name(), PhaseRules.Kind.STAY, decide(r, GamerPhase.LOCATE, 0).kind());
        }
    }

    @Test
    public void aPhaseRegressesOnceASixtySecondsNotEveryTick() {
        List<FakeHandler> hs = FakeHandler.full();
        of(hs, GamerPhase.OPEN).regress = Optional.of(GamerPhase.NETHER);
        PhaseRules r = rules(hs);
        assertEquals(PhaseRules.Kind.REGRESS, decide(r, GamerPhase.OPEN, 100).kind());
        assertEquals(PhaseRules.Kind.STAY, decide(r, GamerPhase.OPEN, 101).kind());
        assertEquals(PhaseRules.Kind.STAY, decide(r, GamerPhase.OPEN, 159.9).kind());
        assertEquals(PhaseRules.Kind.REGRESS, decide(r, GamerPhase.OPEN, 160).kind());
    }

    @Test
    public void theCooldownIsPerPhase() {
        List<FakeHandler> hs = FakeHandler.full();
        of(hs, GamerPhase.OPEN).regress = Optional.of(GamerPhase.NETHER);
        of(hs, GamerPhase.ROOM).regress = Optional.of(GamerPhase.NETHER);
        PhaseRules r = rules(hs);
        assertEquals(PhaseRules.Kind.REGRESS, decide(r, GamerPhase.OPEN, 100).kind());
        assertEquals(PhaseRules.Kind.REGRESS, decide(r, GamerPhase.ROOM, 101).kind());
    }

    @Test
    public void doneBeatsRegress() {
        List<FakeHandler> hs = FakeHandler.full();
        FakeHandler open = of(hs, GamerPhase.OPEN);
        open.alwaysDone();
        open.regress = Optional.of(GamerPhase.NETHER);
        PhaseRules.Decision d = decide(rules(hs), GamerPhase.OPEN, 0);
        assertEquals(PhaseRules.Kind.ADVANCE, d.kind());
        assertEquals(GamerPhase.END_PREP, d.to());
    }

    @Test
    public void aPhaseWithoutAHandlerStays() {
        PhaseRules r = new PhaseRules(List.of());
        assertEquals(PhaseRules.Kind.STAY, decide(r, GamerPhase.GATHER, 0).kind());
    }
}
