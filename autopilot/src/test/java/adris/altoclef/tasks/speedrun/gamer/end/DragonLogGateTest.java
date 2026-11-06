package adris.altoclef.tasks.speedrun.gamer.end;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.tasks.speedrun.gamer.config.Budgets;
import adris.altoclef.tasks.speedrun.gamer.GamerPhase;
import org.junit.Test;

// the dragon status line rate limit, and the fight budget
public class DragonLogGateTest {

    @Test
    public void firstLogsThenEveryFiveSeconds() {
        DragonLogGate gate = new DragonLogGate();
        assertTrue(gate.shouldLog(100, "circling"));
        assertFalse(gate.shouldLog(102, "circling"));
        assertFalse(gate.shouldLog(104.9, "circling"));
        assertTrue(gate.shouldLog(105, "circling"));
    }

    @Test
    public void aPhaseChangeLogsRightAway() {
        DragonLogGate gate = new DragonLogGate();
        gate.shouldLog(100, "circling");
        assertTrue(gate.shouldLog(101, "landing"));
        assertFalse(gate.shouldLog(102, "landing"));
    }

    @Test
    public void resetLogsAgain() {
        DragonLogGate gate = new DragonLogGate();
        gate.shouldLog(100, "a");
        gate.reset();
        assertTrue(gate.shouldLog(101, "a"));
    }

    @Test
    public void dragonBudgetIsTenMinutes() {
        assertEquals(10, new Budgets().minutes(GamerPhase.DRAGON), 0);
    }
}
