package adris.altoclef.tasks.speedrun.gamer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

// which village chest is worth a detour for iron, and whether the run may still spend time on one. pure (RunState.Pos
// in, RunState.Pos out) so the rules are testable without a game. nothing here knows what a village is, the trick is
// that blacksmith job blocks sit next to the loot: a chest within a few blocks of a grindstone is a weaponsmith's
public final class VillageChests {
    // lower is better: weaponsmiths roll the most iron, then toolsmiths, armorers mostly bring bread and the odd ingot
    public static final int GRINDSTONE = 0;
    public static final int SMITHING_TABLE = 1;
    public static final int BLAST_FURNACE = 2;

    public record Job(RunState.Pos pos, int rank) {
    }

    private record Candidate(RunState.Pos chest, int rank, double dist) {
    }

    private VillageChests() {
    }

    // the budget is per run and survives a relog (RunState), whichever runs out first ends the looting for good
    public static boolean withinBudget(int chestsTried, long ticksSpent, int maxChests, double maxSeconds) {
        return chestsTried < maxChests && ticksSpent < maxSeconds * 20;
    }

    // the best chest to open next, null if there is none. geometry first (cheap, sorted), `usable` last because it is the
    // part that raycasts and asks the world, so it only runs on the few that already look right until one says yes.
    // a job block we placed ourselves (own) never vouches for a chest: a blast furnace from our own kit is not a village
    public static RunState.Pos pick(List<RunState.Pos> chests, List<Job> jobs, List<RunState.Pos> own, List<RunState.Pos> tried,
                                    Predicate<RunState.Pos> usable, double px, double py, double pz,
                                    double lootRadius, double jobRadius) {
        List<Candidate> candidates = new ArrayList<>();
        for (RunState.Pos chest : chests) {
            double dist = dist(chest, px, py, pz);
            if (dist > lootRadius || triedAlready(tried, chest)) {
                continue;
            }
            int rank = bestRank(chest, jobs, own, jobRadius);
            if (rank >= 0) {
                candidates.add(new Candidate(chest, rank, dist));
            }
        }
        candidates.sort(Comparator.comparingInt(Candidate::rank).thenComparingDouble(Candidate::dist));
        for (Candidate c : candidates) {
            if (usable.test(c.chest)) {
                return c.chest;
            }
        }
        return null;
    }

    // -1 = no job block of a real village close enough
    static int bestRank(RunState.Pos chest, List<Job> jobs, List<RunState.Pos> own, double jobRadius) {
        int best = -1;
        for (Job job : jobs) {
            if (own.contains(job.pos) || dist(chest, job.pos.x + 0.5, job.pos.y + 0.5, job.pos.z + 0.5) > jobRadius) {
                continue;
            }
            if (best < 0 || job.rank < best) {
                best = job.rank;
            }
        }
        return best;
    }

    // a double chest is two blocks and one visit empties both, so the other half counts as visited too
    static boolean triedAlready(List<RunState.Pos> tried, RunState.Pos chest) {
        for (RunState.Pos t : tried) {
            if (t.y == chest.y && Math.abs(t.x - chest.x) + Math.abs(t.z - chest.z) <= 1) {
                return true;
            }
        }
        return false;
    }

    private static double dist(RunState.Pos p, double x, double y, double z) {
        double dx = p.x + 0.5 - x;
        double dy = p.y + 0.5 - y;
        double dz = p.z + 0.5 - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
