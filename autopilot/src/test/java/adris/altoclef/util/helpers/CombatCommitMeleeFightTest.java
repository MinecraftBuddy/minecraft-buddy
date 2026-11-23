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

// Tuning cover_melee_fight (off by default): the dug-in cover does not hold while a plain melee mob in contact hit within
// MELEE_FIGHT_TICKS, unless the buddy is shut in already: the zombie that hit is fought first, not dug away from (the cave
// drill: three down with a zombie biting, no top, shot down the shaft)
public class CombatCommitMeleeFightTest {

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
        Tuning.now("cover_melee_fight", 0);
        Files.deleteIfExists(tuning);
        if (tuningWas == null) {
            System.clearProperty("buddy.tuning");
        } else {
            System.setProperty("buddy.tuning", tuningWas);
        }
        Tuning.now("cover_melee_fight", 0);
    }

    private void set(boolean on) throws Exception {
        Files.writeString(tuning, "cover_melee_fight=" + (on ? 1 : 0) + "\n");
        Tuning.now("cover_melee_fight", 0);
    }

    private static Foe skeleton(int id, double distance, long sinceHit) {
        return new Foe(id, distance, true, false, sinceHit, Kind.NORMAL, Double.POSITIVE_INFINITY, true, NEVER);
    }

    private static Foe zombie(double distance, long sinceHit) {
        return new Foe(ZOMBIE, distance, false, false, sinceHit, Kind.NORMAL, distance - 0.6);
    }

    private static Tick tick(long now, float hp, Foe... foes) {
        return new Tick(now, hp, 0, 0, List.of(foes), null, Way.NONE, true, false);
    }

    private static CombatCommit with(boolean shut) {
        CombatCommit c = new CombatCommit();
        c.skelFacts(new CombatCommit.Skel(Set.of(SKELETON, SECOND), Set.of(SKELETON, SECOND), true, true, 0, true, SkelCover.CLOSE_HP,
                SkelCover.RANGE, true, false, true, Set.of(), shut));
        return c;
    }

    @Test
    public void aZombieThatJustBitIsFoughtNotDugAwayFrom() throws Exception {
        Foe[] scene = {zombie(1.5, 2), skeleton(SKELETON, 7, 10), skeleton(SECOND, 9, 30)};
        CombatCommit c = with(false);
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 17, scene)));
        assertEquals(ZOMBIE, c.targetId());

        set(false);
        CombatCommit off = with(false);
        assertEquals(Event.NONE, off.step(tick(NOW, 17, scene)));
        assertEquals(Mode.NONE, off.mode());
        assertEquals("skeleton cover instead of a fight with foe #" + ZOMBIE, off.takeNote());
    }

    // shut in already: the zombie at the walls is no reason to come out
    @Test
    public void shutInTheCoverHolds() {
        CombatCommit c = with(true);
        assertEquals(Event.NONE, c.step(tick(NOW, 17, zombie(1.5, 2), skeleton(SKELETON, 7, 10), skeleton(SECOND, 9, 30))));
        assertEquals(Mode.NONE, c.mode());
    }

    @Test
    public void theKeyIsOffByDefault() {
        assertEquals(0.0, Tuning.DEFAULTS.get("cover_melee_fight"), 0);
    }
}
