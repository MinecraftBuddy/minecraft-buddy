package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CombatRules.Stance;
import org.junit.Test;

// who may eat while we are committed. the stance is what the food chain asks, so this is the whole eating policy
public class CombatCommitStanceTest {

    private static final double NOBODY = Double.POSITIVE_INFINITY;

    // the run ended "clear" with the shooter 18 blocks off (no foe at all): the hit alone keeps the heal-up meal closed for
    // SHOT_MEMORY ticks, then it opens
    @Test
    public void aHitFromBeyondFoeRangeBlocksTheRecoveryMealForAHundredTicks() {
        long until = CombatCommit.hitLatch(Long.MIN_VALUE / 2, 1000);
        for (long now = 1000; now <= 1000 + CombatCommit.SHOT_MEMORY; now += 10) {
            CombatCommit.Bite bite = new CombatCommit.Bite(now <= until, NOBODY, false);
            assertEquals("tick " + now, Stance.FLEE, CombatCommit.stance(Mode.NONE, 4, NOBODY, false, false, bite, true));
        }
        long late = 1000 + CombatCommit.SHOT_MEMORY + 1;
        CombatCommit.Bite open = new CombatCommit.Bite(late <= until, NOBODY, false);
        assertEquals(Stance.CALM, CombatCommit.stance(Mode.NONE, 4, NOBODY, false, false, open, true));
        // a second hit pushes the latch out, never pulls it in
        assertEquals(1150 + CombatCommit.SHOT_MEMORY, CombatCommit.hitLatch(until, 1150));
        assertEquals(until, CombatCommit.hitLatch(until, 1000));
    }

    // no recovery hold either: a hurt bot with nothing in sight still does not start a calm meal inside the latch
    @Test
    public void aCalmMealIsBlockedInsideTheLatchWhenHurt() {
        CombatCommit.Bite shot = new CombatCommit.Bite(true, NOBODY, false);
        assertEquals(Stance.FLEE, CombatCommit.stance(Mode.NONE, 6, NOBODY, false, false, shot, false));
        // healthy enough that nothing is on the line
        assertEquals(Stance.CALM, CombatCommit.stance(Mode.NONE, 20, NOBODY, false, false, shot, false));
    }

    @Test
    public void aGoldenAppleStaysAllowedWhileTheHitLatchIsOn() {
        CombatCommit.Bite shot = new CombatCommit.Bite(true, NOBODY, false);
        assertEquals(Stance.EAT_GAPPLE, CombatCommit.stance(Mode.NONE, 4, NOBODY, false, true, shot, true));
    }

    @Test
    public void nobodyCommittedAndNothingWrongIsAMealtime() {
        assertEquals(Stance.CALM, CombatCommit.stance(Mode.NONE, 20, NOBODY, false, false));
        // a zombie ten blocks off is scenery, lunch carries on
        assertEquals(Stance.CALM, CombatCommit.stance(Mode.NONE, 12, 10, false, false));
        assertTrue(CombatCommit.stance(Mode.NONE, 12, 5, false, false).mayEat());
    }

    @Test
    public void aFightIsNeverAMealtime() {
        assertEquals(Stance.FIGHT, CombatCommit.stance(Mode.FIGHT, 20, 2, false, false));
        // even when the target wandered off the list, we are still in it
        assertEquals(Stance.FIGHT, CombatCommit.stance(Mode.FIGHT, 20, NOBODY, false, false));
        assertFalse(CombatCommit.stance(Mode.FIGHT, 20, 2, false, false).mayEat());
    }

    @Test
    public void aRunWithSomethingCloseDoesNotStopForSnacks() {
        assertEquals(Stance.FIGHT, CombatCommit.stance(Mode.RUN, 20, 8, false, false));
        assertEquals(Stance.FLEE, CombatCommit.stance(Mode.RUN, 6, 8, false, false));
        assertFalse(CombatCommit.stance(Mode.RUN, 6, 5, false, false).mayEat());
    }

    // the run stays through a bite and the food chain does it in the background. a bite may START with the nearest angry thing
    // at RUN_BITE_START or further, and once started only a threat inside RUN_BITE_ABORT drops it
    @Test
    public void aRunMayStartABiteOnlyFromTenBlocksOut() {
        assertTrue(CombatCommit.stance(Mode.RUN, 20, 12.5, false, false).mayEat());
        assertTrue(CombatCommit.stance(Mode.RUN, 5, 30, false, false).mayEat());
        assertTrue(CombatCommit.stance(Mode.RUN, 3, NOBODY, false, false).mayEat());
        assertTrue(CombatCommit.stance(Mode.RUN, 7, CombatRules.RUN_BITE_START, false, false).mayEat());
        // a follower at 9 does not start one (it would be cancelled a second later)
        assertFalse(CombatCommit.stance(Mode.RUN, 7, 9.9, false, false).mayEat());
        assertEquals(Stance.FLEE, CombatCommit.stance(Mode.RUN, 3, 9, false, false));
    }

    @Test
    public void aBiteThatHasStartedOnlyStopsInsideFive() {
        CombatCommit.Bite biting = new CombatCommit.Bite(false, NOBODY, true);
        // the same follower that could not start one at 9 does not stop one under way
        assertEquals(Stance.CALM, CombatCommit.stance(Mode.RUN, 7, 9, false, false, biting));
        assertEquals(Stance.CALM, CombatCommit.stance(Mode.RUN, 7, 5, false, false, biting));
        assertEquals(Stance.FLEE, CombatCommit.stance(Mode.RUN, 3, 4.9, false, false, biting));
        assertFalse(CombatCommit.stance(Mode.RUN, 20, 4.9, false, false, biting).mayEat());
        // and a bite that was dropped does not start again at 7
        assertFalse(CombatCommit.stance(Mode.RUN, 7, 7, false, false).mayEat());
    }

    // the numbers behind the pair, walked: a chewing player gains nothing on a follower (0.86 blocks a second), and a bite is
    // 32 ticks. closing is the blocks a second the follower gains, and each one has to finish its bite with a margin
    private static double firstStart(boolean fast) {
        for (double d = 5; d < 30; d += 0.05) {
            CombatCommit.Bite bite = new CombatCommit.Bite(false, fast ? d : NOBODY, false);
            if (CombatCommit.stance(Mode.RUN, 10, d, false, false, bite) == Stance.CALM) return d;
        }
        throw new AssertionError("never starts");
    }

    private static double finishedAt(double start, double closing) {
        double d = start;
        CombatCommit.Bite biting = new CombatCommit.Bite(false, NOBODY, true);
        for (int tick = 0; tick < 32; tick++) {
            assertEquals("tick " + tick + " at " + d, Stance.CALM, CombatCommit.stance(Mode.RUN, 10, d, false, false, biting));
            d -= closing / 20;
        }
        return d;
    }

    @Test
    public void aWalkingZombieWalkedInFromTenNeverInterruptsTheBite() {
        double start = firstStart(false);
        assertEquals(10, start, 0.05);
        // a zombie goes about 2.3 blocks a second, the chewing player 0.9: it gains 1.5
        double end = finishedAt(start, 1.5);
        assertTrue("ends at " + end, end > CombatRules.RUN_BITE_ABORT + 2);
        // and only once it gets inside the abort line does the stance go back to feet
        double d = end;
        CombatCommit.Bite biting = new CombatCommit.Bite(false, NOBODY, true);
        while (CombatCommit.stance(Mode.RUN, 10, d, false, false, biting) == Stance.CALM) d -= 0.05;
        assertEquals(CombatRules.RUN_BITE_ABORT, d, 0.1);
    }

    @Test
    public void theSameWalkInWorksWhenHurt() {
        // the walk above at 10 hp, now at 6: nothing about the hp changes the lines, and the quick bite is not a thing here
        double d = 10;
        CombatCommit.Bite biting = new CombatCommit.Bite(false, NOBODY, true);
        for (int tick = 0; tick < 32; tick++) {
            assertEquals("tick " + tick, Stance.CALM, CombatCommit.stance(Mode.RUN, 6, d, false, false, biting));
            d -= 1.5 / 20;
        }
        // inside the abort line it is feet again, and hp 3 does not turn it into the quick bite
        assertEquals(Stance.FLEE, CombatCommit.stance(Mode.RUN, 3, 4.5, false, false, biting));
    }

    // chewing a gapple is "biting" too, and the gapple's own lines (hp 8 or less, anything inside the start line) have to
    // hold through it or the use key toggles every other tick
    @Test
    public void aGappleKeepsBeingEatenThroughTheBiteBandWhileHurt() {
        CombatCommit.Bite biting = new CombatCommit.Bite(false, NOBODY, true);
        assertEquals(Stance.EAT_GAPPLE, CombatCommit.stance(Mode.RUN, 7, 7, false, true, biting));
        assertEquals(Stance.EAT_GAPPLE, CombatCommit.stance(Mode.RUN, 7, 7, false, true, CombatCommit.Bite.NONE));
        // far enough that the gapple was never wanted: ordinary food, ordinary bite
        assertEquals(Stance.CALM, CombatCommit.stance(Mode.RUN, 7, 11, false, true, biting));
        // healthy, a gapple is not a snack even mid bite
        assertEquals(Stance.CALM, CombatCommit.stance(Mode.RUN, 15, 7, false, true, biting));
    }

    @Test
    public void somethingFastIsKeptOutOfMayStartInsteadOfWideningTheAbortLine() {
        // spider: ~4 blocks a second, gains 3.2 on a chewing player. baby zombie: ~5.2, gains 4.3
        double start = firstStart(true);
        assertEquals(CombatRules.RUN_BITE_START_FAST, start, 0.05);
        assertTrue(finishedAt(start, 3.2) > CombatRules.RUN_BITE_ABORT + 2);
        assertTrue(finishedAt(start, 4.3) > CombatRules.RUN_BITE_ABORT);
        // a plain zombie's start would not have survived them, that is why they have their own
        assertTrue(10 - 4.3 * 1.6 < CombatRules.RUN_BITE_ABORT);
        // a fast one at 12 does not let a bite start even with nothing else near
        assertFalse(CombatCommit.stance(Mode.RUN, 5, 12, false, false, new CombatCommit.Bite(false, 12, false)).mayEat());
        assertTrue(CombatCommit.stance(Mode.RUN, 5, 13, false, false, new CombatCommit.Bite(false, 13, false)).mayEat());
        // but one that closes in on a bite under way is the abort line's business, not the start line's
        assertEquals(Stance.CALM, CombatCommit.stance(Mode.RUN, 5, 8, false, false, new CombatCommit.Bite(false, 8, true)));
    }

    @Test
    public void aShooterWithALineOnUsKeepsTheRunOffTheMenu() {
        CombatCommit.Bite shot = new CombatCommit.Bite(true, NOBODY, false);
        assertFalse(CombatCommit.stance(Mode.RUN, 7, 15, false, false, shot).mayEat());
        assertEquals(Stance.FLEE, CombatCommit.stance(Mode.RUN, 3, NOBODY, false, false, shot));
        // one that finds us mid bite does not stop it, only a threat inside the abort line does
        assertTrue(CombatCommit.stance(Mode.RUN, 7, 15, false, false, new CombatCommit.Bite(true, NOBODY, true)).mayEat());
        // not shot at, same spot: lunch
        assertTrue(CombatCommit.stance(Mode.RUN, 7, 15, false, false).mayEat());
    }

    @Test
    public void theBiteLinesAreNamedAndOrdered() {
        assertEquals(10, CombatRules.RUN_BITE_START, 0);
        assertEquals(5, CombatRules.RUN_BITE_ABORT, 0);
        assertEquals(13, CombatRules.RUN_BITE_START_FAST, 0);
        assertEquals(15, CombatRules.SHOOTER_RANGE, 0);
        assertTrue(CombatRules.RUN_BITE_ABORT < CombatRules.RUN_BITE_START);
        assertTrue(CombatRules.RUN_BITE_START < CombatRules.RUN_BITE_START_FAST);
    }

    // the quick bite at hp 4 means "nothing next to us". committed, something is within 8, so it is a flee
    @Test
    public void theQuickBiteBecomesAFleeWhileCommitted() {
        assertEquals(Stance.FLEE, CombatCommit.stance(Mode.RUN, 3, 6, false, false));
        assertEquals(Stance.FLEE, CombatCommit.stance(Mode.FIGHT, 3, NOBODY, false, false));
        // not committed (a cooldown, a hurt walk) the old quick bite stands
        assertEquals(Stance.EAT, CombatCommit.stance(Mode.NONE, 3, 6, false, false));
    }

    @Test
    public void aGappleIsWhatGapplesAreFor() {
        assertEquals(Stance.EAT_GAPPLE, CombatCommit.stance(Mode.RUN, 6, 5, false, true));
        assertEquals(Stance.EAT_GAPPLE, CombatCommit.stance(Mode.NONE, 6, 5, false, true));
        // healthy, a gapple is not a snack
        assertEquals(Stance.FIGHT, CombatCommit.stance(Mode.FIGHT, 18, 3, false, true));
    }

    @Test
    public void hurtWithSomethingNearIsNotAMealtimeEvenWithoutACommitment() {
        assertEquals(Stance.FLEE, CombatCommit.stance(Mode.NONE, 7, 6, false, false));
        // hurt but the nearest is past the line: eat
        assertEquals(Stance.CALM, CombatCommit.stance(Mode.NONE, 7, 9, false, false));
    }

    // hurt but not hungry enough to regenerate, nothing around, food in the bag
    @Test
    public void hurtAndCalmWithFoodInTheBagIsAMealtimeThatTheHealRuleWants() {
        float hp = 14;
        int hunger = 15;
        assertEquals(Stance.CALM, CombatCommit.stance(Mode.NONE, hp, NOBODY, false, false));
        assertTrue(CombatRules.healingMeal(hp, hunger));
        // a skeleton ten blocks off that has not touched us is scenery, the meal goes ahead
        assertEquals(Stance.CALM, CombatCommit.stance(Mode.NONE, hp, 10, false, false));
    }

    // a run that ends clear leaves a calm, hurt bot, and the heal rule has it eat before the user task walks on
    @Test
    public void aRunThatEndsClearThenEatsUpToTheRegenLineBeforeWork() {
        CombatCommit c = new CombatCommit();
        long t = 1000;
        float hp = 6;
        int hunger = 15;
        // hurt with a zombie close: a run
        assertEquals(CombatCommit.Event.RUN_START, c.step(new CombatCommit.Tick(t, hp, 0, 0,
                java.util.List.of(new CombatCommit.Foe(1, 5, false, false, 3)), null)));
        // still on our heels at 6: feet only
        assertEquals(Stance.FLEE, CombatCommit.stance(c.mode(), hp, 6, false, false));
        // it falls back to 10 and the bot may chew while it walks on
        assertEquals(Stance.CALM, CombatCommit.stance(c.mode(), hp, 10, false, false));
        assertTrue(CombatRules.healingMeal(hp, hunger));
        // and it closes to 7 mid bite: still chewing, only a threat inside 5 drops it
        CombatCommit.Bite biting = new CombatCommit.Bite(false, NOBODY, true);
        assertEquals(Stance.CALM, CombatCommit.stance(c.mode(), hp, 7, false, false, biting));
        assertEquals(Stance.FLEE, CombatCommit.stance(c.mode(), hp, 4, false, false, biting));
        // far away and quiet for two seconds, 30 blocks out: the run is over
        CombatCommit.Event last = CombatCommit.Event.NONE;
        for (long now = t + 1; now <= t + 100 && c.mode() == Mode.RUN; now++) {
            last = c.step(new CombatCommit.Tick(now, hp, 30, 0, java.util.List.of(), null));
        }
        assertEquals(CombatCommit.Event.RUN_CLEAR, last);
        // calm again, hurt, food in the bag: a meal, then the user task
        assertEquals(Stance.CALM, CombatCommit.stance(c.mode(), hp, NOBODY, false, false));
        assertTrue(CombatRules.healingMeal(hp, hunger));
        // at 18 the bar regenerates by itself, the heal rule is done
        assertFalse(CombatRules.healingMeal(hp, CombatRules.REGEN_HUNGER));
    }

    @Test
    public void whyTheStanceIsWhatItIsSaysSo() {
        assertEquals("running, nearest 6 blocks, too close to eat", CombatCommit.stanceWhy(Mode.RUN, 6, 6, false, CombatCommit.Bite.NONE));
        assertEquals("running, nearest 10 blocks, free to eat", CombatCommit.stanceWhy(Mode.RUN, 6, 10, false, CombatCommit.Bite.NONE));
        assertEquals("running, a shooter has a line on us", CombatCommit.stanceWhy(Mode.RUN, 6, 10, false, new CombatCommit.Bite(true, NOBODY, false)));
        assertEquals("fighting, no meals mid fight", CombatCommit.stanceWhy(Mode.FIGHT, 15, 2, false, CombatCommit.Bite.NONE));
        assertEquals("a lit creeper is close", CombatCommit.stanceWhy(Mode.NONE, 15, NOBODY, true, CombatCommit.Bite.NONE));
        assertEquals("hp 7 with something within 8, nearest 5 blocks", CombatCommit.stanceWhy(Mode.NONE, 7, 5, false, CombatCommit.Bite.NONE));
        assertEquals("calm, nothing angry in sight", CombatCommit.stanceWhy(Mode.NONE, 15, NOBODY, false, CombatCommit.Bite.NONE));
    }

    @Test
    public void aLitCreeperCloseKeepsUsOffTheMenu() {
        assertEquals(Stance.FIGHT, CombatCommit.stance(Mode.NONE, 20, NOBODY, true, false));
        assertFalse(CombatCommit.stance(Mode.NONE, 20, NOBODY, true, false).mayEat());
    }

    // a fight is no mealtime unless we are hungry and nothing that melees is within 4: the skeleton we are walking up to cannot
    // stop a bite, and a bar under 18 with no saturation is no regen at all
    @Test
    public void aFightWithNoMeleeMobNearAndAnEmptyishBarMayTakeABite() {
        CombatCommit.Bite far = new CombatCommit.Bite(true, NOBODY, false, 6, true);
        assertEquals(Stance.CALM, CombatCommit.stance(Mode.FIGHT, 9, 12, false, false, far));
        assertTrue(CombatCommit.stance(Mode.FIGHT, 9, 12, false, false, far).mayEat());
        assertEquals(Stance.CALM, CombatCommit.stance(Mode.FIGHT, 20, 12, false, false, new CombatCommit.Bite(true, NOBODY, false, NOBODY, true)));
        assertEquals("fighting, no melee mob within 4", CombatCommit.stanceWhy(Mode.FIGHT, 9, 12, false, far));
    }

    @Test
    public void aFightKeepsTheFistsUpForAMeleeMobWithinFourOrAFullBar() {
        assertEquals(Stance.FIGHT, CombatCommit.stance(Mode.FIGHT, 15, 4, false, false, new CombatCommit.Bite(false, NOBODY, false, 4, true)));
        assertEquals(Stance.FIGHT, CombatCommit.stance(Mode.FIGHT, 15, 12, false, false, new CombatCommit.Bite(true, NOBODY, false, 6, false)));
        // and a bite already under way gives way to the same line when the zombie walks up
        assertEquals(Stance.FIGHT, CombatCommit.stance(Mode.FIGHT, 15, 3, false, false, new CombatCommit.Bite(false, NOBODY, true, 3, true)));
    }

    @Test
    public void aFightAtGappleHpStillWantsTheGappleBite() {
        CombatCommit.Bite far = new CombatCommit.Bite(true, NOBODY, false, 6, true);
        assertEquals(Stance.EAT_GAPPLE, CombatCommit.stance(Mode.FIGHT, 8, 12, false, true, far));
    }

    // a wither skeleton out of sight is still walking at us: it keeps a run's bite from starting inside RUN_BITE_START, but
    // a bite already going is only dropped at the abort line, same as before
    @Test
    public void anUnseenWitherSkeletonKeepsARunBiteFromStarting() {
        double inf = Double.POSITIVE_INFINITY;
        CombatCommit.Bite close = new CombatCommit.Bite(false, inf, false, inf, false, CombatRules.RUN_BITE_START - 1);
        CombatCommit.Bite far = new CombatCommit.Bite(false, inf, false, inf, false, CombatRules.RUN_BITE_START + 1);
        CombatCommit.Bite chewing = new CombatCommit.Bite(false, inf, true, inf, false, CombatRules.RUN_BITE_ABORT + 1);
        assertFalse(CombatCommit.stance(Mode.RUN, 10, inf, false, false, close) == Stance.CALM);
        // (no hp exception: at 2 hp it is still no bite)
        assertFalse(CombatCommit.stance(Mode.RUN, 2, inf, false, false, close) == Stance.CALM);
        assertEquals(Stance.CALM, CombatCommit.stance(Mode.RUN, 10, inf, false, false, far));
        assertEquals(Stance.CALM, CombatCommit.stance(Mode.RUN, 10, inf, false, false, chewing));
        assertTrue(CombatCommit.stanceWhy(Mode.RUN, 10, inf, false, close).contains("wither skeleton"));
    }
}
