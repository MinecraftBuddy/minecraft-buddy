package adris.altoclef.tasks.speedrun.gamer.tasks;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PearlHuntAwayTest {
    private static PearlHuntTask.AwayCount fresh() {
        return PearlHuntTask.AwayCount.NONE;
    }

    @Test
    public void aFlipFlopStillReachesTheGiveUp() {
        PearlHuntTask.AwayCount c = fresh();
        // lava in reach every other tick, never calm for long
        for (int t = 0; t < 600; t++) {
            c = c.step(t, t % 2 == 0);
        }
        assertTrue(c.ticks() > 300);
    }

    @Test
    public void aSparseFlipReachesTheGiveUpOnWallTime() {
        PearlHuntTask.AwayCount c = fresh();
        // 4 flee ticks every 20: counting only the flee ticks would take 1500 ticks to reach 300
        int giveUpAt = -1;
        for (int t = 0; t < 600 && giveUpAt < 0; t++) {
            c = c.step(t, t % 20 < 4);
            if (c.ticks() > 300) {
                giveUpAt = t;
            }
        }
        assertTrue(giveUpAt > 0 && giveUpAt < 400);
    }

    @Test
    public void fiveCalmSecondsStartTheCountOver() {
        PearlHuntTask.AwayCount c = fresh();
        for (int t = 0; t < 50; t++) {
            c = c.step(t, true);
        }
        // last flee at 49
        assertEquals(50, c.step(148, false).ticks());
        assertEquals(0, c.step(149, false).ticks());
        // and the next flee after that is a new streak
        assertEquals(1, c.step(149, false).step(150, true).ticks());
    }

    @Test
    public void aGapOfAHundredBetweenFleesIsANewStreak() {
        PearlHuntTask.AwayCount c = fresh().step(0, true).step(1, true);
        assertEquals(2, c.ticks());
        // no calm step in between, the flee itself sees the gap
        assertEquals(1, c.step(101, true).ticks());
        assertEquals(101, c.step(100, true).ticks());
        assertEquals(100, c.step(99, true).ticks());
    }

    @Test
    public void ticksSpentOnOtherBranchesCountAsCalm() {
        PearlHuntTask.AwayCount c = fresh();
        for (int t = 0; t < 299; t++) {
            c = c.step(t, true);
        }
        // a fight owned the next 400 ticks and only the first call of the tick after it looks at the clock
        c = c.step(700, false);
        assertEquals(0, c.ticks());
        assertEquals(1, c.step(701, true).ticks());
    }

    @Test
    public void backsOffOnlyWhenThereIsSomethingToLoot() {
        assertTrue(PearlHuntTask.backsOffFrom(true, true));
        assertFalse(PearlHuntTask.backsOffFrom(true, false));
        assertFalse(PearlHuntTask.backsOffFrom(false, true));
    }
}
