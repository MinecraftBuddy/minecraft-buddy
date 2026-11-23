package adris.altoclef.util.helpers;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// (camp_stall) a walk to the blaze camp that comes no closer gives its spawner up for the run (look 235)
public class CampStallRulesTest {

    private static final long STALL = Math.round(CampStallRules.STALL_S * 20);

    @Test
    public void aWalkStalledFarFromTheCampGivesTheSpawnerUp() {
        assertTrue(CampStallRules.giveUp(true, STALL, 75.0));
        assertTrue(CampStallRules.giveUp(true, STALL * 10, CampStallRules.NEAR + 0.5));
    }

    @Test
    public void notBeforeTheClockRunsOut() {
        assertFalse(CampStallRules.giveUp(true, STALL - 1, 75.0));
        assertFalse("a walk just begun", CampStallRules.giveUp(true, 0, 75.0));
    }

    @Test
    public void notByTheCampNorWithNoDistanceNorWithTheSwitchOff() {
        assertFalse("by the camp: the near walk and the fight go on", CampStallRules.giveUp(true, STALL * 10, CampStallRules.NEAR));
        assertFalse("no distance read yet", CampStallRules.giveUp(true, STALL * 10, null));
        assertFalse("the switch off", CampStallRules.giveUp(false, STALL * 10, 75.0));
    }

    @Test
    public void aNearStallBansForTheRunAFarOneOnlyTheSecondTime() {
        assertTrue("look 235: 76 blocks off", CampStallRules.forRun(76, false));
        assertTrue(CampStallRules.forRun(CampStallRules.RUN_RANGE, false));
        assertFalse("heel-234 r4: 183 blocks off, the first time", CampStallRules.forRun(183, false));
        assertTrue("the same spawner dropped so before", CampStallRules.forRun(183, true));
    }
}
