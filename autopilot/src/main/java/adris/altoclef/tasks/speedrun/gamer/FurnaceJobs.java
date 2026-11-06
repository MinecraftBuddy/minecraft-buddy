package adris.altoclef.tasks.speedrun.gamer;

import java.util.List;

// the bookkeeping for furnaces we loaded and walked away from. pure (RunState.FurnaceJob in, numbers out) so the rules are
// testable without a game. the list itself lives in RunState.furnaceJobs, and a job is only a memory: when we get back,
// whatever is in the furnace is the truth and the job is rewritten or dropped to match
public final class FurnaceJobs {
    // furnace cooks an item in 10 s, blast furnace and smoker in 5
    public static final int FURNACE_TICKS = 200;
    public static final int FAST_TICKS = 100;

    private FurnaceJobs() {
    }

    // block registry path -> ticks per item
    public static int ticksPerItem(String kind) {
        return "blast_furnace".equals(kind) || "smoker".equals(kind) ? FAST_TICKS : FURNACE_TICKS;
    }

    // when the last item is done if it all started cooking at startTick. the fuel has to be loaded up front for this to hold,
    // which is the only way the smelt tasks ever call it
    public static long doneTick(String kind, long startTick, int count) {
        return startTick + (long) ticksPerItem(kind) * count;
    }

    // the same for a part of the input (a load left short is due when its fuel is, AsyncSmelting.cookable), to the nearest tick
    public static long doneTick(String kind, long startTick, double count) {
        return startTick + Math.round(ticksPerItem(kind) * Math.max(0, count));
    }

    // a job for this spot replaces the old one: the count was read off the furnace's input slot a moment ago, so it is
    // newer than whatever we remembered
    public static void record(List<RunState.FurnaceJob> jobs, RunState.FurnaceJob job) {
        jobs.removeIf(old -> sameSpot(old, job.pos, job.dimension));
        jobs.add(job);
    }

    private static boolean sameSpot(RunState.FurnaceJob job, RunState.Pos pos, String dimension) {
        return job.pos.equals(pos) && job.dimension.equals(dimension);
    }

    // the station at this spot has something of ours in it. the nether has its own x y z, and a job at 10 64 0 there is not the
    // overworld furnace's, so the dimension is always asked (the visit that takes a station back asks it too)
    public static boolean isBusy(RunState state, RunState.Pos pos, String dimension) {
        for (RunState.FurnaceJob job : state.furnaceJobs) {
            if (sameSpot(job, pos, dimension)) {
                return true;
            }
        }
        return false;
    }

    // the job an interrupted load becomes (Workbenches.adoptLoad): our items are in the station and nothing points at it, nothing is
    // known to be cooking, so it is stranded (not pending output, not pending food) and due now, which sends the next collect trip
    // to it. `input` and `count` are the last look at the slots, the visit reads the real ones
    public static RunState.FurnaceJob adopted(RunState.Pos pos, String dimension, String kind, String input, int count, long now) {
        RunState.FurnaceJob job = new RunState.FurnaceJob(pos, dimension, kind, input, Math.max(1, count), smeltOutput(input), now, now);
        job.stranded = true;
        return job;
    }

    // what a visit that finds the station lit turns the adopted load into is a live job, and pending() counts by output name, so
    // the name has to be the real one (raw ore to ingot, raw meat to cooked, a potato to a baked one). an item that is already a
    // product, or that nothing here cooks, comes back as what it is
    public static String smeltOutput(String input) {
        if (input.startsWith("raw_")) {
            return input.substring("raw_".length()) + "_ingot";
        }
        return switch (input) {
            case "beef", "porkchop", "mutton", "chicken", "rabbit", "cod", "salmon" -> "cooked_" + input;
            case "potato" -> "baked_potato";
            case "kelp" -> "dried_kelp";
            default -> input;
        };
    }

    // what the jobs for this dimension will have given us by the time they are all done
    public static int pending(List<RunState.FurnaceJob> jobs, String outputName) {
        int total = 0;
        for (RunState.FurnaceJob job : jobs) {
            if (job.output.equals(outputName) && !job.stranded) {
                total += job.count;
            }
        }
        return total;
    }

    // the same with the loads that went in this tick and are still in AsyncSmelting's queue (the gamer only drains it on its next
    // tick). a queued job replaces the job at its spot the way record() will, so a reload of the same furnace is counted once.
    // `skip` leaves one spot out (the station a smelt task is reading off its own slots), null for none
    public static int pending(List<RunState.FurnaceJob> jobs, List<RunState.FurnaceJob> queued, String outputName, RunState.Pos skip, String dimension) {
        int total = 0;
        for (RunState.FurnaceJob job : jobs) {
            if (!queuedAt(queued, job) && !skipped(job, skip, dimension)) {
                total += countOf(job, outputName);
            }
        }
        for (int i = 0; i < queued.size(); i++) {
            RunState.FurnaceJob job = queued.get(i);
            // two loads of one furnace in one queue: the later one is what record() keeps
            if (!laterAt(queued, i) && !skipped(job, skip, dimension)) {
                total += countOf(job, outputName);
            }
        }
        return total;
    }

    // a job of ours at this spot, in the list or in the queue
    public static boolean jobAt(List<RunState.FurnaceJob> jobs, List<RunState.FurnaceJob> queued, RunState.Pos pos, String dimension) {
        for (RunState.FurnaceJob job : jobs) {
            if (sameSpot(job, pos, dimension)) {
                return true;
            }
        }
        for (RunState.FurnaceJob job : queued) {
            if (sameSpot(job, pos, dimension)) {
                return true;
            }
        }
        return false;
    }

    private static int countOf(RunState.FurnaceJob job, String outputName) {
        return job.output.equals(outputName) && !job.stranded ? job.count : 0;
    }

    private static boolean skipped(RunState.FurnaceJob job, RunState.Pos skip, String dimension) {
        return skip != null && sameSpot(job, skip, dimension);
    }

    private static boolean queuedAt(List<RunState.FurnaceJob> queued, RunState.FurnaceJob job) {
        for (RunState.FurnaceJob q : queued) {
            if (sameSpot(q, job.pos, job.dimension)) {
                return true;
            }
        }
        return false;
    }

    private static boolean laterAt(List<RunState.FurnaceJob> queued, int i) {
        RunState.FurnaceJob job = queued.get(i);
        for (int j = i + 1; j < queued.size(); j++) {
            if (sameSpot(queued.get(j), job.pos, job.dimension)) {
                return true;
            }
        }
        return false;
    }

    // nutrition the food jobs will have given us once they are done. not in the bag yet, so not in foodUnits(): the planner
    // adds this on top so a loaded smoker is not a reason to go hunting again
    public static int pendingUnits(List<RunState.FurnaceJob> jobs) {
        int total = 0;
        for (RunState.FurnaceJob job : jobs) {
            // meat sitting cold in a station is not dinner on the way (it read as 77 units held and the top-up waited, 13:11:01)
            if (!job.stranded) {
                total += job.count * job.unitsEach;
            }
        }
        return total;
    }

    // the placed list a job's block belongs to, null for kinds we never take back
    public static List<RunState.Pos> placedFor(RunState state, String kind) {
        if ("furnace".equals(kind)) {
            return state.placedFurnaces;
        }
        return "smoker".equals(kind) ? state.placedSmokers : null;
    }

    // the food job a trip that is about to leave the dimension should pick up first: cooking food (a stranded one is raw meat sitting
    // cold, not dinner), ready first, and no further than PackUp.TRIP_BUDGET of walking. `walkOf` = what the walk to it costs from here.
    // null when there is none worth the detour
    public static RunState.FurnaceJob foodJob(List<RunState.FurnaceJob> jobs, java.util.function.ToDoubleFunction<RunState.FurnaceJob> walkOf) {
        return foodJob(jobs, walkOf, j -> false);
    }

    // `banned` = pathing gave up on its furnace: picked only when no other food job is left
    public static RunState.FurnaceJob foodJob(List<RunState.FurnaceJob> jobs, java.util.function.ToDoubleFunction<RunState.FurnaceJob> walkOf,
                                              java.util.function.Predicate<RunState.FurnaceJob> banned) {
        return soonest(jobs.stream().filter(j -> j.unitsEach > 0 && !j.stranded && walkOf.applyAsDouble(j) <= PackUp.TRIP_BUDGET).toList(), banned);
    }

    // the job that is ready first, null if there are none
    public static RunState.FurnaceJob soonest(List<RunState.FurnaceJob> jobs) {
        return soonest(jobs, j -> false);
    }

    // a banned job (pathing gave up on its furnace) comes last, never out: the phase cannot end while a job is left
    public static RunState.FurnaceJob soonest(List<RunState.FurnaceJob> jobs, java.util.function.Predicate<RunState.FurnaceJob> banned) {
        RunState.FurnaceJob best = null;
        RunState.FurnaceJob bestBanned = null;
        for (RunState.FurnaceJob job : jobs) {
            if (banned.test(job)) {
                if (bestBanned == null || job.doneTick < bestBanned.doneTick) {
                    bestBanned = job;
                }
            } else if (best == null || job.doneTick < best.doneTick) {
                best = job;
            }
        }
        return best != null ? best : bestBanned;
    }

    // ticks until the last job is done, 0 when they all are
    public static long ticksLeft(List<RunState.FurnaceJob> jobs, long now) {
        long left = 0;
        for (RunState.FurnaceJob job : jobs) {
            left = Math.max(left, job.doneTick - now);
        }
        return left;
    }

    // ticks left on `inputCount` items when the one cooking right now is `arrow` of the way done (0..1). the furnace's own slots
    // are the truth, this is the same sum CollectFromFurnaceTask uses to decide between waiting and leaving
    public static long remainingTicks(String kind, int inputCount, double arrow) {
        return Math.round(ticksPerItem(kind) * (inputCount - Math.min(1.0, Math.max(0.0, arrow))));
    }

    // food that came back out of a station because it was not cooking: the cook that put it there starts again at once (raw in
    // the bag, a station, fuel on paper) unless it sits out the backoff. a visit that took it back because we are leaving is not this
    public static boolean backsOffCook(RunState.FurnaceJob job, boolean tookBackStalled) {
        return tookBackStalled && (job.unitsEach > 0 || job.stranded);
    }

    // a visit that is still going took something out of the station: the job keeps its full count until the visit ends, so a cut off
    // visit (a mob, a death) left it counting what is already in the bag. `seen` = input + output in the slots at the last look, -1
    // for no look. only ever lowers the count and moves the timer, nothing else: whether the job is cooking or counts as stalled is
    // the visit's end to say (a half cooked stranded batch we just took the cooked half of is still cold). true = brought down
    public static boolean settleMidVisit(List<RunState.FurnaceJob> jobs, RunState.FurnaceJob job, int seen, long remaining, long now) {
        if (seen < 0 || seen >= job.count) {
            return false;
        }
        if (seen == 0) {
            return jobs.remove(job);
        }
        job.count = seen;
        job.doneTick = now + Math.max(1, remaining);
        return true;
    }

    // is this very job still on the books (identity, a removed job must not come back through an equal one)
    public static boolean listed(List<RunState.FurnaceJob> jobs, RunState.FurnaceJob job) {
        for (RunState.FurnaceJob j : jobs) {
            if (j == job) {
                return true;
            }
        }
        return false;
    }

    // what a job looks like after we visited: what is still cooking (input slot count) or nothing left to come back for.
    // the furnace sat in an unloaded chunk for who knows how long, so the old doneTick was a guess and this one is not
    public static void afterVisit(List<RunState.FurnaceJob> jobs, RunState.FurnaceJob job, int inputLeft, long now) {
        afterVisit(jobs, job, inputLeft, (long) ticksPerItem(job.kind) * inputLeft, now);
    }

    // same with the time left read off the cook arrow instead of assuming the current item has not started
    public static void afterVisit(List<RunState.FurnaceJob> jobs, RunState.FurnaceJob job, int inputLeft, long remaining, long now) {
        afterVisit(jobs, job, inputLeft, remaining, now, false);
    }

    // `capped` = the trip stood at the furnace until its cap ran out. the re-stamp below gives the job a fresh honest looking
    // timer, so without this count a furnace that never finishes looks fine at every visit and holds the phase for ever
    // (FurnacePlan.stuck reads the count)
    public static void afterVisit(List<RunState.FurnaceJob> jobs, RunState.FurnaceJob job, int inputLeft, long remaining, long now,
                                  boolean capped) {
        if (inputLeft <= 0) {
            jobs.remove(job);
            return;
        }
        if (inputLeft < job.count) {
            job.stalls = 0;
        } else if (capped) {
            job.stalls++;
        }
        job.count = inputLeft;
        job.startTick = now;
        job.doneTick = now + Math.max(1, remaining);
        job.visited = true;
        // the visit found it lit or fueled (a stalled one is taken back out, never gets here), so it is cooking now
        job.stranded = false;
    }
}
