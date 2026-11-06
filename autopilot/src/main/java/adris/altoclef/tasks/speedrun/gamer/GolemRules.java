package adris.altoclef.tasks.speedrun.gamer;

import java.util.ArrayList;
import java.util.List;

// the numbers and decisions of the golem hunt with no minecraft in them, so a test can poke them. all of the vanilla
// facts below were read out of the 1.21.4 mojmap jar, not remembered:
//  - iron golem is 1.4 wide and 2.7 tall (EntityType.IRON_GOLEM sized(1.4, 2.7)), 100 hp, knockback resistance 1
//  - Mob.getAttackBoundingBox is its own box inflated by DEFAULT_ATTACK_REACH (sqrt(2.04) - 0.6 = 0.828) in x and z
//    and by ZERO in y, and isWithinMeleeAttackRange is an AABB.intersects (strict) against our hitbox. so the whole
//    question is "is our hitbox bottom below the golem's head", nothing about horizontal distance saves us
//  - MeleeAttackGoal.canUse is true with a partial path or in range, so a golem under a pillar keeps coming and
//    stands against it, it just can never get its box up to our feet
//  - Player.isWithinEntityInteractionRange (canInteractWithEntity before 26.3) is the distance from our EYE to the nearest point of the golem's box against the
//    entity interaction range, 3.0 in survival. the server is looser (it asks with a 3.0 buffer) but we play fair
public final class GolemRules {
    public static final double GOLEM_HEIGHT = 2.7;
    public static final double EYE_HEIGHT = 1.62;
    public static final double INTERACT_RANGE = 3.0;

    private GolemRules() {
    }

    // our hitbox bottom (feet) has to be at or above the golem's head to be out of its attack box. the margin is
    // what makes it a real 3.0 on flat ground instead of a 2.7 that a slab or a path block eats
    public static double safeFeetY(double golemFeetY, double margin) {
        return golemFeetY + GOLEM_HEIGHT + margin;
    }

    // a golem walks up one block without thinking about it
    public static final double STEP_UP = 1.0;

    // the feet height the golem could be standing at right beside the pillar: where it is now, or the highest ground a step
    // up from there on one of the sides (NaN = no step on that side). the pillar is built for that one, because a golem that
    // steps up next to a pillar made for the low side gets its head over our feet and the hit lands before ours does
    public static double groundBeside(double golemFeetY, double[] sideSurfaces) {
        double limit = Math.floor(golemFeetY + 1.0e-3) + STEP_UP + 1.0e-3;
        double best = golemFeetY;
        for (double top : sideSurfaces) {
            if (top > best && top <= limit) {
                best = top;
            }
        }
        return best;
    }

    // how many blocks to put under us so we end up on safeFeetY. 0 = already high enough. -1 = more than we want to
    // stack. feet are an integer (standing on a block) so the answer is a ceil, with a hair of slack for float noise
    public static int blocksToRaise(double ourFeetY, double golemFeetY, double margin, int maxBlocks) {
        double need = safeFeetY(golemFeetY, margin) - ourFeetY;
        int blocks = (int) Math.ceil(need - 1.0e-6);
        if (blocks <= 0) {
            return 0;
        }
        return blocks > maxBlocks ? -1 : blocks;
    }

    // blocks to carry on top of the pillar's need. a block that lands wrong, a slab, a step we did not see: two spare is
    // what the 4-block-need-5-block-bag run was missing
    public static final int SPARE_BLOCKS = 2;
    // a golem this far above our feet is up a cliff or out of a tunnel, and walking to it is not a plan (the pillar budget
    // is 5, nobody is climbing 10 blocks to start a fight)
    public static final double MAX_RISE = 6;
    // we stand where the golem's ground is, give or take: a step, a path block, a slab. count on being one block under
    public static final double GROUND_SLACK = 1.0;
    // an aborted fight that gave its golem a cooldown instead of burning it, at most this many times per run
    public static final int MAX_REFUNDS = 3;
    public static final double RETRY_COOLDOWN_SECONDS = 60;

    // pillar blocks the fight will want, decided BEFORE we walk over there (the fight itself used to find out at the foot of
    // the golem, bail, and burn the golem with it). -1 = not worth starting: too tall, or too far above us
    public static int launchNeed(double ourFeetY, double golemFeetY, double margin, int maxBlocks, boolean nearGolem) {
        if (golemFeetY - ourFeetY > MAX_RISE) {
            return -1;
        }
        if (!nearGolem) {
            // far away our feet say nothing about where we will stand at its foot (the walk there follows its ground), so
            // count on being a block under it. using our real Y from the bottom of a hill hid every golem on top of it
            return blocksToRaise(golemFeetY - GROUND_SLACK, golemFeetY, margin, maxBlocks);
        }
        // next to it the fight stacks from our real feet, and the block of slack used to promise a pillar the fight then
        // called too tall. under its ground that is the real number, above it we still assume the slack
        return blocksToRaise(Math.min(ourFeetY, golemFeetY - GROUND_SLACK), golemFeetY, margin, maxBlocks);
    }

    // which golems the hunt may even look at. the nearest golem used to win before any of this was asked, so one angry or
    // up a cliff blocked every calm one behind it
    public static boolean eligible(boolean angry, boolean tried, boolean coolingDown, int launchNeed) {
        return !angry && !tried && !coolingDown && launchNeed >= 0;
    }

    // what the bag has to hold to launch: the configured floor, or the need plus spares if that is more
    public static int blocksWanted(int need, int minBlocks) {
        return Math.max(minBlocks, need + SPARE_BLOCKS);
    }

    // why a fight ended early. the first few are about the day we picked, not the golem, so the golem is not burned
    public enum Abort {
        NONE(false), NO_BLOCKS(true), PILLAR_STUCK(true), MONSTERS(true), ANGRY_ON_GROUND(true),
        // on the pillar and never landed a hit: the golem wandered off or the ray was blocked. the day, not the golem
        OUT_OF_REACH(true),
        // the golem stopped existing for us (unloaded, teleported) before we ever touched it
        LOST(true),
        TOO_TALL(false), UNREACHABLE(false), GONE(false), GAVE_UP(false);

        private final boolean retryable;

        Abort(boolean retryable) {
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }
    }

    // give the golem back to the pool (with a cooldown) instead of marking it tried. capped, or a pillar that never works
    // would be tried for ever
    public static boolean refund(Abort why, int refundsUsed) {
        return why.retryable() && refundsUsed < MAX_REFUNDS;
    }

    public static boolean coolingDown(long now, Long until) {
        return until != null && now < until;
    }

    // can it hit us right now: our hitbox bottom is under its head. no hair of slack, vanilla's check is strict but we
    // would rather be wrong in the cautious direction
    public static boolean golemCanHitUs(double ourFeetY, double golemFeetY) {
        return ourFeetY < golemFeetY + GOLEM_HEIGHT + 1.0e-3;
    }

    // can WE hit it: distance from our eye to the nearest point of its box
    public static boolean inReach(double eyeX, double eyeY, double eyeZ,
                                  double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                                  double range) {
        double dx = Math.max(Math.max(minX - eyeX, 0), eyeX - maxX);
        double dy = Math.max(Math.max(minY - eyeY, 0), eyeY - maxY);
        double dz = Math.max(Math.max(minZ - eyeZ, 0), eyeZ - maxZ);
        return dx * dx + dy * dy + dz * dz < range * range;
    }

    // points of the golem's box worth aiming a line of sight at, nearest first. vanilla melee needs the hitbox in reach and
    // nothing solid in the way of the part of it we hit, not a clear ray to the middle of its head, which is what a pillar
    // corner kills while the golem is standing right there. every point is inside the range itself
    public static List<double[]> aimPoints(double eyeX, double eyeY, double eyeZ,
                                           double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                                           double range) {
        double cx = clamp(eyeX, minX, maxX);
        double cy = clamp(eyeY, minY, maxY);
        double cz = clamp(eyeZ, minZ, maxZ);
        double mx = (minX + maxX) / 2;
        double mz = (minZ + maxZ) / 2;
        double[][] raw = {
                {cx, cy, cz},
                // a hair inside the box: a ray that ends exactly on a face can graze the block that face is touching
                {cx + (mx - cx) * 0.2, cy, cz + (mz - cz) * 0.2},
                {mx, maxY - 0.1, mz},
                {mx, (minY + maxY) / 2, mz},
        };
        List<double[]> out = new ArrayList<>();
        for (double[] pt : raw) {
            double dx = pt[0] - eyeX;
            double dy = pt[1] - eyeY;
            double dz = pt[2] - eyeZ;
            if (dx * dx + dy * dy + dz * dz < range * range) {
                out.add(pt);
            }
        }
        return out;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // the golem's box for a golem standing at (x, feetY, z)
    public static boolean inReachOfGolem(double eyeX, double eyeY, double eyeZ, double gx, double gFeetY, double gz, double range) {
        double half = 0.7;
        return inReach(eyeX, eyeY, eyeZ, gx - half, gFeetY, gz - half, gx + half, gFeetY + GOLEM_HEIGHT, gz + half, range);
    }

    // everything the trigger looks at. plain numbers so the test does not need a world
    public record Inputs(boolean ironNeeded, boolean overworld, boolean golemFound, boolean golemAngry, boolean alreadyTried,
                         int attemptsUsed, int maxAttempts, boolean hasWeapon, int buildBlocks, int minBlocks,
                         float health, float minHealth, boolean hostilesNearby, boolean onGround, boolean inFluid,
                         int pillarNeed) {
    }

    public enum Verdict {
        GO, NO_IRON_NEEDED, WRONG_DIMENSION, NO_GOLEM, GOLEM_ANGRY, TRIED, OUT_OF_ATTEMPTS, NO_WEAPON, NO_BLOCKS, TOO_HURT,
        HOSTILES, NOT_STANDING, BAD_GROUND;

        public boolean go() {
            return this == GO;
        }
    }

    public static Verdict shouldHunt(Inputs in) {
        if (!in.ironNeeded) return Verdict.NO_IRON_NEEDED;
        if (!in.overworld) return Verdict.WRONG_DIMENSION;
        if (in.attemptsUsed >= in.maxAttempts) return Verdict.OUT_OF_ATTEMPTS;
        if (!in.golemFound) return Verdict.NO_GOLEM;
        // one that is already angry at us while we stand on the ground is mob defense's business, not a plan
        if (in.golemAngry) return Verdict.GOLEM_ANGRY;
        if (in.alreadyTried) return Verdict.TRIED;
        if (!in.hasWeapon) return Verdict.NO_WEAPON;
        // pillarNeed < 0 = too tall or up a cliff, no amount of blocks fixes that
        if (in.pillarNeed < 0) return Verdict.BAD_GROUND;
        if (in.buildBlocks < blocksWanted(in.pillarNeed, in.minBlocks)) return Verdict.NO_BLOCKS;
        if (in.health < in.minHealth) return Verdict.TOO_HURT;
        if (in.hostilesNearby) return Verdict.HOSTILES;
        if (!in.onGround || in.inFluid) return Verdict.NOT_STANDING;
        return Verdict.GO;
    }

    // we are up on the pillar and it is angry or was hit by us lately: coming down means walking into 7 to 21 damage.
    // it is only safe to leave when it is gone, or when it has had long enough to calm down (anger lasts 20 to 39 s
    // after the last hit) or is far away. all times in ticks
    public static boolean safeToComeDown(boolean golemAlive, boolean golemAngryNow, double golemDistance, long ticksSinceLastHit,
                                         long calmTicks, double farAway) {
        if (!golemAlive) return true;
        if (golemAngryNow) return false;
        if (golemDistance > farAway) return true;
        return ticksSinceLastHit > calmTicks;
    }

    // leave the pillar? only when we want out (or the soft cap hit) AND it is safe. the one exception is the hold cap, so an
    // "angry" flag that never clears (a zombie it is busy with) cannot park us up there for the rest of the run
    public static boolean leavePillar(boolean wantsOut, double fightSeconds, double softCapSeconds, double holdCapSeconds,
                                      boolean safe) {
        if (fightSeconds > holdCapSeconds) return true;
        return (wantsOut || fightSeconds > softCapSeconds) && safe;
    }
}
