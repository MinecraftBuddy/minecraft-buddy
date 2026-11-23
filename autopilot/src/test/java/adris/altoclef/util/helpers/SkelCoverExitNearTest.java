package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

// Tuning cover_exit_near (off by default): the cover's exit at the hp line ("no line for 5 s") waits until no skeleton stands
// within RANGE by position too: shut in there is never a line, and the buddy came out at 19 hp beside two skeletons 1.4 to 4
// blocks off. full hp waits too, and with the key off the exits are as before
public class SkelCoverExitNearTest {

    private static final float LINE = SkelCover.HP_LINE;
    private Path tuning;
    private String tuningWas;

    @Before
    public void on() throws Exception {
        tuningWas = System.getProperty("buddy.tuning");
        tuning = Files.createTempFile("tuning", ".properties");
        System.setProperty("buddy.tuning", tuning.toString());
        set(true);
    }

    @After
    public void off() throws Exception {
        Files.writeString(tuning, "");
        Tuning.now("cover_exit_near", 0);
        Files.deleteIfExists(tuning);
        if (tuningWas == null) {
            System.clearProperty("buddy.tuning");
        } else {
            System.setProperty("buddy.tuning", tuningWas);
        }
        Tuning.now("cover_exit_near", 0);
    }

    private void set(boolean on) throws Exception {
        Files.writeString(tuning, "cover_exit_near=" + (on ? 1 : 0) + "\n");
        Tuning.now("cover_exit_near", 0);
    }

    private static SkelCover.Hold held() {
        SkelCover.Hold h = new SkelCover.Hold();
        h.holdRules(true);
        h.step(0, true, true, 10, LINE, 1, 12, 12, false, null, true, false);
        assertTrue(h.on());
        h.takeNote();
        h.step(10, false, true, 18, LINE, 1, 4, 4, false, null, true, false);
        return h;
    }

    @Test
    public void theHpLineWaitsWhileASkeletonStandsClose() {
        SkelCover.Hold h = held();
        long gone = 10 + SkelCover.LINE_GONE_TICKS;
        h.step(gone, false, true, 18.5f, LINE, 0, Double.POSITIVE_INFINITY, 4, false, null, true, false);
        assertTrue(h.on());
        // the skeleton walked off past RANGE: out at the hp line as before
        h.step(gone + 20, false, true, 18.5f, LINE, 0, Double.POSITIVE_INFINITY, SkelCover.RANGE + 1, false, null, true, false);
        assertFalse(h.on());
        assertEquals("skeleton cover: out of cover: hp 18.5, no line for 5 s", h.takeNote());
    }

    // full hp too: the cave drill's box was left at 20 hp beside three skeletons and a zombie
    @Test
    public void fullHpWaitsTooWhileASkeletonStandsClose() throws Exception {
        SkelCover.Hold h = held();
        h.step(10 + SkelCover.LINE_GONE_TICKS, false, true, 20, LINE, 0, Double.POSITIVE_INFINITY, 4, false, null, true, false);
        assertTrue(h.on());
        h.step(30 + SkelCover.LINE_GONE_TICKS, false, true, 20, LINE, 0, Double.POSITIVE_INFINITY, SkelCover.RANGE + 1, false, null, true, false);
        assertFalse(h.on());

        set(false);
        SkelCover.Hold off = held();
        off.step(10 + SkelCover.LINE_GONE_TICKS, false, true, 20, LINE, 0, Double.POSITIVE_INFINITY, 4, false, null, true, false);
        assertFalse(off.on());
    }

    @Test
    public void withTheKeyOffTheHpLineLetsGoBesideIt() throws Exception {
        set(false);
        SkelCover.Hold h = held();
        h.step(10 + SkelCover.LINE_GONE_TICKS, false, true, 18.5f, LINE, 0, Double.POSITIVE_INFINITY, 4, false, null, true, false);
        assertFalse(h.on());
    }

    @Test
    public void theKeyIsOffByDefault() {
        assertEquals(0.0, Tuning.DEFAULTS.get("cover_exit_near"), 0);
    }
}
