package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Kind;
import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CombatCommit.Tick;
import java.util.List;
import org.junit.Test;

// the bail line is "the next hit would put us at or under 8", not "we are at 8": hits of 3 to 5 skip right over 8. that is for
// crowds, shooters and everything not yet fighting; one on one in a fight the real hp is the line
public class CombatCommitNextHitTest {

    private static final int ZOMBIE = 1;
    private static final int SKELETON = 2;

    private static Foe zombie(long sinceHit) {
        return new Foe(ZOMBIE, 2, false, false, sinceHit);
    }

    private static Foe skeleton(double distance, long sinceHit, boolean sight, long before) {
        return new Foe(SKELETON, distance, true, false, sinceHit, Kind.NORMAL, Double.POSITIVE_INFINITY, sight, before);
    }

    private static Tick tick(long now, float hp, Foe target, Foe... foes) {
        return new Tick(now, hp, 0, 0, List.of(foes), target);
    }

    @Test
    public void armorZeroHitsOfThreeBailAtTenNotSix() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.NONE, c.step(tick(1000, 16, null, zombie(CombatCommit.NEVER))));
        // 16 -> 13, the zombie is on us: still a fight to take
        assertEquals(Event.FIGHT_START, c.step(tick(1020, 13, null, zombie(0))));
        assertEquals(Mode.FIGHT, c.mode());
        // 13 -> 10 with another 3 on the way: that is the line, not 6
        // (a second zombie on us: the one on one fight has its own line, see aLoneMeleeFoeIsFoughtDownToEight)
        Foe other = new Foe(7, 3, false, false, 0);
        assertEquals(Event.FIGHT_TO_RUN, c.step(tick(1030, 10, zombie(0), zombie(0), other)));
        assertEquals(Mode.RUN, c.mode());
        assertEquals(CombatCommit.Why.LOW_HP, c.why());
    }

    private static Foe wither(int id) {
        return new Foe(id, 2, false, false, 0);
    }

    @Test
    public void aLoneMeleeFoeIsFoughtDownToEight() {
        CombatCommit c = new CombatCommit();
        c.step(tick(1000, 16, null));
        // one wither skeleton, 3.9 a hit: hp 12.1 and the fight is on
        assertEquals(Event.FIGHT_START, c.step(tick(1001, 12.1f, null, wither(20))));
        // 11 minus the next hit is under 8, but it is one mob and a fight we are in: no run
        assertEquals(Event.NONE, c.step(tick(1010, 11, wither(20), wither(20))));
        assertEquals(Mode.FIGHT, c.mode());
        // the real hp is the line
        assertEquals(Event.FIGHT_TO_RUN, c.step(tick(1020, 8, wither(20), wither(20))));
        assertEquals(Mode.RUN, c.mode());
    }

    @Test
    public void twoWitherSkeletonsOnUsStillRunAtEleven() {
        CombatCommit c = new CombatCommit();
        c.step(tick(1000, 16, null));
        assertEquals(Event.FIGHT_START, c.step(tick(1001, 12.1f, null, wither(20))));
        assertEquals(Event.FIGHT_TO_RUN, c.step(tick(1010, 11, wither(20), wither(20), wither(21))));
        assertEquals(Mode.RUN, c.mode());
    }

    @Test
    public void fullIronHitsOfTwoKeepTheOldFeel() {
        CombatCommit c = new CombatCommit();
        c.step(tick(1000, 16, null, zombie(CombatCommit.NEVER)));
        assertEquals(Event.FIGHT_START, c.step(tick(1020, 14, null, zombie(0))));
        assertEquals(Event.NONE, c.step(tick(1040, 12, zombie(0), zombie(0))));
        assertEquals(Event.NONE, c.step(tick(1060, 11, zombie(0), zombie(0))));
        assertEquals(Mode.FIGHT, c.mode());
    }

    @Test
    public void aFourDamageArrowAtTwelveEndsAFight() {
        CombatCommit c = new CombatCommit();
        c.step(tick(1000, 16, null, zombie(CombatCommit.NEVER)));
        assertEquals(Event.FIGHT_START, c.step(tick(1010, 16, null, zombie(0))));
        // (two on us, one of them the zombie: a zombie and the skeleton alone would be the shooter fight)
        Foe other = new Foe(7, 3, false, false, 0);
        assertEquals(Event.FIGHT_TO_RUN, c.step(tick(1020, 12, zombie(0), zombie(0), other, skeleton(12, 0, true, CombatCommit.NEVER))));
    }

    @Test
    public void aHitFromLongAgoMovesNothing() {
        CombatCommit c = new CombatCommit();
        c.step(tick(1000, 16, null));
        c.step(tick(1001, 12, null));
        // 41 ticks on the 4 is gone: 12 with a zombie that has not hit us is nothing
        assertEquals(Event.NONE, c.step(tick(1042, 12, null, zombie(CombatCommit.NEVER))));
        // inside the window the same hp with the same zombie near is a run
        CombatCommit d = new CombatCommit();
        d.step(tick(1000, 16, null));
        d.step(tick(1001, 12, null));
        assertEquals(Event.RUN_START, d.step(tick(1002, 12, null, zombie(CombatCommit.NEVER))));
    }

    @Test
    public void aGapInTheWatchIsNotAHit() {
        CombatCommit c = new CombatCommit();
        c.step(tick(1000, 20, null));
        // the chain was off for a while, the hp it comes back to is just the hp
        c.step(tick(1500, 12, null, zombie(0)));
        assertEquals(Mode.FIGHT, c.mode());
    }

    @Test
    public void heavyFloorStaysAtTen() {
        Foe brute = new Foe(3, 2, false, false, 0, Kind.HEAVY);
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(1000, 11, null, brute)));
        CombatCommit d = new CombatCommit();
        assertEquals(Event.RUN_START, d.step(tick(1000, 10, null, brute)));
    }

    @Test
    public void heavyLineMovesUpWithABigHit() {
        Foe brute = new Foe(3, 2, false, false, 0, Kind.HEAVY);
        CombatCommit c = new CombatCommit();
        c.step(tick(1000, 18, null));
        // took a 5 and stands at 13: 8 + 5 is the line now
        Event event = c.step(tick(1001, 13, null, brute));
        assertEquals(Event.RUN_START, event);
        assertEquals(CombatCommit.Why.HEAVY, c.why());
    }

    @Test
    public void aShooterThatHitUsAndSeesUsBlocksABiteFromPastTheRayRange() {
        assertTrue(CombatCommit.shotAt(skeleton(CombatCommit.FOE_RANGE, 30, true, CombatCommit.NEVER), false));
        // the memory runs out
        assertFalse(CombatCommit.shotAt(skeleton(CombatCommit.FOE_RANGE, CombatCommit.SHOT_MEMORY + 1, true, CombatCommit.NEVER), false));
        // no line
        assertFalse(CombatCommit.shotAt(skeleton(CombatCommit.FOE_RANGE, 30, false, CombatCommit.NEVER), false));
        // never hit us: the range line and the ray, as before
        assertFalse(CombatCommit.shotAt(skeleton(CombatCommit.FOE_RANGE, CombatCommit.NEVER, false, CombatCommit.NEVER), true));
        assertTrue(CombatCommit.shotAt(skeleton(14, CombatCommit.NEVER, false, CombatCommit.NEVER), true));
        assertFalse(CombatCommit.shotAt(skeleton(14, CombatCommit.NEVER, false, CombatCommit.NEVER), false));
        // a zombie is not a shooter
        assertFalse(CombatCommit.shotAt(new Foe(ZOMBIE, 5, false, false, 3, Kind.NORMAL, Double.POSITIVE_INFINITY, true, CombatCommit.NEVER), true));
    }

    @Test
    public void aRunWithAShooterOnUsDoesNotStartABite() {
        // the stance half: RUN, nothing close, but the brain says a shooter has a line
        CombatRules.Stance stance = CombatCommit.stance(Mode.RUN, 7, 12, false, false,
                new CombatCommit.Bite(true, Double.POSITIVE_INFINITY, false));
        assertFalse(stance.mayEat());
    }

}
