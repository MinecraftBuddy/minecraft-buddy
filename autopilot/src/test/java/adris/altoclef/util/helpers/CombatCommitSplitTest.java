package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Kind;
import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CombatCommit.Tick;
import adris.altoclef.util.helpers.CombatCommit.Way;
import java.util.List;
import org.junit.Test;

// a cube dies and its splits have not bitten us yet: they are the same fight, not the end of it
public class CombatCommitSplitTest {

    private static final long NOW = 1000;
    private static final long NEVER = CombatCommit.NEVER;

    private static Foe cube(int id, String type, double x, double z, long sinceHit) {
        return cube(id, type, true, x, 0, z, sinceHit);
    }

    private static Foe cube(int id, String type, boolean splits, double x, double y, double z, long sinceHit) {
        return new Foe(id, 2, false, false, sinceHit, Kind.NORMAL, 2.0).withSplit(type, splits, x, y, z);
    }

    private static Tick tick(long now, Foe target, Foe... foes) {
        return new Tick(now, 20, 0, 0, List.of(foes), target, Way.NONE, true);
    }

    private static CombatCommit fightingCube(Foe cube) {
        CombatCommit c = new CombatCommit();
        c.step(tick(NOW, cube, cube));
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(cube.id(), c.targetId());
        return c;
    }

    @Test
    public void aFreshSplitNextToTheCorpseIsNext() {
        Foe big = cube(1, "magma_cube", 4, 4, 3);
        CombatCommit c = fightingCube(big);
        Event e = c.step(tick(NOW + 1, null, cube(2, "magma_cube", 4.5, 4.5, NEVER), cube(3, "magma_cube", 6, 4, NEVER)));
        assertEquals(Event.FIGHT_NEXT, e);
        assertEquals(2, c.targetId());
    }

    @Test
    public void aSplitFarFromTheCorpseIsNotInherited() {
        CombatCommit c = fightingCube(cube(1, "slime", 4, 4, 3));
        assertEquals(Event.NONE, c.step(tick(NOW + 1, null, cube(2, "slime", 9, 9, NEVER))));
        assertEquals(1, c.targetId());
        assertEquals(Event.FIGHT_DEAD, c.step(tick(NOW + 2 + CombatCommit.SPLIT_WAIT_TICKS, null, cube(2, "slime", 9, 9, NEVER))));
    }

    @Test
    public void aDifferentTypeNearbyIsNotInherited() {
        CombatCommit c = fightingCube(cube(1, "magma_cube", 4, 4, 3));
        assertEquals(Event.NONE, c.step(tick(NOW + 1, null, cube(2, "slime", 4.5, 4.5, NEVER))));
        assertEquals(1, c.targetId());
        assertEquals(Event.FIGHT_DEAD, c.step(tick(NOW + 2 + CombatCommit.SPLIT_WAIT_TICKS, null, cube(2, "slime", 4.5, 4.5, NEVER))));
    }

    @Test
    public void aPlainMobDyingInheritsNothing() {
        Foe zombie = new Foe(1, 2, false, false, 3, Kind.NORMAL, 2.0);
        CombatCommit c = fightingCube(zombie);
        Event e = c.step(tick(NOW + 1, null, cube(2, "slime", 0, 0, NEVER)));
        assertEquals(Event.FIGHT_DEAD, e);
    }

    @Test
    public void theChildrenComeAfterTheDeathAnimation() {
        CombatCommit c = fightingCube(cube(1, "magma_cube", 4, 4, 3));
        // nothing around while the body falls over: the fight waits, it does not end
        for (long now = NOW + 1; now <= NOW + 20; now++) {
            assertEquals(Event.NONE, c.step(tick(now, null)));
            assertEquals(Mode.FIGHT, c.mode());
        }
        assertEquals(Event.FIGHT_NEXT, c.step(tick(NOW + 21, null, cube(2, "magma_cube", 4.5, 4.5, NEVER))));
        assertEquals(2, c.targetId());
    }

    @Test
    public void theWaitForChildrenEnds() {
        CombatCommit c = fightingCube(cube(1, "slime", 4, 4, 3));
        long last = NOW + 1 + CombatCommit.SPLIT_WAIT_TICKS;
        for (long now = NOW + 1; now < last; now++) {
            assertEquals(Event.NONE, c.step(tick(now, null)));
        }
        assertEquals(Event.FIGHT_DEAD, c.step(tick(last + 1, null)));
    }

    private static Foe ender(int id, long sinceHit) {
        return new Foe(id, 2, false, false, sinceHit, Kind.ENDERMAN, 2.0);
    }

    @Test
    public void anEndermanThatBlinkedAwayIsNotDeadYet() {
        CombatCommit c = fightingCube(ender(1, 3));
        for (long now = NOW + 1; now <= NOW + CombatCommit.ENDER_WAIT_TICKS; now++) {
            assertEquals(Event.NONE, c.step(tick(now, null)));
            assertEquals(Mode.FIGHT, c.mode());
        }
        assertEquals(Event.FIGHT_DEAD, c.step(tick(NOW + CombatCommit.ENDER_WAIT_TICKS + 2, null)));
    }

    @Test
    public void anEndermanThatComesBackIsTheSameFight() {
        CombatCommit c = fightingCube(ender(1, 3));
        assertEquals(Event.NONE, c.step(tick(NOW + 1, null)));
        assertEquals(Event.NONE, c.step(tick(NOW + 30, null)));
        assertEquals(Event.FIGHT_NEXT, c.step(tick(NOW + 31, null, ender(2, NEVER))));
        assertEquals(2, c.targetId());
    }

    @Test
    public void aSizeOneCubeLeavesNothingToWaitFor() {
        CombatCommit c = fightingCube(cube(1, "magma_cube", false, 4, 0, 4, 3));
        assertEquals(Event.FIGHT_DEAD, c.step(tick(NOW + 1, null)));
    }

    @Test
    public void aSameTypeFoeFarBelowIsNotTheSplit() {
        CombatCommit c = fightingCube(cube(1, "magma_cube", true, 4, 0, 4, 3));
        Event e = c.step(tick(NOW + 1, null, cube(2, "magma_cube", true, 4, -10, 4, NEVER)));
        assertNotEquals(Event.FIGHT_NEXT, e);
    }
}
