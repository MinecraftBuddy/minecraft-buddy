package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// (night_hole_bedless) a cover taken on the surface at night is held until dawn: the hp exits, the far exit and the stall wait
// for the day; the cover's own give-up and a run or a fight taking over still let go. the cover let go at 18 hp after 5 s with
// no line in the dark, and the buddy with no armour was shot or swarmed out there
public class SkelCoverDawnTest {

    private static final float LINE = SkelCover.HP_LINE;
    private static final double FAR = Double.POSITIVE_INFINITY;

    // taken at 10 hp with a skeleton's line within 12; the hold rules on as on the rig
    private static SkelCover.Hold held(boolean key, boolean night, boolean surface) {
        SkelCover.Hold h = new SkelCover.Hold();
        h.holdRules(true);
        h.nightHoleFacts(key, night, surface);
        h.step(0, true, true, 10, LINE, 1, 12, 12, false, null, true, false);
        assertTrue(h.on());
        h.takeNote();
        return h;
    }

    @Test
    public void theHpLineWithNoLineWaitsForDawnOnTheSurfaceAtNight() {
        SkelCover.Hold h = held(true, true, true);
        assertTrue(h.tillDawn());
        h.step(10, false, true, 18.3f, LINE, 0, FAR, 30, false, null, true, false);
        h.step(10 + SkelCover.LINE_GONE_TICKS, false, true, 18.3f, LINE, 0, FAR, 30, false, null, true, false);
        assertTrue(h.on());
        assertEquals("skeleton cover: hp 18.3, no line for 5 s, but night on the surface at 18.3 hp: the cover is held until dawn (night_hole_bedless)",
                h.takeNote());
        // full hp, the far exit and the stall's minutes: still in, and said only once
        h.step(400, false, true, SkelCover.FULL_HP, LINE, 0, FAR, 30, false, null, true, false);
        h.step(400 + SkelCover.STALL_TICKS + SkelCover.STALL_CAP_TICKS + 10, false, true, SkelCover.FULL_HP, LINE, 0, FAR, 30, false, null, true, false);
        assertTrue(h.on());
        assertNull(h.takeNote());
        // dawn: the hp exit as before
        h.nightHoleFacts(true, false, false);
        assertFalse(h.tillDawn());
        h.step(9000, false, true, SkelCover.FULL_HP, LINE, 0, FAR, 30, false, null, true, false);
        assertFalse(h.on());
        assertEquals("skeleton cover: out of cover: hp 20.0", h.takeNote());
    }

    // no hp back and no food all night is no reason to walk out into it either
    @Test
    public void theStallWaitsForDawnToo() {
        SkelCover.Hold h = held(true, true, true);
        h.step(SkelCover.STALL_TICKS + 1, false, true, 10, LINE, 0, FAR, 30, false, null, false, false);
        assertTrue(h.on());
        assertTrue(h.takeNote().contains("held until dawn (night_hole_bedless)"));
    }

    // the cover task gave up (an arrow in the shut hole): out as its give-up, night or not
    @Test
    public void aCoverThatGaveUpStillGoes() {
        SkelCover.Hold h = held(true, true, true);
        h.step(10, false, true, 12, LINE, 1, 12, 12, false, "an arrow in the hole", true, false);
        assertFalse(h.on());
        assertEquals("skeleton cover: out of cover: no cover here (an arrow in the hole)", h.takeNote());
        // at the hp exit too: the give-up's words, not the hp's
        SkelCover.Hold full = held(true, true, true);
        full.step(10, false, true, SkelCover.FULL_HP, LINE, 0, FAR, 30, false, "an arrow in the hole", true, false);
        assertFalse(full.on());
        assertEquals("skeleton cover: out of cover: no cover here (an arrow in the hole)", full.takeNote());
    }

    // hurt in there by what got in: the run or the fight takes over and the cover ends
    @Test
    public void aRunOrAFightStillTakesOver() {
        SkelCover.Hold h = held(true, true, true);
        h.interrupt(20, "a run took over");
        assertFalse(h.on());
        assertEquals("skeleton cover: out of cover: a run took over", h.takeNote());
    }

    // taken under a roof or in a cave (not the surface), by day, or with the key off: the exits as before
    @Test
    public void offTheSurfaceByDayOrWithTheKeyOffAsBefore() {
        for (SkelCover.Hold h : new SkelCover.Hold[]{held(true, true, false), held(true, false, true), held(false, true, true)}) {
            assertFalse(h.tillDawn());
            h.step(10, false, true, 18.3f, LINE, 0, FAR, 12, false, null, true, false);
            h.step(10 + SkelCover.LINE_GONE_TICKS, false, true, 18.3f, LINE, 0, FAR, 12, false, null, true, false);
            assertFalse(h.on());
            assertEquals("skeleton cover: out of cover: hp 18.3, no line for 5 s", h.takeNote());
        }
    }

    // the surface is as it was when the cover was taken: dug two down the buddy is under it, still held
    @Test
    public void theSurfaceIsTheOneTheCoverWasTakenOn() {
        SkelCover.Hold h = held(true, true, true);
        h.nightHoleFacts(true, true, false);
        assertTrue(h.tillDawn());
        h.step(10, false, true, SkelCover.FULL_HP, LINE, 0, FAR, 30, false, null, true, false);
        assertTrue(h.on());
        // a cover taken in the hole under it is not
        SkelCover.Hold under = held(true, true, false);
        under.nightHoleFacts(true, true, true);
        assertFalse(under.tillDawn());
    }
}
