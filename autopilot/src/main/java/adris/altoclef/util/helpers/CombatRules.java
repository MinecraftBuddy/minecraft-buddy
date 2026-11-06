package adris.altoclef.util.helpers;

import java.util.List;

// the pure half of "are we in a fight, and what do we do about our health bar". no world, just numbers, so the eat
// vs fight vs flee call can be tested without minecraft. MobDefenseChain feeds it real data and FoodChain listens.
//
// the bug this exists for: health <= 10 made the food chain eat, eating made the defense chain switch itself off, and
// vanilla cannot swing a sword while chewing. a piglin was very happy about this
public final class CombatRules {

    // a lit fuse this close and we do not sit down to eat. we do not run from it either, walking past is faster
    public static final double CREEPER_RANGE = 10;

    // "is it on top of us": the only range a fight starts from (it also has to have hit us), and the corner rule's
    public static final double CONTACT_RANGE = 3;
    // melee mobs this close make a crowd for the weapon pick and the crits. not a reason to run any more, just to swing
    // for damage per second
    public static final double SWARM_RANGE = 6;
    // at or below FLEE_HEALTH anything angry this close is a reason to leave
    public static final double LOW_HP_RANGE = 8;

    // at or below this (half hearts) in a fight we leave instead of trading blows. the one hp line: the commitment, the
    // chain's checks and the kill aura's shield gate all read this and nothing else
    public static final float FLEE_HEALTH = 8;
    // the one exception, and it is a named one: a heavy hitter (hoglin, brute, vindicator...) lands 8 to 13
    // in one swing, so at 10 hp it is already "one hit from the respawn screen" and not a fight to start or to stay in.
    // nearly everything else hits for less than 8 (the wither skeleton is a plain fight with its own bail, see
    // CombatCommit.lowHit), which is why the flee line is where it is
    public static final float HEAVY_FLEE_HEALTH = 10;
    // at or below this a bite is better than a fight we are losing, as long as nothing is on top of us
    public static final float CRITICAL_HEALTH = 4;
    public static final double QUICK_BITE_CLEARANCE = 3;
    // a run may sit down to a bite when the nearest thing angry at us is at least RUN_BITE_START away (and nothing that shoots
    // has a line on us), and once it has started it is only dropped when somebody gets inside RUN_BITE_ABORT. one line for
    // both made a follower at 9 cancel every bite, and a bite that never lands is just a slower run.
    // the arithmetic: a bite is 32 ticks (1.6 s) and a chewing player walks at a fifth of the speed, about 0.9 blocks a second
    // (and cannot sprint). a zombie goes about 2.3 (the speed attribute counts twice for a mob, once as the speed and once as
    // the forward input), so it gains about 1.5 blocks a second, 2.4 over a bite: a bite started at 10 ends with it at 7.6,
    // 2.6 blocks clear of the abort line. a spider (0.3) goes about 4 and gains 5.1 over a bite, a baby zombie (0.345) about
    // 5.2 and gains 6.9: no pair of lines gives those margin at 10, and widening the abort line would drop every bite, so
    // they are kept out of "may start" instead: nothing that fast within RUN_BITE_START_FAST, which is the abort line plus
    // the worst gain (6.9) plus a block of slack, after which they end a bite at 6 or more
    public static final double RUN_BITE_START = 10;
    public static final double RUN_BITE_ABORT = 5;
    public static final double RUN_BITE_START_FAST = 13;
    // a mob with at least this movement speed attribute is "fast" for the bite (spiders, baby zombies, vindicators, and an
    // angry zombified piglin at 0.23 + 0.05; a plain zombie is 0.23, a skeleton 0.25). the epsilon is float noise on 0.28
    public static final double FAST_MOB_SPEED = 0.28 - 1e-6;
    // a shooter this far off with a line to us counts as "being shot at": no sitting down to a meal in its sights
    public static final double SHOOTER_RANGE = 15;
    // a committed melee fight has the shield up from this far out: most things swing once every 20 ticks or so and the
    // shield needs 5 to block. up, it holds one block past that, our own swing knocks the target back a bit and every
    // re-raise is 5 ticks of no block
    public static final double FIGHT_SHIELD_RANGE = CONTACT_RANGE + 1.5;
    public static final double FIGHT_SHIELD_HOLD = FIGHT_SHIELD_RANGE + 1;
    // on a rim (see rimShield) a melee foe this close is one hit from knocking us off
    public static final double RIM_SHIELD_FOE_RANGE = 4;
    // a cube counts as on us this close (EntityHelper). a flat CONTACT_RANGE was a small cube's reach, but a size 4 one is two
    // blocks wide and bites from past 3, so its first hit was in before it was a foe. its own width on top covers every size
    public static double cubeBiteRange(double width) {
        return CONTACT_RANGE + width;
    }
    // a cube or an angry piglin this close on a rim starts the fight before its first hit (CombatCommit.rimEngage). a big
    // cube covers three blocks in one hop, so at three the hit was in before the fight was. the fight lets go at
    // RIM_RELEASE, one block past it
    public static final double RIM_ENGAGE_RANGE = 5;
    public static final double RIM_RELEASE = RIM_ENGAGE_RANGE + 1;
    // the run's rim shield (rimRunFoe) looks this far: it stops the run, so only for what is about to land
    public static final double RIM_RUN_RANGE = 3;
    // the run's rim shield is up this many ticks in a row on one foe and then the run moves on, so it can never pin us there
    // (the commit's corner timer is held while it is up, see CombatCommit.pauseCorner)
    public static final int RIM_HOLD_TICKS = 60;

    // the meal rules, all in one place so the food chain and the tests read the same numbers.
    // natural regeneration only runs with the hunger bar at 18 or more, so hurt and under it means eat until it is there.
    // the older rules (hp under BRUISED_HEALTH, or hunger at HUNGRY_AT or less) leave a band where a bot neither regenerates
    // nor eats, and this closes it
    public static final int REGEN_HUNGER = 18;
    // any missing hp counts. at 18.6 with the bar on 17 nothing regenerates and nothing eats, and the next fight starts
    // from there. a steak into a bar of 17 wastes a few points, the bag carries plenty
    public static final float HURT_HEALTH = 20;
    // at or below this health any hunger under 20 is a meal
    public static final float DESPERATE_HEALTH = 10;
    // the older rules (the food chain config defaults): hungry at or below HUNGRY_AT, or below BRUISED_HEALTH with a
    // bar that is above it
    public static final int HUNGRY_AT = 10;
    public static final int BRUISED_HEALTH = 14;

    private CombatRules() {
    }

    // hurt, and the hunger bar is under the line where we start healing by ourselves
    public static boolean healingMeal(float health, int hunger) {
        return health < HURT_HEALTH && hunger < REGEN_HUNGER;
    }

    // standing still only heals if the bar is high enough or there is something to eat that does it
    public static boolean canRegen(int hunger, boolean healFood) {
        return hunger >= REGEN_HUNGER || healFood;
    }

    public static boolean desperateMeal(float health, int hunger) {
        return health <= DESPERATE_HEALTH && hunger < 20;
    }

    // hungry enough for a bite in a fight. burning counts at any bar short of full: fire takes 1 a second through armor for
    // 15 seconds after lava, and only a full bar with saturation in it heals faster than that (up to 2 a second). at 18 the
    // slow regen loses to the fire
    public static boolean fightHungry(int hunger, boolean burning) {
        return hunger < REGEN_HUNGER || (burning && hunger < 20);
    }

    // the chain asked for the wheel at this priority. a priority is a promise that something is running, so with no task
    // (or one that was born finished, a run we are already outside of) it is a zero. the live bug was eleven seconds of
    // "Mob Defense holds the wheel at 70.0 with nothing running"
    public static float wheelPriority(float priority, boolean hasTask, boolean taskFinished) {
        if (priority <= 0) return priority;
        return hasTask && !taskFinished ? priority : 0;
    }

    public enum Stance {
        // not fighting, eat whenever the food chain wants
        CALM,
        // fighting and healthy enough to keep at it. no eating, you cannot swing and chew
        FIGHT,
        // fighting and losing. run, eat once we are out of it
        FLEE,
        // fighting but about to die with nothing next to us, one bite is worth it
        EAT,
        // fighting and holding a gapple, which is what gapples are for
        EAT_GAPPLE;

        // the food chain may chew right now
        public boolean mayEat() {
            return this == CALM || this == EAT || this == EAT_GAPPLE;
        }
    }

    // above the flee line: healthy enough for the aura to put a shield up and for a fight to carry on
    public static boolean aboveFleeLine(float health) {
        return health > FLEE_HEALTH;
    }

    // the shield in every committed melee fight: the target is melee and close, or the pillager we walked up to (point blank
    // its bolts come from right where the shield faces). with a sword there is no crit hop for it to
    // veto (a sword never hops) and a melee target comes to us, one that never does is dropped by the stall rule. up: it is up
    // already, and then the hold range is the one asked
    public static boolean fightShield(boolean up, int targetId, List<CombatCommit.Foe> foes) {
        double reach = up ? FIGHT_SHIELD_HOLD : FIGHT_SHIELD_RANGE;
        for (CombatCommit.Foe foe : foes) {
            if (foe.id() == targetId && (foe.melee() || foe.patrol()) && foe.distance() <= reach) return true;
        }
        return false;
    }

    // the shield comes down for the walk up. a raised shield is a third of the speed and a mid jump lands short, and three blocks
    // of that under fire is longer than the hit it would have blocked. once it is in contact the path does not matter
    public static boolean fightShieldDown(boolean pathLive, double targetDistance) {
        return pathLive && targetDistance > CONTACT_RANGE;
    }

    // whether the aura's shield holds us still. a fight never does: a paused path never gets its first tick, so nothing ever
    // walks anywhere (same word as MobDefenseChain.startShielding). the rim guard and the plain aura shield still do, standing is the point there
    public static boolean auraShieldStandsStill(boolean fighting) {
        return !fighting;
    }

    // a fully blocked hit skips the knockback (not for a hoglin or zoglin, they still throw us), so on a ledge above a drop the shield is up whenever a melee foe is close. a
    // creeper is not a melee foe to the rules and the aura keeps the shield off it anyway. shooters are the blazeInContact
    // flag's business (a blaze that came to bite), a skeleton at point blank is not a knockback problem
    public static boolean rimShield(boolean edgeNear, boolean blazeInContact, List<CombatCommit.Foe> foes) {
        return rimShield(edgeNear, blazeInContact, foes, false);
    }

    // lavaClose: a cube past arm's reach doesn't count. this shield stands us still and the aura can't land a swing from there, so
    // it would be the wait CombatCommit.rimEngage just talked us out of. one in reach keeps it, that stand has a swing in it
    public static boolean rimShield(boolean edgeNear, boolean blazeInContact, List<CombatCommit.Foe> foes, boolean lavaClose) {
        if (!edgeNear) return false;
        if (blazeInContact) return true;
        for (CombatCommit.Foe foe : foes) {
            if (lavaClose && foe.splitType() != null && !foe.inReach()) continue;
            if (foe.melee() && foe.distance() <= RIM_SHIELD_FOE_RANGE) return true;
        }
        return false;
    }

    // the edge scan (the way fight's engage reads it) looks as far as the engage does, the shield keeps its own closer rule. a
    // scan that only woke at the shield's four blocks made the engage work at four, and a hopping cube covers that in one go
    public static final double RIM_SCAN_RANGE = Math.max(RIM_SHIELD_FOE_RANGE, RIM_ENGAGE_RANGE);

    public static boolean rimScan(boolean blazeInContact, List<CombatCommit.Foe> foes) {
        if (blazeInContact) return true;
        for (CombatCommit.Foe foe : foes) {
            if (foe.melee() && foe.distance() <= RIM_SCAN_RANGE) return true;
        }
        return false;
    }

    // a plain melee foe whose hit shoves us (cubes of any size, an angry zombified piglin). heavy hitters, hoglins included, are
    // not it: the aura keeps its shield off them and a run from them is the heavy rules' business
    public static boolean rimKnocker(CombatCommit.Foe foe) {
        return foe.knocker() && foe.melee() && foe.kind() == CombatCommit.Kind.NORMAL;
    }

    // a slime or magma cube among them. target may be null
    public static boolean cubeAbout(List<CombatCommit.Foe> foes, CombatCommit.Foe target) {
        if (target != null && target.splitType() != null) return true;
        for (CombatCommit.Foe foe : foes) {
            if (foe.splitType() != null) return true;
        }
        return false;
    }

    // on a rim in a run: the closest foe within RIM_RUN_RANGE that is a knocker or has hit us (plain melee only), or null.
    // the run walks on with it biting and the next hit is the knock into the lava, so the shield goes up for it
    public static CombatCommit.Foe rimRunFoe(boolean edgeNear, List<CombatCommit.Foe> foes) {
        if (!edgeNear) return null;
        CombatCommit.Foe best = null;
        for (CombatCommit.Foe foe : foes) {
            if (!foe.melee() || foe.kind() != CombatCommit.Kind.NORMAL || foe.distance() > RIM_RUN_RANGE) continue;
            if (!foe.knocker() && !foe.hitUs()) continue;
            if (best == null || foe.distance() < best.distance()) best = foe;
        }
        return best;
    }

    // nearestThreat is the distance to the closest mob that can actually hurt us (infinity for none)
    public static Stance stance(boolean inCombat, float health, double nearestThreat, boolean hasGapple) {
        return stance(inCombat, health, nearestThreat, hasGapple, true);
    }

    // hasFood: the food chain has something it would eat. without it the EAT stance is a promise nobody can keep (it still
    // sets the in-combat flag in the food chain and the bucket chain), so it is the flee stance instead
    public static Stance stance(boolean inCombat, float health, double nearestThreat, boolean hasGapple, boolean hasFood) {
        if (!inCombat) return Stance.CALM;
        if (health <= FLEE_HEALTH && hasGapple) return Stance.EAT_GAPPLE;
        if (health <= CRITICAL_HEALTH && nearestThreat > QUICK_BITE_CLEARANCE && hasFood) return Stance.EAT;
        if (health <= FLEE_HEALTH) return Stance.FLEE;
        return Stance.FIGHT;
    }
}
