package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.speedrun.gamer.GamerPhase;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

import java.util.function.Predicate;

// topping the water bucket back up when there is a pond right there (and the end prep gate, which looks further). the mlg clutch spends it, and the next time it is
// needed (lava, a fall) is usually twenty minutes later and nowhere near water. the rules are pure, the chain hands in what it sees
public final class WaterUpkeep {

    private WaterUpkeep() {
    }

    // a pond further than this is a trip, and trips belong to the kit planner (the end prep gate takes a longer one on a clock)
    public static final double RANGE = 16;
    // one go at it, then the pond gets a rest. 15 s is a walk of 16 blocks plus the scoop with room to spare
    public static final int TIMEOUT_TICKS = 300;
    // after any failure, 60 s before the next go
    public static final int COOLDOWN_TICKS = 1200;
    // looking for the pond walks a cube of blocks, once in a while is plenty
    public static final int LOOK_TICKS = 40;

    // only inside a gamer run, and not where it juggles buckets on purpose: the portal cast (water and lava buckets in and
    // out), and the end where EndGear does its own water. outside a run (null) the user is driving and gets no surprise detours
    public static boolean phaseAllows(GamerPhase phase) {
        if (phase == null) {
            return false;
        }
        return switch (phase) {
            case PORTAL, END_PREP, DRAGON, DONE, STUCK -> false;
            default -> true;
        };
    }

    // calm = nothing else has the bot (no clutch, cleanup, swim up, meal, fire). room = the full bucket has a slot to land in
    public static boolean wanted(boolean emptyBucket, boolean waterBucket, boolean evaporates, boolean threat, boolean calm,
                                 boolean room, GamerPhase phase) {
        return emptyBucket && !waterBucket && !evaporates && !threat && calm && room && phaseAllows(phase);
    }

    public static boolean coolingDown(int now, int failedAt) {
        // the tick counter restarts on a reconnect, a stamp from before it is old news
        return GameClock.since(failedAt, now) < COOLDOWN_TICKS;
    }

    public static boolean lookedRecently(int now, int lookedAt) {
        return GameClock.since(lookedAt, now) < LOOK_TICKS;
    }

    public static boolean timedOut(int now, int startedAt) {
        return GameClock.since(startedAt, now) >= TIMEOUT_TICKS;
    }

    // how far from the source we look for somewhere dry to stand
    private static final int SHORE = 3;

    // water we can scoop from above: a source with open air over it, and a way out again. a flooded cave with a one block gap
    // under its ceiling has both of the first two and is a trap anyway
    public static boolean pondSource(AltoClef mod, BlockPos p) {
        Level world = mod.getWorld();
        return BucketPour.isWaterSource(world, p)
                && !byEndPortal(p, q -> world.getBlockState(q).is(Blocks.END_PORTAL))
                && FluidSources.exposedSource(world.getFluidState(p), world.getFluidState(p.above()))
                && world.getBlockState(p.above()).getCollisionShape(world, p.above()).isEmpty()
                && pondOpen(!world.getBlockState(p.above(2)).getCollisionShape(world, p.above(2)).isEmpty(), dryStandNearby(world, p));
    }

    // water poured into the portal room settles on the portal itself (and on the frame next to it). the scoop walks onto it
    // and drops us into the End mid errand. same height as the water or one under it, the 3x3 around it. pure for the tests
    public static boolean byEndPortal(BlockPos p, Predicate<BlockPos> isEndPortal) {
        for (int dy = -1; dy <= 0; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (isEndPortal.test(p.offset(dx, dy, dz))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // a solid lid right over the air layer means the pond is a pocket, and nowhere dry to stand means we would be swimming
    // for the scoop. either way it is not a pond, it is a cave with water in it
    public static boolean pondOpen(boolean lidOverTheGap, boolean dryStandNearby) {
        return !lidOverTheGap && dryStandNearby;
    }

    // feet and head clear of anything solid and of water, with a floor that is not water under it
    public static boolean standable(boolean floorSolid, boolean floorWet, boolean feetClear, boolean headClear, boolean wet) {
        return floorSolid && !floorWet && feetClear && headClear && !wet;
    }

    private static boolean dryStandNearby(Level world, BlockPos p) {
        BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
        for (int dx = -SHORE; dx <= SHORE; dx++) {
            for (int dz = -SHORE; dz <= SHORE; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    c.set(p.getX() + dx, p.getY() + dy, p.getZ() + dz);
                    if (dryStand(world, c)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean dryStand(Level world, BlockPos feet) {
        BlockPos below = feet.below();
        BlockPos head = feet.above();
        return standable(!world.getBlockState(below).getCollisionShape(world, below).isEmpty(), !world.getFluidState(below).isEmpty(),
                world.getBlockState(feet).getCollisionShape(world, feet).isEmpty(), world.getBlockState(head).getCollisionShape(world, head).isEmpty(),
                !world.getFluidState(feet).isEmpty() || !world.getFluidState(head).isEmpty());
    }

    // nearest pond source within range (and 8 up or down), straight line. a cube walk, which is why callers only ask
    // once in a while. skip = ponds that already ate their clock
    public static BlockPos nearestPond(AltoClef mod, int range, Predicate<BlockPos> skip) {
        BlockPos me = mod.getPlayer().blockPosition();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        BlockPos best = null;
        double bestDist = (double) range * range;
        for (int dx = -range; dx <= range; dx++) {
            for (int dz = -range; dz <= range; dz++) {
                for (int dy = -8; dy <= 8; dy++) {
                    double d = dx * dx + dy * dy + dz * dz;
                    if (d > bestDist) {
                        continue;
                    }
                    p.set(me.getX() + dx, me.getY() + dy, me.getZ() + dz);
                    if (pondSource(mod, p) && !mod.getBlockTracker().unreachable(p) && !skip.test(p)) {
                        best = p.immutable();
                        bestDist = d;
                    }
                }
            }
        }
        return best;
    }
}
