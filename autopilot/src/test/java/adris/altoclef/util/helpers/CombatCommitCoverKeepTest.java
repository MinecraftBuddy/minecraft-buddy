package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;

import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Kind;
import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CombatCommit.Tick;
import adris.altoclef.util.helpers.CombatCommit.Way;
import adris.altoclef.util.helpers.CombatCommit.Why;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

// Tuning cover_keep (off by default; test vlucht): the skeleton cover under way is not left for a low hp, shot or crowd run while
// the threat is arrows alone (no melee mob within 4 or on the buddy, no creeper within 6). here the pillbox from the bag (the
// ground does not dig) with a zombie 6 off: the old rules ran out of it
public class CombatCommitCoverKeepTest {

    private static final long NEVER = CombatCommit.NEVER;
    private static final long NOW = 1000;
    private static final int SKELETON = 6;
    private static final int SECOND = 7;
    private static final int THIRD = 8;
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
        Tuning.now("cover_keep", 0);
        Files.deleteIfExists(tuning);
        if (tuningWas == null) {
            System.clearProperty("buddy.tuning");
        } else {
            System.setProperty("buddy.tuning", tuningWas);
        }
        Tuning.now("cover_keep", 0);
    }

    private void set(boolean on) throws Exception {
        Files.writeString(tuning, "cover_keep=" + (on ? 1 : 0) + "\n");
        Tuning.now("cover_keep", 0);
    }

    private static Foe skeleton(int id, double distance, long sinceHit) {
        return new Foe(id, distance, true, false, sinceHit, Kind.NORMAL, Double.POSITIVE_INFINITY, true, NEVER);
    }

    private static Foe zombie(double distance, long sinceHit) {
        return new Foe(ZOMBIE, distance, false, false, sinceHit, Kind.NORMAL, distance - 0.6);
    }

    private static Tick tick(float hp, Foe... foes) {
        return new Tick(NOW, hp, 0, 0, List.of(foes), null, Way.NONE, true, false);
    }

    // the cover takes the buddy, the ground does not dig (a pillbox from the bag), lava near or not
    private static CombatCommit machine(boolean held, boolean lava) {
        CombatCommit c = new CombatCommit();
        c.skelFacts(new CombatCommit.Skel(Set.of(SKELETON, SECOND, THIRD), Set.of(SKELETON, SECOND, THIRD), true, false, 0, true,
                SkelCover.CLOSE_HP, SkelCover.RANGE, false, lava, true));
        c.fleeFacts(true, false, held, SkelCover.HP_LINE);
        return c;
    }

    // two skeletons shooting, a zombie 6 off that has not hit: a low hp run
    private static Foe[] scene(Foe... more) {
        Foe[] base = {skeleton(SKELETON, 10, 20), skeleton(SECOND, 12, 30), zombie(6, NEVER)};
        Foe[] all = java.util.Arrays.copyOf(base, base.length + more.length);
        System.arraycopy(more, 0, all, base.length, more.length);
        return all;
    }

    @Test
    public void theCoverUnderWayIsKeptFromArrowsAlone() {
        CombatCommit c = machine(true, false);
        assertEquals(Event.NONE, c.step(tick(7, scene())));
        assertEquals(Mode.NONE, c.mode());
        assertEquals("skeleton cover kept: arrows alone, no melee mob within 4 [LOW_HP] (cover_keep)", c.takeNote());
    }

    @Test
    public void withTheSwitchOffItIsTheRun() throws Exception {
        set(false);
        CombatCommit c = machine(true, false);
        assertEquals(Event.RUN_START, c.step(tick(7, scene())));
        assertEquals(Why.LOW_HP, c.why());
    }

    // only wanted on this tick, not under way: the run as before
    @Test
    public void aCoverNotYetUnderWayKeepsTheRun() {
        assertEquals(Event.RUN_START, machine(false, false).step(tick(7, scene())));
    }

    @Test
    public void aMeleeMobWithinFourOrOnTheBuddyKeepsTheRun() {
        Foe[] close = {skeleton(SKELETON, 10, 20), skeleton(SECOND, 12, 30), zombie(3.5, NEVER)};
        assertEquals(Event.RUN_START, machine(true, false).step(tick(7, close)));
        Foe[] hitUs = {skeleton(SKELETON, 10, 20), skeleton(SECOND, 12, 30), zombie(5, 40)};
        assertEquals(Event.RUN_START, machine(true, false).step(tick(7, hitUs)));
    }

    @Test
    public void aCreeperWithinSixOrLavaKeepsTheRun() {
        assertEquals(Event.RUN_START, machine(true, false).step(tick(7, scene(new Foe(11, 5, false, true, NEVER)))));
        assertEquals(Event.RUN_START, machine(true, true).step(tick(7, scene())));
    }

    // three skeletons that hit from within 6 are a crowd: no run out of the cover for them either
    @Test
    public void aCrowdOfSkeletonsKeepsTheCover() throws Exception {
        Foe[] crowd = {skeleton(SKELETON, 5, 20), skeleton(SECOND, 5.5, 30), skeleton(THIRD, 5.8, 40), zombie(7, NEVER)};
        CombatCommit c = machine(true, false);
        assertEquals(Event.NONE, c.step(tick(15, crowd)));
        assertEquals("skeleton cover kept: arrows alone, no melee mob within 4 [CROWD] (cover_keep)", c.takeNote());
        set(false);
        CombatCommit off = machine(true, false);
        assertEquals(Event.RUN_START, off.step(tick(15, crowd)));
        assertEquals(Why.CROWD, off.why());
    }

    // the pure rule
    @Test
    public void arrowsAloneAsPlainNumbers() {
        assertEquals(true, CombatCommit.arrowsAlone(List.of(skeleton(SKELETON, 3, 5), zombie(4.5, NEVER)), 10));
        assertEquals(false, CombatCommit.arrowsAlone(List.of(skeleton(SKELETON, 3, 5), zombie(4, NEVER)), 10));
        assertEquals(false, CombatCommit.arrowsAlone(List.of(zombie(5.5, 60)), 10));
        assertEquals(false, CombatCommit.arrowsAlone(List.of(new Foe(11, 6, false, true, NEVER)), 10));
        assertEquals(true, CombatCommit.arrowsAlone(List.of(new Foe(11, 6.5, false, true, NEVER)), 10));
    }
}
