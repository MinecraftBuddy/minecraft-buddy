package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;

import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Kind;
import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CombatCommit.Tick;
import adris.altoclef.util.helpers.CombatCommit.Way;
import adris.altoclef.util.helpers.CombatCommit.Why;
import baritone.api.utils.Dimension;
import java.util.List;
import org.junit.Test;

// an aware wither skeleton with a line on us inside 4 blocks is a fight we start in the Nether, blazes watching or not
public class CombatCommitWitherTest {

    private static final long NEVER = CombatCommit.NEVER;

    private static Foe wither(int id, double distance, boolean sight) {
        return new Foe(id, distance, false, false, NEVER, Kind.NORMAL, Double.POSITIVE_INFINITY, sight, NEVER).withWither(true);
    }

    private static Foe blaze() {
        return new Foe(2, 8, true, false, NEVER, Kind.FLYER, Double.POSITIVE_INFINITY, true, NEVER);
    }

    private static Tick tick(long now, float hp, List<Foe> foes, Foe target, boolean armed) {
        return new Tick(now, hp, 0, 0, foes, target, Way.NONE, armed);
    }

    @Test
    public void startsOnAWitherSkeletonWithALineWhileABlazeWatches() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(0, 20, List.of(wither(1, 3.5, true), blaze()), null, true)));
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(Why.IN_WAY, c.why());
        assertEquals(1, c.targetId());
    }

    @Test
    public void startsInsideTheCooldownToo() {
        CombatCommit c = new CombatCommit();
        c.step(tick(0, 20, List.of(wither(1, 4, true)), null, true));
        // it died: the cooldown is on
        c.step(tick(1, 20, List.of(), null, true));
        assertEquals(Mode.NONE, c.mode());
        assertEquals(true, c.coolingDown(5));
        assertEquals(Event.FIGHT_START, c.step(tick(5, 20, List.of(wither(3, 4, true)), null, true)));
        assertEquals(3, c.targetId());
    }

    @Test
    public void notBeyondFiveBlocksNotWithoutALineNotBareHanded() {
        assertEquals(Event.NONE, new CombatCommit().step(tick(0, 20, List.of(wither(1, 4.5, true)), null, true)));
        assertEquals(Event.NONE, new CombatCommit().step(tick(0, 20, List.of(wither(1, 4, false)), null, true)));
        assertEquals(Event.NONE, new CombatCommit().step(tick(0, 20, List.of(wither(1, 4, true)), null, false)));
    }

    @Test
    public void notWhenLow() {
        // (hurt it is the low hp run's call, what matters is that it is not a fight we pick)
        CombatCommit c = new CombatCommit();
        org.junit.Assert.assertNotEquals(Event.FIGHT_START, c.step(tick(0, 4, List.of(wither(1, 4, true)), null, true)));
    }

    @Test
    public void notWithAPickaxeInHand() {
        Way pick = new Way(java.util.Set.of(), false, 0, false);
        assertEquals(Event.NONE, new CombatCommit().step(new Tick(0, 20, 0, 0, List.of(wither(1, 4, true)), null, pick, true)));
    }

    @Test
    public void aSkeletonThatIsNotMarkedStaysScenery() {
        // only the Nether marks one, an overworld skeleton is the shooter rules' business
        Foe plain = new Foe(1, 4, false, false, NEVER, Kind.NORMAL, Double.POSITIVE_INFINITY, true, NEVER);
        assertEquals(Event.NONE, new CombatCommit().step(tick(0, 20, List.of(plain), null, true)));
    }

    @Test
    public void onlyTheNetherMarksAWitherSkeleton() {
        FoeRules.Candidate c = new FoeRules.Candidate("wither_skeleton", 3, 5, () -> false, () -> true, () -> true, NEVER, 0, false, false);
        org.junit.Assert.assertTrue(FoeRules.accept(Dimension.NETHER, c).wither());
        org.junit.Assert.assertFalse(FoeRules.accept(Dimension.OVERWORLD, c).wither());
    }

    @Test
    public void oneThatStaysJustPastTheLineClearsOnceAndIsNotPickedAgain() {
        CombatCommit c = new CombatCommit();
        c.step(tick(0, 20, List.of(wither(1, 3, true)), null, true));
        Foe far = wither(1, 4.5, true);
        Event end = Event.NONE;
        long t = 1;
        for (; t < 100 && end == Event.NONE; t++) end = c.step(tick(t, 20, List.of(far), far, true));
        assertEquals(Event.FIGHT_CLEARED, end);
        for (long stop = t + CombatCommit.STALL_TICKS * 2; t < stop; t++) {
            assertEquals(Event.NONE, c.step(tick(t, 20, List.of(far), null, true)));
        }
    }

    @Test
    public void theFightHoldsThroughAKnockbackBlip() {
        CombatCommit c = new CombatCommit();
        c.step(tick(0, 20, List.of(wither(1, 3, true), blaze()), null, true));
        Foe far = wither(1, 4.6, true);
        for (long t = 1; t < 20; t++) {
            assertEquals(Event.NONE, c.step(tick(t, 20, List.of(far, blaze()), far, true)));
        }
        assertEquals(Mode.FIGHT, c.mode());
        Foe back = wither(1, 2, true);
        assertEquals(Event.NONE, c.step(tick(20, 20, List.of(back, blaze()), back, true)));
        assertEquals(Mode.FIGHT, c.mode());
    }

    private static Tick lingeringTick(long now, float hp, boolean lingering, List<Foe> foes) {
        return new Tick(now, hp, 0, 0, foes, null, Way.NONE, true, false, false, RecoverHold.Body.NONE, false, 0, false, lingering);
    }

    // (the buddy's fork: a lone one hurt is still the fight. the run at 15.8 hp headed back into the corridor it came from and
    // took the hit from the front, attempt 220, 4 Nov 2026. the pack and the flee line keep their runs)
    @Test
    public void aLoneWitherSkeletonIsAFightHurtOrNotWithWitherOrFireOnUs() {
        for (boolean lingering : new boolean[]{true, false}) {
            for (float hp : new float[]{CombatCommit.LINGER_PICK_HP, 15, 13}) {
                CombatCommit c = new CombatCommit();
                assertEquals("hp " + hp + " lingering " + lingering, Event.FIGHT_START, c.step(lingeringTick(0, hp, lingering, List.of(wither(1, 3, true)))));
                assertEquals(Mode.FIGHT, c.mode());
            }
        }
    }

    @Test
    public void atTheFleeLineALoneWitherSkeletonIsStillARun() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(lingeringTick(0, CombatCommit.FLEE_HP, true, List.of(wither(1, 3, true)))));
        assertEquals(Mode.RUN, c.mode());
    }

    private static Foe witherAt(int id, double distance, double y) {
        Foe f = wither(id, distance, true);
        return new Foe(f.id(), f.distance(), false, false, NEVER, Kind.NORMAL, Double.POSITIVE_INFINITY, true, NEVER, false, null, false, 0, y, 0,
                false, false, false, true);
    }

    private static Tick fightTick(long now, float hp, List<Foe> foes, Foe target, boolean lingering) {
        return new Tick(now, hp, 0, 0, foes, target, Way.NONE, true, false, false, RecoverHold.Body.NONE, false, 4.5f, false, lingering);
    }

    @Test
    public void aPackOnOurFloorIsARunNotAFight() {
        List<Foe> pack = List.of(witherAt(1, 3, 0), witherAt(2, 7, 1), witherAt(3, 9, 0));
        assertEquals(Event.RUN_START, new CombatCommit().step(tick(0, 20, pack, null, true)));
    }

    @Test
    public void aSkeletonTwoFloorsDownIsNotAPack() {
        List<Foe> foes = List.of(witherAt(1, 3, 0), witherAt(2, 8, -6));
        assertEquals(Event.FIGHT_START, new CombatCommit().step(tick(0, 20, foes, null, true)));
    }

    @Test
    public void heightIsReadOverUsNotOverTheTarget() {
        // target 3 up and the other 3 down: 6 apart, but both inside 4 of our floor
        List<Foe> foes = List.of(witherAt(1, 3, 3), witherAt(2, 6, -3));
        assertEquals(Event.RUN_START, new CombatCommit().step(tick(0, 20, foes, null, true)));
    }

    @Test
    public void aLoneSkeletonFightStaysPastTheProjectedFleeLine() {
        CombatCommit c = new CombatCommit();
        Foe w = witherAt(1, 3, 0);
        c.step(tick(0, 20, List.of(w), null, true));
        // 10.5 hp with a 4.5 hit coming is 6 projected: the old bail. real hp is above the line, so we stay
        assertEquals(Event.NONE, c.step(fightTick(1, 10.5f, List.of(w), w, true)));
        assertEquals(Mode.FIGHT, c.mode());
    }

    @Test
    public void aSecondSkeletonOnOurFloorStillBails() {
        CombatCommit c = new CombatCommit();
        Foe w = witherAt(1, 3, 0);
        c.step(tick(0, 20, List.of(w), null, true));
        Event e = c.step(fightTick(1, 10.5f, List.of(w, witherAt(2, 6, 0)), w, true));
        org.junit.Assert.assertNotEquals(Mode.FIGHT, c.mode());
        org.junit.Assert.assertNotEquals(Event.NONE, e);
    }

    // attempt 220: on the walk, outside a fight, one hit us at 12 hp. its next 4.5 put us under the flee line and the run turned
    // back into it in the corridors. alone it is the hit fight; with a second one on our floor it is still a run
    @Test
    public void aLoneWitherSkeletonThatHitsUsAtTwelveIsTheHitFight() {
        // (knocked back out of contact by its own hit)
        for (double d : new double[]{1.5, 3.5}) {
            Foe hit = new Foe(1, d, false, false, 0, Kind.NORMAL, Double.POSITIVE_INFINITY, true, NEVER).withWither(true);
            CombatCommit c = new CombatCommit();
            assertEquals("at " + d, Event.FIGHT_START, c.step(fightTick(0, 12, List.of(hit), null, true)));
        }
        // (further off it is no run either: it walks up into the start range and the start rule takes it)
        Foe off = new Foe(1, 5, false, false, 0, Kind.NORMAL, Double.POSITIVE_INFINITY, true, NEVER).withWither(true);
        org.junit.Assert.assertNotEquals(Event.RUN_START, new CombatCommit().step(fightTick(0, 12, List.of(off), null, true)));
    }

    @Test
    public void aRefusedSkeletonThatHitsUsStartsTheHitFight() {
        CombatCommit c = new CombatCommit();
        // (refused for the pack on our floor)
        assertEquals(Event.RUN_START, c.step(lingeringTick(0, 20, false, List.of(witherAt(1, 3, 0), witherAt(2, 7, 0)))));
        // (the run plays out with nothing around)
        long now = 1;
        for (; now < 400 && c.mode() != Mode.NONE; now++) c.step(tick(now, 20, List.of(), null, true));
        assertEquals(Mode.NONE, c.mode());
        Foe hit = new Foe(1, 1.5, false, false, 0, Kind.NORMAL, Double.POSITIVE_INFINITY, true, NEVER).withWither(true);
        assertEquals(Event.FIGHT_START, c.step(tick(now, 20, List.of(hit), null, true)));
        assertEquals(Why.HIT, c.why());
    }
}
