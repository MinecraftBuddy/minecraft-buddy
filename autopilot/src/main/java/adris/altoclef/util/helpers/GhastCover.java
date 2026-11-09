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
    public static final int RADIUS = 8;
    // a second with no path in its sight before going for cover: a path being worked out is no reason to move
    static final int NO_PATH_TICKS = 20;
    // half a game minute for the walk to cover
    static final int WALK_TICKS = 600;

    // ---- the wait doubled (Tuning ghast_wait_double, on by default; route jar 8, 9 Nov 2026). the shelter let go after ten game
    // seconds out of the ghast's sight, and the walk (the trade, the errand's walk to the wart) stepped back into its line:
    // 10 to 12 times in 3 minutes, some 90 to 130 s lost each time (realfort-route-5's t1 run 11). on a release the ghast and
    // the cover are kept; the same ghast sending the buddy into cover again within AGAIN_TICKS of a release doubles the wait
    // out of its sight, at most DOUBLINGS times (10, 20, 40 s), the old cover taken again while that ghast cannot see it; and
    // any wait ends once the ghast is dead or GONE or more off. the stay's own cap (two minutes) holds over all of it, far
    // under the 20 minute stall clock
    public static final long AGAIN_TICKS = 600;
    public static final int DOUBLINGS = 2;
    // (route jar 12, 9 Nov 2026, look 36: GONE was 64, the shelter's own sight, so a ghast hovering at 56 to 74 off was "gone"
    // and seen again by turns: 9 releases in 2.5 minutes, the walk back in its line within 0 to 3 s each time, 13 covers.
    // released now only past GONE, a margin over the sight; Tuning ghast_gone sets it, 64 the old way)
    public static final double GONE = 80;

    public static double gone() {
        return Tuning.get("ghast_gone", GONE);
    }

    public static boolean doubleOn() {
        return Tuning.get("ghast_wait_double", 0) > 0;
    }

    // the doublings for a new shelter: one more than the last release's (at most DOUBLINGS) when the same ghast sends the buddy
    // in again within AGAIN_TICKS of that release (sinceRelease, game ticks), else none
    public static int streak(boolean sameGhast, long sinceRelease, int last) {
        if (!sameGhast || sinceRelease < 0 || sinceRelease > AGAIN_TICKS) return 0;
        return Math.min(last + 1, DOUBLINGS);
    }

    // the wait out of the ghast's sight before a release: base doubled streak times
    public static long holdTicks(long base, int streak) {
        return base << Math.max(0, Math.min(streak, DOUBLINGS));
    }

    // the ghast that sent the buddy into cover is no more reason to wait: dead, or gone() or more off
    public static boolean ghastGone(boolean alive, double distance) {
        return !alive || distance >= gone();
    }

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
        adris.altoclef.util.snapshot.Snapshots.chose("ghast.cover", cover);
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
        int[] c = pick(cells, SafeStand.cells(level), at.getX(), at.getY(), at.getZ(),
                (x, y, z) -> !seesCell(level, eye, new BlockPos(x, y, z), player));
        return c == null ? null : new BlockPos(c[0], c[1], c[2]);
    }

    // cover's choice without a world (the snapshots' harness feeds it): walk is the world as Baritone reads it, for the drops;
    // body by collision, for where a body stands; hidden whether the ghast sees no body standing in a cell. {x, y, z} or null
    public static int[] pick(CliffAvoidance.Cells walk, CliffAvoidance.Cells body, int ax, int ay, int az, BlazeCover.Hidden hidden) {
        return pick(walk, body, ax, ay, az, hidden, RADIUS);
    }

    // the same within radius of (ax, az)
    public static int[] pick(CliffAvoidance.Cells walk, CliffAvoidance.Cells body, int ax, int ay, int az, BlazeCover.Hidden hidden, int radius) {
        int[] best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                double flat = dx * dx + dz * dz;
                if (flat > radius * radius || flat >= bestD) continue;
                for (int dy = -2; dy <= 2; dy++) {
                    int x = ax + dx, y = ay + dy, z = az + dz;
                    if (!standable(body, x, y, z)) continue;
                    if (nearDrop(walk, x, y, z)) continue;
                    if (!hidden.at(x, y, z)) continue;
                    best = new int[]{x, y, z};
                    bestD = flat;
                }
            }
        }
        return best;
    }

    // a cover cell keeps MARGIN from any drop: one two blocks from the cliff's edge was out of one ghast's sight, a second
    // ghast higher up saw it and its ball pushed the buddy over the edge into the sea (the lavasea2 drill, 4 Nov 2026)
    static final int MARGIN = 4;

    // solid ground with room on all sides (the buddy's fork, route jar 5): a cell to stand in with no drop within MARGIN of
    // it, where a ghast's ball can knock the buddy about but not off a drop. GhastShelterChain's fallback when no cell nearby
    // is out of the ghast's sight
    public static boolean safeGround(CliffAvoidance.Cells walk, CliffAvoidance.Cells body, int x, int y, int z) {
        return standable(body, x, y, z) && !nearDrop(walk, x, y, z);
    }

    // where to go back to with no cover from the ghast: the last solid ground with room the buddy stood on (the way out along
    // its own bridge is known), within back of (ax, az) and still that; else the nearest within radius, seen or not. null with
    // neither
    public static int[] ground(CliffAvoidance.Cells walk, CliffAvoidance.Cells body, int ax, int ay, int az, int[] last, double back, int radius) {
        if (last != null && Math.hypot(last[0] - ax, last[2] - az) <= back && safeGround(walk, body, last[0], last[1], last[2])) return last.clone();
        return pick(walk, body, ax, ay, az, (x, y, z) -> true, radius);
    }

    // the cells as Baritone reads them, for the drops
    public static CliffAvoidance.Cells walkCells(AltoClef mod) {
        return CliffAvoidance.of(new BlockStateInterface(mod.getClientBaritone().getPlayerContext(), true));
    }

    private static boolean nearDrop(CliffAvoidance.Cells cells, int x, int y, int z) {
        for (int dx = -MARGIN; dx <= MARGIN; dx++) {
            for (int dz = -MARGIN; dz <= MARGIN; dz++) {
                if ((dx != 0 || dz != 0) && CliffAvoidance.columnUnsafe(cells, x + dx, y, z + dz)) return true;
            }
        }
        return false;
    }

    // feet and head free of blocks and fluid, a floor of a block under them (SafeStand.cells' reading: by collision, the fluids
    // apart)
    static boolean standable(CliffAvoidance.Cells body, int x, int y, int z) {
        return body.at(x, y, z) == CliffAvoidance.Cell.OPEN && body.at(x, y + 1, z) == CliffAvoidance.Cell.OPEN
                && body.at(x, y - 1, z) == CliffAvoidance.Cell.SOLID;
    }

    // a line from the eye to the head of one standing in the cell
    public static boolean seesCell(Level level, Vec3 eye, BlockPos cell, net.minecraft.world.entity.Entity who) {
        return clear(level, eye, new Vec3(cell.getX() + 0.5, cell.getY() + 1.6, cell.getZ() + 0.5), who);
    }

    // nothing solid between the two points
    public static boolean clear(Level level, Vec3 from, Vec3 to, net.minecraft.world.entity.Entity who) {
        BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, who));
        return hit.getType() == HitResult.Type.MISS;
    }
}
