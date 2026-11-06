package adris.altoclef.ui.gui.kit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ScrollerTest {

    private static final long MS = 1_000_000L;

    private static Scroller of(float content, float view) {
        Scroller s = new Scroller();
        s.setSizes(content, view);
        return s;
    }

    @Test
    public void clampsAtBothEnds() {
        Scroller s = of(1000, 300);
        s.scrollBy(-50, 0);
        assertEquals(0f, s.target(), 0f);
        s.scrollBy(5000, 0);
        assertEquals(700f, s.target(), 0f);
        assertEquals(700f, s.offset(1000 * MS), 0f);
    }

    @Test
    public void shortContentDoesNotScroll() {
        Scroller s = of(100, 300);
        assertFalse(s.scrollable());
        s.scrollBy(40, 0);
        assertEquals(0f, s.offset(500 * MS), 0f);
    }

    @Test
    public void easesTowardTheTarget() {
        Scroller s = of(1000, 300);
        s.scrollBy(100, 0);
        float mid = s.offset(30 * MS);
        assertTrue(mid > 0f && mid < 100f);
        assertEquals(100f, s.offset(Theme.ANIM_FAST * MS), 0.001f);
    }

    @Test
    public void shrinkingContentPullsTheOffsetBack() {
        Scroller s = of(1000, 300);
        s.snapTo(700);
        s.setSizes(500, 300);
        assertEquals(200f, s.offset(0), 0f);
    }

    @Test
    public void ensureVisibleMovesTheLeastAmount() {
        Scroller s = of(1000, 300);
        s.ensureVisible(100, 136, 0);
        assertEquals(0f, s.target(), 0f);
        s.ensureVisible(400, 436, 0);
        assertEquals(136f, s.target(), 0f);
        s.ensureVisible(50, 86, 0);
        assertEquals(50f, s.target(), 0f);
        s.ensureVisible(150, 186, 0);
        assertEquals(50f, s.target(), 0f);
    }

    @Test
    public void thumbGeometry() {
        Scroller s = of(1000, 250);
        assertEquals(50f, s.thumbHeight(200, 10), 0.001f);
        s.snapTo(0);
        assertEquals(0f, s.thumbTop(200, 10, 0), 0.001f);
        s.snapTo(s.maxOffset());
        assertEquals(150f, s.thumbTop(200, 10, 0), 0.001f);
        s.snapTo(375);
        assertEquals(75f, s.thumbTop(200, 10, 0), 0.001f);
    }

    @Test
    public void thumbHasAMinimumSize() {
        Scroller s = of(100000, 100);
        assertEquals(24f, s.thumbHeight(200, 24), 0f);
    }

    @Test
    public void draggingTheThumbScrollsTheContentFurther() {
        Scroller s = of(1000, 250);
        s.dragThumb(30, 200, 10, 0);
        assertEquals(30f * 750f / 150f, s.offset(0), 0.001f);
        s.dragThumb(1000, 200, 10, 0);
        assertEquals(750f, s.offset(0), 0f);
        s.dragThumb(-1000, 200, 10, 0);
        assertEquals(0f, s.offset(0), 0f);
    }

    @Test
    public void clickingTheTrackCentresTheThumb() {
        Scroller s = of(1000, 250);
        s.jumpToTrack(100, 200, 10, 0);
        assertEquals(375f, s.target(), 0.001f);
        s.jumpToTrack(0, 200, 10, 0);
        assertEquals(0f, s.target(), 0f);
    }

    @Test
    public void barFadesInWhileScrollingAndOutAfterSixHundredMs() {
        Scroller s = of(1000, 300);
        assertEquals(0f, s.visibility(0, false), 0f);
        long t = 1000 * MS;
        s.scrollBy(10, t);
        s.visibility(t, false);
        assertEquals(1f, s.visibility(t + Theme.ANIM * MS, false), 0.001f);
        assertEquals(1f, s.visibility(t + 599 * MS, false), 0.001f);
        s.visibility(t + 700 * MS, false);
        assertEquals(0f, s.visibility(t + 700 * MS + Theme.ANIM * MS, false), 0.001f);
    }

    @Test
    public void hoveringKeepsTheBarUp() {
        Scroller s = of(1000, 300);
        s.visibility(0, true);
        assertEquals(1f, s.visibility(5000 * MS, true), 0.001f);
    }

    @Test
    public void barStaysHiddenWhenNothingScrolls() {
        Scroller s = of(100, 300);
        s.visibility(0, true);
        assertEquals(0f, s.visibility(1000 * MS, true), 0f);
    }
}
