package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Kind;
import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CombatCommit.Tick;
import adris.altoclef.util.helpers.CombatCommit.Way;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

// Tuning flee_arrow_dig (off by default; test vlucht): a run under the cover's hp line that took a skeleton's arrow digs in where
// the ground digs, the threat arrows alone, whether or not the cover took the buddy that tick (a failed cover's retry), and the
// arrow counts for a second, not the 5 ticks its knock spends in the air; the brain takes the cover on hearing it
public class CombatCommitArrowDigTest {

    private static final long NEVER = CombatCommit.NEVER;
    private static final long NOW = 1000;
    private static final int SKELETON = 6;
    private static final int SECOND = 7;
    private static final int ZOMBIE = 10;
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
        Tuning.now("flee_arrow_dig", 0);
        Files.deleteIfExists(tuning);
        if (tuningWas == null) {
            System.clearProperty("buddy.tuning");
        } else {
            System.setProperty("buddy.tuning", tuningWas);
        }
        Tuning.now("flee_arrow_dig", 0);
    }

    private void set(boolean on) throws Exception {
        Files.writeString(tuning, "flee_arrow_dig=" + (on ? 1 : 0) + "\n");
        Tuning.now("flee_arrow_dig", 0);
    }

    private static Foe skeleton(double distance, long sinceHit) {
        return new Foe(SKELETON, distance, true, false, sinceHit, Kind.NORMAL, Double.POSITIVE_INFINITY, true, NEVER);
    }

    private static Foe second(double distance) {
        return new Foe(SECOND, distance, true, false, 30, Kind.NORMAL, Double.POSITIVE_INFINITY, true, NEVER);
    }

    private static Foe zombie(double distance) {
        return new Foe(ZOMBIE, distance, false, false, NEVER, Kind.NORMAL, distance - 0.6);
    }

    // a tick a run is walking, half a block a tick so the stuck rule has nothing to say
    private static Tick walking(long now, float hp, Foe... foes) {
        return new Tick(now, hp, (now - NOW) * 0.5, 0, List.of(foes), null, Way.NONE, true, true);
    }

    private static CombatCommit.Skel facts(boolean cover, boolean dig) {
        return new CombatCommit.Skel(Set.of(SKELETON, SECOND), Set.of(SKELETON, SECOND), true, false, 0, cover, SkelCover.CLOSE_HP,
                SkelCover.RANGE, dig, false, true);
    }

    // a run begun with no cover to be had (a cover that failed lately waits its retry): the cover still does not take the buddy
    private static CombatCommit running(float coverLine) {
        CombatCommit c = new CombatCommit();
        c.skelFacts(facts(false, true));
        c.fleeFacts(true, false, false, coverLine);
        assertEquals(Event.RUN_START, c.step(walking(NOW, 7, skeleton(10, 20), second(12), zombie(6))));
        assertEquals(Mode.RUN, c.mode());
        return c;
    }

    @Test
    public void anArrowInTheRunDigsInThoughTheCoverDidNotTake() {
        CombatCommit c = running(SkelCover.HP_LINE);
        assertEquals(Event.RUN_TO_COVER, c.step(walking(NOW + 10, 6, skeleton(10, 3), second(12), zombie(6))));
        // (the step after the run ends counts the cover as taken: no run straight back)
        assertEquals(Mode.NONE, c.mode());
        assertTrue(c.arrowDug());
        // (the next step says it no more: the brain took the cover on this one)
        c.step(walking(NOW + 11, 6, skeleton(10, 4), second(12), zombie(6)));
        assertFalse(c.arrowDug());
        assertEquals(Mode.NONE, c.mode());
    }

    @Test
    public void withTheSwitchOffTheRunGoesOn() throws Exception {
        set(false);
        CombatCommit c = running(SkelCover.HP_LINE);
        assertEquals(Event.NONE, c.step(walking(NOW + 10, 6, skeleton(10, 3), second(12), zombie(6))));
        assertEquals(Mode.RUN, c.mode());
        assertFalse(c.arrowDug());
    }

    // the arrow's knock keeps the buddy in the air past the old 5 ticks: with the key it counts for 20
    @Test
    public void theArrowCountsForASecond() throws Exception {
        CombatCommit c = running(SkelCover.HP_LINE);
        c.skelFacts(facts(true, true));
        assertEquals(Event.RUN_TO_COVER, c.step(walking(NOW + 20, 6, skeleton(10, 12), second(12), zombie(6))));
        assertFalse(c.arrowDug());
        set(false);
        CombatCommit off = running(SkelCover.HP_LINE);
        off.skelFacts(facts(true, true));
        assertEquals(Event.NONE, off.step(walking(NOW + 20, 6, skeleton(10, 12), second(12), zombie(6))));
        assertEquals(Mode.RUN, off.mode());
    }

    @Test
    public void aMeleeMobWithinFourKeepsTheRun() {
        CombatCommit c = running(SkelCover.HP_LINE);
        assertEquals(Event.NONE, c.step(walking(NOW + 10, 6, skeleton(10, 3), second(12), zombie(3.5))));
        assertEquals(Mode.RUN, c.mode());
    }

    @Test
    public void overTheCoverLineOrGroundThatWillNotDigKeepsTheRun() {
        CombatCommit c = running(5);
        assertEquals(Event.NONE, c.step(walking(NOW + 10, 6, skeleton(10, 3), second(12), zombie(6))));
        CombatCommit d = running(SkelCover.HP_LINE);
        d.skelFacts(facts(false, false));
        assertEquals(Event.NONE, d.step(walking(NOW + 10, 6, skeleton(10, 3), second(12), zombie(6))));
        assertEquals(Mode.RUN, d.mode());
    }

    // the brain's side: the cover taken though a failed one waits its retry and no skeleton has a line this tick
    @Test
    public void theHoldIsTakenWhateverItsRetry() {
        SkelCover.Hold h = new SkelCover.Hold();
        h.step(0, true, true, 10, SkelCover.HP_LINE, 1, 12, 12, false, null, true, false);
        assertTrue(h.on());
        h.step(1, true, true, 10, SkelCover.HP_LINE, 1, 12, 12, false, "the dig failed", true, false);
        assertFalse(h.on());
        assertFalse(h.takes(50, true, true));
        h.force(50, 7);
        assertTrue(h.on());
        assertEquals("skeleton cover: an arrow in the run at 7.0 hp, taking cover where the buddy stands (flee_arrow_dig)", h.takeNote());
        h.step(51, false, true, 7, SkelCover.HP_LINE, 0, Double.POSITIVE_INFINITY, 30, false, null, true, false);
        assertTrue(h.on());
    }
}
