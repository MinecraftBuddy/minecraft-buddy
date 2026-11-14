package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// (witch_cover) the skeleton cover's hold taken for a witch: out only once the poison is over, the hp is back to the cover's rule
// and no witch has had a line for 5 s; the far exit waits for no witch within 20 and the poison over; with the key off the old
// rules
public class SkelCoverWitchTest {

    private static final float LINE = SkelCover.HP_LINE;
    private static final double FAR = Double.POSITIVE_INFINITY;
    private static final long GONE = SkelCover.LINE_GONE_TICKS;

    // a hold with the hold rules on, taken at tick 100 at 14 hp for a witch 8 off with a line, poisoned
    private static SkelCover.Hold taken() {
        SkelCover.Hold h = new SkelCover.Hold();
        h.holdRules(true);
        h.witchFacts(true, true, true, 1, 8, 8);
        h.step(100, true, true, 14, LINE, 0, FAR, FAR, false, null, true, false);
        return h;
    }

    // one tick in cover: no skeleton about, the witch at witchAt with or without a line, poisoned or not
    private static void tick(SkelCover.Hold h, long now, float hp, boolean poisoned, int witchLines, double witchAt) {
        h.witchFacts(true, false, poisoned, witchLines, witchLines > 0 ? witchAt : FAR, witchAt);
        h.step(now, false, true, hp, LINE, 0, FAR, FAR, false, null, true, false);
    }

    @Test
    public void aWitchsCoverSaysSoAndIsAWitchs() {
        SkelCover.Hold h = taken();
        assertTrue(h.on());
        assertTrue(h.witch());
        assertEquals("witch cover: 1 witch with a line within 8.0 at 14.0 hp, poisoned, taking cover", h.takeNote());
    }

    @Test
    public void fullHpIsNoWayOutWhileThePoisonIsOn() {
        SkelCover.Hold h = taken();
        h.takeNote();
        tick(h, 200, 20, true, 0, 10);
        assertTrue(h.on());
        // the poison over, the witch's line gone since the take (no line after it): out at full hp
        tick(h, 201, 20, false, 0, 10);
        assertFalse(h.on());
        assertEquals("witch cover: out of cover: hp 20.0, no poison, no witch's line for 5 s", h.takeNote());
    }

    @Test
    public void aWitchsLineHoldsTheCoverFiveSecondsPastIt() {
        SkelCover.Hold h = taken();
        h.takeNote();
        // the poison over at 18 hp, but a witch had a line at 300
        tick(h, 300, 18, false, 1, 9);
        tick(h, 300 + GONE - 1, 18, false, 0, 9);
        assertTrue(h.on());
        tick(h, 300 + GONE, 18, false, 0, 9);
        assertFalse(h.on());
        assertEquals("witch cover: out of cover: hp 18.0, no line for 5 s, no poison, no witch's line for 5 s", h.takeNote());
    }

    @Test
    public void theFarExitWaitsForNoWitchWithinTwentyAndThePoisonOver() {
        SkelCover.Hold h = taken();
        h.takeNote();
        // a witch 18 off with no line: within 20, the far clock does not run
        for (long t = 110; t < 110 + 2 * SkelCover.LEAVE_TICKS; t += 10) tick(h, t, 10, false, 0, 18);
        assertTrue(h.on());
        // 25 off but poisoned: no far exit either
        for (long t = 400; t <= 400 + 2 * SkelCover.LEAVE_TICKS; t += 10) tick(h, t, 10, true, 0, 25);
        assertTrue(h.on());
        // 25 off and the poison over: out after LEAVE_TICKS
        tick(h, 700, 10, false, 0, 25);
        tick(h, 700 + SkelCover.LEAVE_TICKS - 1, 10, false, 0, 25);
        assertTrue(h.on());
        tick(h, 700 + SkelCover.LEAVE_TICKS, 10, false, 0, 25);
        assertFalse(h.on());
        assertEquals("witch cover: out of cover: no skeleton or witch within 20 for 5 s", h.takeNote());
    }

    @Test
    public void theStallLeavesOnlyWithNoWitchsLineAndNoneWithinSixteen() {
        SkelCover.Hold h = taken();
        h.takeNote();
        // no hp back for the stall's minute, the witch 12 off with no line: staying
        tick(h, 100 + SkelCover.STALL_TICKS, 14, false, 0, 12);
        assertTrue(h.on());
        assertEquals("witch cover: no hp back in 60 s, staying while a witch or skeleton is within 16", h.takeNote());
        // the witch walked off to 17: out
        tick(h, 110 + SkelCover.STALL_TICKS, 14, false, 0, 17);
        assertFalse(h.on());
        assertEquals("witch cover: out of cover: no hp back in 60 s, no skeleton or witch within 16", h.takeNote());
    }

    // the key off: the skeleton cover's rules as before, poison or not
    @Test
    public void withTheKeyOffThePoisonHoldsNothing() {
        SkelCover.Hold h = new SkelCover.Hold();
        h.holdRules(true);
        h.witchFacts(false, true, true, 1, 8, 8);
        h.step(100, true, true, 14, LINE, 1, 9, 9, false, null, true, false);
        assertTrue(h.on());
        assertFalse(h.witch());
        assertEquals("skeleton cover: 1 skeleton with a line within 9.0 at 14.0 hp, taking cover", h.takeNote());
        h.witchFacts(false, false, true, 1, 8, 8);
        h.step(120, false, true, 20, LINE, 0, FAR, 9, false, null, true, false);
        assertFalse(h.on());
        assertEquals("skeleton cover: out of cover: hp 20.0", h.takeNote());
    }

    // a skeleton's cover with the key on and no witch about: out at full hp as before
    @Test
    public void aSkeletonsCoverWithNoWitchAboutLeavesAsBefore() {
        SkelCover.Hold h = new SkelCover.Hold();
        h.holdRules(true);
        h.witchFacts(true, false, false, 0, FAR, FAR);
        h.step(100, true, true, 14, LINE, 1, 9, 9, false, null, true, false);
        assertTrue(h.on());
        assertFalse(h.witch());
        h.takeNote();
        h.witchFacts(true, false, false, 0, FAR, FAR);
        h.step(120, false, true, 20, LINE, 0, FAR, 9, false, null, true, false);
        assertFalse(h.on());
        assertEquals("skeleton cover: out of cover: hp 20.0", h.takeNote());
    }
}
