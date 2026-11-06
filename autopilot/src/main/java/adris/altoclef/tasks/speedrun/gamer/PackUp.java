package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.util.helpers.WalkCost;

import java.util.List;
import java.util.Set;
import java.util.function.ToIntFunction;

// leaving the mine with a furnace or smoker still cooking at the bottom of it. the jobs are a memory that survives the walk,
// but the walk back down is the cost: this is the one chance to take the contents (and the station) along while we are
// still standing next to it. pure (jobs and numbers in, a choice out), IronPhase does the walking. the trip itself is a TAKE_ALL
// (FurnacePlan.leaving): it waits when the job is nearly done (FurnacePlan.leaveWindow) and otherwise takes the unfinished input
// back out, so nothing is left behind either way
public final class PackUp {
    private PackUp() {
    }

    // a job this deep below the open sky is one we would be climbing away from
    public static boolean stranded(int jobDepth) {
        return jobDepth > SmeltSurface.GO_UP_DEPTH;
    }

    // the stranded job to go and get, the one that is ready first. `tried` are the spots we already made a trip for, so one
    // that cannot be emptied must not send us round again for ever. `depthOf` is how deep its furnace is, `walkOf` what the
    // walk to it costs from here (WalkCost units), one that is a long way off waits for the plain due-collect like it always did
    public static RunState.FurnaceJob pick(List<RunState.FurnaceJob> jobs, Set<RunState.Pos> tried, ToIntFunction<RunState.FurnaceJob> depthOf,
                                           java.util.function.ToDoubleFunction<RunState.FurnaceJob> walkOf) {
        return pick(jobs, tried, depthOf, walkOf, j -> false);
    }

    // `banned` = pathing gave up on its furnace: it goes after the others, never out
    public static RunState.FurnaceJob pick(List<RunState.FurnaceJob> jobs, Set<RunState.Pos> tried, ToIntFunction<RunState.FurnaceJob> depthOf,
                                           java.util.function.ToDoubleFunction<RunState.FurnaceJob> walkOf,
                                           java.util.function.Predicate<RunState.FurnaceJob> banned) {
        return FurnaceJobs.soonest(jobs.stream()
                .filter(j -> !tried.contains(j.pos) && stranded(depthOf.applyAsInt(j)) && walkOf.applyAsDouble(j) <= TRIP_BUDGET)
                .toList(), banned);
    }

    // the trip budget, in WalkCost.estimate units (4 per block of height), not the station line: this is not about reusing the
    // furnace, it is the one chance to take the contents without a second climb down. a furnace 10 down and 20 across (60) is
    // well worth it, one in another area waits for the plain due-collect. STATION_FORGET (128) is a straight line, it would
    // let this walk 128 down a shaft (512 of climbing) and is the wrong question anyway
    public static final double TRIP_BUDGET = 180;

    // walk cost from us to the job's furnace
    public static double walk(RunState.FurnaceJob job, double px, double py, double pz) {
        return WalkCost.estimate(job.pos.x + 0.5 - px, job.pos.y - py, job.pos.z + 0.5 - pz);
    }
}
