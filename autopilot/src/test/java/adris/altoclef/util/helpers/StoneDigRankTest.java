package adris.altoclef.util.helpers;

import net.minecraft.world.level.block.Blocks;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.OptionalInt;
import java.util.function.IntPredicate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StoneDigRankTest {
    // we stand at the middle of cell (0, 64, 0)
    private static final double X = 0.5, Y = 64, Z = 0.5;

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    private static double at(int x, int y, int z, boolean exposed) {
        return StoneDigRank.score(X, Y, Z, x, y, z, exposed);
    }

    @Test
    public void aWallAtOurLevelBeatsTheFloorWeStandOn() {
        // the floor is right there, 1 block down, and plain distance loved it
        assertTrue(at(3, 64, 0, true) < at(0, 63, 0, true));
        assertTrue(at(8, 64, 0, true) < at(0, 63, 0, true));
    }

    @Test
    public void feetLevelAndTheCellAboveCostTheSame() {
        assertEquals(at(2, 64, 0, true), at(2, 65, 0, true), 1e-9);
        // two up is a climb, 4 a block from there
        assertEquals(at(2, 65, 0, true) + 4, at(2, 66, 0, true), 1e-9);
    }

    @Test
    public void everythingBelowTheFeetIsPricedAsAHole() {
        double flat = at(2, 64, 0, true);
        assertEquals(flat + 10, at(2, 63, 0, true), 1e-9);
        assertEquals(flat + 20, at(2, 62, 0, true), 1e-9);
    }

    @Test
    public void theStoneWeStandOnIsTheLastResort() {
        // nothing else within 16 and the floor is the pick, that is the only time we dig down
        double standing = at(0, 63, 0, true);
        assertTrue(standing > at(16, 64, 0, true));
        assertTrue(standing < at(40, 64, 0, true));
        // the whole column under us, not just the top
        assertTrue(at(0, 62, 0, true) > standing);
        // a floor block next to us is not the column
        assertTrue(at(1, 63, 0, true) < standing);
    }

    @Test
    public void exposedStoneBeatsBuriedStoneAtTheSameSpot() {
        assertEquals(at(3, 64, 1, true) + StoneDigRank.BURIED, at(3, 64, 1, false), 1e-9);
        // and a short walk to a face beats a buried block next to us
        assertTrue(at(5, 64, 0, true) < at(1, 64, 0, false));
    }

    @Test
    public void aHillsideFiveBlocksOffBeatsAShaftIntoTheDirt() {
        // stone under a layer of grass is a buried block two down
        assertTrue(at(6, 65, 0, true) < at(0, 62, 0, false));
        assertTrue(at(6, 65, 0, true) < at(1, 62, 0, false));
    }

    @Test
    public void standingOnABlockTopWithFloatNoiseStillCountsAsThatLevel() {
        // 63.9999 is standing on top of y 63, our feet are in 64
        assertEquals(StoneDigRank.score(X, 64, Z, 2, 64, 0, true), StoneDigRank.score(X, 63.9999, Z, 2, 64, 0, true), 1e-9);
    }

    @Test
    public void stoneUnderAStackOfGravelLosesToPlainStoneNextToIt() {
        double plain = StoneDigRank.score(X, Y, Z, 4, 64, 0, true, 0);
        // two gravel on it: dearer than a few blocks of extra walking, so the bare stone one over wins
        double stacked = StoneDigRank.score(X, Y, Z, 3, 64, 0, true, 2);
        assertEquals(StoneDigRank.score(X, Y, Z, 3, 64, 0, true) + 2 * StoneDigRank.FALLING_EXTRA, stacked, 1e-9);
        assertTrue(plain < stacked);
        // it is still a candidate when it is all there is, just a pricey one
        assertTrue(stacked < at(40, 64, 0, true));
    }

    @Test
    public void aTallStackIsCappedSoItIsNeverTheEndOfTheWorld() {
        assertEquals(StoneDigRank.score(X, Y, Z, 3, 64, 0, true, StoneDigRank.FALLING_CAP),
                StoneDigRank.score(X, Y, Z, 3, 64, 0, true, 16), 1e-9);
        // and the old way of asking is the same as asking about nothing on top
        assertEquals(at(3, 64, 0, true), StoneDigRank.score(X, Y, Z, 3, 64, 0, true, 0), 1e-9);
    }

    // the tunnel's floor in these: feet at 64, stone line two blocks into it, so floor 52 and the band is 52-53
    private static final int FLOOR = 52;

    private static double tunnel(double fy, int x, int y, int z, boolean exposed) {
        return StoneDigRank.tunnelScore(X, fy, Z, x, y, z, exposed, 0, FLOOR);
    }

    @Test
    public void inTheBandThereIsNoUpOrDown() {
        // walking the tunnel: feet on the floor, the face ahead at feet and head height costs the walk and nothing else
        assertEquals(tunnel(FLOOR, 2, FLOOR, 0, true), tunnel(FLOOR, 2, FLOOR + 1, 0, true), 1e-9);
        assertEquals(StoneDigRank.score(X, FLOOR, Z, 2, FLOOR, 0, true), tunnel(FLOOR, 2, FLOOR, 0, true), 1e-9);
        // from up on the surface the band is just as free, that is the point of latching
        assertEquals(tunnel(FLOOR, 2, FLOOR, 0, true), tunnel(64, 2, FLOOR, 0, true), 1e-9);
    }

    @Test
    public void aboveTheBandIsAClimb() {
        double band = tunnel(FLOOR, 2, FLOOR + 1, 0, true);
        assertEquals(band + 4, tunnel(FLOOR, 2, FLOOR + 2, 0, true), 1e-9);
        assertEquals(band + 12, tunnel(FLOOR, 2, FLOOR + 4, 0, true), 1e-9);
    }

    @Test
    public void underTheFloorIsPricedOutOfIt() {
        double band = tunnel(FLOOR, 2, FLOOR, 0, true);
        assertEquals(band + StoneDigRank.BELOW_FLOOR, tunnel(FLOOR, 2, FLOOR - 1, 0, true), 1e-9);
        assertEquals(band + 2 * StoneDigRank.BELOW_FLOOR, tunnel(FLOOR, 2, FLOOR - 2, 0, true), 1e-9);
        // a buried band block four over still beats an exposed one a block under the floor next to us
        assertTrue(tunnel(FLOOR, 4, FLOOR, 0, false) < tunnel(FLOOR, 1, FLOOR - 1, 0, true));
    }

    @Test
    public void theWayDownIsOneColumnOverNotUnderOurFeet() {
        // up on the surface, both buried: the band block under us pays STANDING_ON, the one beside it doesn't
        double under = tunnel(64, 0, FLOOR + 1, 0, false);
        double beside = tunnel(64, 1, FLOOR + 1, 0, false);
        assertEquals(beside - 1 + StoneDigRank.STANDING_ON, under, 1e-9);
        assertTrue(beside < under);
        // and in the tunnel the floor under us is the last thing we want
        assertTrue(tunnel(FLOOR, 0, FLOOR - 1, 0, true) > tunnel(FLOOR, 1, FLOOR - 1, 0, true));
    }

    @Test
    public void theTunnelStillMindsBuriedAndGravel() {
        assertEquals(tunnel(FLOOR, 3, FLOOR, 0, true) + StoneDigRank.BURIED, tunnel(FLOOR, 3, FLOOR, 0, false), 1e-9);
        // the ceiling over the band is often gravel, and it counts from the block up
        assertEquals(tunnel(FLOOR, 3, FLOOR + 1, 0, true) + 2 * StoneDigRank.FALLING_EXTRA,
                StoneDigRank.tunnelScore(X, FLOOR, Z, 3, FLOOR + 1, 0, true, 2, FLOOR), 1e-9);
    }

    @Test
    public void weTunnelOnlyWhenTheBestIsUnderUsAndNothingIsCheap() {
        // the shaft case: the best is a block down the column and costs more than a short walk would
        assertTrue(StoneDigRank.shouldTunnel(64, 53, at(1, 53, 0, false)));
        // a wall at our level is never a reason
        assertFalse(StoneDigRank.shouldTunnel(64, 64, 20));
        // one down, one over, exposed: cheap enough to just take
        assertFalse(StoneDigRank.shouldTunnel(64, 63, at(1, 63, 0, true)));
    }

    // dirt from the feet down to 54, stone from 53
    private static final IntPredicate STONE_FROM_53 = y -> y <= 53;
    private static final IntPredicate NO_HOLES = y -> false;

    @Test
    public void theFloorIsTwoIntoTheFirstStoneDown() {
        // feet walk 52, head in 53
        assertEquals(52, StoneDigRank.tunnelFloor(64, false, STONE_FROM_53, NO_HOLES).getAsInt());
        // already in it: we stay at our level
        assertEquals(64, StoneDigRank.tunnelFloor(64, true, y -> true, NO_HOLES).getAsInt());
        // nothing within the scan: no tunnel
        assertTrue(StoneDigRank.tunnelFloor(64, false, y -> y < 64 - StoneDigRank.FLOOR_SCAN, NO_HOLES).isEmpty());
        assertEquals(64 - StoneDigRank.FLOOR_SCAN - 1, StoneDigRank.tunnelFloor(64, false, y -> y <= 64 - StoneDigRank.FLOOR_SCAN, NO_HOLES).getAsInt());
    }

    @Test
    public void aHoleOnTheWayDownIsNotAStoneLine() {
        // a cave from 58 to 55 under the dirt: the stone under it is the cave's floor
        assertTrue(StoneDigRank.tunnelFloor(64, false, STONE_FROM_53, y -> y >= 55 && y <= 58).isEmpty());
        // a hole under the stone doesn't matter, we never get that far
        assertEquals(52, StoneDigRank.tunnelFloor(64, false, STONE_FROM_53, y -> y < 50).getAsInt());
    }

    @Test
    public void askingAgainFromTheTunnelFloorGivesTheSameFloor() {
        // a plain tunnel: stone under the feet and beside them, which is underLine, and it keeps the level
        int floor = StoneDigRank.tunnelFloor(64, false, STONE_FROM_53, NO_HOLES).getAsInt();
        assertEquals(floor, StoneDigRank.tunnelFloor(floor, true, STONE_FROM_53, NO_HOLES).getAsInt());
        // a dug out room has nothing beside the feet, and from the world alone that reads as the surface: two deeper.
        // that is why the floor is remembered (StoneTunnelTest), this pins down what happens without it
        assertEquals(floor - 2, StoneDigRank.tunnelFloor(floor, false, STONE_FROM_53, NO_HOLES).getAsInt());
    }

    @Test
    public void theRememberedFloorOnlyWinsForTheRoomCase() {
        // the room: the world reads two under what we walked, the memory keeps it
        assertEquals(52, StoneDigRank.pickFloor(OptionalInt.of(50), OptionalInt.of(52)).getAsInt());
        assertEquals(52, StoneDigRank.pickFloor(OptionalInt.of(51), OptionalInt.of(52)).getAsInt());
        assertEquals(52, StoneDigRank.pickFloor(OptionalInt.of(52), OptionalInt.of(52)).getAsInt());
        // a floor from down in a dip, with a hill's stone line way over it: the world, or we dig twenty down for it
        assertEquals(69, StoneDigRank.pickFloor(OptionalInt.of(69), OptionalInt.of(52)).getAsInt());
        // a memory way over what is under us now (the stone line drops off): the world again
        assertEquals(40, StoneDigRank.pickFloor(OptionalInt.of(40), OptionalInt.of(52)).getAsInt());
        // nothing remembered, or no stone line here at all
        assertEquals(52, StoneDigRank.pickFloor(OptionalInt.of(52), OptionalInt.empty()).getAsInt());
        assertTrue(StoneDigRank.pickFloor(OptionalInt.empty(), OptionalInt.of(52)).isEmpty());
    }

    @Test
    public void onlyTheNaturalStoneFamilyIsTheStoneLine() {
        assertTrue(StoneDigRank.stoneLine(Blocks.STONE));
        assertTrue(StoneDigRank.stoneLine(Blocks.DEEPSLATE));
        assertTrue(StoneDigRank.stoneLine(Blocks.TUFF));
        assertFalse(StoneDigRank.stoneLine(Blocks.COBBLESTONE));
        assertFalse(StoneDigRank.stoneLine(Blocks.DIRT));
        assertFalse(StoneDigRank.stoneLine(Blocks.GRAVEL));
    }

    @Test
    public void aNewStoneHasToBeClearlyCheaperToTakeOver() {
        assertTrue(StoneDigRank.switches(20, 20 - StoneDigRank.SWITCH_MARGIN));
        assertFalse(StoneDigRank.switches(20, 20 - StoneDigRank.SWITCH_MARGIN + 0.5));
        assertFalse(StoneDigRank.switches(20, 25));
        // the wall next to us beats the deep stone we were walking to by a mile
        assertTrue(StoneDigRank.switches(at(1, 53, 0, false), at(1, 64, 0, true)));
    }

    @Test
    public void neighboursUnderUsDoNotTradePlacesAsOurFeetCrossTheLine() {
        // two buried stones side by side six under us, feet a hair either side of the line between their columns
        int x = -12, y = 61;
        double fx = x + 0.5, fy = 67;
        for (double fz : new double[]{-6.01, -5.99}) {
            double six = StoneDigRank.score(fx, fy, fz, x, y, -6, false, 0);
            double seven = StoneDigRank.score(fx, fy, fz, x, y, -7, false, 0);
            // with the standing-on part in, whichever we are over loses by a mile, so each step was a switch
            assertTrue(StoneDigRank.switches(Math.max(six, seven), Math.min(six, seven)));
            // the switch call goes without it, and then neither is worth giving the other up for
            double sixSwitch = six - StoneDigRank.standingOnPart(fx, fy, fz, x, y, -6);
            double sevenSwitch = seven - StoneDigRank.standingOnPart(fx, fy, fz, x, y, -7);
            assertFalse(StoneDigRank.switches(sixSwitch, sevenSwitch));
            assertFalse(StoneDigRank.switches(sevenSwitch, sixSwitch));
        }
        // and it is the same test score uses: nothing off for a stone beside us or above our feet
        assertEquals(0, StoneDigRank.standingOnPart(fx, fy, -6.01, x + 1, y, -7), 1e-9);
        assertEquals(0, StoneDigRank.standingOnPart(fx, fy, -6.01, x, 67, -7), 1e-9);
        assertEquals(StoneDigRank.STANDING_ON, StoneDigRank.standingOnPart(fx, fy, -6.01, x, y, -7), 1e-9);
    }

    @Test
    public void bandIsTheFloorAndTheCellAboveIt() {
        assertTrue(StoneDigRank.inBand(FLOOR, FLOOR));
        assertTrue(StoneDigRank.inBand(FLOOR + 1, FLOOR));
        assertFalse(StoneDigRank.inBand(FLOOR + 2, FLOOR));
        assertFalse(StoneDigRank.inBand(FLOOR - 1, FLOOR));
    }

    @Test
    public void onlyStoneishTargetsGetTheRanking() {
        assertTrue(StoneDigRank.stoneOnly(Blocks.STONE));
        assertTrue(StoneDigRank.stoneOnly(Blocks.STONE, Blocks.COBBLESTONE));
        assertTrue(StoneDigRank.stoneOnly(Blocks.DEEPSLATE));
        assertFalse(StoneDigRank.stoneOnly(Blocks.IRON_ORE));
        assertFalse(StoneDigRank.stoneOnly(Blocks.STONE, Blocks.DIRT));
        assertFalse(StoneDigRank.stoneOnly(Blocks.OAK_LOG));
        assertFalse(StoneDigRank.stoneOnly());
    }
}
