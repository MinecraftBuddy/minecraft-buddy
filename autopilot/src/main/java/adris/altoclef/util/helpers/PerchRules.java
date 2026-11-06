package adris.altoclef.util.helpers;

import adris.altoclef.util.helpers.CombatCommit.Foe;
import java.util.List;
import java.util.Map;
import java.util.Set;

// the numbers of the perch (stack up where we stand and hit the melee chasers from the top), no minecraft in them.
// 26.3 Mob.isWithinMeleeAttackRange is the mob's own box inflated sideways only, against our plain box, so a mob can only hit
// us while our feet are under the top of its head. same trick GolemFightTask plays on golems, GolemRules has the long version
public final class PerchRules {
    private PerchRules() {
    }

    // only ever this tall of a pillar, past it the blocks and the air above stop adding up
    public static final int MAX_H = 3;
    public static final int MIN_H = 2;
    // a hair over the head so a slab or a float error does not give it the hit back
    public static final double MARGIN = 0.15;
    public static final double EYE = 1.62;
    // we hit at 3.0, this leaves room for the swing to be a tick late
    public static final double REACH = 2.9;
    // a foe hugging the pillar has its box edge half a block from our column centre
    public static final double HUG = 0.5;
    // chasers are everything angry this close
    public static final double RANGE = CombatCommit.LOW_HP_RANGE;
    // a shooter that hit us this lately is not a perch (the top of a pillar is a target)
    public static final long SHOT_QUIET = 60;
    // the perch ends when none of them is this close for QUIET_TICKS
    public static final double LEAVE_RANGE = 6;
    public static final long QUIET_TICKS = 100;
    // the cap, and the "still something at the foot" range that renews it
    public static final long CAP_TICKS = 1800;
    public static final double CAP_RANGE = 4;
    // a second perch inside this many ticks / blocks of the last one is the re-perch, and there is one of those
    public static final long REPERCH_TICKS = 1200;
    public static final double REPERCH_BLOCKS = 40;
    public static final int REPERCH_MAX = 1;
    // the pillar went nowhere for this long (no height gained)
    public static final long PILLAR_TICKS = 30;
    // projectile hits while perched that end it
    public static final int SHOTS_LEAVE = 2;

    // the world's side of one tick. grounded: standing on solid ground. rise: how much higher than our feet the best footing on any of the
    // 8 neighbours is (0 when none is higher). blocks: throwaway blocks in the bag. air: free cells counted up from our feet cell
    // (capped by the caller at a few). shots/shotAt: projectile hits taken so far, and the game tick of the last one.
    // pillarFailed: the perch task gave up on its pillar. heights: foe id -> box height, only for the species a perch is for
    // near6/near4: angry perch species within LEAVE_RANGE / CAP_RANGE counted off the world, not off the foe list (that one drops a mob
    // that cannot reach us, and one at the foot of a pillar cannot), -1 = nobody counted, ask the foe list. creeper8/creeper4: a creeper
    // of any mood that close (a lit one leaves the hostile list)
    public record Facts(boolean grounded, double feetY, double rise, int blocks, int air, boolean hasFood, int shots, long shotAt,
                        boolean pillarFailed, Map<Integer, Double> heights, int near6, int near4, boolean creeper8, boolean creeper4) {
        public static final long NEVER = Long.MIN_VALUE / 2;

        public Facts(boolean grounded, double feetY, double rise, int blocks, int air, boolean hasFood, int shots, long shotAt,
                     boolean pillarFailed, Map<Integer, Double> heights) {
            this(grounded, feetY, rise, blocks, air, hasFood, shots, shotAt, pillarFailed, heights, -1, -1, false, false);
        }
    }

    // melee only mobs that cannot be outrun (angry zombified piglins) or are in the same boat as them. hoglins and zoglins knock us
    // off the pillar column on every hit so it never gets a block down, and wither skeletons lose a sprint, so they run
    private static final Set<String> SPECIES = Set.of("zombified_piglin", "zombie", "zombie_villager", "husk");

    public static boolean species(String type) {
        return SPECIES.contains(type);
    }

    // the pillar needs a few still ticks to stack (movement pillar only jumps when centred and standing), so a chaser at our
    // elbow or a hit that landed a second ago means it will never finish
    public static final double START_GAP = 4;
    public static final long START_QUIET = 20;

    // room to build: nobody that close, and no melee hit on us lately
    public static boolean roomToStart(List<Foe> foes) {
        for (Foe foe : foes) {
            if (foe.distance() > RANGE) continue;
            if (foe.distance() < START_GAP) return false;
            if (!foe.ranged() && foe.sinceHit() <= START_QUIET) return false;
        }
        return true;
    }

    // how many blocks up we need to be: the tallest chaser's head plus the best step beside us plus a hair, whole blocks
    public static int height(double tallest, double rise) {
        return Math.max(MIN_H, (int) Math.ceil(tallest + rise + MARGIN - 1.0e-9));
    }

    // from the top, can our eye (feet + h + EYE) reach the top of a foe hugging the pillar at the foot. the shortest one is the test
    public static boolean reaches(int h, double shortest) {
        double dy = Math.max(0, h + EYE - shortest);
        return Math.sqrt(HUG * HUG + dy * dy) <= REACH;
    }

    // how tall a pillar this spot wants for these foes, 0 when it is not a perch spot at all: a chaser that is not a perch species
    // (shooter, creeper, spider), nobody at all, no solid floor, too tall, too few blocks, no room above, or a foe at the foot out
    // of our reach
    public static int perchHeight(Facts f, List<Foe> foes) {
        if (f == null || !f.grounded() || f.creeper8()) return 0;
        double tallest = 0;
        double shortest = Double.MAX_VALUE;
        int chasers = 0;
        for (Foe foe : foes) {
            if (foe.distance() > RANGE) continue;
            Double height = f.heights().get(foe.id());
            if (height == null || !foe.melee()) return 0;
            chasers++;
            tallest = Math.max(tallest, height);
            shortest = Math.min(shortest, height);
        }
        if (chasers == 0) return 0;
        int h = height(tallest, f.rise());
        if (h > MAX_H || f.blocks() < h || f.air() < h + 2) return 0;
        return reaches(h, shortest) ? h : 0;
    }
}
