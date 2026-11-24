package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

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

// Tuning cover_melee_hold (off by default): in the skeleton cover where the ground will not dig and the buddy is not shut in,
// adult zombie kinds alone do not break the hold: any number with the four cells at the eye's height solid, exactly one with a
// side of a pillbox under way still open. a baby, a spider, or two zombies at an open side break it as before. with the key off
// the run as before, and a note that the hold would have been kept
public class CombatCommitMeleeHoldTest {

    private static final long NEVER = CombatCommit.NEVER;
    private static final long NOW = 1000;
    private static final int SKELETON = 6;
    private static final int ZOMBIE = 10;
    private static final int SECOND = 11;
    private static final int BABY = 12;
    private static final int SPIDER = 13;
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
        Tuning.now("cover_melee_hold", 0);
        Files.deleteIfExists(tuning);
        if (tuningWas == null) {
            System.clearProperty("buddy.tuning");
        } else {
            System.setProperty("buddy.tuning", tuningWas);
        }
        Tuning.now("cover_melee_hold", 0);
    }

    private void set(boolean on) throws Exception {
        Files.writeString(tuning, "cover_melee_hold=" + (on ? 1 : 0) + "\n");
        Tuning.now("cover_melee_hold", 0);
    }

    private static Foe skeleton(double distance) {
        return new Foe(SKELETON, distance, true, false, NEVER, Kind.NORMAL, Double.POSITIVE_INFINITY, true, NEVER);
    }

    private static Foe melee(int id, double distance) {
        return new Foe(id, distance, false, false, NEVER, Kind.NORMAL, distance - 0.6);
    }

    // hurt in the cover: the low hp run unless the cover holds
    private static Tick tick(Foe... foes) {
        return new Tick(NOW, 6, 0, 0, List.of(foes), null, Way.NONE, true, false);
    }

    // in the cover where the ground will not dig, not shut in: walled or with wallsUp of the pillbox's walls up; the ids of the
    // adult zombie kinds as the brain hands them in (the baby and the spider are not among them)
    private static CombatCommit machine(boolean walled, int wallsUp) {
        CombatCommit c = new CombatCommit();
        c.skelFacts(new CombatCommit.Skel(Set.of(SKELETON), Set.of(SKELETON), true, false, 0, true, SkelCover.CLOSE_HP, SkelCover.RANGE,
                false, false, true, Set.of(), false, walled, wallsUp, Set.of(ZOMBIE, SECOND)));
        return c;
    }

    @Test
    public void keyOffOneZombieAtAnOpenPillboxRunsAsBeforeWithItsNote() throws Exception {
        set(false);
        CombatCommit c = machine(false, 3);
        assertEquals(Event.RUN_START, c.step(tick(skeleton(10), melee(ZOMBIE, 1.5))));
        assertEquals(Why.LOW_HP, c.why());
        assertEquals("cover_melee_hold would hold: 1 zombie [open side]", c.takeHoldNote());
    }

    @Test
    public void walledTwoAdultZombiesHold() {
        CombatCommit c = machine(true, 4);
        assertEquals(Event.NONE, c.step(tick(skeleton(10), melee(ZOMBIE, 1.5), melee(SECOND, 2))));
        assertEquals(Mode.NONE, c.mode());
        assertEquals("cover_melee_hold: held against 2 zombies [walled]", c.takeHoldNote());
    }

    @Test
    public void openSideOneAdultZombieHolds() {
        CombatCommit c = machine(false, 2);
        assertEquals(Event.NONE, c.step(tick(skeleton(10), melee(ZOMBIE, 1.5))));
        assertEquals(Mode.NONE, c.mode());
        assertEquals("cover_melee_hold: held against 1 zombie [open side]", c.takeHoldNote());
    }

    @Test
    public void openSideABabyZombieASpiderOrTwoZombiesStillRun() {
        CombatCommit baby = machine(false, 3);
        assertEquals(Event.RUN_START, baby.step(tick(skeleton(10), melee(BABY, 1.5))));
        assertNull(baby.takeHoldNote());

        CombatCommit spider = machine(false, 3);
        assertEquals(Event.RUN_START, spider.step(tick(skeleton(10), melee(SPIDER, 1.5))));

        CombatCommit two = machine(false, 3);
        assertEquals(Event.RUN_START, two.step(tick(skeleton(10), melee(ZOMBIE, 1.5), melee(SECOND, 2))));
        assertNull(two.takeHoldNote());

        // fewer than two walls up: no pillbox under way to hold in
        CombatCommit bare = machine(false, 1);
        assertEquals(Event.RUN_START, bare.step(tick(skeleton(10), melee(ZOMBIE, 1.5))));
    }

    // walled, a spider among the zombies, or a creeper close, is still the run
    @Test
    public void walledASpiderOrACreeperCloseIsStillTheRun() {
        CombatCommit spider = machine(true, 4);
        assertEquals(Event.RUN_START, spider.step(tick(skeleton(10), melee(ZOMBIE, 1.5), melee(SPIDER, 2))));

        CombatCommit creeper = machine(true, 4);
        assertEquals(Event.RUN_START, creeper.step(tick(skeleton(10), melee(ZOMBIE, 1.5), new Foe(20, 4, false, true, NEVER))));
    }

    @Test
    public void theKeyIsOffByDefault() {
        assertEquals(0.0, Tuning.DEFAULTS.get("cover_melee_hold"), 0);
    }
}
