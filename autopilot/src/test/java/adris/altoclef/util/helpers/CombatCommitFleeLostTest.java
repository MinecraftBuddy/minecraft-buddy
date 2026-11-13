package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CombatCommit.Tick;
import adris.altoclef.util.helpers.CombatCommit.Way;
import adris.altoclef.util.helpers.CombatCommit.Why;
import adris.altoclef.util.helpers.DangerMeter.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

// S137, the repair after smoke8 (Tuning risk_flee_lost, on under risk_flee): the meter's run whose own flee path is expected
// to cost the hp left for NO_WAY_TICKS turns on the closest melee mob within NO_WAY_RANGE, armed, and holds that fight.
// smoke8 r4 fled two wither skeletons along a bridge to its end and back past them, its paths priced 15 to 52 hp at 9.6 hp,
// and turned only at the run's cap at 2.1 hp
public class CombatCommitFleeLostTest {

    private static final long NOW = 1000;

    private String tuningWas;
    private Path tuning;

    @Before
    public void setUp() throws Exception {
        tuningWas = System.getProperty("buddy.tuning");
        tuning = Files.createTempFile("tuning", ".properties");
        System.setProperty("buddy.tuning", tuning.toString());
        keys("risk_flee=1\n");
    }

    @After
    public void tearDown() throws Exception {
        keys("");
        Files.deleteIfExists(tuning);
        if (tuningWas == null) {
            System.clearProperty("buddy.tuning");
        } else {
            System.setProperty("buddy.tuning", tuningWas);
        }
    }

    private void keys(String text) throws Exception {
        Files.writeString(tuning, text);
        Tuning.now("risk_flee", 0);
    }

    private static Foe wither(int id, double distance) {
        return new Foe(id, distance, false, false, CombatCommit.NEVER);
    }

    private static Foe skeleton(int id, double distance) {
        return new Foe(id, distance, true, false, CombatCommit.NEVER);
    }

    private static Foe blaze(int id, double distance) {
        return new Foe(id, distance, true, false, CombatCommit.NEVER, CombatCommit.Kind.FLYER);
    }

    private static Tick tick(long now, float hp, double x, boolean armed, Foe... foes) {
        return new Tick(now, hp, x, 0, List.of(foes), null, Way.NONE, armed);
    }

    // a run for the meter at full hp from a wither skeleton on us, then on its way along the bridge
    private static CombatCommit running() {
        CombatCommit c = new CombatCommit();
        c.meter(Level.FLEE);
        assertEquals(Event.RUN_START, c.step(tick(NOW, 17, 0, true, wither(1, 1))));
        assertEquals(Why.DANGER, c.why());
        return c;
    }

    @Test
    public void aFleeWhosePathCostsTheHpLeftTurnsOnTheChaser() {
        CombatCommit c = running();
        c.meter(Level.FLEE, false, false, 15, 9.6);
        assertEquals(Event.NONE, c.step(tick(NOW + 40, 9.6f, 20, true, wither(1, 9.3))));
        assertEquals(Mode.RUN, c.mode());
        c.meter(Level.FLEE, false, false, 30, 9.6);
        assertEquals(Event.RUN_CHASED_FIGHT, c.step(tick(NOW + 50, 9.6f, 21, true, wither(1, 9.3))));
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(1, c.targetId());
        // the fight holds while the skeleton lives within NO_WAY_RANGE, whatever the meter reads
        c.meter(Level.FLEE);
        c.step(new Tick(NOW + 55, 9.6f, 21, 0, List.of(wither(1, 12)), wither(1, 12), Way.NONE, true));
        assertEquals(Mode.FIGHT, c.mode());
    }

    @Test
    public void aFleeWhosePathCostsLessGoesOn() {
        CombatCommit c = running();
        c.meter(Level.FLEE, false, false, 3.8, 9.6);
        c.step(tick(NOW + 40, 9.6f, 20, true, wither(1, 9.3)));
        c.step(tick(NOW + 60, 9.6f, 22, true, wither(1, 9.3)));
        assertEquals(Mode.RUN, c.mode());
    }

    @Test
    public void aReplanWithNoWordYetKeepsTheLastOne() {
        CombatCommit c = running();
        c.meter(Level.FLEE, false, false, 15, 9.6);
        c.step(tick(NOW + 40, 9.6f, 20, true, wither(1, 9.3)));
        // the path cancelled for more risk, the new one not checked yet
        c.meter(Level.FLEE, false, false, Double.NaN, 9.6);
        assertEquals(Event.RUN_CHASED_FIGHT, c.step(tick(NOW + 50, 9.6f, 21, true, wither(1, 9.3))));
    }

    @Test
    public void aWordOfWayOutStartsTheClockAgain() {
        CombatCommit c = running();
        c.meter(Level.FLEE, false, false, 15, 9.6);
        c.step(tick(NOW + 40, 9.6f, 20, true, wither(1, 9.3)));
        c.meter(Level.FLEE, false, false, 2, 9.6);
        c.step(tick(NOW + 45, 9.6f, 21, true, wither(1, 9.3)));
        c.meter(Level.FLEE, false, false, 15, 9.6);
        assertEquals(Event.NONE, c.step(tick(NOW + 50, 9.6f, 22, true, wither(1, 9.3))));
        assertEquals(Mode.RUN, c.mode());
    }

    @Test
    public void noTurnUnarmedNorOnAShooterNorPastTheRange() {
        CombatCommit bare = running();
        bare.meter(Level.FLEE, false, false, 15, 9.6);
        bare.step(tick(NOW + 40, 9.6f, 20, false, wither(1, 9.3)));
        assertNotEquals(Event.RUN_CHASED_FIGHT, bare.step(tick(NOW + 50, 9.6f, 21, false, wither(1, 9.3))));

        CombatCommit shot = new CombatCommit();
        shot.meter(Level.FLEE);
        shot.step(tick(NOW, 17, 0, true, skeleton(2, 6)));
        shot.meter(Level.FLEE, false, false, 15, 9.6);
        shot.step(tick(NOW + 40, 9.6f, 20, true, skeleton(2, 9)));
        assertEquals(Mode.RUN, shot.mode());
        shot.step(tick(NOW + 50, 9.6f, 21, true, skeleton(2, 9)));
        assertEquals(Mode.RUN, shot.mode());

        CombatCommit far = running();
        far.meter(Level.FLEE, false, false, 15, 9.6);
        far.step(tick(NOW + 40, 9.6f, 20, true, wither(1, 17)));
        far.step(tick(NOW + 50, 9.6f, 21, true, wither(1, 17)));
        assertEquals(Mode.RUN, far.mode());
    }

    @Test
    public void offTheRunGoesOn() throws Exception {
        keys("risk_flee=1\nrisk_flee_lost=0\n");
        CombatCommit c = running();
        c.meter(Level.FLEE, false, false, 15, 9.6);
        c.step(tick(NOW + 40, 9.6f, 20, true, wither(1, 9.3)));
        c.step(tick(NOW + 50, 9.6f, 21, true, wither(1, 9.3)));
        assertEquals(Mode.RUN, c.mode());
    }
    // the repair after smoke9 (Tuning risk_blaze_bite): a blaze in its bite during the run is a melee chaser, so a lost flee
    // turns on it; as a shooter it is not. smoke9 r4: two blazes bit through a run that fled 1 to 2 blocks, 13.9 hp to 0 in 4 s
    @Test
    public void aBlazeInItsBiteIsAChaserALostFleeTurnsOn() {
        CombatCommit c = new CombatCommit();
        c.meter(Level.FLEE);
        assertEquals(Event.RUN_START, c.step(tick(NOW, 13.9f, 0, true, blaze(3, 1.5).inBite())));
        c.meter(Level.FLEE, false, false, 15, 9.6);
        c.step(tick(NOW + 40, 9.6f, 1, true, blaze(3, 1.6).inBite()));
        assertEquals(Event.RUN_CHASED_FIGHT, c.step(tick(NOW + 50, 9.6f, 2, true, blaze(3, 1.6).inBite())));
        assertEquals(3, c.targetId());

        CombatCommit shooter = new CombatCommit();
        shooter.meter(Level.FLEE);
        shooter.step(tick(NOW, 13.9f, 0, true, blaze(3, 1.5)));
        shooter.meter(Level.FLEE, false, false, 15, 9.6);
        shooter.step(tick(NOW + 40, 9.6f, 1, true, blaze(3, 1.6)));
        assertNotEquals(Event.RUN_CHASED_FIGHT, shooter.step(tick(NOW + 50, 9.6f, 2, true, blaze(3, 1.6))));
    }
}
