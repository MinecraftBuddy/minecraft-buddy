package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.resources.CollectBedTask;
import adris.altoclef.tasks.speedrun.gamer.Respawn;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;

// the bed questions a night asks the world, with nothing of the gamer in them. the gamer and a schematic build both run a
// night (NightSleepTask) and both ask these the same way
public final class NightBeds {
    private NightBeds() {
    }

    // one of our own beds from an earlier trip, still standing close enough to walk back for. one that is gone is forgotten
    public static boolean leftover(AltoClef mod, RunState state, int bedRadius, Runnable save) {
        RunState.Pos at = state.nightBed;
        if (at == null) {
            return false;
        }
        BlockPos pos = new BlockPos(at.x, at.y, at.z);
        boolean standing = mod.getWorld().hasChunkAt(pos) && mod.getWorld().getBlockState(pos).getBlock() instanceof BedBlock;
        if (standing && pos.closerToCenterThan(mod.getPlayer().position(), bedRadius + 8)) {
            return true;
        }
        // taken or broken by somebody, or too far or unloaded to walk back for: not ours to keep a claim on. a claim left standing
        // here would start a trip that reads an unloaded bed as air and goes looking for its item
        state.nightBed = null;
        save.run();
        return false;
    }

    // a bed that is already standing close by and that we can sleep in right now: not on copper (a trial chamber's), nothing
    // nasty within the game's box around it. our own night bed never gets here, a trip that left one is a cleanup trip first
    public static BlockPos find(AltoClef mod, int bedRadius) {
        var tracker = mod.getBlockTracker();
        BlockPos best = null;
        double bestDist = Double.POSITIVE_INFINITY;
        for (BlockPos pos : tracker.getKnownLocations(CollectBedTask.BEDS)) {
            double dist = pos.distToCenterSqr(mod.getPlayer().position());
            if (dist > (double) bedRadius * bedRadius) {
                continue;
            }
            // sleeping anywhere else moves our respawn off the bed we already have, so the spawn bed beats a nearer one
            if (Respawn.isSpawnBed(pos.getX(), pos.getY(), pos.getZ())) {
                dist = -1;
            }
            if (dist >= bestDist) {
                continue;
            }
            if (!(mod.getWorld().getBlockState(pos).getBlock() instanceof BedBlock) || tracker.unreachable(pos)) {
                continue;
            }
            BlockPos head = WorldHelper.getBedHead(mod, pos);
            if (head == null || NightSleepTask.monstersBlock(mod, head) || onCopper(mod, head)) {
                continue;
            }
            best = pos;
            bestDist = dist;
        }
        return best;
    }

    // what find thinks of the closest bed the tracker knows, closest by plain distance before any of its checks. for the log
    // line when the prep's "is a bed ready" flaps, so it says which check is the one going back and forth
    public static String nearestWhy(AltoClef mod, int bedRadius) {
        var tracker = mod.getBlockTracker();
        BlockPos nearest = null;
        double best = Double.POSITIVE_INFINITY;
        for (BlockPos pos : tracker.getKnownLocations(CollectBedTask.BEDS)) {
            double d = pos.distToCenterSqr(mod.getPlayer().position());
            if (d < best) {
                best = d;
                nearest = pos;
            }
        }
        if (nearest == null) {
            return "no bed known";
        }
        String where = "nearest " + nearest.toShortString() + " at " + Math.round(Math.sqrt(best) * 10) / 10.0;
        if (best > (double) bedRadius * bedRadius) {
            return where + ", past the " + bedRadius + " radius";
        }
        if (!(mod.getWorld().getBlockState(nearest).getBlock() instanceof BedBlock)) {
            return where + ", not a bed any more";
        }
        if (tracker.unreachable(nearest)) {
            return where + ", unreachable";
        }
        BlockPos head = WorldHelper.getBedHead(mod, nearest);
        if (head == null) {
            return where + ", no head";
        }
        if (NightSleepTask.monstersBlock(mod, head)) {
            return where + ", monsters";
        }
        return onCopper(mod, head) ? where + ", on copper" : where + ", usable";
    }

    private static boolean onCopper(AltoClef mod, BlockPos pos) {
        Block below = mod.getWorld().getBlockState(pos.below()).getBlock();
        for (Block copper : ItemHelper.itemsToBlocks(ItemHelper.COPPER_BLOCKS)) {
            if (copper == below) {
                return true;
            }
        }
        return false;
    }
}
