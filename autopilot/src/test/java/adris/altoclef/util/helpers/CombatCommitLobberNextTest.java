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
import adris.altoclef.util.helpers.CombatCommit.Why;
import java.util.List;
import org.junit.Test;

// the target dies with a witch landing hits on us from across the room: the witch is next, not the nearest zombie
public class CombatCommitLobberNextTest {

    private static final long NOW = 1000;
    private static final long NEVER = CombatCommit.NEVER;
    private static final int WITCH = 9;

    // a zombie in reach that has just bitten us
    private static Foe biter(int id) {
        return new Foe(id, 2, false, false, 3, Kind.NORMAL, 2.0);
    }

    private static Foe witch(double distance, long sinceHit, boolean sight) {
        return new Foe(WITCH, distance, true, false, sinceHit, Kind.LOBBER, Double.POSITIVE_INFINITY, sight, NEVER);
    }

    private static Foe skeleton(double distance, long sinceHit) {
        return new Foe(5, distance, true, false, sinceHit, Kind.NORMAL, Double.POSITIVE_INFINITY, true, NEVER);
    }

    private static Tick tick(long now, float hp, boolean boxed, Foe target, Foe... foes) {
        return new Tick(now, hp, 0, 0, List.of(foes), target, Way.NONE, true, false, false, RecoverHold.Body.NONE, boxed);
    }

    // three on us and a run that goes nowhere: the cornered fight, open air around us (nothing boxes us in), on one of them
    private static CombatCommit corneredOnZombie() {
        CombatCommit c = new CombatCommit();
        Foe[] crowd = {new Foe(1, 2, false, false, NEVER), new Foe(2, 2.5, false, false, NEVER), new Foe(3, 2.8, false, false, NEVER)};
        c.step(tick(NOW, 20, false, null, crowd));
        Event seen = Event.NONE;
        for (long now = NOW + 1; c.mode() == Mode.RUN && now < NOW + 200; now++) {
            seen = c.step(tick(now, 20, false, null, crowd));
        }
        assertEquals(Event.RUN_TO_FIGHT, seen);
        assertEquals(Why.CORNERED, c.why());
        assertTrue(c.cornered());
        return c;
    }

    // the cornered target's id is whichever the machine took, the biters left are the other two
    private static Foe[] biters(CombatCommit c) {
        return java.util.stream.IntStream.rangeClosed(1, 3).filter(id -> id != c.targetId()).mapToObj(CombatCommitLobberNextTest::biter)
                .toArray(Foe[]::new);
    }

    private static Foe[] with(Foe[] foes, Foe extra) {
        Foe[] out = java.util.Arrays.copyOf(foes, foes.length + 1);
        out[foes.length] = extra;
        return out;
    }

    @Test
    public void theWitchLandingHitsIsNextAndStaysNextWithZombiesBitingUs() {
        CombatCommit c = corneredOnZombie();
        Foe[] left = biters(c);
        // the target died. the witch hit us 20 ticks ago from 10 blocks with a line, two zombies are still on us
        assertEquals(Event.FIGHT_NEXT, c.step(tick(NOW + 300, 15, false, null, with(left, witch(10, 20, true)))));
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(WITCH, c.targetId());
        // the zombies in reach do not take it back off the witch
        for (long now = NOW + 301; now < NOW + 340; now++) {
            Foe w = witch(10, 20, true);
            assertEquals(Event.NONE, c.step(tick(now, 15, false, w, with(left, w))));
            assertEquals(WITCH, c.targetId());
        }
    }

    @Test
    public void atTheHpLineTheOldPickStands() {
        CombatCommit c = corneredOnZombie();
        // 8 is the hard floor: no new fight with a witch there, a zombie on us is the cornered pick as before
        assertEquals(Event.FIGHT_NEXT, c.step(tick(NOW + 300, 8, false, null, with(biters(c), witch(10, 20, true)))));
        assertTrue(c.targetId() != WITCH);
    }

    @Test
    public void aWitchWithoutALineOrAFreshHitOrInRangeIsNotPicked() {
        for (Foe w : new Foe[]{witch(10, 20, false), witch(10, 300, true), witch(13, 20, true), witch(10, NEVER, true)}) {
            CombatCommit c = corneredOnZombie();
            assertEquals(Event.FIGHT_NEXT, c.step(tick(NOW + 300, 15, false, null, with(biters(c), w))));
            assertTrue(c.targetId() != WITCH);
        }
    }

    @Test
    public void boxedInThereIsNoWalkingToItSoTheZombieStays() {
        CombatCommit c = new CombatCommit();
        Foe[] crowd = {new Foe(1, 1.5, false, false, 3), new Foe(2, 2, false, false, 3), new Foe(3, 2, false, false, 3)};
        assertEquals(Event.RUN_TO_FIGHT, c.step(tick(NOW, 20, true, null, crowd)));
        Foe[] left = biters(c);
        assertEquals(Event.FIGHT_NEXT, c.step(tick(NOW + 1, 15, true, null, with(left, witch(10, 20, true)))));
        assertTrue(c.targetId() != WITCH);
    }

    @Test
    public void aWardenOrHeavyNextToUsIsStillARunAndNotAWitchFight() {
        CombatCommit c = corneredOnZombie();
        Foe warden = new Foe(7, 5, false, false, 3, Kind.UNTOUCHABLE);
        // (nothing else left on us, so the death is the end of the cornered fight and the warden is what is next)
        Event e = c.step(tick(NOW + 300, 15, false, null, warden, witch(10, 20, true)));
        assertEquals(Event.FIGHT_TO_RUN, e);
        assertEquals(Why.HEAVY, c.why());
    }

    @Test
    public void aLiveTargetIsNeverDroppedForTheWitch() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 18, false, null, biter(1))));
        for (long now = NOW + 1; now < NOW + 40; now++) {
            assertEquals(Event.NONE, c.step(tick(now, 18, false, biter(1), biter(1), witch(10, 5, true))));
            assertEquals(1, c.targetId());
        }
    }

    @Test
    public void thePickedWitchIsKeptAbove8AndStillKeptAtTheLineWhenTheFightCameFromARun() {
        CombatCommit c = corneredOnZombie();
        c.step(tick(NOW + 300, 15, false, null, with(biters(c), witch(10, 20, true))));
        assertEquals(WITCH, c.targetId());
        // a poison tick taken and the zombies gone: 11 is under the old low hp line (the hit estimate), the witch we picked is
        // still ours
        Foe w = witch(10, 5, true);
        assertEquals(Event.NONE, c.step(tick(NOW + 301, 11, false, w, w)));
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(WITCH, c.targetId());
        // the line itself is not a run for this one: the fight came out of a run, so the runs are latched off until it is quiet
        assertEquals(Event.NONE, c.step(tick(NOW + 302, 8, false, w, w)));
        assertEquals(Mode.FIGHT, c.mode());
    }

    // a plain fight (not cornered) whose zombie just died with the witch still throwing: the witch fight, at 18 hp
    private static CombatCommit plainFightThenWitch() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 18, false, null, biter(1))));
        assertEquals(Event.FIGHT_NEXT, c.step(tick(NOW + 1, 18, false, null, witch(10, 20, true))));
        assertEquals(WITCH, c.targetId());
        assertFalse(c.cornered());
        return c;
    }

    @Test
    public void withoutTheBoxTheWitchFightIsKeptAt11AndTheWitchAloneIsNotWhatEndsIt() {
        CombatCommit c = plainFightThenWitch();
        Foe w = witch(10, 5, true);
        assertEquals(Event.NONE, c.step(tick(NOW + 2, 14, false, w, w)));
        assertEquals(Event.NONE, c.step(tick(NOW + 3, 11, false, w, w)));
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(Event.FIGHT_TO_RUN, c.step(tick(NOW + 4, 8, false, w, w)));
    }

    @Test
    public void aSkeletonLandingArrowsDoesNotEndTheWitchFightAboveTheHardLine() {
        CombatCommit c = plainFightThenWitch();
        Foe w = witch(10, 5, true);
        c.step(tick(NOW + 2, 14, false, w, w, skeleton(10, 20)));
        // the same 11 hp with a skeleton landing arrows too: one skeleton is the shooter fight, no run for it above the hard line
        assertEquals(Event.NONE, c.step(tick(NOW + 3, 11, false, w, w, skeleton(10, 20))));
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(Event.FIGHT_TO_RUN, c.step(tick(NOW + 4, 8, false, w, w, skeleton(10, 20))));
        assertEquals(Mode.RUN, c.mode());
    }

    @Test
    public void aQuietSkeletonThatHitUsLatelyStillHoldsTheWitchFightAboveTheHardLine() {
        CombatCommit c = plainFightThenWitch();
        Foe w = witch(10, 5, true);
        Foe quiet = new Foe(5, 7, true, false, 150, Kind.NORMAL, Double.POSITIVE_INFINITY, false, NEVER);
        c.step(tick(NOW + 2, 14, false, w, w, quiet));
        assertEquals(Event.NONE, c.step(tick(NOW + 3, 10, false, w, w, quiet)));
        assertEquals(Mode.FIGHT, c.mode());
    }
}
