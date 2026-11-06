package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Kind;
import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CombatCommit.Tick;
import adris.altoclef.util.helpers.CombatCommit.Way;
import adris.altoclef.util.helpers.CombatCommit.Why;
import java.util.List;
import java.util.Set;
import org.junit.Test;

// a cube or an angry piglin walking up to us on a lava rim is a fight before its first hit (its first hit is the knock into the
// lava), and a run along a rim keeps a leashed shield up for it
public class CombatCommitRimTest {

    private static final long NEVER = CombatCommit.NEVER;
    private static final Way RIM = new Way(Set.of(), true, 64).withRim(true);

    private static Foe cube(int id, double distance) {
        return new Foe(id, distance, false, false, NEVER).withKnocker(true);
    }

    private static Tick tick(long now, float health, List<Foe> foes, Foe target, Way way) {
        return new Tick(now, health, 0, 0, foes, target, way);
    }

    @Test
    public void aCubeCloseOnARimIsAFightBeforeItHits() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(0, 20, List.of(cube(1, 2.5)), null, RIM)));
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(Why.RIM, c.why());
        assertEquals(1, c.targetId());
        assertTrue(CombatLog.line(baritone.api.utils.Dimension.NETHER, Event.FIGHT_START, Why.RIM, false, "magma_cube", "", "", 20, 0).contains("(on a rim,"));
    }

    @Test
    public void theSameCubeOffTheRimIsNothing() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.NONE, c.step(tick(0, 20, List.of(cube(1, 2.5)), null, new Way(Set.of(), true, 64))));
        assertEquals(Mode.NONE, c.mode());
    }

    @Test
    public void aCubeFarOnARimIsNothingYet() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.NONE, c.step(tick(0, 20, List.of(cube(1, 5.5)), null, RIM)));
    }

    @Test
    public void aHoglinOnARimIsNotEngaged() {
        CombatCommit c = new CombatCommit();
        Foe hoglin = new Foe(1, 2.5, false, false, NEVER, Kind.HEAVY).withKnocker(true);
        c.step(tick(0, 20, List.of(hoglin), null, RIM));
        assertTrue(c.why() != Why.RIM);
    }

    @Test
    public void aPlainZombieOnARimIsNotEngaged() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.NONE, c.step(tick(0, 20, List.of(new Foe(1, 2.5, false, false, NEVER)), null, RIM)));
    }

    @Test
    public void lowHpOnARimIsNotAFightToPick() {
        CombatCommit c = new CombatCommit();
        c.step(tick(0, 8, List.of(cube(1, 2.5)), null, RIM));
        assertTrue(c.why() != Why.RIM);
    }

    @Test
    public void aCrowdOnARimIsStillTheCrowdRule() {
        CombatCommit c = new CombatCommit();
        c.step(tick(0, 20, List.of(cube(1, 2), cube(2, 2.2), cube(3, 2.4)), null, RIM));
        assertTrue(c.why() != Why.RIM);
    }

    @Test
    public void theRimFightLetsGoOnceItIsPastSixAndNotHitting() {
        CombatCommit c = new CombatCommit();
        c.step(tick(0, 20, List.of(cube(1, 2.5)), null, RIM));
        Foe near = cube(1, 5.5);
        assertEquals(Mode.FIGHT, c.mode());
        c.step(tick(1, 20, List.of(near), near, RIM));
        assertEquals(Mode.FIGHT, c.mode());
        Foe far = cube(1, 6.5);
        assertEquals(Event.FIGHT_CLEARED, c.step(tick(2, 20, List.of(far), far, RIM)));
        assertEquals(Mode.NONE, c.mode());
    }

    // ---- a cube with lava within GROUND_CLEAR: no fight picked, none kept out of reach

    private static final Way LAVA = new Way(Set.of(), true, 64).withLavaClose(true);
    private static final Way RIM_LAVA = RIM.withLavaClose(true);

    private static Foe realCube(int id, double distance, double gap, long sinceHit) {
        return new Foe(id, distance, false, false, sinceHit, Kind.NORMAL, gap).withSplit("magma_cube", true, 0, 64, 0).withKnocker(true);
    }

    private static Tick boxedTick(long now, List<Foe> foes, Foe target, Way way) {
        return new Tick(now, 20, 0, 0, foes, target, way, true, false, false, RecoverHold.Body.NONE, true);
    }

    @Test
    public void wayCarriesBothFlags() {
        Way both = new Way(Set.of(), true, 64).withLavaClose(true).withRim(true);
        assertTrue(both.rim());
        assertTrue(both.lavaClose());
        assertTrue(both.withLavaClose(false).rim());
        assertFalse(Way.NONE.lavaClose());
    }

    @Test
    public void onlyACubeAsksForTheLavaScan() {
        Foe cube = realCube(1, 4, 3.5, NEVER);
        Foe plain = new Foe(2, 4, false, false, NEVER).withKnocker(true);
        assertTrue(CombatRules.cubeAbout(List.of(plain, cube), null));
        assertTrue(CombatRules.cubeAbout(List.of(), cube));
        assertFalse(CombatRules.cubeAbout(List.of(plain), plain));
        assertFalse(CombatRules.cubeAbout(List.of(), null));
    }

    @Test
    public void aCubeOnARimByLavaIsNoFight() {
        CombatCommit c = new CombatCommit();
        for (long t = 0; t < 40; t++) {
            assertEquals(Event.NONE, c.step(tick(t, 20, List.of(realCube(1, 4, 3.5, NEVER)), null, RIM_LAVA)));
        }
        assertEquals(Mode.NONE, c.mode());
    }

    @Test
    public void aCubeOnARimWithTheLavaFurtherOffIsStillTheRimFight() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(0, 20, List.of(realCube(1, 4, 3.5, NEVER)), null, RIM)));
        assertEquals(Why.RIM, c.why());
    }

    @Test
    public void anAngryPiglinOnARimByLavaIsStillTheRimFight() {
        CombatCommit c = new CombatCommit();
        Foe piglin = new Foe(1, 4, false, false, NEVER, Kind.NORMAL, 3.5).withKnocker(true);
        assertEquals(Event.FIGHT_START, c.step(tick(0, 20, List.of(piglin), null, RIM_LAVA)));
        assertEquals(Why.RIM, c.why());
        // and it isn't let go for being out of reach either, that is a cube's deal
        assertEquals(Event.NONE, c.step(tick(1, 20, List.of(piglin), piglin, RIM_LAVA)));
        assertEquals(Mode.FIGHT, c.mode());
    }

    @Test
    public void aCubeOutOfReachByLavaIsLetGoAndStaysGone() {
        CombatCommit c = new CombatCommit();
        Foe cube = realCube(1, 4, 3.5, NEVER);
        c.step(tick(0, 20, List.of(cube), null, RIM));
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(Event.FIGHT_CLEARED, c.step(tick(1, 20, List.of(cube), cube, LAVA)));
        assertEquals(Mode.NONE, c.mode());
        String note = c.takeNote();
        assertNotNull(note);
        assertTrue(note, note.contains("let go of #1"));
        // long past the cooldown, in contact, rim rule back on: it has not hit us since, so it is scenery
        Foe close = realCube(1, 2.5, 2, NEVER);
        for (long t = 2; t < 300; t++) {
            assertEquals(Event.NONE, c.step(tick(t, 20, List.of(close), null, RIM)));
        }
    }

    @Test
    public void aHitFightLetGoStaysGoneUntilAFreshHitInReach() {
        CombatCommit c = new CombatCommit();
        Foe bit = realCube(1, 2.5, 2, 0);
        assertEquals(Event.FIGHT_START, c.step(tick(0, 20, List.of(bit), null, LAVA)));
        assertEquals(Why.HIT, c.why());
        // the swing knocked it out of reach. the old hit is no news: no fight, even back in contact and even with the memory on
        Foe knocked = realCube(1, 4.5, 3.5, 1);
        assertEquals(Event.FIGHT_CLEARED, c.step(tick(1, 20, List.of(knocked), knocked, LAVA)));
        for (long t = 2; t < 40; t++) {
            assertEquals(Event.NONE, c.step(tick(t, 20, List.of(realCube(1, 2.5, 2, t)), null, LAVA)));
        }
        // a new hit is, cooldown or not
        Foe again = realCube(1, 2, 1.5, 0);
        assertEquals(Event.FIGHT_START, c.step(tick(40, 20, List.of(again), null, LAVA)));
        assertEquals(Why.HIT, c.why());
        assertEquals(1, c.targetId());
        // and in reach it stays a fight
        for (long t = 41; t < 100; t++) {
            assertEquals(Event.NONE, c.step(tick(t, 20, List.of(again), again, LAVA)));
        }
        assertEquals(Mode.FIGHT, c.mode());
    }

    // between the task's reach and the arm's the task still won't swing, so that gap can't be a place to wait either
    @Test
    public void theLineIsTheTasksReachNotTheArms() {
        CombatCommit in = new CombatCommit();
        Foe bit = realCube(1, 2.5, 2, 0);
        in.step(tick(0, 20, List.of(bit), null, LAVA));
        Foe justIn = realCube(1, 3, 2.6, 1);
        assertEquals(Event.NONE, in.step(tick(1, 20, List.of(justIn), justIn, LAVA)));
        assertEquals(Mode.FIGHT, in.mode());

        CombatCommit out = new CombatCommit();
        out.step(tick(0, 20, List.of(bit), null, LAVA));
        Foe justOut = realCube(1, 3, 2.8, 1);
        assertTrue(justOut.inReach());
        assertEquals(Event.FIGHT_CLEARED, out.step(tick(1, 20, List.of(justOut), justOut, LAVA)));
    }

    @Test
    public void boxedInItKeepsFightingAndLetsGoOnceTheBoxOpens() {
        CombatCommit c = new CombatCommit();
        Foe cube = realCube(1, 4, 3.5, NEVER);
        c.step(tick(0, 20, List.of(cube), null, RIM));
        for (long t = 1; t < 20; t++) {
            assertEquals(Event.NONE, c.step(boxedTick(t, List.of(cube), cube, LAVA)));
        }
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(Event.FIGHT_CLEARED, c.step(tick(20, 20, List.of(cube), cube, LAVA)));
    }

    @Test
    public void noLavaNoLettingGo() {
        CombatCommit c = new CombatCommit();
        Foe cube = realCube(1, 4, 3.5, NEVER);
        c.step(tick(0, 20, List.of(cube), null, RIM));
        assertEquals(Event.NONE, c.step(tick(1, 20, List.of(cube), cube, RIM)));
        assertEquals(Mode.FIGHT, c.mode());
    }

    // under the hp line a fast cube is the cornered fight, and the let go must not hand it straight back to that rule
    @Test
    public void aFastCornerLetGoDoesNotComeRightBack() {
        long now = 10_000;
        CombatCommit c = new CombatCommit();
        Foe bit = realCube(1, 2, 1.5, 0).withFast(true);
        assertEquals(Event.RUN_TO_FIGHT, c.step(tick(now, 6, List.of(bit), null, LAVA)));
        assertEquals(Mode.FIGHT, c.mode());
        Foe knocked = realCube(1, 3.5, 3.5, 1).withFast(true);
        // under the line the low hp run takes over on the same step
        assertEquals(Event.RUN_START, c.step(tick(now + 1, 6, List.of(knocked), knocked, LAVA)));
        assertEquals(Event.FIGHT_CLEARED, c.endedFirst());
        for (long t = 2; t < 8; t++) {
            c.step(tick(now + t, 6, List.of(realCube(1, 3.5, 3.5, t).withFast(true)), null, LAVA));
            assertEquals(Mode.RUN, c.mode());
        }
    }

    @Test
    public void aFightFromAStuckRunIsNotLetGo() {
        long now = 10_000;
        CombatCommit c = new CombatCommit();
        // in the aura's reach and past the task's, the one gap where the let go and the no room rule both want it
        Foe cube = realCube(1, 2.9, 2.8, NEVER);
        c.step(new Tick(now, 6, 0, 0, List.of(cube), null, LAVA, true, true));
        assertEquals(Mode.RUN, c.mode());
        long t = now + 1;
        while (c.mode() == Mode.RUN && t < now + CombatCommit.NO_ROOM_TICKS + 5) {
            c.step(new Tick(t++, 6, 0, 0, List.of(cube), null, LAVA, true, true));
        }
        assertEquals(Mode.FIGHT, c.mode());
        for (long end = t + 5; t < end; t++) {
            assertEquals(Event.NONE, c.step(new Tick(t, 6, 0, 0, List.of(cube), cube, LAVA, true, true)));
        }
        assertEquals(Mode.FIGHT, c.mode());
    }

    private static Tick lingeringTick(long now, Foe cube, Foe target, Way way) {
        return new Tick(now, 14, 0, 0, List.of(cube), target, way, true, false, false, new RecoverHold.Body(20, false, false, false, true), false);
    }

    @Test
    public void lettingGoIsNoHealHold() {
        CombatCommit c = new CombatCommit();
        Foe cube = realCube(1, 4, 3.5, NEVER);
        c.step(lingeringTick(0, cube, null, RIM));
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(Event.FIGHT_CLEARED, c.step(lingeringTick(1, cube, cube, LAVA)));
        assertFalse(c.recovering());
    }

    // the same body after a rim fight that ends with no lava about does hold, or the test above proves nothing
    @Test
    public void aPlainRimReleaseStillHolds() {
        CombatCommit c = new CombatCommit();
        c.step(lingeringTick(0, realCube(1, 4, 3.5, NEVER), null, RIM));
        Foe far = realCube(1, 6.5, 6, NEVER);
        // (the hold's own rerun rule hears about the cube on the same step, that is what a hold is for)
        assertEquals(Event.RUN_START, c.step(lingeringTick(1, far, far, RIM)));
        assertEquals(Event.FIGHT_CLEARED, c.endedFirst());
        assertTrue(c.recovering());
    }

    @Test
    public void theIdleRimShieldSkipsACubeItCannotHitByLava() {
        Foe out = realCube(1, 3, 3.5, 5);
        Foe in = realCube(1, 3, 2.5, 5);
        Foe plain = new Foe(2, 3, false, false, NEVER, Kind.NORMAL, 3.5);
        assertTrue(CombatRules.rimShield(true, false, List.of(out)));
        assertFalse(CombatRules.rimShield(true, false, List.of(out), true));
        assertTrue(CombatRules.rimShield(true, false, List.of(in), true));
        assertTrue(CombatRules.rimShield(true, false, List.of(out, plain), true));
        assertTrue(CombatRules.rimShield(true, true, List.of(out), true));
    }

    @Test
    public void aRunOnARimWantsTheCubeAtTwo() {
        List<Foe> foes = List.of(cube(1, 2));
        Foe pick = CombatRules.rimRunFoe(true, foes);
        assertNotNull(pick);
        assertEquals(1, pick.id());
        assertNull(CombatRules.rimRunFoe(false, foes));
        assertNull(CombatRules.rimRunFoe(true, List.of(cube(1, 3.5))));
    }

    @Test
    public void aRunOnARimAlsoWantsAnythingPlainThatHitUs() {
        Foe zombie = new Foe(1, 2, false, false, 5);
        assertNotNull(CombatRules.rimRunFoe(true, List.of(zombie)));
        assertNull(CombatRules.rimRunFoe(true, List.of(new Foe(1, 2, false, false, NEVER))));
        assertNull(CombatRules.rimRunFoe(true, List.of(new Foe(1, 2, false, false, 5, Kind.HEAVY))));
    }

    // the brain calls pauseCorner every tick the run shield is up: standing still behind it is not being cornered, and the window
    // starts fresh when it lets go
    @Test
    public void aShieldedStandStillIsNotCorneredAndTheWindowStartsOverAfter() {
        CombatCommit c = new CombatCommit();
        Foe z = new Foe(1, 1.5, false, false, NEVER);
        Way way = new Way(Set.of(), true, 64);
        assertEquals(Event.RUN_START, c.step(tick(100, 6, List.of(z), null, way)));
        long shieldEnd = 101 + 3 * CombatCommit.CORNER_TICKS;
        for (long t = 101; t < shieldEnd; t++) {
            assertEquals(Event.NONE, c.step(tick(t, 6, List.of(z), null, way)));
            c.pauseCorner();
        }
        assertEquals(Mode.RUN, c.mode());
        Event event = Event.NONE;
        long t = shieldEnd;
        for (; t <= shieldEnd + 2 * CombatCommit.CORNER_TICKS && event == Event.NONE; t++) {
            event = c.step(tick(t, 6, List.of(z), null, way));
        }
        assertEquals(Event.RUN_TO_FIGHT, event);
        assertTrue(t - shieldEnd >= CombatCommit.CORNER_TICKS);
    }

    @Test
    public void theRunShieldGivesUpAfterTheLeash() {
        RimHold hold = new RimHold();
        for (int i = 0; i < CombatRules.RIM_HOLD_TICKS; i++) assertTrue(hold.step(7));
        assertFalse(hold.step(7));
        assertFalse(hold.step(7));
        // another foe does not start a new leash, nobody wanting it does
        assertFalse(hold.step(8));
        assertFalse(hold.step(-1));
        assertTrue(hold.step(7));
    }

    @Test
    public void twoCubesTakingTurnsDoNotMakeAFreshLeashEachSwap() {
        RimHold hold = new RimHold();
        int up = 0;
        for (int i = 0; i < 3 * CombatRules.RIM_HOLD_TICKS; i++) {
            if (hold.step(i % 2 == 0 ? 7 : 8)) up++;
        }
        assertEquals(CombatRules.RIM_HOLD_TICKS, up);
    }
}
