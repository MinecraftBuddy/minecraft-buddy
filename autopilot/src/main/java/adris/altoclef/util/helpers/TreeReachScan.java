package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import baritone.api.utils.Dimension;
import baritone.pathing.movement.MovementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.HashMap;
import java.util.Map;

// the world half of TreeReach: reads the column under a log and the cells around it, and remembers the answer for a couple of
// seconds. the log pick asks about the nearest few logs every tick, and the floor scan is a column of world reads
public final class TreeReachScan {
    private static final int CACHE_TICKS = 40;
    // a trunk with its bottom cut away floats, and what counts is how far its lowest log is from the ground
    private static final int CHAIN_MAX = 24;
    // a dirt block that is lying around, in the box around us. one look per few seconds, it is a few thousand reads
    private static final int DIRT_RADIUS = 8;
    private static final int DIRT_UP = 2;
    private static final int DIRT_DOWN = 4;
    private static final int DIRT_LOOK_TICKS = 100;

    // flagged: WorldHelper.dangerousToBreakIfRightAbove on the lowest log of the column. fromGround and gap are only worth
    // reading when it is
    public record Look(boolean flagged, boolean fromGround, int gap) {
    }

    private static final Look FINE = new Look(false, true, 0);

    private final Map<BlockPos, Look> looks = new HashMap<>();
    private int looksAt = Integer.MIN_VALUE;
    private int dirtAt = Integer.MIN_VALUE;
    private boolean dirtNear;

    public Look look(AltoClef mod, BlockPos log, int now) {
        if (now - looksAt >= CACHE_TICKS || now < looksAt) {
            looks.clear();
            looksAt = now;
        }
        return looks.computeIfAbsent(log.immutable(), at -> read(mod, at));
    }

    // TreeReach.buried on the real column. the nether has a roof over everything, it would call every stem buried. a chunk we can't
    // read isn't buried, the pursuit asks again once it is loaded. no cache like look has: past the depth test this stops at the
    // first solid block, and a post has its planks right on top
    public static boolean buried(AltoClef mod, BlockPos log) {
        if (WorldHelper.getCurrentDimension() != Dimension.OVERWORLD || !mod.getWorld().isLoaded(log)) {
            return false;
        }
        int x = log.getX();
        int z = log.getZ();
        int top = mod.getWorld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        return TreeReach.buried(log.getY(), top, y -> ceiling(mod.getWorld().getBlockState(at.set(x, y, z))));
    }

    // what makes a log not a tree: something over it that no tree grows. vines, snow and water don't count, the trunk and its leaves are the tree
    private static boolean ceiling(BlockState state) {
        return !state.isAir() && state.getFluidState().isEmpty() && !state.is(BlockTags.LOGS) && !state.is(BlockTags.LEAVES) && state.isSolid();
    }

    private static Look read(AltoClef mod, BlockPos log) {
        // an unloaded chunk reads as air all the way down, which is a "no floor" for a log that has one. not knowing is not a reason to skip
        if (!loadedAround(mod, log)) {
            return FINE;
        }
        // a log with logs under it is only as reachable as the lowest one: it is the one we cut first. a trunk with its
        // bottom cut away has only its lowest log flagged, the ones above look intact from where they sit
        BlockPos low = log;
        for (int i = 0; i < CHAIN_MAX && mod.getWorld().getBlockState(low.below()).is(BlockTags.LOGS); i++) {
            low = low.below();
        }
        BlockPos bottom = low;
        if (!WorldHelper.dangerousToBreakIfRightAbove(mod, bottom)) {
            return FINE;
        }
        TreeReach.Column column = TreeReach.column(bottom.getY(), y -> cellAt(mod, bottom.getX(), y, bottom.getZ()));
        return new Look(true, TreeReach.fromGround(bottom.getY(), column, sideStandable(mod, bottom)), TreeReach.gap(bottom.getY(), column));
    }

    private static boolean loadedAround(AltoClef mod, BlockPos log) {
        for (int dx = -1; dx <= 1; dx += 2) {
            for (int dz = -1; dz <= 1; dz += 2) {
                if (!mod.getWorld().isLoaded(log.offset(dx, 0, dz))) {
                    return false;
                }
            }
        }
        return mod.getWorld().isLoaded(log);
    }

    private static TreeReach.Cell cellAt(AltoClef mod, int x, int y, int z) {
        BlockPos at = new BlockPos(x, y, z);
        BlockState state = mod.getWorld().getBlockState(at);
        if (state.isAir()) {
            return TreeReach.Cell.AIR;
        }
        if (MovementHelper.isLava(state)) {
            return TreeReach.Cell.LAVA;
        }
        if (MovementHelper.isWater(state)) {
            return TreeReach.Cell.WATER;
        }
        return WorldHelper.isSolid(mod, at) ? TreeReach.Cell.SOLID : TreeReach.Cell.BLOCKING;
    }

    // a neighbouring column with ground at the log's height or within two under it: the sides GoalMineFromSide takes. other
    // logs are not ground (a branch beside the trunk is part of the problem, not a way up), and water is, we can swim in it
    private static boolean sideStandable(AltoClef mod, BlockPos log) {
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                for (int dy = 0; dy >= -2; dy--) {
                    at.set(log.getX() + dx, log.getY() + dy, log.getZ() + dz);
                    BlockState state = mod.getWorld().getBlockState(at);
                    if (MovementHelper.isWater(state) || WorldHelper.isSolid(mod, at) && !state.is(BlockTags.LOGS)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // is there dirt close enough to be worth digging before a tower. grass blocks drop dirt too
    public boolean dirtAround(AltoClef mod, int now) {
        if (now - dirtAt < DIRT_LOOK_TICKS && now >= dirtAt) {
            return dirtNear;
        }
        dirtAt = now;
        dirtNear = scanDirt(mod);
        return dirtNear;
    }

    private static boolean scanDirt(AltoClef mod) {
        BlockPos feet = mod.getPlayer().blockPosition();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int dx = -DIRT_RADIUS; dx <= DIRT_RADIUS; dx++) {
            for (int dz = -DIRT_RADIUS; dz <= DIRT_RADIUS; dz++) {
                for (int dy = DIRT_UP; dy >= -DIRT_DOWN; dy--) {
                    at.set(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
                    BlockState state = mod.getWorld().getBlockState(at);
                    if (state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
