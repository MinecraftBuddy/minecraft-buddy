package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// (mounted_no_run) the skeleton cover's hold taken for a rider: it says so, holds at full hp while a rider is within 16 and for
// 5 s after; with the key off a bare hold lets go at full hp as before. MountRules: which riders count and when one is fought
public class SkelCoverMountTest {

    private static final float LINE = SkelCover.HP_LINE;
    private static final double FAR = Double.POSITIVE_INFINITY;
    private static final long GONE = SkelCover.LINE_GONE_TICKS;

    // a hold with the hold rules on, taken at tick 100 at full hp for a rider 6 off, no skeleton about
    private static SkelCover.Hold taken() {
        SkelCover.Hold h = new SkelCover.Hold();
        h.holdRules(true);
        h.mountFacts(true, true, 6);
        h.step(100, true, true, 20, LINE, 0, FAR, FAR, false, null, true, false);
        return h;
    }

    private static void tick(SkelCover.Hold h, long now, float hp, double riderAt) {
        h.mountFacts(true, false, riderAt);
        h.step(now, false, true, hp, LINE, 0, FAR, FAR, false, null, true, false);
    }

    @Test
    public void aRidersCoverSaysSo() {
        SkelCover.Hold h = taken();
        assertTrue(h.on());
        assertTrue(h.mount());
        assertEquals("mount cover: a mounted mob within 6.0 at 20.0 hp, taking cover", h.takeNote());
    }

    @Test
    public void fullHpHoldsWhileARiderIsWithinSixteenAndFiveSecondsAfter() {
        SkelCover.Hold h = taken();
        h.takeNote();
        tick(h, 200, 20, 12);
        assertTrue(h.on());
        // gone past 16 at 300 (it was within 16 at 299): 5 s more
        tick(h, 299, 20, 15);
        tick(h, 300, 20, FAR);
        tick(h, 299 + GONE - 1, 20, FAR);
        assertTrue(h.on());
        tick(h, 299 + GONE, 20, FAR);
        assertFalse(h.on());
        assertEquals("mount cover: out of cover: hp 20.0, no mounted mob within 16 for 5 s", h.takeNote());
    }

    @Test
    public void withTheKeyOffFullHpLetsGo() {
        SkelCover.Hold h = new SkelCover.Hold();
        h.holdRules(true);
        h.mountFacts(false, true, 6);
        // not wanted with the key off (the brain hands in no rider), so only a skeleton's line takes it
        h.step(100, true, true, 14, LINE, 1, 10, 10, false, null, true, false);
        assertFalse(h.mount());
        h.takeNote();
        h.mountFacts(false, false, 6);
        h.step(101, false, true, 20, LINE, 0, FAR, 10, false, null, true, false);
        assertFalse(h.on());
    }

    @Test
    public void aRiderCountsNearOrHavingHitUs() {
        assertTrue(MountRules.about(8, false));
        assertFalse(MountRules.about(8.5, false));
        assertTrue(MountRules.about(15, true));
        assertFalse(MountRules.about(17, true));
    }

    @Test
    public void aRiderIsFoughtWithABladeAtFourteenOrMore() {
        assertTrue(MountRules.fight(true, 14));
        assertFalse(MountRules.fight(true, 13.9f));
        assertFalse(MountRules.fight(false, 20));
    }
}
