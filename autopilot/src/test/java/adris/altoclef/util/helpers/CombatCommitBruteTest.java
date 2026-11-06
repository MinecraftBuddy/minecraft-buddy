package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;

import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Kind;
import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CombatCommit.Tick;
import adris.altoclef.util.helpers.CombatCommit.Why;
import baritone.api.utils.Dimension;
import java.util.List;
import org.junit.Test;

// a piglin brute that sees us inside 12 blocks is a run at any hp, before it swings. a hoglin is not
public class CombatCommitBruteTest {

    private static final long NEVER = CombatCommit.NEVER;

    private static Foe brute(double distance, boolean sight) {
        return new Foe(9, distance, false, false, NEVER, Kind.HEAVY, Double.POSITIVE_INFINITY, sight, NEVER).withBrute(true);
    }

    private static Tick tick(long now, double x, List<Foe> foes) {
        return new Tick(now, 20, x, 0, foes, null, CombatCommit.Way.NONE);
    }

    @Test
    public void aBruteWithSightAtTwelveIsARunAtFullHp() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(0, 0, List.of(brute(12, true)))));
        assertEquals(Mode.RUN, c.mode());
        assertEquals(Why.HEAVY, c.why());
    }

    @Test
    public void aHoglinAtTheSameSpotIsScenery() {
        Foe hoglin = new Foe(9, 8, false, false, NEVER, Kind.HEAVY, Double.POSITIVE_INFINITY, true, NEVER);
        assertEquals(Event.NONE, new CombatCommit().step(tick(0, 0, List.of(hoglin))));
    }

    @Test
    public void noSightOrBeyondTwelveIsScenery() {
        assertEquals(Event.NONE, new CombatCommit().step(tick(0, 0, List.of(brute(8, false)))));
        assertEquals(Event.NONE, new CombatCommit().step(tick(0, 0, List.of(brute(12.5, true)))));
    }

    @Test
    public void theSameBruteIsNotARunAgainInsideTheExpiry() {
        CombatCommit c = new CombatCommit();
        c.step(tick(0, 0, List.of(brute(10, true))));
        Event end = Event.NONE;
        for (long t = 1; t < 400 && end == Event.NONE; t++) {
            end = c.step(tick(t, t * 0.5, List.of()));
        }
        assertEquals(Event.RUN_CLEAR, end);
        assertEquals(Event.NONE, c.step(tick(500, 24, List.of(brute(10, true)))));
        // past the expiry it is a fresh brute again
        assertEquals(Event.RUN_START, c.step(tick(RunZones.EXPIRY_TICKS + 10, 24, List.of(brute(10, true)))));
    }

    @Test
    public void onlyTheNetherMarksABrute() {
        FoeRules.Candidate c = new FoeRules.Candidate("piglin_brute", 3, 5, () -> false, () -> true, () -> true, NEVER, 0, false, false);
        org.junit.Assert.assertTrue(FoeRules.accept(Dimension.NETHER, c).brute());
        org.junit.Assert.assertFalse(FoeRules.accept(Dimension.OVERWORLD, c).brute());
    }
}
