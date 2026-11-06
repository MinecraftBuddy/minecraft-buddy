package adris.altoclef.tasks.speedrun.gamer.tasks;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

// the pure half of "is it a good idea to walk back to the pile". the last two deaths were plain zombies, and the bot walked
// straight back into the same zombies to get its stuff and died again. no world in here, RecoverItemsTask feeds it numbers
public final class RecoverRules {

    // a hostile this close to the pile is between us and our stuff
    public static final double THREAT_RADIUS = 16;
    // and not eight floors up or down, that one has a lot of rock to get through first
    public static final double THREAT_HEIGHT = 8;
    // how long we are willing to stand off and wait for the crowd to wander off before the stuff is not worth it
    public static final double WAIT_SECONDS = 30;
    // and how far from the threats we wait
    public static final double SAFE_DISTANCE = 24;

    private RecoverRules() {
    }

    public enum Verdict {
        // nothing at the pile, walk
        GO,
        // somebody is there, stay back and see if they leave
        WAIT,
        // they are not leaving. the items were not worth a second death
        GIVE_UP
    }

    // offsets from the death spot
    public static boolean threatens(double dx, double dy, double dz) {
        return dx * dx + dz * dz <= THREAT_RADIUS * THREAT_RADIUS && Math.abs(dy) <= THREAT_HEIGHT;
    }

    // an idle creeper only matters up close or when it can see us. the full radius is for the ones that are coming, a quiet one
    // behind a wall of rock is not worth walking away from a whole kit for
    public static final double IDLE_RADIUS = 8;
    public static final double IDLE_HEIGHT = 4;

    // aggressive = the full threatens range. idle = IDLE_RADIUS/IDLE_HEIGHT, or the full range with a line of sight
    public static boolean threatensAt(boolean aggressive, boolean sighted, double dx, double dy, double dz) {
        if (!threatens(dx, dy, dz)) return false;
        if (aggressive || sighted) return true;
        return dx * dx + dz * dz <= IDLE_RADIUS * IDLE_RADIUS && Math.abs(dy) <= IDLE_HEIGHT;
    }

    // creepers are never neutral: a quiet one in a dark tunnel blows up the same as an awake one, so the angry flag is only
    // for everything else
    public static boolean isThreat(boolean creeper, boolean angry) {
        return creeper || angry;
    }

    // lava eats item entities, the void takes them and the walk back is a dare. not worth it
    public static boolean hopeless(boolean inLava, int y, int minY) {
        return inLava || y < minY;
    }

    // how long one drop may eat before it is written off. a drop on a ledge the pickup task keeps failing to reach stays
    // visible, and the tracker's own unreachable list forgets its failures whenever we get a block closer, so this task
    // would stare at it until the whole 90 s budget was gone
    public static final double CHASE_SECONDS = 20;

    // seconds spent chasing each drop (by entity id), counted whatever order the closest one flips in
    public static final class Chase {
        private final Map<Integer, Double> spent = new HashMap<>();
        private double lastNow = Double.NaN;

        // we are going for this drop right now. true once it has had its CHASE_SECONDS
        public boolean update(int dropId, double nowSeconds) {
            // a gap (the standoff, a long walk) is not chasing, only count what a tick or two could have been
            double dt = Double.isNaN(lastNow) ? 0 : Math.min(Math.max(0, nowSeconds - lastNow), 1.0);
            lastNow = nowSeconds;
            return spent.merge(dropId, dt, Double::sum) > CHASE_SECONDS;
        }

        // not chasing anything this tick, the next update is a fresh start for the clock
        public void idle() {
            lastNow = Double.NaN;
        }

        public void reset() {
            spent.clear();
            lastNow = Double.NaN;
        }
    }

    // close enough that the drops are loaded and the pickup task can see them
    public static final double ARRIVE_RANGE = 6;
    // drops spread out a bit when a player dies, and fall down slopes
    public static final double DROP_RADIUS = 24;
    // how far past the drop radius a chase may drag us before walking back wins again. a drop 24 out on the far side
    // is a bit further than 24 from where we stand when we finally grab it
    public static final double LEASH_MARGIN = 8;

    public enum Step {
        // get back to the pile first
        WALK,
        // there's one of ours lying around, go get it
        PICKUP,
        // standing on the pile and nothing on it
        LOOK
    }

    // how far above or below the pile we may be and still chase its drops. past that the route is mostly rock, and a drop
    // 28 floors down looks "near" in 3D from the surface (the walk back already digs down to it, GoalNear on the pile)
    public static final double PICKUP_HEIGHT = 8;

    // what this tick is for. a drop near the pile always beats the walk back: the walk used to fire the moment a drop
    // 6+ blocks out pulled us past ARRIVE_RANGE, finish instantly, hand back to the pickup, and around it went. dy is us
    // minus the pile, dropDy is us minus the drop: a drop that fell down a shaft is fine from the pile, it is the surface far
    // above both that has to walk down first (and the walk would drag us back up if only the pile counted)
    public static Step step(double fromPile, double dy, double dropDy, boolean dropNearPile) {
        if (dropNearPile && fromPile <= DROP_RADIUS + LEASH_MARGIN
                && (Math.abs(dy) <= PICKUP_HEIGHT || Math.abs(dropDy) <= PICKUP_HEIGHT)) {
            return Step.PICKUP;
        }
        return fromPile > ARRIVE_RANGE ? Step.WALK : Step.LOOK;
    }

    // a pile this far under the open sky is somewhere we have to dig to
    public static final int DEEP_PILE = 6;
    // the most a pickaxe trip stretches the task budget by. it is not a promise past the pile allowance, GamerTask ends the
    // recovery on that clock whatever this says
    public static final double PICK_SECONDS = 150;

    // get a pick before going down for a pile. bare hands break stone at seven and a half seconds a block, so the route
    // baritone plans is enormous and its search times out (that is how a whole pile got written off with zero pickups).
    // only once the pile's chunk is loaded, from far away the surface height is a guess. the wooden one is enough, the stone
    // one is a cobble trip. and not when we are already down there (dy)
    public static boolean pickFirst(boolean chunkLoaded, int pileDepth, double dy, boolean holdsPick) {
        return chunkLoaded && !holdsPick && pileDepth >= DEEP_PILE && Math.abs(dy) > PICKUP_HEIGHT;
    }

    // the task budget with the pick trip taken off the clock, bounded
    public static double stretched(double budgetSeconds, double pickSeconds) {
        return budgetSeconds + Math.min(Math.max(0, pickSeconds), PICK_SECONDS);
    }

    // armor already in the bag may still go on this long after the budget ran out. held pieces can't wander off, but a stuck
    // equip must not run forever either
    public static final double WEAR_GRACE_SECONDS = 3;

    // out of time, but we hold armor from the pile and are still inside the grace
    public static boolean wearPastBudget(boolean holdingArmor, double spentSeconds, double budgetSeconds) {
        return holdingArmor && spentSeconds < budgetSeconds + WEAR_GRACE_SECONDS;
    }

    // the pick trip has had all it gets
    public static boolean pickTimedOut(double pickSeconds) {
        return pickSeconds >= PICK_SECONDS;
    }

    // pile drops no search could reach, by entity id. recovery keeps these itself instead of the global no path ban, which
    // hid every drop of the pile from everybody for two minutes after two empty searches
    public static final int NO_PATH_STREAK = 3;

    public static final class NoPaths {
        private final Set<Integer> skipped = new HashSet<>();
        private final Set<Integer> streak = new HashSet<>();

        public void record(int dropId) {
            skipped.add(dropId);
            streak.add(dropId);
        }

        // closestDrop leaves these alone
        public boolean skips(int dropId) {
            return skipped.contains(dropId);
        }

        // three different drops in a row and nothing picked up between: there is no way to our stuff
        public boolean noWay() {
            return streak.size() >= NO_PATH_STREAK;
        }

        // something came into the bag, the way in exists
        public void pickedUp() {
            streak.clear();
        }

        // a pick changes what a search can do, every old verdict was for bare hands
        public void clear() {
            skipped.clear();
            streak.clear();
        }
    }

    // pile drop ordering. tiers are ItemValue's: the pickaxe if we have none (it makes the rest of the dig cheap), then the
    // pearls and rods, then kit, then the rest. nearest to us within a tier. the bag fills up long before a big pile is
    // done, so whatever we grab first is what we keep
    public static boolean better(int tier, double dist, int bestTier, double bestDist) {
        if (tier != bestTier) {
            return tier < bestTier;
        }
        return dist < bestDist;
    }

    // which drop the pickup task is pinned to. keyed by entity, not item kind: the old item-kind key kept a pickup that
    // went for the nearest andesite anywhere in the world while we were counting the chase on a different one. and by
    // the object, not the id: a drop that leaves tracking range comes back as a new entity with the same id, and the
    // old one reads as dead
    public static final class Pin<T> {
        private T drop;

        // true when the pickup has to be rebuilt for this drop
        public boolean retarget(T chosen) {
            if (chosen == drop) {
                return false;
            }
            drop = chosen;
            return true;
        }

        public T drop() {
            return drop;
        }

        public void clear() {
            drop = null;
        }
    }

    // the walk to the pile heals first: under HOLD_BELOW with no shooter on us we stand until HEAL_TO, a minute at most in total.
    // same shape as NetherTripRules.walkHold, a higher line because the overworld cave is where the skeletons live
    public static final float HOLD_BELOW = 14;
    public static final float HEAL_TO = 18;
    public static final long HOLD_TICKS = 60 * 20L;

    // a mob that hit us lately is not a quiet spot either, and with nothing to regen on standing is just standing
    public static boolean walkHold(float health, boolean shooterSees, long ticksSinceHit, boolean canRegen, boolean holding, long heldTicks) {
        if (shooterSees || !canRegen || ticksSinceHit <= NetherTripRules.ABORT_HIT_TICKS || heldTicks >= HOLD_TICKS) {
            return false;
        }
        return health < (holding ? HEAL_TO : HOLD_BELOW);
    }

    // low and hit lately: the walk is over, same line as the nether one
    public static boolean walkAbort(float health, long ticksSinceHit) {
        return NetherTripRules.walkAbort(health, ticksSinceHit);
    }

    // the despawn timer only runs while the pile's chunk is ticking, and a pile we respawned far from is not: it keeps its age
    // until we are back in range. items tick out to the simulation distance (12 chunks by default), a bit generous on purpose:
    // starting the allowance early only costs a few seconds of the walk, starting it late is chasing a pile that is gone
    public static final double LOADED_RANGE = 192;
    // blocks per second we count on for the walk back. a sprint is about 5.6 on flat ground, caves and detours eat the rest
    public static final double WALK_SPEED = 3;

    public static double walkSeconds(double blocks) {
        return blocks / WALK_SPEED;
    }

    // the task's time on task budget: the 90 s that were always there for the pile itself, plus the walk to it. before this the
    // walk came out of the same 90 s and a pile past 270 blocks could never be reached
    public static double budget(double pileSeconds, double blocks) {
        return pileSeconds + walkSeconds(blocks);
    }

    // how long GamerTask keeps the recovery alive for the pile's age. it starts when the pile can be loaded: at the death when
    // we respawned inside LOADED_RANGE (the items age from the death, same as it always was), otherwise the first time we come
    // within it. the walk there is not in it, a long one used to spend the whole allowance before it got anywhere
    public static final class Allowance {
        private final double deathSeconds;
        private final double seconds;
        private double since = Double.NaN;
        private boolean asked;

        public Allowance(double deathSeconds, double seconds) {
            this.deathSeconds = deathSeconds;
            this.seconds = seconds;
        }

        public boolean expired(double nowSeconds, double blocksToPile) {
            boolean first = !asked;
            asked = true;
            if (Double.isNaN(since) && blocksToPile <= LOADED_RANGE) {
                since = first ? deathSeconds : nowSeconds;
            }
            return !Double.isNaN(since) && nowSeconds - since > seconds;
        }
    }

    // remembers since when the pile has been crowded. a quiet moment resets the clock, the 30 seconds are 30 in a row
    public static final class Gate {
        private double crowdedSince = Double.NaN;

        public Verdict update(double nowSeconds, int threats) {
            if (threats <= 0) {
                crowdedSince = Double.NaN;
                return Verdict.GO;
            }
            if (Double.isNaN(crowdedSince)) {
                crowdedSince = nowSeconds;
            }
            return nowSeconds - crowdedSince >= WAIT_SECONDS ? Verdict.GIVE_UP : Verdict.WAIT;
        }

        public void reset() {
            crowdedSince = Double.NaN;
        }
    }

    // the pile with a full bag. junk (or a block we already hold the building reserve of) is making room just to throw it back
    // down and chase it again: netherrack in, netherrack out, or two stacks taking turns, for ever. noRoom is DropRoom's NONE,
    // which also covers a drop that would be the next garbage once it is in. leaving either costs nothing
    public static boolean skipWhenFull(boolean fits, boolean dropJunk, boolean noRoom) {
        return !fits && (dropJunk || noRoom);
    }
}
