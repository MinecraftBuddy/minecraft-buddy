package adris.altoclef.util.helpers;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

// (skel_cover, skel_close_rule) the overworld's skeletons: when the cover is wanted, when a walk up is off, which cells take the
// line away, and when the cover is taken and left
public class SkelCoverTest {

    private static final float LINE = SkelCover.HP_LINE;

    @Test
    public void theKindsAreTheArrowShootersOnly() {
        for (String kind : List.of("skeleton", "stray", "bogged", "parched")) assertTrue(kind, SkelCover.kind(kind));
        for (String kind : List.of("wither_skeleton", "blaze", "pillager", "zombie")) assertFalse(kind, SkelCover.kind(kind));
    }

    @Test
    public void coverIsWantedUnderTheLineWithALineInTheOverworldOnly() {
        assertTrue(SkelCover.wanted(true, 17.9f, 1, LINE));
        assertFalse(SkelCover.wanted(true, 18, 1, LINE));
        assertFalse(SkelCover.wanted(true, 5, 0, LINE));
        assertFalse(SkelCover.wanted(false, 5, 2, LINE));
        assertFalse(SkelCover.wanted(true, 0, 2, LINE));
    }

    @Test
    public void aWalkUpWantsHpASwordArmourAndNoSecondInSight() {
        float hp = SkelCover.CLOSE_HP;
        assertNull(SkelCover.closeRefusal(hp, true, 1, 0, SkelCover.CLOSE_HP, SkelCover.RANGE));
        assertEquals("hp 15.5, under 16", SkelCover.closeRefusal(15.5f, true, 4, 0, SkelCover.CLOSE_HP, SkelCover.RANGE));
        assertEquals("no sword", SkelCover.closeRefusal(hp, false, 4, 0, SkelCover.CLOSE_HP, SkelCover.RANGE));
        assertEquals("no armour on", SkelCover.closeRefusal(20, true, 0, 0, SkelCover.CLOSE_HP, SkelCover.RANGE));
        assertEquals("1 more skeleton with a line within 16", SkelCover.closeRefusal(20, true, 4, 1, SkelCover.CLOSE_HP, SkelCover.RANGE));
        assertEquals("3 more skeletons with a line within 16", SkelCover.closeRefusal(20, true, 4, 3, SkelCover.CLOSE_HP, SkelCover.RANGE));
    }

    // level: the cells beside the head toward the skeleton, the one the eye is in left out, as far as the reach
    @Test
    public void aLevelLineGoesThroughTheCellsAtTheEyesHeight() {
        List<int[]> cells = SkelCover.rayCells(0.5, 1.62, 0.5, 12.5, 1.62, 0.5, 3.5);
        assertEquals(3, cells.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(i + 1, cells.get(i)[0]);
            assertEquals(1, cells.get(i)[1]);
            assertEquals(0, cells.get(i)[2]);
        }
    }

    // from high above: the cell over the head first
    @Test
    public void aSteepLineFromAboveGoesThroughTheCellOverTheHead() {
        List<int[]> cells = SkelCover.rayCells(0.5, 1.62, 0.5, 1.5, 12, 0.5, 3.5);
        assertEquals(0, cells.get(0)[0]);
        assertEquals(2, cells.get(0)[1]);
        assertEquals(0, cells.get(0)[2]);
    }

    // the toward-negative side and a diagonal: every step is one face, never two cells at once
    @Test
    public void aDiagonalLineStepsOneFaceAtATime() {
        List<int[]> cells = SkelCover.rayCells(0.3, 1.62, 0.6, -9.7, 1.62, -9.4, 3.5);
        int[] last = {0, 1, 0};
        for (int[] c : cells) {
            int moved = Math.abs(c[0] - last[0]) + Math.abs(c[1] - last[1]) + Math.abs(c[2] - last[2]);
            assertEquals(1, moved);
            assertTrue(c[0] <= 0 && c[2] <= 0);
            last = c;
        }
        assertEquals(-1, cells.get(0)[0] + cells.get(0)[2]);
        assertTrue(SkelCover.rayCells(0, 0, 0, 0, 0, 0, 3).isEmpty());
    }

    @Test
    public void theCoverIsTakenWantedAndOnOfferAndLeftAtTheHpLine() {
        SkelCover.Hold h = new SkelCover.Hold();
        assertFalse(h.takes(100, true, false));
        h.step(100, true, false, 10, LINE, 1, 12, 12, false, null);
        assertFalse(h.on());
        assertTrue(h.takes(100, true, true));
        h.step(100, true, true, 10, LINE, 2, 11.5, 11.5, false, null);
        assertTrue(h.on());
        assertEquals("skeleton cover: 2 skeletons with a line within 11.5 at 10.0 hp, taking cover", h.takeNote());
        // in cover the line is gone, the cover holds
        h.step(120, false, true, 12, LINE, 0, Double.POSITIVE_INFINITY, 11, false, null);
        assertTrue(h.on());
        assertTrue(h.takes(120, false, true));
        h.step(140, false, true, 18, LINE, 0, Double.POSITIVE_INFINITY, 11, false, null);
        assertFalse(h.on());
        assertEquals("skeleton cover: out of cover: hp 18.0", h.takeNote());
    }

    // (Tuning cover_night_hold) at night the far exit waits for full hp: early deaths since heel-17 ran at 6 to 7 hp in the dark
    @Test
    public void atNightTheFarExitWaitsForFullHp() {
        SkelCover.Hold h = new SkelCover.Hold();
        h.nightFacts(true, true);
        h.step(100, true, true, 7, LINE, 1, 12, 12, false, null);
        h.takeNote();
        h.step(110, false, true, 7, LINE, 0, Double.POSITIVE_INFINITY, 21, false, null);
        assertTrue(h.takeNote().endsWith("the far exit stays shut until the hp is full (cover_night_hold)"));
        h.step(110 + 3 * SkelCover.LEAVE_TICKS, false, true, 8, LINE, 0, Double.POSITIVE_INFINITY, 21, false, null);
        assertTrue(h.on());
        assertEquals(null, h.takeNote());
        // day again: the far exit's 5 s
        h.nightFacts(true, false);
        h.step(120 + 3 * SkelCover.LEAVE_TICKS, false, true, 9, LINE, 0, Double.POSITIVE_INFINITY, 21, false, null);
        h.step(120 + 4 * SkelCover.LEAVE_TICKS, false, true, 9, LINE, 0, Double.POSITIVE_INFINITY, 21, false, null);
        assertFalse(h.on());
        // the key off: the far exit as before, at night too
        SkelCover.Hold off = new SkelCover.Hold();
        off.nightFacts(false, true);
        off.step(100, true, true, 7, LINE, 1, 12, 12, false, null);
        off.step(110, false, true, 7, LINE, 0, Double.POSITIVE_INFINITY, 21, false, null);
        off.step(110 + SkelCover.LEAVE_TICKS, false, true, 7, LINE, 0, Double.POSITIVE_INFINITY, 21, false, null);
        assertFalse(off.on());
    }

    @Test
    public void theCoverIsLeftWithNoSkeletonWithinTwentyForFiveSeconds() {
        SkelCover.Hold h = new SkelCover.Hold();
        h.step(100, true, true, 10, LINE, 1, 12, 12, false, null);
        h.takeNote();
        h.step(110, false, true, 11, LINE, 0, Double.POSITIVE_INFINITY, 21, false, null);
        h.step(110 + SkelCover.LEAVE_TICKS - 1, false, true, 11, LINE, 0, Double.POSITIVE_INFINITY, 21, false, null);
        assertTrue(h.on());
        // one back within 20 starts the clock over
        h.step(110 + SkelCover.LEAVE_TICKS, false, true, 11, LINE, 0, Double.POSITIVE_INFINITY, 19, false, null);
        h.step(120 + SkelCover.LEAVE_TICKS, false, true, 11, LINE, 0, Double.POSITIVE_INFINITY, 21, false, null);
        h.step(120 + 2 * SkelCover.LEAVE_TICKS - 1, false, true, 11, LINE, 0, Double.POSITIVE_INFINITY, 21, false, null);
        assertTrue(h.on());
        h.step(120 + 2 * SkelCover.LEAVE_TICKS, false, true, 11, LINE, 0, Double.POSITIVE_INFINITY, 21, false, null);
        assertFalse(h.on());
        assertEquals("skeleton cover: out of cover: no skeleton within 20 for 5 s", h.takeNote());
        // nothing to wait for: wanted again, taken again
        assertTrue(h.takes(121 + 2 * SkelCover.LEAVE_TICKS, true, true));
    }

    @Test
    public void noHpBackForAMinuteLeavesAndWaitsHalfAMinute() {
        SkelCover.Hold h = new SkelCover.Hold();
        h.step(0, true, true, 6, LINE, 1, 12, 12, false, null);
        // a meal under way is progress
        h.step(SkelCover.STALL_TICKS - 10, false, true, 6, LINE, 0, Double.POSITIVE_INFINITY, 12, true, null);
        h.step(2 * SkelCover.STALL_TICKS - 11, false, true, 6, LINE, 0, Double.POSITIVE_INFINITY, 12, false, null);
        assertTrue(h.on());
        long out = 2 * SkelCover.STALL_TICKS - 10;
        h.step(out, false, true, 6, LINE, 0, Double.POSITIVE_INFINITY, 12, false, null);
        assertFalse(h.on());
        assertEquals("skeleton cover: out of cover: no hp back in 60 s", h.takeNote());
        assertFalse(h.takes(out + SkelCover.RETRY_STALL_TICKS - 1, true, true));
        assertTrue(h.takes(out + SkelCover.RETRY_STALL_TICKS, true, true));
    }

    @Test
    public void aCoverThatCouldNotBeHadWaitsTenSecondsAndARunEndsIt() {
        SkelCover.Hold h = new SkelCover.Hold();
        h.step(0, true, true, 6, LINE, 1, 12, 12, false, null);
        h.step(5, true, true, 6, LINE, 1, 12, 12, false, "no block and no ground to dig at 0, 64, 0");
        assertFalse(h.on());
        h.takeNote();
        assertFalse(h.takes(5 + SkelCover.RETRY_FAILED_TICKS - 1, true, true));
        h.step(5 + SkelCover.RETRY_FAILED_TICKS, true, true, 6, LINE, 1, 12, 12, false, null);
        assertTrue(h.on());
        h.takeNote();
        h.interrupt(300, "a run took over");
        assertFalse(h.on());
        assertEquals("skeleton cover: out of cover: a run took over", h.takeNote());
        // a run is no reason to wait
        assertTrue(h.takes(301, true, true));
    }

    // ---- (skel_cover_dig_first, skel_cover_hold, skel_cover_wet_fail) the fixes of 14 Nov 2026

    @Test
    public void theCoverDigsFirstThenTheBoxFromTheBag() {
        assertEquals(SkelCover.Way.SHUT, SkelCover.way(true, true, true, true));
        assertEquals(SkelCover.Way.DIG, SkelCover.way(false, false, true, true));
        assertEquals(SkelCover.Way.DIG, SkelCover.way(false, false, true, false));
        assertEquals(SkelCover.Way.BOX, SkelCover.way(false, false, false, true));
        // a hole or a nook with the top missing: the top from the bag, not two more down
        assertEquals(SkelCover.Way.BOX, SkelCover.way(false, true, true, true));
        assertEquals(SkelCover.Way.DIG, SkelCover.way(false, true, true, false));
        assertEquals(SkelCover.Way.NONE, SkelCover.way(false, false, false, false));
    }

    @Test
    public void theBoxGoesUpOnTheSkeletonsSideFirst() {
        List<int[]> east = SkelCover.boxSides(10, 2);
        assertArrayEquals(new int[]{1, 0}, east.get(0));
        assertArrayEquals(new int[]{0, 1}, east.get(1));
        assertArrayEquals(new int[]{0, -1}, east.get(2));
        assertArrayEquals(new int[]{-1, 0}, east.get(3));
        List<int[]> north = SkelCover.boxSides(-1, -5);
        assertArrayEquals(new int[]{0, -1}, north.get(0));
        assertArrayEquals(new int[]{-1, 0}, north.get(1));
        assertArrayEquals(new int[]{1, 0}, north.get(2));
        assertArrayEquals(new int[]{0, 1}, north.get(3));
    }

    // test heel: dug three deep with no top, shot into the hole from the rim
    @Test
    public void aHoleWithNoTopIsNoCoverFromASkeleton() {
        assertFalse(SkelCover.holeHolds(true, false));
        assertTrue(SkelCover.holeHolds(true, true));
        assertTrue(SkelCover.holeHolds(false, false));
    }

    // test heel: the cover task stood 36 s in water
    @Test
    public void offTheGroundASecondIsACoverThatFailed() {
        assertFalse(SkelCover.offGroundFailed(-1, 500));
        assertFalse(SkelCover.offGroundFailed(100, 100 + SkelCover.OFF_GROUND_TICKS - 1));
        assertTrue(SkelCover.offGroundFailed(100, 100 + SkelCover.OFF_GROUND_TICKS));
        // a clock that went back is no second off the ground
        assertFalse(SkelCover.offGroundFailed(100, 50));
    }

    private static SkelCover.Hold held(long at, float hp) {
        SkelCover.Hold h = new SkelCover.Hold();
        h.holdRules(true);
        h.step(at, true, true, hp, LINE, 1, 12, 12, false, null, true, false);
        assertTrue(h.on());
        h.takeNote();
        return h;
    }

    // test heel: five let go at 18 hp with a skeleton's line on them
    @Test
    public void theHpLineIsNotLeftWithALine() {
        SkelCover.Hold h = held(0, 10);
        h.step(10, false, true, 18, LINE, 1, 12, 12, false, null, true, false);
        assertTrue(h.on());
        long lastLine = 10;
        h.step(lastLine + SkelCover.LINE_GONE_TICKS - 1, false, true, 18.5f, LINE, 0, Double.POSITIVE_INFINITY, 12, false, null, true, false);
        assertTrue(h.on());
        h.step(lastLine + SkelCover.LINE_GONE_TICKS, false, true, 18.5f, LINE, 0, Double.POSITIVE_INFINITY, 12, false, null, true, false);
        assertFalse(h.on());
        assertEquals("skeleton cover: out of cover: hp 18.5, no line for 5 s", h.takeNote());
    }

    @Test
    public void fullHpLetsGoWhateverTheLine() {
        SkelCover.Hold h = held(0, 10);
        h.step(5, false, true, SkelCover.FULL_HP, LINE, 2, 9, 9, false, null, true, false);
        assertFalse(h.on());
        assertEquals("skeleton cover: out of cover: hp 20.0", h.takeNote());
    }

    // switched off it is the old hold: out at the line, line or not
    @Test
    public void withoutTheHoldRulesTheLineLetsGoAsBefore() {
        SkelCover.Hold h = new SkelCover.Hold();
        h.step(0, true, true, 10, LINE, 1, 12, 12, false, null, true, false);
        h.step(5, false, true, 18, LINE, 1, 12, 12, false, null, true, false);
        assertFalse(h.on());
    }

    // test heel: four held unhit with no food and walked into the line after the stall's minute
    @Test
    public void theStallLeavesOnlyIntoNoLine() {
        SkelCover.Hold h = held(0, 6);
        long stall = SkelCover.STALL_TICKS;
        h.step(stall, false, true, 6, LINE, 0, Double.POSITIVE_INFINITY, 12, false, null, false, false);
        assertTrue(h.on());
        assertEquals("skeleton cover: no hp back in 60 s, no food, staying while a skeleton is within 16", h.takeNote());
        // said once
        h.step(stall + 1, false, true, 6, LINE, 0, Double.POSITIVE_INFINITY, 12, false, null, false, false);
        assertEquals(null, h.takeNote());
        // the skeleton walks off past the cover's range: out, and the retry waits as before
        long out = stall + 40;
        h.step(out, false, true, 6, LINE, 0, Double.POSITIVE_INFINITY, 17, false, null, false, false);
        assertFalse(h.on());
        assertEquals("skeleton cover: out of cover: no hp back in 60 s, no food, no skeleton within 16", h.takeNote());
        assertFalse(h.takes(out + SkelCover.RETRY_STALL_TICKS - 1, true, true));
    }

    @Test
    public void theStallWithALineAllAlongLetsGoAtItsCap() {
        SkelCover.Hold h = held(0, 6);
        long cap = SkelCover.STALL_TICKS + SkelCover.STALL_CAP_TICKS;
        for (long now = 20; now < cap; now += 20) {
            h.step(now, false, true, 6, LINE, 1, 12, 12, false, null, false, false);
            assertTrue("tick " + now, h.on());
        }
        h.takeNote();
        h.step(cap, false, true, 6, LINE, 1, 12, 12, false, null, false, false);
        assertFalse(h.on());
        assertEquals("skeleton cover: out of cover: no hp back in 60 s, no food, a skeleton about for 5 min", h.takeNote());
    }

    // (skel_cover_over_runs) a zombie at the rim keeps the dug-in cover as a skeleton within 20 does
    @Test
    public void aMeleeMobNearKeepsTheCover() {
        SkelCover.Hold h = held(0, 10);
        for (long now = 10; now <= 10 + 2 * SkelCover.LEAVE_TICKS; now += 10) {
            h.step(now, false, true, 11, LINE, 0, Double.POSITIVE_INFINITY, 30, false, null, true, true);
        }
        assertTrue(h.on());
        long from = 20 + 2 * SkelCover.LEAVE_TICKS;
        h.step(from, false, true, 11, LINE, 0, Double.POSITIVE_INFINITY, 30, false, null, true, false);
        h.step(from + SkelCover.LEAVE_TICKS, false, true, 11, LINE, 0, Double.POSITIVE_INFINITY, 30, false, null, true, false);
        assertFalse(h.on());
    }
}
