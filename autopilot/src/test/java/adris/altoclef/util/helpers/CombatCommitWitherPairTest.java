package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Kind;
import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CombatCommit.Tick;
import adris.altoclef.util.helpers.CombatCommit.Way;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

// Tuning wither_pair_fight (off by default): two wither skeletons on our floor are fought where we stand down to the
// company line, three stay a run; with the switch off a pair is the run it always was
public class CombatCommitWitherPairTest {

    private static final long NEVER = CombatCommit.NEVER;
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
        Tuning.now("wither_pair_fight", 0);
        Files.deleteIfExists(tuning);
        if (tuningWas == null) {
            System.clearProperty("buddy.tuning");
        } else {
            System.setProperty("buddy.tuning", tuningWas);
        }
        Tuning.now("wither_pair_fight", 0);
    }

    private void set(boolean pair) throws Exception {
        Files.writeString(tuning, "wither_pair_fight=" + (pair ? 1 : 0) + "\n");
        Tuning.now("wither_pair_fight", 0);
    }

    private static Foe witherAt(int id, double distance, double y) {
        return new Foe(id, distance, false, false, NEVER, Kind.NORMAL, Double.POSITIVE_INFINITY, true, NEVER, false, null, false, 0, y, 0,
                false, false, false, true);
    }

    // one in contact that hit us lately (both on us: the company line of two, 10)
    private static Foe onUs(int id) {
        return new Foe(id, 1.5, false, false, 3, Kind.NORMAL, Double.POSITIVE_INFINITY, true, NEVER, false, null, false, 0, 0, 0,
                false, false, false, true);
    }

    private static Tick tick(float hp, List<Foe> foes) {
        return new Tick(0, hp, 0, 0, foes, null, Way.NONE, true);
    }

    private static Tick fightTick(long now, float hp, List<Foe> foes, Foe target) {
        return new Tick(now, hp, 0, 0, foes, target, Way.NONE, true, false, false, RecoverHold.Body.NONE, false, 4.5f, false, true);
    }

    @Test
    public void aPairIsFoughtTheNearestFirst() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(20, List.of(witherAt(1, 6, 0), witherAt(2, 3, 0)))));
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(2, c.targetId());
    }

    @Test
    public void offAPairIsStillARun() throws Exception {
        set(false);
        assertEquals(Event.RUN_START, new CombatCommit().step(tick(20, List.of(witherAt(1, 3, 0), witherAt(2, 6, 0)))));
    }

    @Test
    public void threeAreStillARun() {
        List<Foe> pack = List.of(witherAt(1, 3, 0), witherAt(2, 7, 1), witherAt(3, 9, 0));
        assertEquals(Event.RUN_START, new CombatCommit().step(tick(20, pack)));
    }

    @Test
    public void notPickedAtTheLine() {
        assertEquals(Event.RUN_START, new CombatCommit().step(tick(CombatCommit.WITHER_PAIR_LINE + 2, List.of(witherAt(1, 3, 0), witherAt(2, 6, 0)))));
    }

    @Test
    public void theFightHoldsAboveTheLineAndEndsAtIt() {
        CombatCommit c = new CombatCommit();
        Foe w = witherAt(1, 3, 0);
        Foe other = witherAt(2, 6, 0);
        c.step(tick(20, List.of(w, other)));
        // both on us with the wither ticking: 13 less the fight's 2 is above the company line of 10, so the fight goes on
        Foe a = onUs(1);
        Foe b = onUs(2);
        assertEquals(Event.NONE, c.step(fightTick(1, 13, List.of(a, b), a)));
        assertEquals(Mode.FIGHT, c.mode());
        // 11.5 is under it: the run
        Event e = c.step(fightTick(2, 11.5f, List.of(a, b), a));
        assertNotEquals(Mode.FIGHT, c.mode());
        assertNotEquals(Event.NONE, e);
    }
}
