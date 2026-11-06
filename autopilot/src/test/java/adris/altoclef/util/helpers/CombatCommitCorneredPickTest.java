package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CombatCommit.Tick;
import adris.altoclef.util.helpers.CombatCommit.Way;
import java.util.List;
import org.junit.Test;

// a cornered fight chains on whatever has been hitting us in contact and nothing else: not a creeper, not a fresh zombie
// that is only standing close
public class CombatCommitCorneredPickTest {

    private static final long NOW = 1000;
    private static final long NEVER = CombatCommit.NEVER;

    private static Foe zombie(int id, double distance, long sinceHit) {
        return new Foe(id, distance, false, false, sinceHit);
    }

    private static Foe creeper(int id, double distance, long sinceHit) {
        return new Foe(id, distance, false, true, sinceHit);
    }

    private static Tick tick(long now, float hp, boolean armed, Foe target, Foe... foes) {
        return new Tick(now, hp, 0, 0, List.of(foes), target, Way.NONE, armed);
    }

    // three zombies on us and a run that goes nowhere: the cornered fight on zombie 1
    private static CombatCommit corneredOnZombie() {
        CombatCommit c = new CombatCommit();
        Foe[] crowd = {zombie(1, 2, NEVER), zombie(2, 2.5, NEVER), zombie(3, 2.8, NEVER)};
        c.step(tick(NOW, 20, true, null, crowd));
        for (long now = NOW + 1; c.mode() == Mode.RUN && now < NOW + 200; now++) {
            c.step(tick(now, 20, true, null, crowd));
        }
        assertEquals(Mode.FIGHT, c.mode());
        return c;
    }

    @Test
    public void corneredChainsOnAZombieThatHitUs() {
        CombatCommit c = corneredOnZombie();
        int dead = c.targetId();
        int other = dead == 2 ? 3 : 2;
        assertEquals(Event.FIGHT_NEXT, c.step(tick(NOW + 300, 15, true, null, zombie(other, 2, 5))));
        assertEquals(other, c.targetId());
    }

    @Test
    public void corneredDoesNotChainOnAFreshZombieJustStandingClose() {
        CombatCommit c = corneredOnZombie();
        int other = c.targetId() == 2 ? 3 : 2;
        assertEquals(Event.FIGHT_DEAD, c.step(tick(NOW + 300, 15, true, null, zombie(other, 2, NEVER))));
        assertEquals(Mode.NONE, c.mode());
    }

    @Test
    public void corneredNeverChainsOnACreeperEvenOneThatHitUs() {
        CombatCommit c = corneredOnZombie();
        assertNotEquals(Event.FIGHT_NEXT, c.step(tick(NOW + 300, 15, true, null, creeper(9, 1.5, 3))));
    }

    // a run that goes nowhere with one thing on top of us: a zombie is the cornered fight, a creeper is not
    private static Event standStill(Foe foe) {
        CombatCommit c = new CombatCommit();
        Event seen = Event.NONE;
        for (long now = NOW; now < NOW + 300; now++) {
            Event e = c.step(tick(now, 8, true, null, foe));
            if (e == Event.RUN_TO_FIGHT) seen = e;
        }
        return seen;
    }

    @Test
    public void theCornerWindowTurnsOnAZombieButNeverOnACreeper() {
        assertEquals(Event.RUN_TO_FIGHT, standStill(zombie(1, 1.5, NEVER)));
        assertEquals(Event.NONE, standStill(creeper(1, 1.5, NEVER)));
    }
}
