package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasksystem.Task;
import baritone.api.utils.Dimension;
import baritone.pathing.movement.CliffAvoidance;
import baritone.utils.BlockStateInterface;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

// a wander with no path stood still in the open in a ghast's sight: on the tip of its own bridge over the lava sea (the
// buddy's fork, attempts 174 and 176) and on a pillar of its own against a wall (the lavasea drill, twelve deaths in
// fourteen runs, 4 Nov 2026), shot or knocked down again and again. a ghast only shoots what it sees, so the answer is a
// cell nearby with a block between it and the ghast. the nether only, a wander with no path only
public final class GhastCover {
    // a ghast this close with a line on us counts; past it its balls rarely reach
    static final double SIGHT = 64;
    // how far round us a cover cell is looked for
    static final int RADIUS = 8;
    // a second with no path in its sight before going for cover: a path being worked out is no reason to move
    static final int NO_PATH_TICKS = 20;
    // half a game minute for the walk to cover
    static final int WALK_TICKS = 600;

    private long _since = -1;
    private Task _go;
    private long _goSince;
    private String _what = "";

    public Task next(AltoClef mod) {
        long now = mod.getWorld().getGameTime();
        if (_go != null) {
            if (!_go.isFinished(mod) && now >= _goSince && now - _goSince < WALK_TICKS) return _go;
            _go = null;
            _since = -1;
        }
        var player = mod.getPlayer();
        if (WorldHelper.getCurrentDimension() != Dimension.NETHER || !player.onGround()
                || mod.getClientBaritone().getPathingBehavior().isPathing()) {
            _since = -1;
            return null;
        }
        Ghast ghast = seeingGhast(mod, SIGHT);
        if (ghast == null) {
            _since = -1;
            return null;
        }
        if (_since < 0 || now < _since) _since = now;
        if (now - _since < NO_PATH_TICKS) return null;
        BlockPos cover = cover(mod, ghast);
        if (cover == null) return null;
        _what = "A ghast sees us with no way on: into cover at " + cover.toShortString();
        Debug.logInternal("ghast cover: " + cover.toShortString() + " from the ghast at " + ghast.blockPosition().toShortString());
        _go = new GetToBlockTask(cover);
        _goSince = now;
        return _go;
    }

    public String what() {
        return _what;
    }

    // the nearest ghast within sight blocks with a clear line to our eyes
    public static Ghast seeingGhast(AltoClef mod, double sight) {
        var player = mod.getPlayer();
        Ghast best = null;
        double bestD = Double.MAX_VALUE;
        for (Ghast g : mod.getEntityTracker().getTrackedEntities(Ghast.class)) {
            if (!g.isAlive()) continue;
            double d = g.distanceTo(player);
            if (d > sight || d >= bestD) continue;
            if (!clear(mod.getWorld(), g.getEyePosition(), player.getEyePosition(), player)) continue;
            best = g;
            bestD = d;
        }
        return best;
    }

    // the nearest standable cell within RADIUS, no rim beside it, that the ghast cannot see from where it is
    public static BlockPos cover(AltoClef mod, Ghast ghast) {
        var player = mod.getPlayer();
        Level level = mod.getWorld();
        BlockPos at = player.blockPosition();
        CliffAvoidance.Cells cells = CliffAvoidance.of(new BlockStateInterface(mod.getClientBaritone().getPlayerContext(), true));
        Vec3 eye = ghast.getEyePosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                double flat = dx * dx + dz * dz;
                if (flat > RADIUS * RADIUS || flat >= bestD) continue;
                for (int dy = -2; dy <= 2; dy++) {
                    c.set(at.getX() + dx, at.getY() + dy, at.getZ() + dz);
                    if (!standable(level, c)) continue;
                    if (nearDrop(cells, c.getX(), c.getY(), c.getZ())) continue;
                    Vec3 head = new Vec3(c.getX() + 0.5, c.getY() + 1.6, c.getZ() + 0.5);
                    if (clear(level, eye, head, player)) continue;
                    best = c.immutable();
                    bestD = flat;
                }
            }
        }
        return best;
    }

    // a cover cell keeps MARGIN from any drop: one two blocks from the cliff's edge was out of one ghast's sight, a second
    // ghast higher up saw it and its ball pushed the buddy over the edge into the sea (the lavasea2 drill, 4 Nov 2026)
    static final int MARGIN = 4;

    private static boolean nearDrop(CliffAvoidance.Cells cells, int x, int y, int z) {
        for (int dx = -MARGIN; dx <= MARGIN; dx++) {
            for (int dz = -MARGIN; dz <= MARGIN; dz++) {
                if ((dx != 0 || dz != 0) && CliffAvoidance.columnUnsafe(cells, x + dx, y, z + dz)) return true;
            }
        }
        return false;
    }

    private static boolean standable(Level level, BlockPos feet) {
        BlockState f = level.getBlockState(feet);
        BlockState h = level.getBlockState(feet.above());
        BlockState floor = level.getBlockState(feet.below());
        return f.getCollisionShape(level, feet).isEmpty() && h.getCollisionShape(level, feet.above()).isEmpty()
                && f.getFluidState().isEmpty() && h.getFluidState().isEmpty()
                && !floor.getCollisionShape(level, feet.below()).isEmpty() && floor.getFluidState().isEmpty();
    }

    // nothing solid between the two points
    public static boolean clear(Level level, Vec3 from, Vec3 to, net.minecraft.world.entity.Entity who) {
        BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, who));
        return hit.getType() == HitResult.Type.MISS;
    }
}
