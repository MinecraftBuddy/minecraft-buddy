package adris.altoclef.util.helpers;

// the pure half of running away from a crowd. one pull per mob, closer ones pull harder, creepers pull harder still.
// GoalRunAwayFromCrowd hands the pathfinder these numbers, nothing in here knows what a minecraft is
public final class CrowdRepulsion {

    // ticks of walking per block we are still short of the safe distance. around a sprint, it only has to point the
    // right way, the search does the rest
    private static final double COST_PER_BLOCK = 4;
    // a creeper is worth this many zombies when deciding which way is away
    public static final double CREEPER_WEIGHT = 3;

    // frozen in time, so the pathfinder thread never touches an entity
    public record Crowd(double[] x, double[] y, double[] z, double[] weight) {
        public int size() {
            return x.length;
        }
    }

    public static final Crowd EMPTY = new Crowd(new double[0], new double[0], new double[0], new double[0]);

    private CrowdRepulsion() {
    }

    // far enough from every one of them. nobody to run from means we are already as safe as we are getting
    public static boolean inGoal(Crowd crowd, double distance, boolean xzOnly, double x, double y, double z) {
        for (int i = 0; i < crowd.size(); i++) {
            if (squaredDistance(crowd, i, xzOnly, x, y, z) < distance * distance) return false;
        }
        return true;
    }

    // the weighted average of how far short of the safe distance we still are. zero once we are out. dividing by the
    // total weight keeps it in ticks no matter how many mobs there are: eight zombies are not eight times further
    // away than one, they are just more in the way
    public static double heuristic(Crowd crowd, double distance, boolean xzOnly, double x, double y, double z) {
        double shortfall = 0;
        double weights = 0;
        for (int i = 0; i < crowd.size(); i++) {
            double away = Math.sqrt(squaredDistance(crowd, i, xzOnly, x, y, z));
            shortfall += crowd.weight()[i] * Math.max(0, distance - away);
            weights += crowd.weight()[i];
        }
        if (weights <= 0) return 0;
        return shortfall / weights * COST_PER_BLOCK;
    }

    // how much of the committed run is left, in the same ticks as heuristic(). a committed run wants to be this far from
    // where it started as well as away from whoever is chasing, otherwise it loops round the spot it ran from
    public static double originHeuristic(double originX, double originZ, double distance, double x, double z) {
        return Math.max(0, distance - Math.hypot(x - originX, z - originZ)) * COST_PER_BLOCK;
    }

    // far enough from where the run began (flat, a hill is not a reason to run less)
    public static boolean farFromOrigin(double originX, double originZ, double distance, double x, double z) {
        return Math.hypot(x - originX, z - originZ) >= distance;
    }

    private static double squaredDistance(Crowd crowd, int i, boolean xzOnly, double x, double y, double z) {
        double dx = crowd.x()[i] - x, dz = crowd.z()[i] - z;
        double sq = dx * dx + dz * dz;
        if (!xzOnly) {
            double dy = crowd.y()[i] - y;
            sq += dy * dy;
        }
        return sq;
    }
}
