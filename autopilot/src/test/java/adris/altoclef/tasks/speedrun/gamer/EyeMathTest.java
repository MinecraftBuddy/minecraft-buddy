package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EyeMathTest {
    private final GamerConfig cfg = new GamerConfig();

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    private static FakeFacts bag(int rods, int powder, int pearls, int eyes) {
        FakeFacts f = new FakeFacts();
        f.give(Items.BLAZE_ROD, rods).give(Items.BLAZE_POWDER, powder).give(Items.ENDER_PEARL, pearls).give(Items.ENDER_EYE, eyes);
        return f;
    }

    @Test
    public void rodsAreTwoPowderEach() {
        assertEquals(7, EyeMath.rodsNeeded(14, 0, 0));
        assertEquals(6, EyeMath.rodsNeeded(12, 0, 0));
        // odd numbers round up: 13 powder is 7 rods
        assertEquals(7, EyeMath.rodsNeeded(13, 0, 0));
    }

    @Test
    public void eyesAndPowderCountTowardTheRods() {
        assertEquals(5, EyeMath.rodsNeeded(14, 0, 4));
        assertEquals(3, EyeMath.rodsNeeded(14, 4, 4));
        assertEquals(0, EyeMath.rodsNeeded(14, 8, 6));
        assertEquals(0, EyeMath.rodsNeeded(14, 14, 0));
        assertEquals(0, EyeMath.rodsNeeded(14, 20, 0));
    }

    @Test
    public void pearlsAreOnePerEye() {
        assertEquals(14, EyeMath.pearlsNeeded(14, 0));
        assertEquals(9, EyeMath.pearlsNeeded(14, 5));
        assertEquals(0, EyeMath.pearlsNeeded(14, 14));
        assertEquals(0, EyeMath.pearlsNeeded(14, 99));
    }

    @Test
    public void craftableIsTheShortOfPearlsAndPowder() {
        assertEquals(4, EyeMath.craftable(4, 10, 0));
        assertEquals(6, EyeMath.craftable(9, 2, 2));
        assertEquals(0, EyeMath.craftable(0, 5, 5));
        assertEquals(0, EyeMath.craftable(5, 0, 0));
    }

    @Test
    public void leavesWithTheFullTarget() {
        assertTrue(EyeMath.canLeaveNether(bag(8, 0, 16, 0), cfg, 0, false));
        assertFalse(EyeMath.canLeaveNether(bag(7, 0, 16, 0), cfg, 0, false));
        assertFalse(EyeMath.canLeaveNether(bag(8, 0, 15, 0), cfg, 0, false));
    }

    @Test
    public void powderAndEyesStandInForRods() {
        // 4 eyes, 6 powder: 10 of 16 covered, 3 more rods; 12 pearls finish it
        assertTrue(EyeMath.canLeaveNether(bag(3, 6, 12, 4), cfg, 0, false));
        assertFalse(EyeMath.canLeaveNether(bag(2, 6, 12, 4), cfg, 0, false));
        assertTrue(EyeMath.canLeaveNether(bag(0, 0, 0, 16), cfg, 0, false));
    }

    @Test
    public void budgetOverAcceptsTheFloor() {
        FakeFacts floor = bag(7, 0, 13, 0);
        assertFalse(EyeMath.canLeaveNether(floor, cfg, 0, false));
        assertTrue(EyeMath.canLeaveNether(floor, cfg, 0, true));
        // below the floor the budget does not help
        assertFalse(EyeMath.canLeaveNether(bag(7, 0, 12, 0), cfg, 0, true));
        assertFalse(EyeMath.canLeaveNether(bag(6, 0, 13, 0), cfg, 0, true));
        assertEquals(13, cfg.floorEyes);
    }

    @Test
    public void floorCountsPearlsPlusEyes() {
        // 3 eyes made already, 10 pearls, 5 rods = 10 powder: eyes 3 + 10 pearls = 13, powder 3 + 10 = 13
        assertTrue(EyeMath.canLeaveNether(bag(5, 0, 10, 3), cfg, 0, true));
        assertFalse(EyeMath.canLeaveNether(bag(5, 0, 9, 3), cfg, 0, true));
    }

    @Test
    public void eyesPhaseExit() {
        assertTrue(EyeMath.eyesDone(bag(0, 0, 0, 16), cfg, 0));
        assertFalse(EyeMath.eyesDone(bag(8, 0, 16, 0), cfg, 0));
        // at the floor with something left to craft: keep crafting up to the target
        assertFalse(EyeMath.eyesDone(bag(1, 0, 2, 13), cfg, 0));
        // at the floor with nothing left to craft from: done
        assertTrue(EyeMath.eyesDone(bag(0, 0, 3, 13), cfg, 0));
        assertTrue(EyeMath.eyesDone(bag(2, 0, 0, 13), cfg, 0));
        // under the floor, even with nothing to craft, it is not done
        assertFalse(EyeMath.eyesDone(bag(0, 0, 0, 12), cfg, 0));
    }

    @Test
    public void goalsShrinkWithTheFramesAlreadyFilled() {
        assertEquals(16, EyeMath.targetGoal(cfg, 0));
        // the floor keeps its one spare on the first trip (floorEyes 13), it would be capped to 12 without that
        assertEquals(13, EyeMath.floorGoal(cfg, 0));
        // 5 of 12 in: 7 empty, the spare (target - 12 = 4, floor - 12 = 1) rides on top
        assertEquals(11, EyeMath.targetGoal(cfg, 5));
        assertEquals(8, EyeMath.floorGoal(cfg, 5));
        // never negative, never above the configured numbers
        assertEquals(4, EyeMath.targetGoal(cfg, 12));
        assertEquals(0, EyeMath.floorGoal(cfg, 12));
        assertEquals(0, EyeMath.floorGoal(cfg, 99));
    }

    @Test
    public void elevenEyesLeaveTheNetherAfterAFiveFrameOpen() {
        // the audit scenario: OPEN ran out at 5/12, 7 more are enough (11 with the spare)
        assertTrue(EyeMath.canLeaveNether(bag(6, 0, 11, 0), cfg, 5, false));
        assertFalse(EyeMath.canLeaveNether(bag(6, 0, 11, 0), cfg, 0, false));
        // 8 is the floor for that state: only once the budget is nearly over
        FakeFacts eight = bag(4, 0, 8, 0);
        assertFalse(EyeMath.canLeaveNether(eight, cfg, 5, false));
        assertTrue(EyeMath.canLeaveNether(eight, cfg, 5, true));
        assertFalse(EyeMath.canLeaveNether(bag(3, 0, 8, 0), cfg, 5, true));
    }

    @Test
    public void eyesPhaseUsesTheSameGoals() {
        assertTrue(EyeMath.eyesDone(bag(0, 0, 0, 11), cfg, 5));
        assertFalse(EyeMath.eyesDone(bag(0, 0, 0, 9), cfg, 0));
        assertTrue(EyeMath.eyesDone(bag(0, 0, 0, 8), cfg, 5));
        assertFalse(EyeMath.eyesDone(bag(0, 0, 0, 7), cfg, 5));
        // floor with something left to craft keeps crafting
        assertFalse(EyeMath.eyesDone(bag(1, 0, 2, 8), cfg, 5));
    }

    @Test
    public void goalsAgreeWithTheOpenStarvedRule() {
        // OPEN calls it starved below (12 - filled) eyes, and the nether floor leaves with those plus the one spare
        for (int filled = 0; filled < 12; filled++) {
            int need = 12 - filled;
            assertEquals(need + 1, EyeMath.floorGoal(cfg, filled));
            assertTrue(EyeMath.canLeaveNether(bag(0, 0, 0, need + 1), cfg, filled, true));
            assertFalse(EyeMath.canLeaveNether(bag(0, 0, 0, need), cfg, filled, true));
        }
    }

    private static RunState revisit(int filled, boolean read) {
        RunState s = new RunState();
        s.framesFilled = filled;
        s.netherRevisits = 1;
        s.openRead = read;
        return s;
    }

    @Test
    public void aRevisitAfterARealReadWantsExactlyTheEmptyFrames() {
        // 5 of 12 in and OPEN really read it: 7 empty, no spare on the target, no spare on the floor
        RunState s = revisit(5, true);
        assertEquals(7, EyeMath.targetGoal(cfg, s));
        assertEquals(7, EyeMath.floorGoal(cfg, s));
        assertTrue(EyeMath.canLeaveNether(bag(4, 0, 7, 0), cfg, s, false));
        assertFalse(EyeMath.canLeaveNether(bag(4, 0, 6, 0), cfg, s, false));
        // held eyes count toward it
        assertTrue(EyeMath.canLeaveNether(bag(2, 0, 4, 3), cfg, s, false));
        assertTrue(EyeMath.eyesDone(bag(0, 0, 0, 7), cfg, s));
        assertFalse(EyeMath.eyesDone(bag(0, 0, 0, 6), cfg, s));
    }

    @Test
    public void aRevisitThatNeverReadTheRingKeepsTheSpare() {
        // LOCATE/ROOM sent us back (hopeless): framesFilled is the unread zero, no exact goal
        RunState noRead = revisit(0, false);
        assertEquals(16, EyeMath.targetGoal(cfg, noRead));
        assertEquals(13, EyeMath.floorGoal(cfg, noRead));
        // a read ring on the first trip is not a revisit yet
        RunState first = revisit(5, true);
        first.netherRevisits = 0;
        assertEquals(11, EyeMath.targetGoal(cfg, first));
        assertEquals(8, EyeMath.floorGoal(cfg, first));
    }

    @Test
    public void rodGoalCarriesTheSpareUntilTheRingIsKnown() {
        RunState first = new RunState();
        assertEquals(18, EyeMath.rodGoal(cfg, first));
        assertEquals(9, EyeMath.rodsNeeded(EyeMath.rodGoal(cfg, first), 0, 0));
        // a return trip knows the ring: plain goal, no spare
        RunState trip = revisit(5, true);
        assertEquals(7, EyeMath.rodGoal(cfg, trip));
        cfg.spareRods = 0;
        assertEquals(16, EyeMath.rodGoal(cfg, first));
    }

    @Test
    public void exitWaitsForTheSpareRodButTheFloorStaysThirteen() {
        RunState first = new RunState();
        assertFalse(EyeMath.canLeaveNether(bag(8, 0, 16, 0), cfg, first, false));
        assertTrue(EyeMath.canLeaveNether(bag(9, 0, 16, 0), cfg, first, false));
        assertTrue(EyeMath.canLeaveNether(bag(7, 0, 13, 0), cfg, first, true));
    }

    @Test
    public void potentialEyes() {
        assertEquals(10, EyeMath.potentialEyes(bag(2, 1, 8, 5)));
        assertEquals(5, EyeMath.potentialEyes(bag(0, 0, 8, 5)));
    }
}
