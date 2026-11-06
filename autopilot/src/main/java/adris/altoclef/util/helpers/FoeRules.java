package adris.altoclef.util.helpers;

import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Kind;
import baritone.api.utils.Dimension;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

// the pure half of "who counts as a foe here, and what sort". the commitment machine is the same in every dimension, this
// is where the overworld, the nether and the end differ: a few species that are somebody else's business, and a few that
// are not a fight. entity types come in as registry paths ("blaze") so the whole table can be tested without minecraft
public final class FoeRules {

    // a whole set of iron is 15. a bolt lands 1.2 to 2.2 through 13 to 16, and this is about where that stops being true
    public static final int PATROL_ARMOR = 10;

    // lands 8 to 13 in one swing, a run at HEAVY_FLEE_HEALTH instead of the usual line
    private static final Set<String> HEAVY = Set.of("hoglin", "zoglin", "piglin_brute", "vindicator", "ravager");
    // not a fight at any hp. the warden one shots a full bar, the wither is a boss we never summoned
    private static final Set<String> UNTOUCHABLE = Set.of("warden", "wither");

    // a witch this close with a line to us is who the poison is blamed on. the potion has the witch as its owner on the
    // server, but the client is only told "magic damage" with nobody attached
    public static final double WITCH_HIT_RANGE = 12;

    private FoeRules() {
    }

    // the run's crowd: the foes plus every live blaze in range that is not one of them already. the hostile list only holds a
    // blaze that is charged or in melee, so a calm one watching from across the room was invisible and the run walked back to it.
    // a fresh list, the goal reads it on its own schedule
    public static <T> List<T> crowdWithBlazes(List<? extends T> foes, List<? extends T> blazes, ToDoubleFunction<T> distance,
            Predicate<T> alive, double range) {
        List<T> out = new ArrayList<>(foes);
        for (T blaze : blazes) {
            if (alive.test(blaze) && distance.applyAsDouble(blaze) <= range && !out.contains(blaze)) {
                out.add(blaze);
            }
        }
        return out;
    }

    // one witch as the tracker sees it: id, distance, and whether it has a line to us
    public record WitchLook(int id, double distance, boolean sight) {
    }

    // magic damage with nobody attached. (starvation, falling, fire and the rest have their own names, and a harming potion
    // thrown by a witch arrives with the witch attached already, which is the normal hit rule)
    public static boolean unattributedMagic(String damageType, boolean hasAttacker) {
        return !hasAttacker && "magic".equals(damageType);
    }

    // who the unexplained harm (magic damage, or just gaining poison, weakness or slowness) is blamed on: the nearest witch
    // inside WITCH_HIT_RANGE that can see us, null when nobody fits and nothing changes
    public static WitchLook witchBehind(boolean unexplainedHarm, Iterable<WitchLook> witches) {
        if (!unexplainedHarm) return null;
        WitchLook best = null;
        for (WitchLook witch : witches) {
            if (!witch.sight() || witch.distance() > WITCH_HIT_RANGE) continue;
            if (best == null || witch.distance() < best.distance()) best = witch;
        }
        return best;
    }

    // one hostile as the world half sees it. the three questions that cost something (a raycast, a reachability verdict, the
    // behaviour stack) come in as suppliers and are only asked when the cheap ones did not already say no.
    // excluded: a task is fighting this one on its own terms (the blaze rods, the golem on its pillar, the dragon and whatever
    // stands around it) and does not want a second opinion. angry and canHarm are EntityHelper's answers. slimeSize is 0 for
    // anything that is not a slime. sight and sinceHitBefore are for the rules about a shooter that keeps hitting us (see Foe)
    public record Candidate(String type, int id, double distance, BooleanSupplier excluded, BooleanSupplier angry,
                            BooleanSupplier canHarm, long sinceHit, int slimeSize, boolean ranged, boolean creeper, double reachGap,
                            boolean sight, long sinceHitBefore) {
        public Candidate(String type, int id, double distance, BooleanSupplier excluded, BooleanSupplier angry,
                         BooleanSupplier canHarm, long sinceHit, int slimeSize, boolean ranged, boolean creeper, double reachGap) {
            this(type, id, distance, excluded, angry, canHarm, sinceHit, slimeSize, ranged, creeper, reachGap, false, CombatCommit.NEVER);
        }

        public Candidate(String type, int id, double distance, BooleanSupplier excluded, BooleanSupplier angry,
                         BooleanSupplier canHarm, long sinceHit, int slimeSize, boolean ranged, boolean creeper) {
            this(type, id, distance, excluded, angry, canHarm, sinceHit, slimeSize, ranged, creeper, Double.POSITIVE_INFINITY);
        }
    }

    // the foe this candidate makes in this dimension, null when it is not one
    public static Foe accept(Dimension dimension, Candidate c) {
        if (neverAFoe(dimension, c.type())) return null;
        // a size 1 slime cannot hurt us, it is a bouncing pet
        if (c.slimeSize() == 1) return null;
        if (c.excluded().getAsBoolean() || !c.angry().getAsBoolean()) return null;
        // something that hit us can hurt us, no questions. the rest has to be able to get at us (or shoot)
        if (c.sinceHit() > CombatCommit.HIT_MEMORY && !c.canHarm().getAsBoolean()) return null;
        Kind kind = kindOf(dimension, c.type());
        return new Foe(c.id(), c.distance(), shoots(c.type(), c.ranged(), kind), c.creeper(), c.sinceHit(), kind, c.reachGap(), c.sight(), c.sinceHitBefore())
                .withBrute(dimension == Dimension.NETHER && c.type().equals("piglin_brute"))
                .withWither(dimension == Dimension.NETHER && c.type().equals("wither_skeleton"));
    }

    // a shooter a task kept for itself (the rods task's blazes) that hit us lately and has a line on us. never a foe, so it
    // never makes a crowd, a hit or a run, but it is still shooting at us: the heal meal and the run bite must not sit down
    // in front of it. sight is only ever true for a shooter that hit us within HIT_MEMORY (CombatBrain.sees)
    public static boolean excludedShooterOnUs(Dimension dimension, Candidate c) {
        // (a wither skeleton's sight is for the start rule, it is a swordsman and not a shooter on us)
        if (!c.sight() || c.distance() > CombatRules.SHOOTER_RANGE || c.type().equals("wither_skeleton") || neverAFoe(dimension, c.type())) return false;
        return c.excluded().getAsBoolean();
    }

    // the rods task keeps its blazes out of mob defense because the fight is its own, but that fight does not tick while we are
    // busy with a cube next to it, and then nobody is looking after the thing setting us on fire. so one that hit us lately is a
    // foe again for as long as a fight of ours is on. only then: the walk to the spawner still ignores them like the task asked
    public static boolean liftsExclusion(String type, boolean fighting, long sinceHit) {
        return fighting && type.equals("blaze") && sinceHit <= CombatCommit.HIT_MEMORY;
    }

    // a pillager we stand and fight: most of a set of iron and a real weapon (a pickaxe is not a plan). under that the old rules are
    // the right ones, a bolt through no armor is a lot more than the 2 this deal is priced on. the bag only gets asked when the rest said yes
    public static boolean patrol(String type, int armor, BooleanSupplier weapon) {
        return type.equals("pillager") && armor >= PATROL_ARMOR && weapon.getAsBoolean();
    }

    // does it count from where a shooter stands. a blaze shoots (the warden's sonic boom is handled by its kind). a wither
    // skeleton is a skeleton by class and a swordsman by trade: it only ever hits from where it stands next to us, and as a
    // "shooter" its first swing would never count as contact. classRanged is what the class says (MobReachability.isRanged)
    public static boolean shoots(String type, boolean classRanged, Kind kind) {
        return (classRanged && !type.equals("wither_skeleton")) || kind == Kind.FLYER;
    }

    // may the aura swing at this one. the fight target and what is hitting us in contact (brainSwingsAt), and anything a task
    // asked mob defense to stay out of but did not take off the aura's list: the rod task keeps blazes out of the chain and only
    // takes them off the aura beyond 3.5 blocks, so one next to us is the aura's to hit
    public static boolean auraMaySwingAt(boolean brainSwingsAt, boolean leftToATask) {
        return brainSwingsAt || leftToATask;
    }

    // species that the chain stays out of in this dimension. the ghast cannot be walked up to and its fireballs are the aura's
    // (and NetherPhase's cover), a fight or a run from it has nothing to chase and nowhere better to go. the dragon belongs to
    // DragonPhase from the first crystal to the exit portal, running from it is how the island ends up behind us
    public static boolean neverAFoe(Dimension dimension, String type) {
        return switch (dimension) {
            case NETHER -> type.equals("ghast");
            case END -> type.equals("ender_dragon");
            default -> false;
        };
    }

    // what the dragon tasks (the sword and the beds) fight, or stand among, on their own terms while they are live: the
    // dragon, and the endermen around the crystals. they add this to the behaviour stack so the chain neither fights nor runs
    // from them mid-fight. the type is the registry path. an enderman is only theirs while it is calm: one that is angry has
    // a target and it is us, and a mob defense that ignores it just stands there being hit. the calm ones stay out of it
    // because swinging at a calm enderman is what makes it angry in the first place
    public static boolean dragonFightOwns(String type, boolean calm) {
        return type.equals("ender_dragon") || (type.equals("enderman") && calm);
    }

    // the registry path of something that lands 8 to 13 in one swing, for callers that only care about that one kind
    public static boolean isHeavy(String type) {
        return kindOf(Dimension.NETHER, type) == Kind.HEAVY;
    }

    public static Kind kindOf(Dimension dimension, String type) {
        if (UNTOUCHABLE.contains(type)) return Kind.UNTOUCHABLE;
        if (HEAVY.contains(type)) return Kind.HEAVY;
        if (type.equals("witch")) return Kind.LOBBER;
        // angry ones only get here (a calm one is never a foe): never a run, see CombatCommit.runReason
        if (type.equals("enderman")) return Kind.ENDERMAN;
        // blazes only live in the nether, and there they hover over lava where nothing walks up to them
        if (dimension == Dimension.NETHER && type.equals("blaze")) return Kind.FLYER;
        return Kind.NORMAL;
    }
}
