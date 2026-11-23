package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;

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

// Tuning skel_cover_bail (off by default): a fight's bail for low hp or arrows, with a skeleton that shot us lately and still has
// a line, goes into the cover where it holds, as idle() does before the same run; never with a melee mob in contact unless the
// buddy is shut in already. with the key off the run as before, and a note that the cover would have been taken
public class CombatCommitSkelBailTest {

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
        Tuning.now("skel_cover_bail", 0);
        Files.deleteIfExists(tuning);
        if (tuningWas == null) {
            System.clearProperty("buddy.tuning");
        } else {
            System.setProperty("buddy.tuning", tuningWas);
        }
        Tuning.now("skel_cover_bail", 0);
    }

    private void set(boolean on) throws Exception {
        Files.writeString(tuning, "skel_cover_bail=" + (on ? 1 : 0) + "\n");
        Tuning.now("skel_cover_bail", 0);
    }

    // a skeleton with a line that shot us sinceHit ago
    private static Foe skeleton(double distance, long sinceHit) {
        return new Foe(SKELETON, distance, true, false, sinceHit, Kind.NORMAL, Double.POSITIVE_INFINITY, true, NEVER);
    }

    // a second skeleton with a line, its last arrow 30 ticks ago (two of them: no one-shooter fight to fall back on)
    private static Foe second(double distance) {
        return new Foe(SECOND, distance, true, false, 30, Kind.NORMAL, Double.POSITIVE_INFINITY, true, NEVER);
    }

    private static Foe zombie(double distance, long sinceHit) {
        return new Foe(ZOMBIE, distance, false, false, sinceHit, Kind.NORMAL, distance - 0.6);
    }

    private static Tick tick(long now, float hp, Foe target, Foe... foes) {
        return new Tick(now, hp, 0, 0, List.of(foes), target, Way.NONE, true, false);
    }

    // the brain's facts: the cover takes the buddy or not, the ground digs, shut in or not
    private static CombatCommit.Skel facts(boolean cover, boolean shut) {
        return new CombatCommit.Skel(Set.of(SKELETON, SECOND), Set.of(SKELETON, SECOND), true, true, 0, cover, SkelCover.CLOSE_HP,
                SkelCover.RANGE, true, false, true, Set.of(), shut);
    }

    // the zombie's hit starts the fight (the brain wants no cover at 18 hp), then the hp falls with the skeletons shooting
    private static CombatCommit fighting(CombatCommit.Skel later) {
        CombatCommit c = new CombatCommit();
        c.skelFacts(facts(false, false));
        Foe z = zombie(2.5, 2);
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 18, z, z, skeleton(9, NEVER))));
        assertEquals(ZOMBIE, c.targetId());
        c.skelFacts(later);
        return c;
    }

    @Test
    public void aLowHpBailWithASkeletonShootingIsTheCover() throws Exception {
        CombatCommit c = fighting(facts(true, false));
        Foe z = zombie(4.5, 10);
        assertEquals(Event.FIGHT_TO_COVER, c.step(tick(NOW + 20, 6, z, z, skeleton(6, 3), second(9))));
        assertEquals(Mode.NONE, c.mode());
        // (the same tick goes on in idle(), whose cover note is the one left: the cover is the brain's from here)
        assertEquals("skeleton cover instead of a run [LOW_HP]", c.takeNote());
    }

    @Test
    public void withTheKeyOffTheRunAsBeforeAndItsNote() throws Exception {
        set(false);
        CombatCommit c = fighting(facts(true, false));
        Foe z = zombie(4.5, 10);
        assertEquals(Event.FIGHT_TO_RUN, c.step(tick(NOW + 20, 6, z, z, skeleton(6, 3), second(9))));
        assertEquals(Mode.RUN, c.mode());
        assertEquals("skel_cover_bail would cover: skeleton #" + SKELETON + " shot us [LOW_HP]", c.takeNote());
    }

    // a zombie in contact can drop into the open hole: the run as before, unless the buddy is shut in already
    @Test
    public void aZombieInContactKeepsTheRunUnlessShutIn() throws Exception {
        CombatCommit c = fighting(facts(true, false));
        Foe z = zombie(2.0, 10);
        assertEquals(Event.FIGHT_TO_RUN, c.step(tick(NOW + 20, 6, z, z, skeleton(6, 3), second(9))));

        CombatCommit shut = fighting(facts(true, true));
        assertEquals(Event.FIGHT_TO_COVER, shut.step(tick(NOW + 20, 6, z, z, skeleton(6, 3), second(9))));
    }

    // no skeleton shot us lately (only a line): no reason to take the cover from a fight's bail
    @Test
    public void aSkeletonThatHasNotShotKeepsTheRun() throws Exception {
        CombatCommit c = fighting(facts(true, false));
        Foe z = zombie(4.5, 10);
        Foe quiet = new Foe(SKELETON, 6, true, false, NEVER, Kind.NORMAL, Double.POSITIVE_INFINITY, true, NEVER);
        Foe quiet2 = new Foe(SECOND, 9, true, false, NEVER, Kind.NORMAL, Double.POSITIVE_INFINITY, true, NEVER);
        assertEquals(Event.FIGHT_TO_RUN, c.step(tick(NOW + 20, 6, z, z, quiet, quiet2)));
    }

    @Test
    public void theKeyIsOffByDefault() {
        assertEquals(0.0, Tuning.DEFAULTS.get("skel_cover_bail"), 0);
    }
}
