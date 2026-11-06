package adris.altoclef.util.helpers;

import net.minecraft.core.BlockPos;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MineStickTest {
    private static final BlockPos BLOCK = new BlockPos(-97, 83, 3);

    @Test
    public void noBreakNoHold() {
        assertNull(new MineStick().holdOn(0, p -> true));
    }

    @Test
    public void aBlockBeingBrokenIsHeldEveryTickUntilItIsGone() {
        MineStick stick = new MineStick();
        stick.breaking(10, BLOCK);
        for (int tick = 10; tick < 25; tick++) {
            assertEquals(BLOCK, stick.holdOn(tick, p -> true));
        }
        // it broke: the caller's check fails and the drops get their turn
        assertNull(stick.holdOn(26, p -> false));
        // and it stays released, a block that came back is a new block
        assertNull(stick.holdOn(27, p -> true));
    }

    @Test
    public void aPauseLongerThanTheFiveHundredMillisecondFlagStillHolds() {
        // the controller's flag drops after 10 ticks of nothing, a tool swap pause is longer than that
        MineStick stick = new MineStick();
        stick.breaking(100, BLOCK);
        assertEquals(BLOCK, stick.holdOn(100 + 15, p -> true));
        assertEquals(BLOCK, stick.holdOn(100 + MineStick.GRACE_TICKS, p -> true));
    }

    @Test
    public void aBreakThatNeverResumesIsLetGoOfEventually() {
        MineStick stick = new MineStick();
        stick.breaking(100, BLOCK);
        assertNull(stick.holdOn(100 + MineStick.GRACE_TICKS + 1, p -> true));
    }

    @Test
    public void everyFreshTickOfBreakingRestartsTheGrace() {
        MineStick stick = new MineStick();
        stick.breaking(0, BLOCK);
        stick.breaking(25, BLOCK);
        assertEquals(BLOCK, stick.holdOn(50, p -> true));
    }

    @Test
    public void anotherBlockBeingBrokenTakesTheHold() {
        MineStick stick = new MineStick();
        BlockPos other = BLOCK.east();
        stick.breaking(0, BLOCK);
        stick.breaking(5, other);
        assertEquals(other, stick.holdOn(6, p -> true));
        assertTrue(stick.isHolding(other));
        assertFalse(stick.isHolding(BLOCK));
    }

    @Test
    public void clearForgetsIt() {
        MineStick stick = new MineStick();
        stick.breaking(0, BLOCK);
        stick.clear();
        assertNull(stick.holdOn(1, p -> true));
        assertFalse(stick.isHolding(BLOCK));
    }

    @Test
    public void aNewTargetNeedsToBeTwiceAsCloseNotJustCloser() {
        // squared distances: 2x closer is a quarter
        assertTrue(MineStick.clearlyCloser(1, 4));
        assertFalse(MineStick.clearlyCloser(2, 4));
        // the block and the drop next to it trade places as we step, neither is twice as close as the other
        assertFalse(MineStick.clearlyCloser(2.25, 4));
        assertFalse(MineStick.clearlyCloser(4, 2.25));
    }

    @Test
    public void toolSwapIsLeftAloneOnlyWithACrackOnThatBlock() {
        assertTrue(MineStick.toolSwapWouldReset(true, BLOCK, 0.3, BLOCK));
        // nothing cracked yet: the swap is the cheap moment
        assertFalse(MineStick.toolSwapWouldReset(true, BLOCK, 0.0, BLOCK));
        // somebody else's crack, or a stale flag
        assertFalse(MineStick.toolSwapWouldReset(true, BLOCK.east(), 0.3, BLOCK));
        assertFalse(MineStick.toolSwapWouldReset(false, BLOCK, 0.3, BLOCK));
        assertFalse(MineStick.toolSwapWouldReset(true, null, 0.3, BLOCK));
    }

    @Test
    public void aBlockInReachIsHeldBeforeTheControllerSaysAnything() {
        // the first ticks of a break: nothing has called breaking() yet
        MineStick stick = new MineStick();
        assertTrue(stick.wouldBegin(BLOCK));
        stick.begin(0, BLOCK);
        assertEquals(BLOCK, stick.holdOn(1, p -> true));
        assertTrue(stick.isHolding(BLOCK));
    }

    @Test
    public void aDropAppearingMidBreakWaitsForTheBreakToFinish() {
        MineStick stick = new MineStick();
        stick.begin(0, BLOCK);
        boolean[] broken = {false};
        for (int tick = 1; tick <= 12; tick++) {
            if (tick > 3) {
                stick.breaking(tick, BLOCK);
            }
            // a drop is rolling around the whole time and would win the pick, but a held block is not up for it
            assertEquals("tick " + tick, BLOCK, stick.holdOn(tick, p -> !broken[0]));
            assertFalse(MineStick.dropMayTakeOver(false, stick.isHolding(BLOCK), false));
        }
        // the block broke, now the drop gets its turn
        broken[0] = true;
        assertNull(stick.holdOn(13, p -> !broken[0]));
        assertTrue(MineStick.dropMayTakeOver(false, stick.isHolding(BLOCK), false));
    }

    @Test
    public void aDropAboutToDespawnStillTakesOver() {
        assertTrue(MineStick.dropMayTakeOver(false, true, true));
        assertFalse(MineStick.aboutToDespawn(100));
        assertFalse(MineStick.aboutToDespawn(MineStick.DESPAWN_SOON_AGE - 1));
        assertTrue(MineStick.aboutToDespawn(MineStick.DESPAWN_SOON_AGE));
    }

    @Test
    public void twoDropsDoNotTradePlaces() {
        // we are on one, the other being nearer or in reach changes nothing, in either state of the block hold
        assertFalse(MineStick.dropMayTakeOver(true, false, false));
        assertFalse(MineStick.dropMayTakeOver(true, true, false));
        assertFalse(MineStick.dropMayTakeOver(true, false, true));
        // off a block that nobody holds, the drop is welcome
        assertTrue(MineStick.dropMayTakeOver(false, false, false));
    }

    @Test
    public void aBreakNeverGetsAbandonedWhileDropsKeepAppearing() {
        // ten blocks in a row, a drop in sight on every tick of every one of them. the way it used to go, the drop won
        // the first ticks and each block was left at its first crack. a block is abandoned if it is not the hold while
        // it is still standing
        MineStick stick = new MineStick();
        int tick = 0;
        int abandoned = 0;
        int finished = 0;
        for (int n = 0; n < 10; n++) {
            BlockPos block = BLOCK.offset(n, 0, 0);
            boolean[] gone = {false};
            stick.begin(tick, block);
            for (int t = 0; t < 14; t++) {
                tick++;
                if (t >= 2) {
                    stick.breaking(tick, block);
                }
                BlockPos held = stick.holdOn(tick, p -> !gone[0]);
                if (!block.equals(held)) {
                    abandoned++;
                }
            }
            gone[0] = true;
            assertNull(stick.holdOn(++tick, p -> !gone[0]));
            finished++;
        }
        assertEquals(0, abandoned);
        assertEquals(10, finished);
    }

    @Test
    public void aBlockWeNeverStartOnIsHeldOnceAndNotForever() {
        // it is in reach but nothing ever hits it (a wall in the way). one grace, then it is just a block again
        MineStick stick = new MineStick();
        stick.begin(0, BLOCK);
        assertEquals(BLOCK, stick.holdOn(MineStick.GRACE_TICKS, p -> true));
        assertNull(stick.holdOn(MineStick.GRACE_TICKS + 1, p -> true));
        // asking again for the same target is not a new start
        assertFalse(stick.wouldBegin(BLOCK));
        stick.begin(MineStick.GRACE_TICKS + 2, BLOCK);
        assertNull(stick.holdOn(MineStick.GRACE_TICKS + 3, p -> true));
        // a different one is
        assertTrue(stick.wouldBegin(BLOCK.east()));
    }

    @Test
    public void breakingSomethingElseOnTheWayDoesNotBuyTheTargetAnotherFreeHold() {
        MineStick stick = new MineStick();
        BlockPos other = BLOCK.east();
        stick.begin(0, BLOCK);
        // a block in the tunnel gets cracked first, then it is gone and the target is up again
        stick.breaking(5, other);
        assertNull(stick.holdOn(6, p -> !p.equals(other)));
        assertFalse(stick.wouldBegin(BLOCK));
    }

    @Test
    public void aBlockThatBrokeAndCameBackIsAFreshStart() {
        MineStick stick = new MineStick();
        stick.begin(0, BLOCK);
        assertNull(stick.holdOn(1, p -> false));
        assertTrue(stick.wouldBegin(BLOCK));
        stick.begin(5, BLOCK);
        assertEquals(BLOCK, stick.holdOn(6, p -> true));
    }

    @Test
    public void calmSlotPrefersEmptyThenNonToolsAndNeverATool() {
        boolean[] empty = new boolean[9];
        boolean[] dmg = new boolean[9];
        // hotbar of tools and blocks, pick in hand (slot 0): the block slot, not a tool
        java.util.Arrays.fill(dmg, true);
        dmg[3] = false;
        assertEquals(3, MineStick.calmSlot(empty, dmg, 0));
        // an empty slot beats a block slot
        empty[6] = true;
        assertEquals(6, MineStick.calmSlot(empty, dmg, 0));
        // all tools: stay put
        java.util.Arrays.fill(empty, false);
        dmg[3] = true;
        assertEquals(-1, MineStick.calmSlot(empty, dmg, 0));
        // the slot in hand is never the answer
        empty[0] = true;
        assertEquals(-1, MineStick.calmSlot(empty, dmg, 0));
    }
}
