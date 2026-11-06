package adris.altoclef.tasks.speedrun.gamer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

// which world bed is worth walking to for the bed shortfall, and whether the run may still spend time on one. pure so the
// rules are testable without a game. the trick is the same as VillageChests: nothing here knows what a village is, a bed
// counts when something villagey sits near it (a job block, a bell, or a second bed, villages have one per house)
public final class BedRules {
    private record Candidate(RunState.Pos bed, double dist) {
    }

    private BedRules() {
    }

    // the beds that are ours and not the world's: the spawn bed and the one put down for the night, whichever exist
    public static List<RunState.Pos> ownBeds(RunState.Pos spawn, RunState.Pos night) {
        return ownBeds(spawn, night, null);
    }

    // and the one the game says we respawn at, which is ours whoever put it down
    public static List<RunState.Pos> ownBeds(RunState.Pos spawn, RunState.Pos night, RunState.Pos respawn) {
        List<RunState.Pos> out = new ArrayList<>();
        if (spawn != null) {
            out.add(spawn);
        }
        if (night != null) {
            out.add(night);
        }
        if (respawn != null) {
            out.add(respawn);
        }
        return out;
    }

    // per run and it survives a relog (RunState). the per bed timeout is the caller's, this is the whole allowance
    public static boolean withinBudget(long ticksSpent, double maxSeconds) {
        return ticksSpent < maxSeconds * 20;
    }

    // the nearest bed worth taking, null if there is none. `beds` is every tracked bed block (a bed is two of them),
    // `evidence` the job blocks and bells. a bed we own (the spawn bed) is never taken and never vouches for another one,
    // and neither does a job block we placed. `usable` last, it is the part that raycasts and asks the world
    public static RunState.Pos pick(List<RunState.Pos> beds, List<RunState.Pos> evidence, List<RunState.Pos> ownBeds,
                                    List<RunState.Pos> ownJobs, List<RunState.Pos> tried, Predicate<RunState.Pos> usable,
                                    double px, double py, double pz, double radius, double evidenceRadius) {
        List<RunState.Pos> villagey = new ArrayList<>();
        for (RunState.Pos job : evidence) {
            if (!ownJobs.contains(job)) {
                villagey.add(job);
            }
        }
        List<Candidate> candidates = new ArrayList<>();
        for (RunState.Pos bed : beds) {
            double dist = dist(bed, px, py, pz);
            if (dist > radius || touchesAny(ownBeds, bed) || touchesAny(tried, bed)) {
                continue;
            }
            if (inVillage(bed, villagey, beds, ownBeds, evidenceRadius)) {
                candidates.add(new Candidate(bed, dist));
            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::dist));
        for (Candidate c : candidates) {
            if (usable.test(c.bed)) {
                return c.bed;
            }
        }
        return null;
    }

    // a job block or a bell close by, or a bed of some other house. the other half of this very bed does not count, it
    // is one block away from everything it could possibly vouch for
    static boolean inVillage(RunState.Pos bed, List<RunState.Pos> evidence, List<RunState.Pos> beds, List<RunState.Pos> ownBeds,
                             double evidenceRadius) {
        for (RunState.Pos job : evidence) {
            if (dist(job, bed.x + 0.5, bed.y + 0.5, bed.z + 0.5) <= evidenceRadius) {
                return true;
            }
        }
        for (RunState.Pos other : beds) {
            if (!touches(other, bed) && !touchesAny(ownBeds, other)
                    && dist(other, bed.x + 0.5, bed.y + 0.5, bed.z + 0.5) <= evidenceRadius) {
                return true;
            }
        }
        return false;
    }

    // the two halves of a bed lie side by side at the same height, so one entry stands for both
    public static boolean touches(RunState.Pos a, RunState.Pos b) {
        return a.y == b.y && Math.abs(a.x - b.x) + Math.abs(a.z - b.z) <= 1;
    }

    private static boolean touchesAny(List<RunState.Pos> list, RunState.Pos bed) {
        for (RunState.Pos p : list) {
            if (touches(p, bed)) {
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
