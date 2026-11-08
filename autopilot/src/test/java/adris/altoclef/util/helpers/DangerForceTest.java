package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import adris.altoclef.util.helpers.DangerForce.Act;
import adris.altoclef.util.helpers.DangerForce.Choice;
import adris.altoclef.util.helpers.DangerForce.Rule;
import adris.altoclef.util.helpers.DangerForce.Threat;
import adris.altoclef.util.helpers.DangerMeter.Level;
import java.util.List;
import org.junit.Test;

// the forced drills' choice: off does nothing, fight takes the meter's top threat while it is not calm, flee runs from the
// weighted crowd until the meter has been calm 2 s
public class DangerForceTest {
    private static Threat th(int id, String kind, double x, double t, double push) {
        return new Threat(id, kind, x, 64, 0, t, push);
    }

    private static final Threat HOGLIN = th(1, "hoglin", 10, 4, 0);
    private static final Threat WITHER = th(2, "wither_skeleton", -6, 9, 0);
    // a calm zombified piglin by the lava: push only
    private static final Threat PIGLIN = th(3, "zombified_piglin", 2, 0, 1.5);

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
            assertSame(Choice.NONE, rule.step(DangerForce.OFF, level, 0, List.of(HOGLIN, WITHER)));
        }
    }

    @Test
    public void fightTakesTheTopThreatWhileTheMeterIsNotCalm() {
        Rule rule = new Rule();
        // calm: nothing, however close the hoglin
        assertSame(Choice.NONE, rule.step(DangerForce.FIGHT, Level.CALM, 0, List.of(HOGLIN, WITHER)));
        // flee, cover or fight on the meter: the fight is forced, on the mob adding the most to T
        for (Level level : new Level[]{Level.FLEE, Level.COVER, Level.FIGHT}) {
            Rule r = new Rule();
            Choice c = r.step(DangerForce.FIGHT, level, 0, List.of(HOGLIN, WITHER, PIGLIN));
            assertEquals(Act.FIGHT, c.act());
            assertEquals(2, c.target().id());
        }
        // calm again: back to the order
        rule.step(DangerForce.FIGHT, Level.FLEE, 0, List.of(HOGLIN));
        assertSame(Choice.NONE, rule.step(DangerForce.FIGHT, Level.CALM, 5, List.of(HOGLIN)));
    }

    @Test
    public void aPushOnlyBodyIsNoTarget() {
        Rule rule = new Rule();
        assertSame(Choice.NONE, rule.step(DangerForce.FIGHT, Level.COVER, 0, List.of(PIGLIN)));
        assertNull(DangerForce.top(List.of(PIGLIN), -1));
    }

    @Test
    public void theTargetIsKeptUntilAnotherAddsHalfAgainAsMuch() {
        Rule rule = new Rule();
        assertEquals(1, rule.step(DangerForce.FIGHT, Level.FIGHT, 0, List.of(HOGLIN)).target().id());
        // a wither skeleton adding 5 against the hoglin's 4: the hoglin stays the target
        Threat wither5 = th(2, "wither_skeleton", -6, 5, 0);
        assertEquals(1, rule.step(DangerForce.FIGHT, Level.FIGHT, 5, List.of(HOGLIN, wither5)).target().id());
        // at 6 (1.5 times 4) it takes over
        Threat wither6 = th(2, "wither_skeleton", -6, 6, 0);
        assertEquals(2, rule.step(DangerForce.FIGHT, Level.FIGHT, 10, List.of(HOGLIN, wither6)).target().id());
        // the target gone from the meter: the next top
        assertEquals(1, rule.step(DangerForce.FIGHT, Level.FIGHT, 15, List.of(HOGLIN)).target().id());
    }

    @Test
    public void fleeRunsUntilTheMeterHasBeenCalmTwoSeconds() {
        Rule rule = new Rule();
        assertSame(Choice.NONE, rule.step(DangerForce.FLEE, Level.CALM, 0, List.of(HOGLIN)));
        Choice c = rule.step(DangerForce.FLEE, Level.FIGHT, 10, List.of(HOGLIN, WITHER));
        assertEquals(Act.FLEE, c.act());
        assertNull(c.target());
        assertEquals(2, c.crowd().size());
        // the meter calm, the mobs out of its count: the flee goes on from the last crowd
        assertEquals(Act.FLEE, rule.step(DangerForce.FLEE, Level.CALM, 20, List.of()).act());
        assertEquals(2, rule.step(DangerForce.FLEE, Level.CALM, 40, List.of()).crowd().size());
        assertEquals(Act.FLEE, rule.step(DangerForce.FLEE, Level.CALM, 59, List.of()).act());
        // 40 ticks calm: back to the order
        assertSame(Choice.NONE, rule.step(DangerForce.FLEE, Level.CALM, 60, List.of()));
        assertSame(Choice.NONE, rule.step(DangerForce.FLEE, Level.CALM, 80, List.of()));
    }

    @Test
    public void aMeterNotCalmAgainStartsTheTwoSecondsOver() {
        Rule rule = new Rule();
        rule.step(DangerForce.FLEE, Level.FLEE, 0, List.of(HOGLIN));
        rule.step(DangerForce.FLEE, Level.CALM, 20, List.of());
        // cover again at 45, calm at 50: the calm counts from 50
        assertEquals(Act.FLEE, rule.step(DangerForce.FLEE, Level.COVER, 45, List.of(HOGLIN)).act());
        assertEquals(Act.FLEE, rule.step(DangerForce.FLEE, Level.CALM, 50, List.of()).act());
        assertEquals(Act.FLEE, rule.step(DangerForce.FLEE, Level.CALM, 89, List.of()).act());
        assertSame(Choice.NONE, rule.step(DangerForce.FLEE, Level.CALM, 90, List.of()));
    }

    @Test
    public void fleeIsFromTheWeightedCentre() {
        Rule rule = new Rule();
        // the wither skeleton at -6 adding 9, the hoglin at 10 adding 4, the piglin at 2 pushing 1.5: weights 9, 4, 1.5
        Choice c = rule.step(DangerForce.FLEE, Level.FLEE, 0, List.of(HOGLIN, WITHER, PIGLIN));
        assertEquals((9 * -6 + 4 * 10 + 1.5 * 2) / 14.5, c.cx(), 1e-9);
        assertEquals(64, c.cy(), 1e-9);
        // nobody adds anything: equal weights
        double[] mid = DangerForce.centre(List.of(th(5, "zombie", 4, 0, 0), th(6, "zombie", 8, 0, 0)));
        assertEquals(6, mid[0], 1e-9);
    }

    @Test
    public void theSwitchTurnedMidFightStartsOver() {
        Rule rule = new Rule();
        rule.step(DangerForce.FLEE, Level.FLEE, 0, List.of(HOGLIN));
        // from flee to fight: the fight at once, no flee left over
        assertEquals(Act.FIGHT, rule.step(DangerForce.FIGHT, Level.FLEE, 5, List.of(HOGLIN)).act());
        // and off: nothing, then flee from scratch (calm first is nothing)
        assertSame(Choice.NONE, rule.step(DangerForce.OFF, Level.FLEE, 10, List.of(HOGLIN)));
        assertSame(Choice.NONE, rule.step(DangerForce.FLEE, Level.CALM, 15, List.of(HOGLIN)));
    }
}
