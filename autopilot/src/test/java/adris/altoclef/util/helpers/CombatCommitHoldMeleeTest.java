package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;

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

// (pillbox_hold_melee) test heel: a shut pillbox on ground that would not dig was left at once for a fight with a spider, and
// skeletons and a zombie killed the buddy outside it. shut in, a melee mob at the walls cannot reach in: no fight and no run on it
// while the cover holds; with the key off (shut never handed in) the fight or the run as before
public class CombatCommitHoldMeleeTest {

    private static final long NEVER = CombatCommit.NEVER;
    private static final long NOW = 1000;
    private static final int SKELETON = 6;
    private static final int SPIDER = 10;

    private static Foe skeleton(double distance, long sinceHit) {
        return new Foe(SKELETON, distance, true, false, sinceHit, Kind.NORMAL, Double.POSITIVE_INFINITY, true, NEVER);
    }

    private static Foe spider(int id, double distance, long sinceHit) {
        return new Foe(id, distance, false, false, sinceHit, Kind.NORMAL, distance - 0.6);
    }

    private static Tick tick(long now, float hp, boolean armed, Foe... foes) {
        return new Tick(now, hp, 0, 0, List.of(foes), null, Way.NONE, armed, false);
    }

    // in the pillbox: the cover takes the buddy, the ground will not dig, shut in or not
    private static CombatCommit machine(boolean shut) {
        CombatCommit c = new CombatCommit();
        c.skelFacts(new CombatCommit.Skel(Set.of(SKELETON), Set.of(SKELETON), true, false, 0, true, SkelCover.CLOSE_HP, SkelCover.RANGE,
                false, false, true, Set.of(), shut));
        return c;
    }

    @Test
    public void armedAMeleeHitAtTheWallsOfAShutPillboxIsNoFight() {
        Foe[] scene = {skeleton(12, NEVER), spider(SPIDER, 1.2, 2)};
        CombatCommit c = machine(true);
        assertEquals(Event.NONE, c.step(tick(NOW, 15, true, scene)));
        assertEquals(Mode.NONE, c.mode());
        assertEquals("pillbox_hold_melee: shut in, foe #" + SPIDER + " at the walls: no fight", c.takeNote());

        assertEquals(Event.FIGHT_START, machine(false).step(tick(NOW, 15, true, scene)));
    }

    @Test
    public void unarmedAMeleeHitAtTheWallsIsNoRun() {
        Foe[] scene = {skeleton(12, NEVER), spider(SPIDER, 1.2, 2)};
        CombatCommit c = machine(true);
        assertEquals(Event.NONE, c.step(tick(NOW, 15, false, scene)));
        assertEquals("pillbox_hold_melee: shut in, foe #" + SPIDER + " at the walls: no run", c.takeNote());

        assertEquals(Event.RUN_START, machine(false).step(tick(NOW, 15, false, scene)));
    }

    // hurt with a crowd of them at the walls: the cover holds in place of the low hp run
    @Test
    public void aLowHpRunWithMeleeAtTheWallsIsTheCoverShutIn() {
        Foe[] scene = {skeleton(10, NEVER), spider(SPIDER, 1.5, NEVER), spider(SPIDER + 1, 2, NEVER)};
        CombatCommit c = machine(true);
        assertEquals(Event.NONE, c.step(tick(NOW, 6, true, scene)));
        assertEquals(Mode.NONE, c.mode());

        CombatCommit off = machine(false);
        assertEquals(Event.RUN_START, off.step(tick(NOW, 6, true, scene)));
        assertEquals(Why.LOW_HP, off.why());
    }

    // a creeper close is still the run, shut in or not
    @Test
    public void aCreeperCloseIsStillTheRun() {
        Foe creeper = new Foe(20, 4, false, true, NEVER);
        CombatCommit c = machine(true);
        assertEquals(Event.RUN_START, c.step(tick(NOW, 6, true, skeleton(10, NEVER), spider(SPIDER, 1.5, NEVER), creeper)));
    }
}
