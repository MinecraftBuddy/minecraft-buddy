package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import adris.altoclef.util.helpers.DangerForce.Act;
import adris.altoclef.util.helpers.DangerForce.Choice;
import adris.altoclef.util.helpers.DangerForce.Rule;
import adris.altoclef.util.helpers.DangerForce.Threat;
import adris.altoclef.util.helpers.DangerMeter.Level;
import java.util.List;
import java.util.Set;
import java.util.function.IntPredicate;
import org.junit.Test;

// the forced drills' choice: off does nothing; the arm locks at the meter's first reading not calm and holds until the switch
// goes off: fight takes the meter's top threat (the strongest remembered one alive when the meter counts nobody), flee runs
// from the weighted centre of every threat remembered since the lock that is alive; nobody left, the arm stands
public class DangerForceTest {
    private static Threat th(int id, String kind, double x, double t, double push) {
        return new Threat(id, kind, x, 64, 0, t, push);
    }

    private static final Threat HOGLIN = th(1, "hoglin", 10, 4, 0);
    private static final Threat WITHER = th(2, "wither_skeleton", -6, 9, 0);
    // a calm zombified piglin by the lava: push only
    private static final Threat PIGLIN = th(3, "zombified_piglin", 2, 0, 1.5);
    private static final IntPredicate ALL = id -> true;

    private static IntPredicate only(Integer... ids) {
        Set<Integer> s = Set.of(ids);
        return s::contains;
    }

    @Test
    public void theSwitchReadsAsOneOfThree() {
        assertEquals(DangerForce.OFF, DangerForce.mode(0));
        assertEquals(DangerForce.FIGHT, DangerForce.mode(1));
        assertEquals(DangerForce.FLEE, DangerForce.mode(2));
        assertEquals(DangerForce.OFF, DangerForce.mode(3));
        assertEquals(DangerForce.OFF, DangerForce.mode(-1));
    }

    @Test
    public void offDoesNothingWhateverTheMeterSays() {
        Rule rule = new Rule();
        for (Level level : Level.values()) {
            assertSame(Choice.NONE, rule.step(DangerForce.OFF, level, 0, List.of(HOGLIN, WITHER), ALL));
        }
        assertFalse(rule.locked());
    }

    @Test
    public void nothingBeforeTheMeterLeavesCalm() {
        Rule rule = new Rule();
        assertSame(Choice.NONE, rule.step(DangerForce.FIGHT, Level.CALM, 0, List.of(HOGLIN, WITHER), ALL));
        assertSame(Choice.NONE, rule.step(DangerForce.FLEE, Level.CALM, 5, List.of(HOGLIN, WITHER), ALL));
        assertFalse(rule.locked());
    }

    @Test
    public void fightTakesTheTopThreatOnceNotCalm() {
        for (Level level : new Level[]{Level.FLEE, Level.COVER, Level.FIGHT}) {
            Rule r = new Rule();
            Choice c = r.step(DangerForce.FIGHT, level, 7, List.of(HOGLIN, WITHER, PIGLIN), ALL);
            assertEquals(Act.FIGHT, c.act());
            assertEquals(2, c.target().id());
            assertTrue(r.locked());
            assertEquals(7, r.lockedAt());
        }
    }

    @Test
    public void theFightHoldsWhenTheMeterTurnsCalm() {
        Rule rule = new Rule();
        rule.step(DangerForce.FIGHT, Level.FLEE, 0, List.of(HOGLIN), ALL);
        // calm, the hoglin out of the meter's count: still the hoglin, while it lives
        Choice c = rule.step(DangerForce.FIGHT, Level.CALM, 5, List.of(), ALL);
        assertEquals(Act.FIGHT, c.act());
        assertEquals(1, c.target().id());
        // dead: nobody left, the arm stands (and keeps the wheel)
        assertSame(Choice.STAND, rule.step(DangerForce.FIGHT, Level.CALM, 10, List.of(), only()));
        assertTrue(rule.locked());
    }

    @Test
    public void withNobodyCountedTheStrongestRememberedAliveIsTheTarget() {
        Rule rule = new Rule();
        rule.step(DangerForce.FIGHT, Level.FLEE, 0, List.of(HOGLIN, WITHER), ALL);
        // the wither skeleton (the target) dies; the meter counts nobody: the hoglin
        Choice c = rule.step(DangerForce.FIGHT, Level.FIGHT, 5, List.of(), only(1));
        assertEquals(1, c.target().id());
    }

    @Test
    public void aPushOnlyBodyIsNoTarget() {
        Rule rule = new Rule();
        assertSame(Choice.STAND, rule.step(DangerForce.FIGHT, Level.COVER, 0, List.of(PIGLIN), ALL));
        assertNull(DangerForce.top(List.of(PIGLIN), -1));
    }

    @Test
    public void theTargetIsKeptUntilAnotherAddsHalfAgainAsMuch() {
        Rule rule = new Rule();
        assertEquals(1, rule.step(DangerForce.FIGHT, Level.FIGHT, 0, List.of(HOGLIN), ALL).target().id());
        // a wither skeleton adding 5 against the hoglin's 4: the hoglin stays the target
        Threat wither5 = th(2, "wither_skeleton", -6, 5, 0);
        assertEquals(1, rule.step(DangerForce.FIGHT, Level.FIGHT, 5, List.of(HOGLIN, wither5), ALL).target().id());
        // at 6 (1.5 times 4) it takes over
        Threat wither6 = th(2, "wither_skeleton", -6, 6, 0);
        assertEquals(2, rule.step(DangerForce.FIGHT, Level.FIGHT, 10, List.of(HOGLIN, wither6), ALL).target().id());
        // the target gone from the meter: the next top
        assertEquals(1, rule.step(DangerForce.FIGHT, Level.FIGHT, 15, List.of(HOGLIN), ALL).target().id());
    }

    @Test
    public void theFleeHoldsFromEveryoneRememberedAlive() {
        Rule rule = new Rule();
        Choice c = rule.step(DangerForce.FLEE, Level.FIGHT, 10, List.of(HOGLIN), ALL);
        assertEquals(Act.FLEE, c.act());
        assertNull(c.target());
        // the wither skeleton counted later joins the crowd; calm and counting nobody, the flee goes on from both
        rule.step(DangerForce.FLEE, Level.FLEE, 15, List.of(WITHER), ALL);
        Choice calm = rule.step(DangerForce.FLEE, Level.CALM, 20, List.of(), ALL);
        assertEquals(Act.FLEE, calm.act());
        assertEquals(2, calm.crowd().size());
        // long calm: still fleeing (no going back to work)
        assertEquals(Act.FLEE, rule.step(DangerForce.FLEE, Level.CALM, 2000, List.of(), ALL).act());
        // the hoglin dead: the crowd is the wither skeleton; both dead: the arm stands
        assertEquals(1, rule.step(DangerForce.FLEE, Level.CALM, 2010, List.of(), only(2)).crowd().size());
        assertSame(Choice.STAND, rule.step(DangerForce.FLEE, Level.CALM, 2020, List.of(), only()));
    }

    @Test
    public void fleeIsFromTheWeightedCentre() {
        Rule rule = new Rule();
        // the wither skeleton at -6 adding 9, the hoglin at 10 adding 4, the piglin at 2 pushing 1.5: weights 9, 4, 1.5
        Choice c = rule.step(DangerForce.FLEE, Level.FLEE, 0, List.of(HOGLIN, WITHER, PIGLIN), ALL);
        assertEquals((9 * -6 + 4 * 10 + 1.5 * 2) / 14.5, c.cx(), 1e-9);
        assertEquals(64, c.cy(), 1e-9);
        // nobody adds anything: equal weights
        double[] mid = DangerForce.centre(List.of(th(5, "zombie", 4, 0, 0), th(6, "zombie", 8, 0, 0)));
        assertEquals(6, mid[0], 1e-9);
    }

    @Test
    public void theSwitchTurnedStartsOver() {
        Rule rule = new Rule();
        rule.step(DangerForce.FLEE, Level.FLEE, 0, List.of(HOGLIN), ALL);
        // from flee to fight: unlocked, the fight waits for the meter again; not calm, at once
        assertEquals(Act.FIGHT, rule.step(DangerForce.FIGHT, Level.FLEE, 5, List.of(HOGLIN), ALL).act());
        // off: nothing and unlocked; then flee with the meter calm is nothing
        assertSame(Choice.NONE, rule.step(DangerForce.OFF, Level.FLEE, 10, List.of(HOGLIN), ALL));
        assertFalse(rule.locked());
        assertSame(Choice.NONE, rule.step(DangerForce.FLEE, Level.CALM, 15, List.of(HOGLIN), ALL));
    }
}
