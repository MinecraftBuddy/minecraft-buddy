package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.container.SmeltInBlastFurnaceTask;
import adris.altoclef.tasks.container.SmeltInFurnaceTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.SmeltTarget;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.Baritone;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

// decides, once per smelt batch, whether the ore goes in a blast furnace that is already standing nearby (villages have
// one, armorer) or in the plain furnace. one of these lives in each Collect*IngotTask
final class SmeltRouter {

    enum Held {NONE, FURNACE, BLAST}

    enum Pick {NEARBY_BLAST, DEFAULT}

    // walking out to the edge of the range and then having the range shrink back is how you get a bot that
    // oscillates between two smelters forever. once we picked the blast furnace it gets some slack
    private static final double STAY_SLACK = 1.5;

    private Held held = Held.NONE;
    private boolean pickedBlast;
    private BlockPos blastPos;
    private SmeltTarget blastTarget;
    private SmeltInBlastFurnaceTask blastTask;
    private SmeltTarget furnaceTarget;
    private SmeltInFurnaceTask furnaceTask;

    // pure on purpose so it can be tested without a world. distToBlast is infinity when there isn't a usable one.
    // the caller drops BLAST back to NONE when that blast furnace stopped existing
    static Pick pick(boolean useNearby, double distToBlast, double range, Held held, boolean pickedBlastLastTick) {
        if (held == Held.FURNACE) {
            // iron is already cooking in there, leaving it behind would strand it
            return Pick.DEFAULT;
        }
        if (held == Held.BLAST) {
            return Pick.NEARBY_BLAST;
        }
        if (!useNearby) {
            return Pick.DEFAULT;
        }
        double reach = pickedBlastLastTick ? range * STAY_SLACK : range;
        return distToBlast <= reach ? Pick.NEARBY_BLAST : Pick.DEFAULT;
    }

    // the task to run if a nearby blast furnace should do this smelt, otherwise null and the caller carries on with its
    // normal furnace logic. call this first every tick, it also notices when a smelt has actually started
    Task tryNearbyBlast(AltoClef mod, SmeltTarget target) {
        updateHeld(mod);
        Optional<BlockPos> near = findBlastFurnace(mod);
        double dist = Double.POSITIVE_INFINITY;
        if (near.isPresent()) {
            dist = Math.sqrt(near.get().distToCenterSqr(mod.getPlayer().position()));
        }
        Pick pick = pick(Baritone.settings().altoUseNearbyBlastFurnace.value, dist,
                Baritone.settings().altoNearbyBlastFurnaceRange.value, held, pickedBlast);
        if (pick != Pick.NEARBY_BLAST || near.isEmpty()) {
            pickedBlast = false;
            blastTask = null;
            return null;
        }
        BlockPos pos = near.get();
        if (blastTask == null || !pos.equals(blastPos) || !target.equals(blastTarget)) {
            blastTask = new SmeltInBlastFurnaceTask(target, pos);
            blastTarget = target;
        }
        blastPos = pos;
        pickedBlast = true;
        return blastTask;
    }

    // the plain furnace task, the same instance every tick so we can ask it whether it started. tryNearbyBlast has
    // to run before this on a tick
    SmeltInFurnaceTask furnace(SmeltTarget target) {
        if (furnaceTask == null || !target.equals(furnaceTarget)) {
            furnaceTask = new SmeltInFurnaceTask(target);
            furnaceTarget = target;
        }
        return furnaceTask;
    }

    private void updateHeld(AltoClef mod) {
        if (held == Held.NONE) {
            if (blastTask != null && blastTask.hasStartedSmelting()) {
                held = Held.BLAST;
            } else if (furnaceTask != null && furnaceTask.hasStartedSmelting()) {
                held = Held.FURNACE;
            }
        }
        if (held == Held.BLAST && (blastPos == null || !mod.getBlockTracker().blockIsValid(blastPos, Blocks.BLAST_FURNACE))) {
            // it's gone (or the tracker gave up on reaching it, which blacklists it so we won't pick it again)
            held = Held.NONE;
            pickedBlast = false;
            blastTask = null;
        }
    }

    private Optional<BlockPos> findBlastFurnace(AltoClef mod) {
        if (held == Held.BLAST) {
            return Optional.ofNullable(blastPos);
        }
        // keep the one we already picked while it's still around so two close ones don't trade places on us
        if (pickedBlast && blastPos != null && mod.getBlockTracker().blockIsValid(blastPos, Blocks.BLAST_FURNACE)) {
            return Optional.of(blastPos);
        }
        // same reach test DoStuffInContainerTask uses, so we only pick one it is willing to walk to
        return mod.getBlockTracker().getNearestTracking(p -> WorldHelper.canReach(mod, p), Blocks.BLAST_FURNACE);
    }
}
