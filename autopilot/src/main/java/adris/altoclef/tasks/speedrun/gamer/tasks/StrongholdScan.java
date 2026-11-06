package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.entity.AbstractKillEntityTask;
import adris.altoclef.util.helpers.SeenFilter;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.world.FrameGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.monster.Silverfish;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

// the little game-touching queries the three stronghold tasks share. blocks count once the player has SEEN them (SeenFilter),
// except knownFrames: the portal room search takes any frame the client has loaded
public final class StrongholdScan {
    // never test more than this many tracked blocks per scan: the tracker can hold thousands of bricks and the
    // seen filter only spends 8 raycasts a tick anyway, so the nearest ones are the ones worth asking about
    public static final int MAX_SCAN = 200;

    // the closest cap positions to origin, nearest first. the tracker hands them out in scan order, and the same first
    // unseen few would otherwise eat the seen filter's budget every scan while a visible one further down starves
    public static List<BlockPos> nearest(List<BlockPos> candidates, BlockPos origin, int cap) {
        List<BlockPos> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble(p -> p.distSqr(origin)));
        return sorted.size() > cap ? new ArrayList<>(sorted.subList(0, cap)) : sorted;
    }

    // frames that were in line of sight, as {x, y, z, facingX, facingZ}
    public static List<int[]> seenFrames(AltoClef mod) {
        List<int[]> out = new ArrayList<>();
        for (BlockPos pos : nearest(mod.getBlockTracker().getKnownLocations(Blocks.END_PORTAL_FRAME), mod.getPlayer().blockPosition(), MAX_SCAN)) {
            BlockState state = mod.getWorld().getBlockState(pos);
            // the tracker remembers unloaded chunks, so check the block is really still a frame
            if (!state.is(Blocks.END_PORTAL_FRAME) || !SeenFilter.isSeen(mod, pos)) {
                continue;
            }
            Direction facing = state.getValue(EndPortalFrameBlock.FACING);
            out.add(new int[]{pos.getX(), pos.getY(), pos.getZ(), facing.getStepX(), facing.getStepZ()});
        }
        return out;
    }

    // ring centre from the frames. facing first (it settles a lone side), plain positions if the facings disagree
    // with the model (they would only disagree if i have the generated facing backwards, so do not hang on it)
    public static Optional<int[]> centre(List<int[]> frames) {
        Optional<int[]> withFacing = FrameGeometry.centreFromFrames(frames);
        if (withFacing.isPresent()) {
            return withFacing;
        }
        List<int[]> plain = new ArrayList<>();
        for (int[] f : frames) {
            plain.add(new int[]{f[0], f[1], f[2]});
        }
        return FrameGeometry.centreFromFrames(plain);
    }

    // how far from the stronghold estimate a loaded frame still counts. the estimate is a few hundred blocks off at worst
    // and a frame this side of it is nobody else's: the only frames in the overworld are the stronghold's
    public static final int KNOWN_FRAME_RANGE = 200;

    // positions within range blocks (flat) of x,z, a plain filter so it is testable
    public static List<BlockPos> withinFlat(List<BlockPos> candidates, double x, double z, double range) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos p : candidates) {
            double dx = p.getX() + 0.5 - x;
            double dz = p.getZ() + 0.5 - z;
            if (dx * dx + dz * dz <= range * range) {
                out.add(p);
            }
        }
        return out;
    }

    // every frame the client has loaded near the estimate, as {x, y, z, facingX, facingZ}. no line of sight needed, this is
    // the same thing #goto end_portal_frame trusts. the tracker remembers unloaded chunks, so the block is re-read
    public static List<int[]> knownFrames(AltoClef mod, double nearX, double nearZ) {
        List<int[]> out = new ArrayList<>();
        List<BlockPos> close = withinFlat(mod.getBlockTracker().getKnownLocations(Blocks.END_PORTAL_FRAME), nearX, nearZ, KNOWN_FRAME_RANGE);
        for (BlockPos pos : nearest(close, mod.getPlayer().blockPosition(), MAX_SCAN)) {
            if (!mod.getChunkTracker().isChunkLoaded(pos)) {
                continue;
            }
            BlockState state = mod.getWorld().getBlockState(pos);
            if (!state.is(Blocks.END_PORTAL_FRAME)) {
                continue;
            }
            Direction facing = state.getValue(EndPortalFrameBlock.FACING);
            out.add(new int[]{pos.getX(), pos.getY(), pos.getZ(), facing.getStepX(), facing.getStepZ()});
        }
        return out;
    }

    public static boolean frameHasEye(AltoClef mod, int[] pos) {
        BlockPos p = new BlockPos(pos[0], pos[1], pos[2]);
        if (!mod.getChunkTracker().isChunkLoaded(p)) {
            return false;
        }
        BlockState state = mod.getWorld().getBlockState(p);
        return state.is(Blocks.END_PORTAL_FRAME) && state.getValue(EndPortalFrameBlock.HAS_EYE);
    }

    public static boolean isFrame(AltoClef mod, int[] pos) {
        BlockPos p = new BlockPos(pos[0], pos[1], pos[2]);
        return mod.getChunkTracker().isChunkLoaded(p) && mod.getWorld().getBlockState(p).is(Blocks.END_PORTAL_FRAME);
    }

    public static boolean portalIsOpen(AltoClef mod, int[] centre) {
        BlockPos p = new BlockPos(centre[0], centre[1], centre[2]);
        return mod.getChunkTracker().isChunkLoaded(p) && mod.getWorld().getBlockState(p).is(Blocks.END_PORTAL);
    }

    // nearest silverfish spawner we have seen. the old logic from beat minecraft 2, plus the seen filter
    public static Optional<BlockPos> silverfishSpawner(AltoClef mod) {
        return mod.getBlockTracker().getNearestTracking(
                pos -> WorldHelper.getSpawnerEntity(mod, pos) instanceof Silverfish && SeenFilter.isSeen(mod, pos),
                Blocks.SPAWNER);
    }

    // marvion's trick: after fiddling with eyes the hand is full of eye, put a weapon back so a zombie does not get a free
    // hit. the best one we carry (an axe if that is the kit, the kill tasks make the same pick), not a fixed list of swords
    public static void swordAfterEye(AltoClef mod) {
        if (mod.getPlayer().getMainHandItem().is(Items.ENDER_EYE)) {
            AbstractKillEntityTask.equipWeapon(mod);
        }
    }

    public static boolean havePickaxe(AltoClef mod) {
        return mod.getItemStorage().hasItem(Items.NETHERITE_PICKAXE, Items.DIAMOND_PICKAXE, Items.IRON_PICKAXE,
                Items.STONE_PICKAXE, Items.WOODEN_PICKAXE);
    }

    private StrongholdScan() {
    }
}
