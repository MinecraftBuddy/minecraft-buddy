package adris.altoclef.tasks.resources;

import java.util.ArrayList;
import java.util.List;

// which animal to chase for dinner. plain numbers in and out so a test can poke it without a game (CollectFoodTask has
// the world, this has the opinions). the old rule was "first type in the list that has any loaded animal", so a pig
// 80 blocks away beat a sheep 5 blocks away. get rekt, pig
public final class FoodHunt {
    private FoodHunt() {
    }

    // cooked nutrition (1.21.4) times the average raw drop count, from the loot tables (from memory, not from the jar):
    // pig and cow 1-3, chicken 1, sheep 1-2 mutton. we cook everything, so cooked is what counts. no fish: they sit in
    // water, and the one time we chased a cod it cost a 5 s path search that went nowhere
    public enum Kind {
        PIG(8, 2.0),
        COW(8, 2.0),
        CHICKEN(6, 1.0),
        SHEEP(6, 1.5),
        // 5 cooked, but a rabbit drops 0-1 raw. 1.0 is generous on purpose: it is the only meat in a desert or a snowfield
        RABBIT(5, 1.0);

        final double units;

        Kind(int cookedNutrition, double averageDrops) {
            this.units = cookedNutrition * averageDrops;
        }
    }

    // what a kill and the walk back cost on top of the walk there, in blocks. a chicken 5 blocks away (6 units over 13)
    // narrowly beats a pig 30 away (16 over 38), and a cow 10 away (16 over 18) beats a chicken 6 away (6 over 14)
    static final double OVERHEAD_BLOCKS = 8;
    // a block up or down is a lot more than a block along: it is pillaring or a long way round, not a straight walk
    static final double VERTICAL_WEIGHT = 2;
    // the new best has to be this much better before we drop the animal we are already running at, or every pig that
    // wanders a block closer would turn us around
    static final double STICKY_FACTOR = 1.5;
    // sheep are also the wool source for the beds. killing one gives a wool and some mutton, shearing gives wool for
    // free, so while the kit still wants wool a sheep has to win by about this much to be eaten
    static final double WOOL_SHEEP_FACTOR = 1.3;

    // a hunt this close (by distance() below) is not dropped for a pile of hay: the pig is right there, the hay is not going
    // anywhere. past it the animal is a lost cause and the usual pick can have it
    static final double COMMIT_RADIUS = 16;
    // an animal this close is worth a detour of a few swings even when we are headed for something else
    static final double ALONG_THE_WAY_RADIUS = 6;

    // set by the gamer's iron phase. a static because the food task is a different task tree altogether
    private static volatile boolean woolWanted = false;

    public static void setWoolWanted(boolean wanted) {
        woolWanted = wanted;
    }

    public static boolean isWoolWanted() {
        return woolWanted;
    }

    // entity id, what it is, and how far it is by distance() below
    record Candidate(int id, Kind kind, double distance) {
    }

    static double distance(double dx, double dy, double dz) {
        return Math.sqrt(dx * dx + dz * dz) + Math.abs(dy) * VERTICAL_WEIGHT;
    }

    static double score(Kind kind, double distance, boolean wool) {
        double score = kind.units / (distance + OVERHEAD_BLOCKS);
        if (wool && kind == Kind.SHEEP) {
            score /= WOOL_SHEEP_FACTOR;
        }
        return score;
    }

    // finish what we started: alive, edible and reachable (ok) and close enough that walking away would be silly
    static boolean keepHunting(boolean ok, double distance) {
        return ok && distance <= COMMIT_RADIUS;
    }

    // only the ones close enough to bother with, for the kill-it-while-passing check
    static List<Candidate> within(List<Candidate> candidates, double radius) {
        List<Candidate> near = new ArrayList<>();
        for (Candidate c : candidates) {
            if (c.distance() <= radius) {
                near.add(c);
            }
        }
        return near;
    }

    // the best candidate, except that the one we are already chasing (currentId, -1 for none) stays unless the best is
    // STICKY_FACTOR times better. null when there is nobody to chase
    static Candidate choose(List<Candidate> candidates, int currentId, boolean wool) {
        Candidate best = null;
        Candidate current = null;
        double bestScore = 0;
        double currentScore = 0;
        for (Candidate c : candidates) {
            double s = score(c.kind(), c.distance(), wool);
            if (best == null || s > bestScore) {
                best = c;
                bestScore = s;
            }
            if (c.id() == currentId) {
                current = c;
                currentScore = s;
            }
        }
        if (current != null && bestScore <= currentScore * STICKY_FACTOR) {
            return current;
        }
        return best;
    }
}
