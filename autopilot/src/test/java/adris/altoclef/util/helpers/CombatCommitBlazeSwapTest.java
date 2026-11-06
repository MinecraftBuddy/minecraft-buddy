package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Kind;
import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CombatCommit.Tick;
import adris.altoclef.util.helpers.CombatCommit.Way;
import adris.altoclef.util.helpers.CombatCommit.Why;
import java.util.List;
import org.junit.Test;

// a blaze on our floor shooting us from a few blocks off while we trade blows with a cube: it is the one doing the damage, so it
// goes first. still one target, and the cube gets its turn when the blaze is dead
public class CombatCommitBlazeSwapTest {

    private static final long NEVER = CombatCommit.NEVER;
    private static final long NOW = 10_000;
    private static final float HP = 14;

    private static Foe cube(int id, double distance) {
        return new Foe(id, distance, false, false, 3).withFast(true);
    }

    // height is over our feet, the same y the brain fills in
    private static Foe blaze(int id, double distance, long sinceHit, boolean sight, double height) {
        return new Foe(id, distance, true, false, sinceHit, Kind.FLYER, Double.POSITIVE_INFINITY, sight, NEVER).withSplit(null, false, 0, height, 0);
    }

    private static Foe blaze(int id, double distance) {
        return blaze(id, distance, 3, true, 0);
    }

    private static Tick tick(long now, float hp, Foe target, Foe... foes) {
        return new Tick(now, hp, 0, 0, List.of(foes), target);
    }

    private static CombatCommit fightingCube() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, HP, null, cube(1, 2))));
        return c;
    }

    // the same fight after it swapped: the blaze is the target
    private static CombatCommit walkingToBlaze() {
        CombatCommit c = fightingCube();
        Foe target = cube(1, 2);
        assertEquals(Event.FIGHT_NEXT, c.step(tick(NOW + 1, HP, target, target, blaze(2, 5))));
        assertEquals(2, c.targetId());
        return c;
    }

    private static void staysOnTheCube(Foe target, Tick t, CombatCommit c) {
        assertEquals(Event.NONE, c.step(t));
        assertEquals(target.id(), c.targetId());
        assertEquals(Mode.FIGHT, c.mode());
    }

    @Test
    public void aBlazeOnOurFloorWithALineIsTheNextTarget() {
        CombatCommit c = walkingToBlaze();
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(Why.HIT, c.why());
        assertFalse(c.cornered());
    }

    @Test
    public void tooFarIsNotAWalk() {
        CombatCommit c = fightingCube();
        Foe target = cube(1, 2);
        staysOnTheCube(target, tick(NOW + 1, HP, target, target, blaze(2, CombatCommit.BLAZE_SWAP_RANGE + 0.5)), c);
    }

    @Test
    public void aHoveringOneIsNotAWalkEither() {
        CombatCommit c = fightingCube();
        Foe target = cube(1, 2);
        staysOnTheCube(target, tick(NOW + 1, HP, target, target, blaze(2, 5, 3, true, CombatCommit.BLAZE_FLOOR_HEIGHT + 0.5)), c);
        staysOnTheCube(target, tick(NOW + 2, HP, target, target, blaze(2, 5, 3, true, -CombatCommit.BLAZE_FLOOR_HEIGHT - 0.5)), c);
    }

    @Test
    public void noLineNoSwap() {
        CombatCommit c = fightingCube();
        Foe target = cube(1, 2);
        staysOnTheCube(target, tick(NOW + 1, HP, target, target, blaze(2, 5, 3, false, 0)), c);
    }

    @Test
    public void notOneThatHasNotHitUsLately() {
        CombatCommit c = fightingCube();
        Foe target = cube(1, 2);
        staysOnTheCube(target, tick(NOW + 1, HP, target, target, blaze(2, 5, CombatCommit.SHOT_MEMORY + 1, true, 0)), c);
    }

    @Test
    public void notAtTheHpLine() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_TO_FIGHT, c.step(tick(NOW, 8, null, cube(1, 2))));
        Foe target = cube(1, 2);
        staysOnTheCube(target, tick(NOW + 1, 8, target, target, blaze(2, 5)), c);
    }

    @Test
    public void notBoxedIn() {
        CombatCommit c = fightingCube();
        Foe target = cube(1, 2);
        staysOnTheCube(target, new Tick(NOW + 1, HP, 0, 0, List.of(target, blaze(2, 5)), target, Way.NONE, true, false, false,
                RecoverHold.Body.NONE, true), c);
    }

    @Test
    public void notAlongARim() {
        CombatCommit c = fightingCube();
        Foe target = cube(1, 2);
        staysOnTheCube(target, new Tick(NOW + 1, HP, 0, 0, List.of(target, blaze(2, 5)), target, Way.NONE.withRim(true), true), c);
    }

    @Test
    public void notBareHanded() {
        CombatCommit c = fightingCube();
        Foe target = cube(1, 2);
        c.step(new Tick(NOW + 1, HP, 0, 0, List.of(target, blaze(2, 5)), target, Way.NONE, false));
        assertEquals(1, c.targetId());
    }

    @Test
    public void notOffAWitherSkeletonOrAHeavyHitterOrAnEnderman() {
        Foe wither = new Foe(1, 2, false, false, 3, Kind.NORMAL, Double.POSITIVE_INFINITY, true, NEVER).withWither(true);
        Foe heavy = new Foe(1, 2, false, false, 3, Kind.HEAVY);
        Foe ender = new Foe(1, 2, false, false, 3, Kind.ENDERMAN);
        for (Foe target : List.of(wither, heavy, ender)) {
            CombatCommit c = new CombatCommit();
            assertEquals(Event.FIGHT_START, c.step(tick(NOW, HP, null, target)));
            staysOnTheCube(target, tick(NOW + 1, HP, target, target, blaze(2, 5)), c);
        }
    }

    @Test
    public void thePickSticksWithTheCubeBitingUs() {
        CombatCommit c = walkingToBlaze();
        Foe target = blaze(2, 5);
        Foe biting = new Foe(1, 2, false, false, 3, Kind.NORMAL, 1.0).withFast(true);
        for (long t = 2; t < 40; t++) {
            assertEquals(Event.NONE, c.step(tick(NOW + t, HP, target, target, biting)));
            assertEquals(2, c.targetId());
        }
    }

    @Test
    public void theWalkOutlastsTheUsualBlazeReleaseUntilItLeavesOurFloor() {
        CombatCommit c = walkingToBlaze();
        Foe ok = blaze(2, CombatCommit.CONTACT + CombatCommit.FLYER_SLACK + 1);
        assertEquals(Event.NONE, c.step(tick(NOW + 2, HP, ok, ok)));
        Foe bobbing = blaze(2, 5, 3, true, CombatCommit.BLAZE_FLOOR_HEIGHT + 1);
        assertEquals(Event.NONE, c.step(tick(NOW + 3, HP, bobbing, bobbing)));
        Foe gone = blaze(2, 5, 3, true, CombatCommit.BLAZE_FLOOR_HEIGHT + CombatCommit.FLYER_SLACK + 0.5);
        assertEquals(Event.FIGHT_CLEARED, c.step(tick(NOW + 4, HP, gone, gone)));
    }

    @Test
    public void walkingOutOfRangeEndsItToo() {
        CombatCommit c = walkingToBlaze();
        Foe gone = blaze(2, CombatCommit.BLAZE_SWAP_RANGE + CombatCommit.FLYER_SLACK + 0.5);
        assertEquals(Event.FIGHT_CLEARED, c.step(tick(NOW + 2, HP, gone, gone)));
    }

    @Test
    public void anUnpickedBlazeStillGetsTheOldRelease() {
        CombatCommit c = new CombatCommit();
        Foe near = blaze(2, 2);
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, HP, null, near)));
        Foe drifted = blaze(2, CombatCommit.CONTACT + CombatCommit.FLYER_SLACK + 0.5);
        assertEquals(Event.FIGHT_CLEARED, c.step(tick(NOW + 1, HP, drifted, drifted)));
    }

    @Test
    public void theHpLineEndsTheWalk() {
        CombatCommit c = walkingToBlaze();
        Foe target = blaze(2, 5);
        assertEquals(Event.FIGHT_TO_RUN, c.step(tick(NOW + 2, 8, target, target)));
        assertEquals(Mode.RUN, c.mode());
    }

    // the same walk with the latch on, which is what a swap out of a corner carries
    private static CombatCommit latchedOnBlaze() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_TO_FIGHT, c.step(tick(NOW, 7, null, cube(1, 2))));
        Foe target = cube(1, 2);
        assertEquals(Event.FIGHT_NEXT, c.step(tick(NOW + 1, HP, target, target, blaze(2, 2))));
        assertEquals(2, c.targetId());
        return c;
    }

    @Test
    public void theHpLineGetsPastTheLatchInContactToo() {
        CombatCommit c = latchedOnBlaze();
        Foe target = blaze(2, 2);
        assertEquals(Event.FIGHT_TO_RUN, c.step(tick(NOW + 2, 8, target, target)));
        assertEquals(Mode.RUN, c.mode());
        assertEquals(Why.LOW_HP, c.why());
    }

    @Test
    public void theHpLineWithACubeStillOnUsIsTheCornerNotARun() {
        CombatCommit c = latchedOnBlaze();
        Foe target = blaze(2, 2);
        assertEquals(Event.NONE, c.step(tick(NOW + 2, 8, target, target, cube(1, 2))));
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals("fast", c.cornerKind());
    }

    @Test
    public void theSwapEndsACornerAndTheCubeGetsItsTurnAfter() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_TO_FIGHT, c.step(tick(NOW, 7, null, cube(1, 2))));
        Foe target = cube(1, 2);
        // healed a bit since, with the blaze shooting
        assertEquals(Event.FIGHT_NEXT, c.step(tick(NOW + 1, HP, target, target, blaze(2, 5))));
        assertEquals(2, c.targetId());
        assertFalse(c.cornered());
        assertNull(c.cornerKind());
        // the blaze is dead, the cube in contact is next
        assertEquals(Event.FIGHT_NEXT, c.step(tick(NOW + 2, HP, null, cube(1, 2))));
        assertEquals(1, c.targetId());
    }

    @Test
    public void aSecondBlazeDoesNotPullUsOffTheFirst() {
        CombatCommit c = walkingToBlaze();
        Foe target = blaze(2, 2);
        // another blaze shooting is not a reason to drop the one we walked up to
        assertEquals(Event.NONE, c.step(tick(NOW + 2, HP, target, target, blaze(3, 4))));
        assertEquals(2, c.targetId());
    }
}
