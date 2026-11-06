package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;

import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Kind;
import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CombatCommit.Tick;
import adris.altoclef.util.helpers.CombatCommit.Way;
import adris.altoclef.util.helpers.CombatCommit.Why;
import java.util.List;
import java.util.Set;
import org.junit.Test;

// a heavy hitter standing in the path is a run, never a fight we pick at full hp. one that hits us stays the HIT fight
public class CombatCommitHeavyWayTest {

    private static final long NEVER = CombatCommit.NEVER;

    private static Foe hoglin(double distance, long sinceHit) {
        return new Foe(7, distance, false, false, sinceHit, Kind.HEAVY);
    }

    private static Tick tick(long now, List<Foe> foes, Way way) {
        return new Tick(now, 20, 0, 0, foes, null, way);
    }

    @Test
    public void aHoglinInThePathAtFullHpIsARunNotAFight() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(0, List.of(hoglin(1.5, NEVER)), new Way(Set.of(7), true, 64))));
        assertEquals(Mode.RUN, c.mode());
        assertEquals(Why.HEAVY, c.why());
    }

    @Test
    public void aHoglinBesideThePathIsScenery() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.NONE, c.step(tick(0, List.of(hoglin(1.5, NEVER)), new Way(Set.of(), true, 64))));
    }

    @Test
    public void aHoglinInThePathButOutOfContactIsScenery() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.NONE, c.step(tick(0, List.of(hoglin(5, NEVER)), new Way(Set.of(7), true, 64))));
    }

    @Test
    public void boxedInWithAHoglinNearIsNotTheBoxedFightEither() {
        CombatCommit c = new CombatCommit();
        Foe h = hoglin(2, NEVER);
        for (long t = 0; t < CombatCommit.WAY_BOXED_TICKS + 20; t++) {
            assertEquals(Event.NONE, c.step(tick(t, List.of(h), new Way(Set.of(), true, 64))));
        }
    }

    @Test
    public void aHoglinThatHitUsIsStillTheHitFight() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(0, List.of(hoglin(1.5, 0)), new Way(Set.of(7), true, 64))));
        assertEquals(Why.HIT, c.why());
    }

    @Test
    public void aZombieInThePathIsStillAFight() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(0, List.of(new Foe(1, 1.5, false, false, NEVER)), new Way(Set.of(1), true, 64))));
        assertEquals(Why.IN_WAY, c.why());
    }

    @Test
    public void theRunEndsAndTheSameHoglinInThePathStartsItAgainOnlyOnceWeAreBack() {
        CombatCommit c = new CombatCommit();
        c.step(tick(0, List.of(hoglin(1.5, NEVER)), new Way(Set.of(7), true, 64)));
        // we run 24 blocks and nothing is near: the run ends
        Event end = Event.NONE;
        for (long t = 1; t < 400 && end == Event.NONE; t++) {
            end = c.step(new Tick(t, 20, t * 0.5, 0, List.of(), null, Way.NONE));
        }
        assertEquals(Event.RUN_CLEAR, end);
        // far from the hoglin the path through it is no contact, so nothing starts
        assertEquals(Event.NONE, c.step(new Tick(500, 20, 24, 0, List.of(hoglin(30, NEVER)), null, new Way(Set.of(7), true, 64))));
    }

    // the same hoglin back in the path after the run: no second run (that is the loop), no fight picked either. the zones keep the
    // sweep's path off it, anywhere else we walk on and a hit is the HIT fight
    @Test
    public void theSameHoglinBackInThePathIsNotARunAgain() {
        CombatCommit c = new CombatCommit();
        Foe h = hoglin(1.5, NEVER);
        c.step(tick(0, List.of(h), new Way(Set.of(7), true, 64)));
        Event end = Event.NONE;
        for (long t = 1; t < 400 && end == Event.NONE; t++) {
            end = c.step(new Tick(t, 20, t * 0.5, 0, List.of(), null, Way.NONE));
        }
        assertEquals(Event.RUN_CLEAR, end);
        for (long t = 600; t < 700; t++) {
            assertEquals(Event.NONE, c.step(tick(t, List.of(h), new Way(Set.of(7), true, 64))));
        }
        assertEquals(Mode.NONE, c.mode());
    }

    @Test
    public void aDifferentHoglinInThePathIsStillARun() {
        CombatCommit c = new CombatCommit();
        c.step(tick(0, List.of(hoglin(1.5, NEVER)), new Way(Set.of(7), true, 64)));
        c.reset();
        Foe other = new Foe(8, 1.5, false, false, NEVER, Kind.HEAVY);
        assertEquals(Event.RUN_START, c.step(tick(10, List.of(other), new Way(Set.of(8), true, 64))));
    }
}
