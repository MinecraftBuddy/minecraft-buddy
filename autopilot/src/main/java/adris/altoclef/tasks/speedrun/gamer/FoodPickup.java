package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherTripRules;
import adris.altoclef.tasksystem.Task;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

// the food we cooked at home before the nether is not in the bag, and a trip that goes back down on bag units alone walks past it
// (or hunts for what is already sitting in the smoker). one visit first, the same trip the portal phase makes on its way out
// (FurnaceWatch.leave): the walk is bounded by PackUp.TRIP_BUDGET (FurnaceJobs.foodJob) and the whole detour by COLLECT_SECONDS.
// the death trip and the food trip both use it; each holds its own, the benches themselves live in RunState so a second
// Workbenches sees the same stations the phases do. no job is not "done": a smoker loaded while the hunt runs is picked up too
public final class FoodPickup {
    private final FurnaceWatch furnaces = new FurnaceWatch(new Workbenches());
    // the stations a trip already went to, so one that is still cooking after a capped wait is not walked to again for ever
    private final Set<RunState.Pos> visited = new HashSet<>();
    private boolean done;
    private long start = -1;

    public FoodPickup() {
        // exit mode: it never loads a new batch of meat on the way out
        furnaces.newExitPhase();
    }

    // a new trip gets a new look
    public void reset() {
        done = false;
        start = -1;
        visited.clear();
        furnaces.respawned();
    }

    // a death took the body the visit was on, the jobs stay in RunState
    public void respawned() {
        furnaces.respawned();
    }

    // the task for this tick, null when there is nothing (left) to pick up. overworld only, the caller checks
    public Task tick(AltoClef mod, GamerContext ctx) {
        long now = ctx.facts().gameTime();
        // the station we just emptied comes down first, a pickup in flight runs to the end
        Task next = furnaces.finishing(mod, ctx);
        if (next == null) {
            if (!NetherTripRules.collectingFood(done, start, now)) {
                furnaces.drop();
                done = true;
                return null;
            }
            furnaces.housekeeping(mod, ctx);
            // the visit under way (or just ended: that is when its job leaves the list) before a new job is picked
            next = furnaces.active(mod, ctx);
            if (next != null && start < 0) {
                start = now;
            }
            if (next == null) {
                Vec3 me = mod.getPlayer().position();
                RunState.FurnaceJob job = FurnaceJobs.foodJob(ctx.facts().furnaceJobs(),
                        j -> visited.contains(j.pos) ? Double.MAX_VALUE : PackUp.walk(j, me.x, me.y, me.z), FurnaceWatch.banned(mod));
                if (job == null) {
                    return null;
                }
                visited.add(job.pos);
                if (start < 0) {
                    start = now;
                }
                next = furnaces.leave(mod, ctx, job, FurnacePlan.Leaving.DIMENSION, 0);
                if (next == null) {
                    return null;
                }
            }
        }
        return next;
    }

    public String hud() {
        return furnaces.hud();
    }
}
