package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CombatCommit.Tick;
import adris.altoclef.util.helpers.CombatCommit.Way;
import adris.altoclef.util.helpers.CombatCommit.Why;
import java.util.List;
import java.util.Set;
import org.junit.Test;

// wither and fire keep hurting after the hit, so the one on one "real hp is the line" exemption does not hold while one is on us
public class CombatCommitLingeringTest {

    private static Foe wither(int id, double distance, long sinceHit) {
        return new Foe(id, distance, false, false, sinceHit);
    }

    private static Tick tick(long now, float hp, boolean lingering, Foe target, Way way, Foe... foes) {
        return new Tick(now, hp, now * 0.3, 0, List.of(foes), target, way, true, false, false, RecoverHold.Body.NONE, false, 0, false, lingering);
    }

    private static Way inWay(int id) {
        return new Way(Set.of(id), true, 64);
    }

    // the same fight with the lingering flag as the only difference: 20 -> 16 is a 4 hit and starts it, then 12 is the test hp
    private static CombatCommit fightAt(boolean lingering) {
        CombatCommit c = new CombatCommit();
        c.step(tick(1000, 20, lingering, null, Way.NONE));
        assertEquals(Event.FIGHT_START, c.step(tick(1001, 16, lingering, null, Way.NONE, wither(20, 2, 0))));
        return c;
    }

    @Test
    public void withLingeringOnALoneFoeBailsAtTwelve() {
        CombatCommit c = fightAt(true);
        assertEquals(Event.FIGHT_TO_RUN, c.step(tick(1010, 12, true, wither(20, 2, 0), Way.NONE, wither(20, 2, 0))));
        assertEquals(Why.LOW_HP, c.why());
    }

    @Test
    public void withoutLingeringALoneFoeIsStillFoughtAtTwelve() {
        CombatCommit c = fightAt(false);
        assertEquals(Event.NONE, c.step(tick(1010, 12, false, wither(20, 2, 0), Way.NONE, wither(20, 2, 0))));
        assertEquals(Mode.FIGHT, c.mode());
    }

    @Test
    public void aPathFightIsNotPickedHurtAndBurning() {
        assertEquals(Event.NONE, new CombatCommit().step(tick(0, 14, true, null, inWay(1), wither(1, 1.5, CombatCommit.NEVER))));
        CombatCommit control = new CombatCommit();
        assertEquals(Event.FIGHT_START, control.step(tick(0, 14, false, null, inWay(1), wither(1, 1.5, CombatCommit.NEVER))));
        assertEquals(Why.IN_WAY, control.why());
    }

    @Test
    public void aRunFromAWitherSkeletonDoesNotFlapIntoFights() {
        CombatCommit c = fightAt(true);
        assertEquals(Event.FIGHT_TO_RUN, c.step(tick(1010, 12, true, wither(20, 2, 0), Way.NONE, wither(20, 2, 0))));
        // slow regen: hp sits around 12 to 13 with the foe still near and in the way, and nothing may start a fight
        for (long t = 1011; t < 1400; t++) {
            Event e = c.step(tick(t, 12 + (t % 2), true, null, inWay(20), wither(20, 5, CombatCommit.NEVER)));
            assertNotEquals("tick " + t, Event.FIGHT_START, e);
            assertNotEquals("tick " + t, Event.RUN_TO_FIGHT, e);
            assertNotEquals("tick " + t, Event.RUN_CHASED_FIGHT, e);
        }
    }
}
