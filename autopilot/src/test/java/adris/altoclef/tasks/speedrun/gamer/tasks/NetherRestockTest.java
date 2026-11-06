package adris.altoclef.tasks.speedrun.gamer.tasks;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NetherRestockTest {
    @Test
    public void aFullBagDoesNotRestock() {
        NetherRestock r = new NetherRestock();
        assertFalse(r.update(64, false, true, false, 0));
        assertFalse(r.update(40, false, true, false, 1));
    }

    @Test
    public void lowBagStartsAndHoldsUntilTheTarget() {
        NetherRestock r = new NetherRestock();
        assertTrue(r.update(7, false, true, false, 0));
        // 40 is above the low bar but the dig is not over until the target
        assertTrue(r.update(40, false, true, false, 10));
        assertTrue(r.update(63, false, true, false, 20));
        assertFalse(r.update(64, false, true, false, 30));
        assertFalse(r.isActive());
    }

    @Test
    public void giveUpNeverRestocksOnALowerBarThanWalking() {
        assertTrue(NetherRestock.GIVE_UP_LOW >= NetherRestock.LOW);
        NetherRestock r = new NetherRestock();
        assertTrue(r.update(NetherRestock.LOW - 1, false, true, false, 0));
        NetherRestock giveUp = new NetherRestock();
        assertTrue(giveUp.update(NetherRestock.GIVE_UP_LOW - 1, true, true, false, 0));
        NetherRestock plenty = new NetherRestock();
        assertFalse(plenty.update(NetherRestock.GIVE_UP_LOW, true, true, false, 0));
    }

    @Test
    public void noPickNoRestock() {
        NetherRestock r = new NetherRestock();
        assertFalse(r.update(0, true, false, false, 0));
        assertTrue(r.update(0, true, true, false, 1));
        // lost the pick mid dig
        assertFalse(r.update(0, false, false, false, 2));
        assertFalse(r.isActive());
    }

    @Test
    public void aNormalFinishCoolsDownToo() {
        NetherRestock r = new NetherRestock();
        assertTrue(r.update(10, true, true, false, 0));
        assertFalse(r.update(64, false, true, false, 10));
        // the bag drops straight back under the bar (a bridge ate it), still no new restock
        assertFalse(r.update(10, true, true, false, 11));
        assertTrue(r.update(10, true, true, false, 10 + NetherRestock.COOLDOWN_SECONDS));
    }

    @Test
    public void exhaustedEndsItWithACooldown() {
        NetherRestock r = new NetherRestock();
        assertTrue(r.update(10, true, true, false, 0));
        assertTrue(r.update(10, true, true, true, 5));
        // a blip that clears is forgiven
        assertTrue(r.update(10, true, true, false, 6));
        assertTrue(r.update(10, true, true, true, 7));
        assertFalse(r.update(10, true, true, true, 7 + NetherRestock.EXHAUSTED_SECONDS));
        assertFalse(r.update(10, true, true, false, 8 + NetherRestock.EXHAUSTED_SECONDS));
        assertTrue(r.update(10, true, true, false, 7 + NetherRestock.EXHAUSTED_SECONDS + NetherRestock.COOLDOWN_SECONDS));
    }

    @Test
    public void bandKeepsToAWindowAroundTheStartingHeightAndRadius() {
        BlockPos o = new BlockPos(100, 70, 100);
        assertTrue(NetherRestock.inRestockBand(o, new BlockPos(101, 70, 100)));
        assertTrue(NetherRestock.inRestockBand(o, new BlockPos(100, 74, 100)));
        assertTrue(NetherRestock.inRestockBand(o, new BlockPos(100, 64, 101)));
        assertFalse(NetherRestock.inRestockBand(o, new BlockPos(100, 75, 100)));
        assertFalse(NetherRestock.inRestockBand(o, new BlockPos(100, 63, 100)));
        // radius 48 flat
        assertTrue(NetherRestock.inRestockBand(o, new BlockPos(148, 70, 100)));
        assertFalse(NetherRestock.inRestockBand(o, new BlockPos(149, 70, 100)));
    }

    @Test
    public void aLedgeSeesTheRockBelowItAndNotTheCliffsFarAbove() {
        BlockPos ledge = new BlockPos(0, 70, 0);
        assertTrue(NetherRestock.inRestockBand(ledge, new BlockPos(3, 66, 2)));
        assertFalse(NetherRestock.inRestockBand(ledge, new BlockPos(3, 107, 2)));
        assertFalse(NetherRestock.inRestockBand(ledge, new BlockPos(3, 113, 2)));
    }

    @Test
    public void onlyTheBlockUnderTheFeetIsOutOfTheBandBelowUs() {
        BlockPos o = new BlockPos(100, 70, 100);
        assertFalse(NetherRestock.inRestockBand(o, new BlockPos(100, 69, 100)));
        // the rest of the floor around is in, a bridge over lava is kept safe by the lava reject and not by the band
        assertTrue(NetherRestock.inRestockBand(o, new BlockPos(101, 69, 100)));
        assertTrue(NetherRestock.inRestockBand(o, new BlockPos(100, 69, 99)));
        assertTrue(NetherRestock.inRestockBand(o, new BlockPos(100, 68, 100)));
    }

    @Test
    public void aWallAtFeetLevelIsFairGame() {
        assertTrue(NetherRestock.keepsFooting(70, new BlockPos(101, 70, 100), false));
        assertTrue(NetherRestock.keepsFooting(70, new BlockPos(101, 72, 100), false));
    }

    @Test
    public void aFloorBlockNeedsSolidUnderIt() {
        assertTrue(NetherRestock.keepsFooting(70, new BlockPos(101, 69, 100), true));
        // a bridge block with air under it is the path we are walking on
        assertFalse(NetherRestock.keepsFooting(70, new BlockPos(101, 69, 100), false));
    }

    @Test
    public void footingFollowsTheLiveFeetNotTheStartingHeight() {
        BlockPos row = new BlockPos(101, 70, 100);
        // dy 0 from where the dig began was a wall, one step up it is floor
        assertTrue(NetherRestock.keepsFooting(70, row, false));
        assertFalse(NetherRestock.keepsFooting(71, row, false));
        assertTrue(NetherRestock.keepsFooting(71, row, true));
    }

    @Test
    public void anEmptyBagDigsAgainWithoutWaitingOutTheCooldown() {
        // an empty bag that dug a dry spot is not parked for two minutes
        NetherRestock dry = new NetherRestock();
        assertTrue(dry.update(0, false, true, true, 0));
        assertTrue(dry.update(0, false, true, true, 1));
        assertFalse(dry.update(0, false, true, true, 1 + NetherRestock.EXHAUSTED_SECONDS));
        assertTrue(dry.update(0, false, true, true, 2 + NetherRestock.EXHAUSTED_SECONDS));
    }

    @Test
    public void anEmptyBagThatRanOutTheClockWithSomethingToShowStillWaitsOutTheCooldown() {
        NetherRestock r = new NetherRestock();
        assertTrue(r.update(2, false, true, false, 0));
        assertFalse(r.update(5, false, true, false, NetherRestock.MAX_SECONDS));
        assertFalse(r.update(5, false, true, false, NetherRestock.MAX_SECONDS + 1));
        assertTrue(r.update(5, false, true, false, NetherRestock.MAX_SECONDS + NetherRestock.COOLDOWN_SECONDS));
    }

    // the whole clock and not one block: every pick was a path it couldn't make, that is a dry dig and the next one is the
    // fallback, right away when the bag is empty
    @Test
    public void aClockEndWithNothingGainedIsADryEnd() {
        NetherRestock r = new NetherRestock();
        assertTrue(r.update(2, false, true, false, 0));
        assertFalse(r.update(2, false, true, false, NetherRestock.MAX_SECONDS));
        assertEquals(1, r.exhaustedEnds());
        assertTrue(r.update(2, false, true, false, NetherRestock.MAX_SECONDS + 1));
    }

    // the empty bag line: at EMPTY the cooldown holds, one under it a dry end digs again right away
    @Test
    public void theCooldownSkipStopsAtTheEmptyBagLine() {
        NetherRestock atLine = new NetherRestock();
        assertTrue(atLine.update(NetherRestock.EMPTY, false, true, true, 0));
        assertTrue(atLine.update(NetherRestock.EMPTY, false, true, true, 1));
        assertFalse(atLine.update(NetherRestock.EMPTY, false, true, true, 1 + NetherRestock.EXHAUSTED_SECONDS));
        assertFalse(atLine.update(NetherRestock.EMPTY, false, true, false, 2 + NetherRestock.EXHAUSTED_SECONDS));
        NetherRestock under = new NetherRestock();
        assertTrue(under.update(NetherRestock.EMPTY - 1, false, true, true, 0));
        assertTrue(under.update(NetherRestock.EMPTY - 1, false, true, true, 1));
        assertFalse(under.update(NetherRestock.EMPTY - 1, false, true, true, 1 + NetherRestock.EXHAUSTED_SECONDS));
        assertTrue(under.update(NetherRestock.EMPTY - 1, false, true, false, 2 + NetherRestock.EXHAUSTED_SECONDS));
    }

    // LOW is 32 now, so a bag of 10 is low but not empty: a dry end there must not turn into a dig every few seconds
    @Test
    public void aLowButNotEmptyBagThatEndedDryWaitsOutTheCooldown() {
        NetherRestock r = new NetherRestock();
        assertTrue(r.update(NetherRestock.LOW - 1, false, true, true, 0));
        assertTrue(r.update(NetherRestock.LOW - 1, false, true, true, 1));
        assertFalse(r.update(NetherRestock.LOW - 1, false, true, true, 1 + NetherRestock.EXHAUSTED_SECONDS));
        assertFalse(r.update(NetherRestock.LOW - 1, false, true, false, 2 + NetherRestock.EXHAUSTED_SECONDS));
        assertTrue(r.update(NetherRestock.LOW - 1, false, true, false, 1 + NetherRestock.EXHAUSTED_SECONDS + NetherRestock.COOLDOWN_SECONDS));
    }

    @Test
    public void aDigThatGoesNowhereStopsAndCoolsDown() {
        NetherRestock r = new NetherRestock();
        assertTrue(r.update(10, true, true, false, 0));
        assertFalse(r.update(10, true, true, false, NetherRestock.MAX_SECONDS));
        // still short, but we just tried
        assertFalse(r.update(10, true, true, false, NetherRestock.MAX_SECONDS + 10));
        assertTrue(r.update(10, true, true, false, NetherRestock.MAX_SECONDS + NetherRestock.COOLDOWN_SECONDS));
    }

    @Test
    public void dryEndsCountInARowAndAnyOtherEndResetsThem() {
        NetherRestock r = new NetherRestock();
        double t = 0;
        t = dryRestock(r, t);
        assertEquals(1, r.exhaustedEnds());
        // second one after the cooldown
        t = dryRestock(r, t + NetherRestock.COOLDOWN_SECONDS);
        assertEquals(2, r.exhaustedEnds());
        // one that ends on the clock with blocks to show for it breaks the run
        t += NetherRestock.COOLDOWN_SECONDS;
        assertTrue(r.update(10, true, true, false, t));
        assertFalse(r.update(12, true, true, false, t + NetherRestock.MAX_SECONDS));
        assertEquals(0, r.exhaustedEnds());
    }

    // starts a restock at t and feeds it "nothing left to dig" until it gives up, returns when it did
    private static double dryRestock(NetherRestock r, double t) {
        assertTrue(r.update(10, true, true, true, t));
        // the first exhausted tick starts the grace, the end comes EXHAUSTED_SECONDS after it
        assertTrue(r.update(10, true, true, true, t + 1));
        assertFalse(r.update(10, true, true, true, t + 1 + NetherRestock.EXHAUSTED_SECONDS));
        return t + 1 + NetherRestock.EXHAUSTED_SECONDS;
    }

    @Test
    public void aFullBagEndIsNotADryEnd() {
        NetherRestock r = new NetherRestock();
        assertTrue(r.update(10, true, true, true, 0));
        assertTrue(r.update(10, true, true, true, 1));
        assertFalse(r.update(NetherRestock.TARGET, false, true, true, 1 + NetherRestock.EXHAUSTED_SECONDS));
        assertEquals(0, r.exhaustedEnds());
    }

    @Test
    public void aTopUpAskStartsOnAnyBagUnderTheTarget() {
        NetherRestock r = new NetherRestock();
        // 40 would never start on the sweep's bars
        assertTrue(r.update(40, NetherRestock.TARGET, true, false, 0));
        assertFalse(r.update(NetherRestock.TARGET, NetherRestock.TARGET, true, false, 10));
        NetherRestock full = new NetherRestock();
        assertFalse(full.update(NetherRestock.TARGET, NetherRestock.TARGET, true, false, 0));
    }

    // a lava sea with its surface at y 31, everything below it lava
    private static boolean sea(BlockPos p) {
        return p.getY() <= 31;
    }

    @Test
    public void aPillarInALavaSeaIsOffLimitsAllTheWayUp() {
        // the top of the staircase dug down a pillar: lava three below
        assertTrue(NetherRestock.nearLava(new BlockPos(74, 34, 3), NetherRestockTest::sea));
        assertTrue(NetherRestock.nearLava(new BlockPos(74, 33, 3), NetherRestockTest::sea));
        // four up is far enough
        assertFalse(NetherRestock.nearLava(new BlockPos(74, 35, 3), NetherRestockTest::sea));
    }

    @Test
    public void lavaTwoBlocksOffToTheSideCounts() {
        BlockPos pool = new BlockPos(2, 64, 0);
        assertTrue(NetherRestock.nearLava(new BlockPos(0, 64, 0), p -> p.equals(pool)));
        assertTrue(NetherRestock.nearLava(new BlockPos(0, 64, 0), p -> p.equals(new BlockPos(2, 64, 2))));
        assertFalse(NetherRestock.nearLava(new BlockPos(0, 64, 0), p -> p.equals(new BlockPos(3, 64, 0))));
    }

    @Test
    public void lavaAboveOnlyCountsOneUp() {
        assertTrue(NetherRestock.nearLava(new BlockPos(0, 64, 0), p -> p.equals(new BlockPos(0, 65, 1))));
        assertFalse(NetherRestock.nearLava(new BlockPos(0, 64, 0), p -> p.equals(new BlockPos(0, 66, 0))));
    }

    @Test
    public void noLavaNoReject() {
        assertFalse(NetherRestock.nearLava(new BlockPos(10, 70, 10), p -> false));
    }

    @Test
    public void theArrivalAskFiresOncePerRestock() {
        NetherRestock r = new NetherRestock();
        assertTrue(r.takeArrivalAsk());
        assertFalse(r.takeArrivalAsk());
        assertFalse(r.takeArrivalAsk());
        // a new phase entry is a new restock
        assertTrue(new NetherRestock().takeArrivalAsk());
    }

    @Test
    public void theArrivalDigRunsPastTheSweepsTargetAndTheSweepsBarsComeBackAfter() {
        NetherRestock r = new NetherRestock();
        assertTrue(r.update(56, NetherRestock.ARRIVAL_TARGET, NetherRestock.ARRIVAL_TARGET, true, false, 0));
        // later ticks ask with the sweep's numbers, the dig under way keeps its own stop
        assertTrue(r.update(NetherRestock.TARGET, NetherRestock.LOW, NetherRestock.TARGET, true, false, 10));
        assertFalse(r.update(NetherRestock.ARRIVAL_TARGET, NetherRestock.LOW, NetherRestock.TARGET, true, false, 20));
        // the bag going back to the entry size is no new dig, cooldown or not
        assertFalse(r.update(56, NetherRestock.LOW, NetherRestock.TARGET, true, false, 20 + NetherRestock.COOLDOWN_SECONDS));
    }

    private static NetherRestock arrivalDigThatRanDry(int held) {
        NetherRestock r = new NetherRestock();
        assertTrue(r.update(held, NetherRestock.ARRIVAL_TARGET, NetherRestock.ARRIVAL_TARGET, true, true, 0));
        assertTrue(r.update(held, NetherRestock.LOW, NetherRestock.TARGET, true, true, 1));
        assertFalse(r.update(held, NetherRestock.LOW, NetherRestock.TARGET, true, true, 1 + NetherRestock.EXHAUSTED_SECONDS));
        return r;
    }

    @Test
    public void aDryArrivalDigOnAHealthyBagCountsForNothing() {
        assertEquals(0, arrivalDigThatRanDry(56).exhaustedEnds());
        assertEquals(0, arrivalDigThatRanDry(NetherRestock.LOW).exhaustedEnds());
    }

    @Test
    public void aDryArrivalDigOnAThinBagIsADryDigLikeAnyOther() {
        assertEquals(1, arrivalDigThatRanDry(NetherRestock.LOW - 1).exhaustedEnds());
    }

    @Test
    public void anArrivalDigThatRunsTheClockWithNothingCountsForNothingEither() {
        NetherRestock r = new NetherRestock();
        assertTrue(r.update(56, NetherRestock.ARRIVAL_TARGET, NetherRestock.ARRIVAL_TARGET, true, false, 0));
        assertFalse(r.update(56, NetherRestock.LOW, NetherRestock.TARGET, true, false, NetherRestock.MAX_SECONDS));
        assertEquals(0, r.exhaustedEnds());
    }

    @Test
    public void theTrackerLinesSayWhyTheDigStopped() {
        NetherRestock full = new NetherRestock();
        full.update(56, NetherRestock.ARRIVAL_TARGET, NetherRestock.ARRIVAL_TARGET, true, false, 100);
        assertEquals("restock: dig start, 56 blocks, filling to 128", full.startLine());
        full.update(128, NetherRestock.LOW, NetherRestock.TARGET, true, false, 142);
        assertEquals("restock: dig end, 56 -> 128 blocks in 42 s, full", full.endLine(128, 142));

        assertEquals("restock: dig end, 20 -> 20 blocks in 5 s, dry", arrivalDigThatRanDry(20).endLine(20, 5));

        NetherRestock clock = new NetherRestock();
        clock.update(56, NetherRestock.ARRIVAL_TARGET, NetherRestock.ARRIVAL_TARGET, true, false, 0);
        clock.update(90, NetherRestock.LOW, NetherRestock.TARGET, true, false, NetherRestock.MAX_SECONDS);
        assertEquals("restock: dig end, 56 -> 90 blocks in 75 s, clock", clock.endLine(90, NetherRestock.MAX_SECONDS));

        NetherRestock noPick = new NetherRestock();
        noPick.update(10, NetherRestock.LOW, NetherRestock.TARGET, true, false, 0);
        noPick.update(10, NetherRestock.LOW, NetherRestock.TARGET, false, false, 3);
        assertEquals("restock: dig end, 10 -> 10 blocks in 3 s, no pick", noPick.endLine(10, 3));
    }

    @Test
    public void theFallbackOnlyDigsWhatIsInArmsReach() {
        Vec3 eye = new Vec3(0.5, 65.62, 0.5);
        // the wall beside us and the one at head height
        assertTrue(NetherRestock.inReach(eye, new BlockPos(1, 64, 0)));
        assertTrue(NetherRestock.inReach(eye, new BlockPos(0, 66, 2)));
        // three over and one down is still a swing, five over is a walk
        assertTrue(NetherRestock.inReach(eye, new BlockPos(3, 64, 0)));
        assertFalse(NetherRestock.inReach(eye, new BlockPos(5, 65, 0)));
    }
}
