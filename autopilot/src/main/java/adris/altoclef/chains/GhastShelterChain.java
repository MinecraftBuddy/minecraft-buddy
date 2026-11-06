package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.IdleTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.helpers.GhastCover;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.utils.Dimension;
import baritone.pathing.movement.CliffAvoidance;
import baritone.utils.BlockStateInterface;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.monster.Ghast;
import baritone.api.pathing.path.IPathExecutor;
import baritone.api.utils.BetterBlockPos;
import java.util.List;

// the buddy's fork: no crossing over the nether's lava sea, and no standing on a rim, in a ghast's sight. a ghast's ball
// knocked the buddy off its own bridge mid-crossing six times in ten runs of the lavasea2 drill at rate 100 and twice in
// three at 20 ticks a second, and 174 and 176 died the same way in the series. GhastCover only helps a wander with no
// path; while a path runs (the bridge being built) nothing looked at the ghast. here, exposed and seen, the wheel goes to
// the nearest cell a block shields from the ghast, and it stays there for a while after the ghast lost sight. a stall is
// what it costs, a death what it saves. 55: over the user task and the take along, under the fights and the creepers
public class GhastShelterChain extends SingleTaskChain {
    public static final float PRIORITY = 55;
    // a ghast shoots at 64: at 48 one standing off further shot the buddy off a rim at y 72 into the sea at 30 while the rule
    // saw nothing (attempt 231, 4 Nov 2026)
    static final double SIGHT = 64;
    // in cover this long after the ghast lost sight before the order goes on (it drifts off, or the punch gets it)
    static final int HOLD_TICKS = 200;
    // the walk to cover gets half a game minute, a stay at most two
    static final int WALK_TICKS = 600;
    static final int STAY_TICKS = 2400;
    // before a crossing: a ghast anywhere this close, seen or not, and the path's next nodes over the sea. the shelter that
    // only answered a ghast already seeing the buddy on its bridge changed nothing in the drill (four passes in ten, as
    // without it): one ball in the first second is enough. so the crossing waits until no ghast is this close
    static final double NEAR = 64;
    static final int LOOKAHEAD = 8;

    private BlockPos _cover;
    private Task _go;
    // standing in cover is a task too: a chain that wins with a finished one holds the wheel over nothing
    private final Task _stay = new IdleTask();
    private long _since = Long.MIN_VALUE;
    private long _clearSince = Long.MIN_VALUE;
    // waiting before a crossing (any ghast near) rather than hiding from one that sees us
    private boolean _before;

    public GhastShelterChain(TaskRunner runner) {
        super(runner);
    }

    @Override
    public float getPriority(AltoClef mod) {
        var player = mod.getPlayer();
        var level = mod.getWorld();
        if (player == null || level == null || WorldHelper.getCurrentDimension() != Dimension.NETHER
                || mod.getUserTaskChain() == null || !mod.getUserTaskChain().isActive()) {
            reset();
            return Float.NEGATIVE_INFINITY;
        }
        long now = level.getGameTime();
        Ghast ghast = GhastCover.seeingGhast(mod, SIGHT);
        if (_cover != null) {
            if (now < _since || now - _since > STAY_TICKS || player.isInLava()) {
                reset();
                return Float.NEGATIVE_INFINITY;
            }
            boolean there = player.blockPosition().distSqr(_cover) <= 2;
            if (!there && now - _since > WALK_TICKS) {
                reset();
                return Float.NEGATIVE_INFINITY;
            }
            if (there) {
                if (_before ? nearestGhast(mod, NEAR) != null : ghast != null) {
                    _clearSince = Long.MIN_VALUE;
                } else if (_clearSince == Long.MIN_VALUE) {
                    _clearSince = now;
                } else if (now - _clearSince >= HOLD_TICKS) {
                    Debug.logInternal(_before ? "ghast shelter: no ghast near for ten game seconds, the crossing goes on"
                            : "ghast shelter: out of its sight for ten game seconds, the order goes on");
                    reset();
                    return Float.NEGATIVE_INFINITY;
                }
            }
            setTask(there ? _stay : _go);
            return PRIORITY;
        }
        if (!player.onGround() || player.isInLava()) return Float.NEGATIVE_INFINITY;
        BlockPos feet = player.blockPosition();
        CliffAvoidance.Cells cells = CliffAvoidance.of(new BlockStateInterface(mod.getClientBaritone().getPlayerContext(), true));
        boolean exposed = ghast != null && (CliffAvoidance.overSea(cells, feet.getX(), feet.getY(), feet.getZ())
                || CliffAvoidance.dropNear(cells, feet.getX(), feet.getY(), feet.getZ()));
        Ghast near = exposed ? ghast : nearestGhast(mod, NEAR);
        boolean before = !exposed && near != null && crossingAhead(mod, cells);
        if (!exposed && !before) {
            // (why not, once a few seconds while one is near: in sight, over the sea, a drop by us)
            if (near != null) say(now, "not exposed: " + (ghast == null ? "none in sight" : "in sight at " + Math.round(ghast.distanceTo(player)))
                    + ", over the sea " + CliffAvoidance.overSea(cells, feet.getX(), feet.getY(), feet.getZ())
                    + ", a drop by us " + CliffAvoidance.dropNear(cells, feet.getX(), feet.getY(), feet.getZ()));
            return Float.NEGATIVE_INFINITY;
        }
        // (the buddy's fork: in a blaze fight with its corner near, the corner is the cover from a ghast too: the cover picked for
        // the ghast alone stood by the spawner in the blazes' fire, blazeroom drill, corner15 run 3 and corner19 run 2, 4 Nov 2026)
        BlockPos fight = adris.altoclef.util.helpers.BlazeShelter.fresh(now);
        BlockPos cover = fight != null && player.position().distanceTo(net.minecraft.world.phys.Vec3.atBottomCenterOf(fight))
                <= adris.altoclef.util.helpers.BlazeShelter.RANGE ? fight : GhastCover.cover(mod, near);
        if (cover == null) return Float.NEGATIVE_INFINITY;
        _before = before;
        _cover = cover;
        _go = new GetToBlockTask(cover);
        _since = now;
        _clearSince = Long.MIN_VALUE;
        Debug.logInternal("ghast shelter: " + (before ? "a crossing over the sea ahead with a ghast near at " : "on a rim or over the sea in the sight of a ghast at ")
                + near.blockPosition().toShortString() + ", into cover at " + cover.toShortString());
        setTask(_go);
        return PRIORITY;
    }

    private static Ghast nearestGhast(AltoClef mod, double range) {
        Ghast best = null;
        double bestD = range;
        for (Ghast g : mod.getEntityTracker().getTrackedEntities(Ghast.class)) {
            if (!g.isAlive()) continue;
            double d = g.distanceTo(mod.getPlayer());
            if (d <= bestD) {
                best = g;
                bestD = d;
            }
        }
        return best;
    }

    // the user task's path goes out over the lava sea within its next few nodes
    private static long _lastSaid = Long.MIN_VALUE;

    private static boolean crossingAhead(AltoClef mod, CliffAvoidance.Cells cells) {
        IPathExecutor current = mod.getClientBaritone().getPathingBehavior().getCurrent();
        long now = mod.getWorld().getGameTime();
        if (current == null) {
            say(now, "no path running");
            return false;
        }
        List<BetterBlockPos> positions = current.getPath().positions();
        int from = Math.max(0, current.getPosition());
        int seen = 0;
        for (int i = from; i < positions.size() && i <= from + LOOKAHEAD; i++) {
            BetterBlockPos p = positions.get(i);
            seen++;
            if (CliffAvoidance.overSea(cells, p.x, p.y, p.z)) return true;
        }
        // (why the crossing was never seen in the drill: what the path held, once a few seconds with a ghast near)
        say(now, "path ahead " + seen + " nodes, none over the sea, the last " + (positions.isEmpty() ? "-" : positions.get(positions.size() - 1).toString()));
        return false;
    }

    private static void say(long now, String what) {
        if (_lastSaid != Long.MIN_VALUE && now >= _lastSaid && now - _lastSaid < 100) return;
        _lastSaid = now;
        Debug.logInternal("ghast shelter: a ghast near, " + what);
    }

    private void reset() {
        _cover = null;
        _go = null;
        _since = Long.MIN_VALUE;
        _clearSince = Long.MIN_VALUE;
        _before = false;
    }

    @Override
    protected void onTaskFinish(AltoClef mod) {
        // at the cover cell the stay is getPriority's to end
    }

    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    public String getName() {
        return "Ghast Shelter";
    }
}
