package adris.altoclef.tasks.speedrun.gamer.end;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.tasks.speedrun.gamer.end.PillarBed.Step;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

public class PillarBedTest {
    private static final BlockPos TOP = new BlockPos(0, 67, 0);

    @Test
    public void theStandIsTuckedWestOfThePillarOneBelowItsTop() {
        assertEquals(new BlockPos(-1, 66, 0), PillarBed.stand(TOP));
        assertEquals(new BlockPos(0, 68, 0), PillarBed.bed(TOP));
    }

    @Test
    public void atTheStandIsFlatAndFeet() {
        BlockPos stand = PillarBed.stand(TOP);
        assertTrue(PillarBed.atStand(stand, -0.5, 0.5, 66));
        assertTrue(PillarBed.atStand(stand, -0.2, 0.6, 66));
        // a jump is still at the stand
        assertTrue(PillarBed.atStand(stand, -0.5, 0.5, 67));
        assertFalse(PillarBed.atStand(stand, 0.1, 0.5, 66));
        // the well floor right under it passes the flat check, but it isn't where we walk to
        assertFalse(PillarBed.atStand(stand, -0.5, 0.5, 65));
    }

    @Test
    public void sittingOrLowIsSat() {
        assertTrue(PillarBed.sat(true, 90, 67));
        assertTrue(PillarBed.sat(false, 68.9, 67));
        assertFalse(PillarBed.sat(false, 69, 67));
    }

    @Test
    public void flyingGoesToTheStandAndWaitsThere() {
        assertEquals(Step.STAND, PillarBed.step(false, false, false, false));
        assertEquals(Step.WAIT, PillarBed.step(false, false, true, false));
        // a bed left over from the last landing waits too, no clicks in the air
        assertEquals(Step.WAIT, PillarBed.step(false, false, true, true));
    }

    @Test
    public void landingPlacesThenClicksAndStillWalksIfItHasTo() {
        assertEquals(Step.STAND, PillarBed.step(false, true, false, false));
        assertEquals(Step.PLACE, PillarBed.step(false, true, true, false));
        assertEquals(Step.CLICK, PillarBed.step(false, true, true, true));
    }

    @Test
    public void satIsTheRingWhateverElseIsTrue() {
        assertEquals(Step.RING, PillarBed.step(true, false, true, true));
        assertEquals(Step.RING, PillarBed.step(true, true, false, false));
    }

    @Test
    public void placesOnlyFacingEastEveryOtherTick() {
        assertTrue(PillarBed.placeNow(Direction.EAST, 100));
        assertFalse(PillarBed.placeNow(Direction.EAST, 101));
        assertFalse(PillarBed.placeNow(Direction.NORTH, 100));
        assertFalse(PillarBed.placeNow(Direction.WEST, 100));
    }

    @Test
    public void onlyAnEastFootIsTheRightWay() {
        assertTrue(PillarBed.rightWay(Direction.EAST, true));
        for (Direction other : Direction.values()) {
            if (other != Direction.EAST) {
                assertFalse(other.name(), PillarBed.rightWay(other, true));
            }
        }
        // the head half on the pillar means the foot is hanging off it, whichever way that reads
        assertFalse(PillarBed.rightWay(Direction.EAST, false));
    }

    @Test
    public void clicksAreSpacedPastTheInvulnerableWindow() {
        assertTrue(PillarBed.clickNow(true, 5, false, 100, 89));
        assertFalse(PillarBed.clickNow(true, 5, false, 100, 90));
        assertTrue(PillarBed.clickNow(true, 5, false, 100, Long.MIN_VALUE / 2));
    }

    @Test
    public void clicksWantTheHeadCloseAndCover() {
        assertTrue(PillarBed.clickNow(true, 5.29, false, 100, 0));
        assertFalse(PillarBed.clickNow(true, 5.3, false, 100, 0));
        assertFalse(PillarBed.clickNow(false, 5, false, 100, 0));
    }

    @Test
    public void finishingForgetsTheHeadButNotCoverOrSpacing() {
        assertTrue(PillarBed.clickNow(true, 20, true, 100, 0));
        assertFalse(PillarBed.clickNow(false, 20, true, 100, 0));
        assertFalse(PillarBed.clickNow(true, 20, true, 100, 90));
    }

    @Test
    public void finishWantsTheBedsToBeEnoughAndTheBodyClose() {
        assertTrue(PillarBed.finish(28, 2, 4));
        assertTrue(PillarBed.finish(14, 1, 1));
        assertFalse(PillarBed.finish(28.5, 2, 3));
        assertFalse(PillarBed.finish(20, 2, 4.1));
        assertFalse(PillarBed.finish(10, 0, 1));
    }

    @Test
    public void theBlastIsTheMiddleOfTheHeadHalf() {
        assertEquals(new Vec3(1.5, 68.5, 0.5), PillarBed.blast(TOP));
    }

    // a standing player's box with its feet here
    private static double seenAt(double x, double y, double z) {
        return PillarBed.seen(TOP, x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3);
    }

    private static double gapAt(double x, double y, double z) {
        return PillarBed.reachGap(TOP, x, y + 1.62, z);
    }

    @Test
    public void theStandSeesNothingOfTheBlast() {
        assertEquals(0, seenAt(-0.5, 66, 0.5), 1e-9);
        // off centre on the same block is still behind it
        assertEquals(0, seenAt(-0.3, 66, 0.9), 1e-9);
    }

    @Test
    public void theFloorBesideThePillarIsBehindItToo() {
        assertEquals(0, seenAt(-1.5, 64, 0.5), 1e-9);
        assertEquals(0, seenAt(-0.5, 64, 0.5), 1e-9);
    }

    @Test
    public void outInTheOpenSeesAllOfIt() {
        // the east side has nothing between us and it
        assertEquals(1, seenAt(4.5, 66, 0.5), 1e-9);
        // west but over the pillar's top the rays never touch it
        assertEquals(1, seenAt(-3.5, 69, 0.5), 1e-9);
    }

    @Test
    public void aBlockAboveTheStandThePillarOnlyCoversOurLegs() {
        double partly = seenAt(-0.5, 67, 0.5);
        assertTrue(partly > PillarBed.SEEN_LIMIT);
        assertTrue(partly < 1);
        // whole rays out of the 45 the real grid shoots
        assertEquals(0, partly * 45 - Math.rint(partly * 45), 1e-9);
    }

    @Test
    public void theBedIsInReachFromTheStandAndTheFloor() {
        assertTrue(gapAt(-0.5, 66, 0.5) <= PillarBed.BED_REACH);
        assertTrue(gapAt(-1.5, 64, 0.5) <= PillarBed.BED_REACH);
        assertEquals(Math.hypot(1.5, 68 - 65.62), gapAt(-1.5, 64, 0.5), 1e-9);
        assertTrue(gapAt(-6.5, 64, 0.5) > PillarBed.BED_REACH);
    }

    @Test
    public void eastHasToBeWhatTheLookReadsAs() {
        assertTrue(PillarBed.looksEast(TOP, -0.5, 0.5));
        assertTrue(PillarBed.looksEast(TOP, -1.5, 1.5));
        assertFalse(PillarBed.looksEast(TOP, -1.5, 3.5));
        assertFalse(PillarBed.looksEast(TOP, 2.5, 0.5));
        assertFalse(PillarBed.looksEast(TOP, 0.5, 0.5));
    }

    @Test
    public void coverNeedsAllThree() {
        assertTrue(PillarBed.covered(true, 2.8, 0));
        assertTrue(PillarBed.covered(true, PillarBed.BED_REACH, PillarBed.SEEN_LIMIT));
        assertFalse(PillarBed.covered(false, 2.8, 0));
        assertFalse(PillarBed.covered(true, 4.6, 0));
        assertFalse(PillarBed.covered(true, 2.8, PillarBed.SEEN_LIMIT + 0.01));
    }

    @Test
    public void onlyANewBedCaresWhichWayWeLook() {
        assertTrue(PillarBed.fightHere(true, true, false));
        assertTrue(PillarBed.fightHere(true, false, true));
        assertFalse(PillarBed.fightHere(true, false, false));
        assertFalse(PillarBed.fightHere(false, true, true));
    }

    @Test
    public void shovedSidewaysABedThatsDownStillGetsClicked() {
        // behind the pillar and in reach, but too far round for a look at the top to read as east
        double x = -0.5;
        double y = 64;
        double z = 1.5;
        assertFalse(PillarBed.looksEast(TOP, x, z));
        boolean covered = PillarBed.covered(true, gapAt(x, y, z), seenAt(x, y, z));
        assertTrue(covered);
        assertEquals(Step.CLICK, PillarBed.step(false, true, PillarBed.fightHere(covered, true, false), true));
        // with nothing down we can't place from there, that's the walk back
        assertEquals(Step.STAND, PillarBed.step(false, true, PillarBed.fightHere(covered, false, false), false));
    }

    @Test
    public void coverPicksUpWhereTheStandLeftOff() {
        // shoved down beside the pillar: not the stand, still a place to fight from
        assertFalse(PillarBed.atStand(PillarBed.stand(TOP), -1.5, 0.5, 64));
        assertTrue(PillarBed.covered(true, gapAt(-1.5, 64, 0.5), seenAt(-1.5, 64, 0.5)));
        assertTrue(PillarBed.looksEast(TOP, -1.5, 0.5));
        // out east it's not, that's the walk back
        assertFalse(PillarBed.covered(true, gapAt(3.5, 64, 0.5), seenAt(3.5, 64, 0.5)));
    }
}
