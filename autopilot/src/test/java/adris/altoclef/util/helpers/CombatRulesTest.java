package adris.altoclef.util.helpers;

import static adris.altoclef.util.helpers.CombatRules.Stance.CALM;
import static adris.altoclef.util.helpers.CombatRules.Stance.EAT;
import static adris.altoclef.util.helpers.CombatRules.Stance.EAT_GAPPLE;
import static adris.altoclef.util.helpers.CombatRules.Stance.FIGHT;
import static adris.altoclef.util.helpers.CombatRules.Stance.FLEE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

// the eat / fight / flee call and the one hp line, as numbers
public class CombatRulesTest {

    private static final double NOBODY = Double.POSITIVE_INFINITY;

    // in a fight with the nearest thing at `threat` blocks
    private static CombatRules.Stance fighting(float health, double threat, boolean gapple) {
        return CombatRules.stance(true, health, threat, gapple);
    }

    @Test
    public void calmMeansEatWhenYouWant() {
        assertEquals(CALM, CombatRules.stance(false, 10, NOBODY, false));
        assertEquals(CALM, CombatRules.stance(false, 2, 12, true));
        assertTrue(CALM.mayEat());
    }

    @Test
    public void tenHpPiglinInOurFaceIsAFightNotALunch() {
        // needsToEat used to say yes at 10 hp, which turned the defense off in the middle of the fight
        CombatRules.Stance s = fighting(10, 1.5, false);
        assertEquals(FIGHT, s);
        assertFalse(s.mayEat());
    }

    @Test
    public void healthyFightStaysAFight() {
        assertEquals(FIGHT, fighting(20, 3, false));
        assertEquals(FIGHT, fighting(9, 3, false));
    }

    @Test
    public void lowHpInAFightMeansLeave() {
        CombatRules.Stance s = fighting(8, 2, false);
        assertEquals(FLEE, s);
        assertFalse(s.mayEat());
        assertEquals(FLEE, fighting(5, 2, false));
    }

    @Test
    public void almostDeadWithNothingCloseTakesAQuickBite() {
        CombatRules.Stance s = fighting(4, 5, false);
        assertEquals(EAT, s);
        assertTrue(s.mayEat());
    }

    @Test
    public void almostDeadWithSomethingOnUsRuns() {
        assertEquals(FLEE, fighting(4, 2, false));
        assertEquals(FLEE, fighting(2, 3, false));
    }

    @Test
    public void quickBiteIsNotAnOptionAtEightHp() {
        // 8 hp with a mob five blocks out is still a flee, the bite is for the last four
        assertEquals(FLEE, fighting(8, 5, false));
    }

    @Test
    public void gappleInAFightIsEatenWhenHurt() {
        CombatRules.Stance s = fighting(8, 1, true);
        assertEquals(EAT_GAPPLE, s);
        assertTrue(s.mayEat());
        assertEquals(EAT_GAPPLE, fighting(3, 1, true));
    }

    @Test
    public void gappleIsNotWastedWhileHealthy() {
        assertEquals(FIGHT, fighting(16, 1, true));
    }

    @Test
    public void gappleDoesNothingOutsideAFight() {
        assertEquals(CALM, CombatRules.stance(false, 4, NOBODY, true));
    }

    @Test
    public void aPriorityNeedsATaskThatIsStillRunning() {
        // "Mob Defense holds the wheel at 70.0 with nothing running"
        assertEquals(70f, CombatRules.wheelPriority(70, true, false), 0);
        assertEquals(0f, CombatRules.wheelPriority(70, false, false), 0);
        assertEquals("a run we were already outside of", 0f, CombatRules.wheelPriority(80, true, true), 0);
        assertEquals("not asking for it is not asking for it", Float.NEGATIVE_INFINITY, CombatRules.wheelPriority(Float.NEGATIVE_INFINITY, false, true), 0);
        assertEquals(0f, CombatRules.wheelPriority(0, false, false), 0);
    }

    @Test
    public void fusingCreeperAtLowHpIsStillAFlee() {
        assertEquals(FLEE, fighting(6, 4, false));
    }

    // ---- the one hp line

    @Test
    public void theFleeLineIsEight() {
        assertEquals(8, CombatRules.FLEE_HEALTH, 0);
    }

    @Test
    public void theCommitmentAndTheFleeStanceUseTheSameLine() {
        assertEquals(CombatRules.FLEE_HEALTH, CombatCommit.FLEE_HP, 0);
        // hp 8 is a flee in the stance and a run in the machine, hp 8.5 is neither
        assertEquals(FLEE, fighting(CombatRules.FLEE_HEALTH, 2, false));
        assertEquals(FIGHT, fighting(CombatRules.FLEE_HEALTH + 0.5f, 2, false));
        assertEquals(CombatCommit.Event.RUN_START, new CombatCommit().step(new CombatCommit.Tick(100, CombatRules.FLEE_HEALTH, 0, 0,
                java.util.List.of(new CombatCommit.Foe(1, 2, false, false, 3)), null)));
        assertEquals(CombatCommit.Event.FIGHT_START, new CombatCommit().step(new CombatCommit.Tick(100, CombatRules.FLEE_HEALTH + 0.5f, 0, 0,
                java.util.List.of(new CombatCommit.Foe(1, 2, false, false, 3)), null)));
    }

    @Test
    public void theAuraShieldGateIsTheSameLine() {
        // KillAura asks aboveFleeLine, unless the machine has us in a committed fight (then the flee line was its call and the
        // aura keeps going): 8 is not healthy enough, 8.5 is, and the old 10 gate is gone
        assertFalse(CombatRules.aboveFleeLine(CombatRules.FLEE_HEALTH));
        assertTrue(CombatRules.aboveFleeLine(CombatRules.FLEE_HEALTH + 0.5f));
        assertTrue(CombatRules.aboveFleeLine(9));
        assertFalse(CombatRules.aboveFleeLine(0));
    }

    @Test
    public void theHeavyLineIsTheOnlyExceptionAndItIsAboveTheFleeLine() {
        assertEquals(10, CombatRules.HEAVY_FLEE_HEALTH, 0);
        assertEquals(CombatRules.HEAVY_FLEE_HEALTH, CombatCommit.HEAVY_FLEE_HP, 0);
        assertTrue(CombatRules.HEAVY_FLEE_HEALTH > CombatRules.FLEE_HEALTH);
    }

    // ---- the meal lines

    @Test
    public void hurtWithAHungerBarTooLowToRegenerateIsAMeal() {
        // hp 14 with hunger 15 is under neither older line (hp under 14, hunger 10 or less) and under the regen line
        assertTrue(CombatRules.healingMeal(14, 15));
        assertTrue(CombatRules.healingMeal(17.5f, 17));
        assertTrue(CombatRules.healingMeal(5, 11));
        assertTrue(CombatRules.healingMeal(1, 0));
    }

    @Test
    public void notHurtOrAlreadyRegeneratingIsNotAHealingMeal() {
        // 18 is where the bar regenerates by itself
        assertFalse(CombatRules.healingMeal(14, CombatRules.REGEN_HUNGER));
        assertFalse(CombatRules.healingMeal(14, 20));
        // and full hp is not hurt at all
        assertFalse(CombatRules.healingMeal(CombatRules.HURT_HEALTH, 10));
        assertFalse(CombatRules.healingMeal(20, 5));
        // but a nick under full with the bar under the regen line is a meal, or it never heals
        assertTrue(CombatRules.healingMeal(18.6f, 17));
    }

    @Test
    public void theOlderMealLinesKeepTheirNumbers() {
        assertEquals(18, CombatRules.REGEN_HUNGER);
        assertEquals(20, CombatRules.HURT_HEALTH, 0);
        assertEquals(10, CombatRules.DESPERATE_HEALTH, 0);
        assertEquals(10, CombatRules.HUNGRY_AT);
        assertEquals(14, CombatRules.BRUISED_HEALTH);
        assertTrue(CombatRules.desperateMeal(10, 19));
        assertFalse(CombatRules.desperateMeal(10, 20));
        assertFalse(CombatRules.desperateMeal(10.5f, 5));
    }

    @Test
    public void theSharedRangesAreWhereTheMachineReadsThem() {
        assertEquals(3, CombatRules.CONTACT_RANGE, 0);
        assertEquals(6, CombatRules.SWARM_RANGE, 0);
        assertEquals(8, CombatRules.LOW_HP_RANGE, 0);
        assertEquals(10, CombatRules.CREEPER_RANGE, 0);
        assertEquals(CombatRules.CONTACT_RANGE, CombatCommit.CONTACT, 0);
        assertEquals(CombatRules.LOW_HP_RANGE, CombatCommit.LOW_HP_RANGE, 0);
        // the stall tracker uses the same two, a mob in contact is never stuck and one on our heels while we run neither
        assertEquals(CombatRules.CONTACT_RANGE, MobReachRules.STALL_EXEMPT_RANGE, 0);
        assertEquals(CombatRules.LOW_HP_RANGE, MobReachRules.FLEEING_STALL_RANGE, 0);
    }

    // ---- the fight shield. foe 1 is the fight target throughout

    private static final long NEVER = CombatCommit.NEVER;

    private static CombatCommit.Foe zombie(int id, double distance, long sinceHit) {
        return new CombatCommit.Foe(id, distance, false, false, sinceHit);
    }

    private static CombatCommit.Foe hoglin(int id, double distance) {
        return new CombatCommit.Foe(id, distance, false, false, NEVER, CombatCommit.Kind.HEAVY);
    }

    private static CombatCommit.Foe magmaCube(int id, double distance) {
        return new CombatCommit.Foe(id, distance, false, false, NEVER, CombatCommit.Kind.NORMAL);
    }

    private static CombatCommit.Foe skeleton(int id, double distance) {
        return new CombatCommit.Foe(id, distance, true, false, NEVER);
    }

    private static CombatCommit.Foe creeper(int id, double distance) {
        return new CombatCommit.Foe(id, distance, false, true, NEVER);
    }

    private static CombatCommit.Foe enderman(int id, double distance) {
        return new CombatCommit.Foe(id, distance, false, false, NEVER, CombatCommit.Kind.ENDERMAN);
    }

    private static boolean shield(boolean up, CombatCommit.Foe... foes) {
        return CombatRules.fightShield(up, 1, List.of(foes));
    }

    @Test
    public void theFightShieldWantsAMeleeTargetCloseAndHoldsALittleFurther() {
        for (CombatCommit.Foe[] foe : new CombatCommit.Foe[][]{
                {zombie(1, 4, NEVER)}, {hoglin(1, 4)}, {enderman(1, 4)}, {magmaCube(1, 4)}}) {
            assertTrue(shield(false, foe));
        }
        assertFalse(shield(false, zombie(1, 5, NEVER)));
        assertFalse(shield(false, hoglin(1, 5)));
        assertTrue(shield(true, zombie(1, 5, NEVER)));
        assertTrue(shield(true, hoglin(1, 5)));
        assertTrue(shield(true, enderman(1, 5)));
        assertTrue(shield(true, magmaCube(1, 5)));
        assertFalse(shield(true, zombie(1, 6, NEVER)));
        assertFalse(shield(true, hoglin(1, 6)));
    }

    @Test
    public void aLoneZombieGetsTheFightShieldToo() {
        assertTrue(shield(false, zombie(1, 1, 0)));
        assertTrue(shield(true, zombie(1, 1, 0)));
    }

    @Test
    public void aShooterOrACreeperTargetNeverGetsTheFightShield() {
        assertFalse(shield(false, skeleton(1, 2)));
        assertFalse(shield(true, skeleton(1, 2)));
        assertFalse(shield(false, creeper(1, 2)));
        assertFalse(shield(true, creeper(1, 2)));
    }

    @Test
    public void onlyTheTargetCountsForTheFightShield() {
        assertFalse(shield(false, zombie(2, 2, 0)));
        assertFalse(shield(false, zombie(1, 6, 0), zombie(2, 2, 0), zombie(3, 2, 0)));
        assertFalse(shield(true, zombie(2, 2, 0), zombie(3, 2, 0)));
    }

    @Test
    public void aPileInContactStillGetsTheFightShield() {
        assertTrue(shield(false, zombie(1, 2.5, 30), zombie(2, 2.9, 10)));
        assertTrue(shield(false, zombie(1, 2.5, NEVER), zombie(2, 2.9, 10)));
    }

    @Test
    public void theHealMealCoversTheWholeBandTheRodsTaskWaitsIn() {
        // the rods task heals to RECOVERED_HEALTH after a run, and eating has to reach that far on its own
        assertTrue(CombatRules.healingMeal(BlazeFightRules.RECOVERED_HEALTH - 0.5f, CombatRules.REGEN_HUNGER - 1));
        assertTrue(CombatRules.healingMeal(14.9f, 15));
    }

    @Test
    public void noFoodMeansNoEatStance() {
        // hp 3 with nobody near: the quick bite, but only with something to bite
        assertEquals(CombatRules.Stance.EAT, CombatRules.stance(true, 3, NOBODY, false, true));
        assertEquals(CombatRules.Stance.FLEE, CombatRules.stance(true, 3, NOBODY, false, false));
        // the gapple is its own food and does not need the pick
        assertEquals(CombatRules.Stance.EAT_GAPPLE, CombatRules.stance(true, 3, NOBODY, true, false));
        // the old four argument form still assumes food
        assertEquals(CombatRules.Stance.EAT, CombatRules.stance(true, 3, NOBODY, false));
    }

    // ---- the rim shield

    private static CombatCommit.Foe shooter(int id, double distance) {
        return new CombatCommit.Foe(id, distance, true, false, NEVER);
    }

    @Test
    public void theRimShieldWantsAnEdgeAndAMeleeFoeWithinFour() {
        assertTrue(CombatRules.rimShield(true, false, List.of(zombie(1, 4, NEVER))));
        assertFalse(CombatRules.rimShield(true, false, List.of(zombie(1, 4.5, NEVER))));
        // no drop near us, no rim
        assertFalse(CombatRules.rimShield(false, false, List.of(zombie(1, 1, 5))));
        assertFalse(CombatRules.rimShield(true, false, List.of()));
    }

    @Test
    public void theEdgeScanWakesAsFarOutAsTheEngageNotTheShield() {
        assertTrue(CombatRules.rimScan(false, List.of(zombie(1, 5, NEVER))));
        assertFalse(CombatRules.rimScan(false, List.of(zombie(1, 5.5, NEVER))));
        assertTrue(CombatRules.rimScan(true, List.of()));
        assertFalse(CombatRules.rimScan(false, List.of(shooter(1, 2))));
        // the shield itself still waits for four
        assertFalse(CombatRules.rimShield(true, false, List.of(zombie(1, 5, NEVER))));
    }

    @Test
    public void theRimShieldOnlyCountsABlazeInContactAmongShooters() {
        assertTrue(CombatRules.rimShield(true, true, List.of()));
        assertFalse(CombatRules.rimShield(false, true, List.of()));
        // a skeleton at point blank is no knockback problem, and a creeper is not melee here
        assertFalse(CombatRules.rimShield(true, false, List.of(shooter(1, 1))));
        assertFalse(CombatRules.rimShield(true, false, List.of(new CombatCommit.Foe(1, 1, false, true, NEVER))));
    }

    @Test
    public void burningIsHungryUntilTheBarIsFull() {
        // not burning: the regen line, same as before
        assertTrue(CombatRules.fightHungry(17, false));
        assertFalse(CombatRules.fightHungry(18, false));
        // burning: 18 and 19 still want the bite, 20 cannot take one
        assertTrue(CombatRules.fightHungry(18, true));
        assertTrue(CombatRules.fightHungry(19, true));
        assertFalse(CombatRules.fightHungry(20, true));
    }

    // magma cube width is 0.52 per size
    @Test
    public void aBigCubeIsAFoeFromFurtherOutThanItBites() {
        assertEquals(5.08, CombatRules.cubeBiteRange(0.52 * 4), 1e-9);
        assertEquals(4.04, CombatRules.cubeBiteRange(0.52 * 2), 1e-9);
        assertEquals(3.52, CombatRules.cubeBiteRange(0.52), 1e-9);
        // a size 4 bites from about 3.2, the old flat 3 was short of that
        assertTrue(CombatRules.cubeBiteRange(0.52 * 4) > 3.2);
        // never tighter than it was
        assertTrue(CombatRules.cubeBiteRange(0.52) > CombatRules.CONTACT_RANGE);
    }

    @Test
    public void theShieldComesDownForTheWalkUpOnly() {
        assertTrue(CombatRules.fightShieldDown(true, CombatRules.CONTACT_RANGE + 0.5));
        assertFalse(CombatRules.fightShieldDown(true, CombatRules.CONTACT_RANGE));
        // no path going: nothing to walk, the fight's own shield rule decides
        assertFalse(CombatRules.fightShieldDown(false, CombatRules.CONTACT_RANGE + 0.5));
    }

    @Test
    public void aFightsShieldNeverStandsUsStillTheRimGuardsDoes() {
        assertFalse(CombatRules.auraShieldStandsStill(true));
        assertTrue(CombatRules.auraShieldStandsStill(false));
    }

    @Test
    public void aPatrolTargetGetsTheFightShieldAPlainShooterDoesNot() {
        CombatCommit.Foe skeleton = new CombatCommit.Foe(1, 4, true, false, NEVER);
        assertFalse(shield(false, skeleton));
        assertTrue(shield(false, skeleton.withPatrol(true)));
        assertTrue(shield(true, new CombatCommit.Foe(1, 5, true, false, NEVER).withPatrol(true)));
        assertFalse(shield(false, new CombatCommit.Foe(1, 5, true, false, NEVER).withPatrol(true)));
        // only the one we are on, the other four are not who it faces
        assertFalse(shield(false, new CombatCommit.Foe(2, 4, true, false, NEVER).withPatrol(true)));
    }
}
