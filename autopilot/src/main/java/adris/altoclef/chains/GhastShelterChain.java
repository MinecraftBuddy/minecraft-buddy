package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.IdleTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.helpers.GhastCover;
import adris.altoclef.util.helpers.StandModel;
import adris.altoclef.util.helpers.StandPick;
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
    // before a crossing: a ghast this close that sees the buddy or the crossing, and the path's next nodes over the sea. the
    // shelter that only answered a ghast already seeing the buddy on its bridge changed nothing in the drill (four passes in
    // ten, as without it): one ball in the first second is enough. so the crossing waits until no ghast is this close
    static final double NEAR = 64;
    // after a stay of STAY_TICKS or a walk of WALK_TICKS, this long with no shelter before it may take one again
    static final int REST_TICKS = 200;
    static final int LOOKAHEAD = 8;

    // (the buddy's fork, Tuning ghast_bridge, on by default, route jar 5: realfort-route-1 run 1 bridged over a drop of 36 blocks
    // to the ground in a ghast's sight. the crossing rule saw "none over the sea" (no lava under it); on the rim the cover two
    // blocks back was seen too, "no other cover, no shelter for 10 s", and in those 10 s the walk went back out on the
    // bridge; out there no cell within 8 was out of sight or off the drop, so the rule did nothing, silently, and a ball
    // knocked the buddy off: 20 damage, no clutch in the nether. with the switch: a path node over more than ghast_deep_drop
    // blocks of air (DEEP_DROP) counts as over the sea; seen in a cover that is solid ground with room (GhastCover.safeGround:
    // no drop within its MARGIN of 4) the buddy stays there until the ghast is out of sight for HOLD_TICKS; with no cover
    // out of sight, back to solid ground with room (GhastCover.ground: the last such cell stood on within GROUND_BACK, else
    // the nearest within GROUND_RADIUS), seen or not; a ghast seen in the last RECENT_TICKS counts as in sight on a rim; and
    // a rest after a stay holds off the crossing rule only, never the rim's)
    // a fall of more than 10 blocks: 7 damage or more, and a knock off a bridge seldom lands on its own floor (fall damage is
    // the fall less 3, armour points take none of it); lava under it at any depth counts too (CliffAvoidance.deepUnder)
    static final double DEEP_DROP = 10;
    static final int GROUND_RADIUS = 16;
    static final double GROUND_BACK = 32;
    // the last solid ground with room is looked at this often (its check reads some 700 cells), and a failed search for one
    // not tried again for GROUND_RETRY
    static final int GROUND_EVERY = 10;
    static final int GROUND_RETRY = 20;
    // a ghast out of sight this long ago still counts on a rim: it bobs behind the terrain between its shots (3 s, a ghast
    // shoots about every 3 s while it sees its mark)
    static final int RECENT_TICKS = 60;
    private int[] _lastGround;
    private long _groundCheckedAt = Long.MIN_VALUE;
    private long _groundFailAt = Long.MIN_VALUE;
    private Ghast _seenGhast;
    private long _seenAt = Long.MIN_VALUE;
    private boolean _stayingSeen;
    // the cover a rest began at: during the rest the rim's trigger never takes it again (it would rest again at once)
    private BlockPos _restedFrom;

    static boolean bridgeOn() {
        return adris.altoclef.util.helpers.Tuning.get("ghast_bridge", 0) > 0;
    }

    private BlockPos _cover;
    private Task _go;
    // standing in cover is a task too: a chain that wins with a finished one holds the wheel over nothing
    private final Task _stay = new IdleTask();
    private long _since = Long.MIN_VALUE;
    private long _walkSince = Long.MIN_VALUE;
    private long _restUntil = Long.MIN_VALUE;
    private long _clearSince = Long.MIN_VALUE;
    // waiting before a crossing (any ghast near) rather than hiding from one that sees us
    private boolean _before;
    // the cover is the blaze fight's corner: the walk is round by a standing pillbox's door (BoxWay), and the ghast's own cover
    // once that had its time
    private boolean _fightCorner;
    private final adris.altoclef.util.helpers.BoxWay _way = new adris.altoclef.util.helpers.BoxWay();
    // the cover from the model of where to stand (tuning stand_model), as far round as GhastCover looks
    static final int STAND_RADIUS = 8;
    private final StandPick _stand = new StandPick("ghast shelter");

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
        boolean bridge = bridgeOn();
        if (bridge) {
            if (ghast != null) {
                _seenGhast = ghast;
                _seenAt = now;
            }
            noteGround(mod, now);
        }
        if (_cover != null) {
            if (now < _since || player.isInLava()) {
                reset();
                return Float.NEGATIVE_INFINITY;
            }
            if (now - _since > STAY_TICKS) {
                Debug.logInternal("ghast shelter: " + STAY_TICKS / 20 + " s in cover, the order goes on, no shelter for " + REST_TICKS / 20 + " s");
                rest(now);
                return Float.NEGATIVE_INFINITY;
            }
            boolean there = player.blockPosition().distSqr(_cover) <= 2;
            if (!there && (now < _walkSince || now - _walkSince > WALK_TICKS)) {
                Debug.logInternal("ghast shelter: the cover at " + _cover.toShortString() + " not reached in " + WALK_TICKS / 20 + " s, no shelter for " + REST_TICKS / 20 + " s");
                rest(now);
                return Float.NEGATIVE_INFINITY;
            }
            if (there && ghast != null) {
                // (still in a ghast's sight there: from where it is now that cell is no cover, so the next one. the idle there held
                // the buddy in its sight up to STAY_TICKS)
                BlockPos other = coverFrom(mod, ghast, null);
                if (other != null && !other.equals(_cover)) {
                    Debug.logInternal("ghast shelter: seen in cover at " + _cover.toShortString() + " by the ghast at " + ghast.blockPosition().toShortString()
                            + ", on to cover at " + other.toShortString());
                    walkTo(other, false, now);
                    setTask(_go);
                    return PRIORITY;
                }
                if (other == null || player.blockPosition().equals(_cover)) {
                    if (bridge) {
                        CliffAvoidance.Cells walk = GhastCover.walkCells(mod);
                        if (_stayingSeen || GhastCover.safeGround(walk, adris.altoclef.util.helpers.SafeStand.cells(level), _cover.getX(), _cover.getY(), _cover.getZ())) {
                            if (!_stayingSeen) {
                                _stayingSeen = true;
                                Debug.logInternal("ghast shelter: seen in cover at " + _cover.toShortString() + " by the ghast at " + ghast.blockPosition().toShortString()
                                        + ", no other cover; solid ground with room, the buddy stays till the ghast is out of sight for " + HOLD_TICKS / 20 + " s");
                            }
                            _clearSince = Long.MIN_VALUE;
                            setTask(_stay);
                            return PRIORITY;
                        }
                        BlockPos back = ground(mod, walk, now);
                        if (back != null && !back.equals(_cover)) {
                            Debug.logInternal("ghast shelter: seen in cover at " + _cover.toShortString() + ", no other cover and a drop by it, back to solid ground at " + back.toShortString());
                            walkTo(back, false, now);
                            setTask(_go);
                            return PRIORITY;
                        }
                    }
                    Debug.logInternal("ghast shelter: seen in cover at " + _cover.toShortString() + " and no other cover, no shelter for " + REST_TICKS / 20 + " s");
                    rest(now);
                    return Float.NEGATIVE_INFINITY;
                }
                // (beside the cell, seen: into the cell itself)
                _clearSince = Long.MIN_VALUE;
                setTask(_go);
                return PRIORITY;
            }
            if (there) {
                if (_before && nearestGhast(mod, NEAR) != null) {
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
            if (!there && _fightCorner) {
                Task way = _way.step(mod, _cover);
                if (way != null) {
                    setTask(way);
                    return PRIORITY;
                }
                Ghast g = ghast != null ? ghast : nearestGhast(mod, NEAR);
                BlockPos other = g == null ? null : coverFrom(mod, g, null);
                if (other == null) {
                    reset();
                    return Float.NEGATIVE_INFINITY;
                }
                Debug.logInternal("ghast shelter: the blaze fight's corner not reached, into cover at " + other.toShortString());
                walkTo(other, false, now);
                setTask(_go);
                return PRIORITY;
            }
            setTask(there ? _stay : _go);
            return PRIORITY;
        }
        // (after a stay or a walk that ran out its time, a while with no shelter: the trigger at once took the same cover again)
        // (ghast_bridge: the rest holds off the crossing rule only; on a rim in a ghast's sight the shelter is taken at once)
        boolean resting = _restUntil != Long.MIN_VALUE && now < _restUntil && now >= _restUntil - REST_TICKS;
        if (resting && !bridge) return Float.NEGATIVE_INFINITY;
        if (!player.onGround() || player.isInLava()) return Float.NEGATIVE_INFINITY;
        BlockPos feet = player.blockPosition();
        CliffAvoidance.Cells cells = CliffAvoidance.of(new BlockStateInterface(mod.getClientBaritone().getPlayerContext(), true));
        Ghast threat = ghast;
        if (bridge && threat == null && _seenGhast != null && _seenGhast.isAlive() && now >= _seenAt && now - _seenAt <= RECENT_TICKS
                && _seenGhast.distanceTo(player) <= SIGHT) threat = _seenGhast;
        boolean exposed = threat != null && (CliffAvoidance.overSea(cells, feet.getX(), feet.getY(), feet.getZ())
                || CliffAvoidance.dropNear(cells, feet.getX(), feet.getY(), feet.getZ()));
        if (resting && !exposed) return Float.NEGATIVE_INFINITY;
        Ghast close = exposed ? threat : nearestGhast(mod, NEAR);
        // (before a crossing only for a ghast that sees us or the crossing: one with a wall between it and both shoots at
        // neither, and the wait for it held the order in cover up to STAY_TICKS)
        int deep = bridge ? (int) Math.round(adris.altoclef.util.helpers.Tuning.get("ghast_deep_drop", DEEP_DROP)) : 0;
        List<BlockPos> ahead = !exposed && close != null ? seaAhead(mod, cells, deep) : List.of();
        Ghast near = exposed ? threat : ahead.isEmpty() ? null : watching(mod, NEAR, ahead);
        boolean before = !exposed && near != null;
        if (!exposed && !before) {
            // (why not, once a few seconds while one is near: in sight, over the sea, a drop by us, the crossing in sight)
            if (close != null) say(now, "not exposed: " + (ghast == null ? "none in sight" : "in sight at " + Math.round(ghast.distanceTo(player)))
                    + ", over the sea " + CliffAvoidance.overSea(cells, feet.getX(), feet.getY(), feet.getZ())
                    + ", a drop by us " + CliffAvoidance.dropNear(cells, feet.getX(), feet.getY(), feet.getZ())
                    + (ahead.isEmpty() ? "" : ", a crossing ahead in no ghast's sight"));
            return Float.NEGATIVE_INFINITY;
        }
        // (the buddy's fork: in a blaze fight with its corner near, the corner is the cover from a ghast too: the cover picked for
        // the ghast alone stood by the spawner in the blazes' fire, blazeroom drill, corner15 run 3 and corner19 run 2, 4 Nov 2026.
        // only a corner the ghast has no line to: one it sees is no cover from it)
        BlockPos fight = adris.altoclef.util.helpers.BlazeShelter.fresh(now);
        boolean fightNear = fight != null && player.position().distanceTo(net.minecraft.world.phys.Vec3.atBottomCenterOf(fight))
                <= adris.altoclef.util.helpers.BlazeShelter.RANGE;
        boolean fightCorner;
        BlockPos cover;
        if (StandPick.on()) {
            // (stand_model on: the corner is one cell among the rest, the model weighs the ghast's line to it with every other mob)
            cover = coverFrom(mod, near, fightNear ? fight : null);
            fightCorner = cover != null && cover.equals(fight);
        } else {
            fightCorner = fightNear && !GhastCover.seesCell(level, near.getEyePosition(), fight, player);
            cover = fightCorner ? fight : GhastCover.cover(mod, near);
        }
        // (ghast_bridge: no cell out of its sight, so back to solid ground with room, seen or not; before a crossing that is
        // where the crossing waits)
        boolean back = false;
        if (cover == null && bridge) {
            cover = ground(mod, cells, now);
            back = cover != null;
            if (cover == null) say(now, "no cover from it and no solid ground with room within " + GROUND_RADIUS + (exposed ? ", on a rim" : ", a crossing ahead"));
        }
        if (cover == null || resting && cover.equals(_restedFrom)) return Float.NEGATIVE_INFINITY;
        _before = before;
        _since = now;
        walkTo(cover, fightCorner, now);
        Debug.logInternal("ghast shelter: " + (before ? "a crossing over the sea" + (bridge ? " or a deep drop" : "") + " ahead in the sight of a ghast at "
                        : "on a rim or over the sea in the sight of a ghast at ")
                + near.blockPosition().toShortString() + (exposed && ghast == null ? " (seen " + (now - _seenAt) / 20.0 + " s ago)" : "")
                + (back ? ", no cover from it within " + GhastCover.RADIUS + ", back to solid ground at " : ", into cover at ") + cover.toShortString());
        setTask(_go);
        return PRIORITY;
    }

    // the cover from a ghast: with stand_model on the model's cell (StandModel: every mob about weighed, the ghast's push off a
    // rim among them, the blaze fight's corner as one more cell), else, or with none from it, GhastCover's nearest cell out of
    // the ghast's line
    private BlockPos coverFrom(AltoClef mod, Ghast ghast, BlockPos corner) {
        if (StandPick.on()) {
            BlockPos c = _stand.cell(mod, StandModel.Query.of().radius(STAND_RADIUS).hitBack(0).maxRate(StandModel.RUN_RATE),
                    corner == null ? List.of() : List.of(corner), null);
            if (c != null) return c;
        }
        return GhastCover.cover(mod, ghast);
    }

    // the walk to a cover cell, its own clock (WALK_TICKS); the stay's clock (STAY_TICKS) goes on
    private void walkTo(BlockPos cover, boolean fightCorner, long now) {
        _cover = cover;
        _stayingSeen = false;
        adris.altoclef.util.snapshot.Snapshots.chose(fightCorner ? "ghast.corner" : "ghast.cover", cover);
        _fightCorner = fightCorner;
        _way.reset();
        _go = new GetToBlockTask(cover);
        _walkSince = now;
        _clearSince = Long.MIN_VALUE;
    }

    // (ghast_bridge) the last solid ground with room stood on, looked at every GROUND_EVERY ticks on the ground
    private void noteGround(AltoClef mod, long now) {
        if (_groundCheckedAt != Long.MIN_VALUE && now >= _groundCheckedAt && now - _groundCheckedAt < GROUND_EVERY) return;
        _groundCheckedAt = now;
        var player = mod.getPlayer();
        if (!player.onGround() || player.isInLava()) return;
        BlockPos f = player.blockPosition();
        if (GhastCover.safeGround(GhastCover.walkCells(mod), adris.altoclef.util.helpers.SafeStand.cells(mod.getWorld()), f.getX(), f.getY(), f.getZ())) {
            _lastGround = new int[]{f.getX(), f.getY(), f.getZ()};
        }
    }

    // (ghast_bridge) where to go back to with no cover out of the ghast's sight, GhastCover.ground; a search that found none
    // is not made again for GROUND_RETRY ticks
    private BlockPos ground(AltoClef mod, CliffAvoidance.Cells walk, long now) {
        if (_groundFailAt != Long.MIN_VALUE && now >= _groundFailAt && now - _groundFailAt < GROUND_RETRY) return null;
        BlockPos at = mod.getPlayer().blockPosition();
        int[] c = GhastCover.ground(walk, adris.altoclef.util.helpers.SafeStand.cells(mod.getWorld()), at.getX(), at.getY(), at.getZ(), _lastGround,
                GROUND_BACK, GROUND_RADIUS);
        if (c == null) {
            _groundFailAt = now;
            return null;
        }
        return new BlockPos(c[0], c[1], c[2]);
    }

    private void rest(long now) {
        _restedFrom = _cover;
        reset();
        _restUntil = now + REST_TICKS;
    }

    // the nearest ghast within range with a line to our eyes or to a cell of the crossing ahead
    private static Ghast watching(AltoClef mod, double range, List<BlockPos> cells) {
        var player = mod.getPlayer();
        Ghast best = null;
        double bestD = range;
        for (Ghast g : mod.getEntityTracker().getTrackedEntities(Ghast.class)) {
            if (!g.isAlive()) continue;
            double d = g.distanceTo(player);
            if (d > bestD) continue;
            boolean sees = GhastCover.clear(mod.getWorld(), g.getEyePosition(), player.getEyePosition(), player);
            for (int i = 0; i < cells.size() && !sees; i++) sees = GhastCover.seesCell(mod.getWorld(), g.getEyePosition(), cells.get(i), player);
            if (!sees) continue;
            best = g;
            bestD = d;
        }
        return best;
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

    private static long _lastSaid = Long.MIN_VALUE;

    // the cells of the user task's path over the lava sea within its next few nodes; none with no path or none over the sea
    // (ghast_bridge: with deep above 0 a node over more than deep blocks of air counts too, CliffAvoidance.deepUnder)
    private static List<BlockPos> seaAhead(AltoClef mod, CliffAvoidance.Cells cells, int deep) {
        IPathExecutor current = mod.getClientBaritone().getPathingBehavior().getCurrent();
        long now = mod.getWorld().getGameTime();
        if (current == null) {
            say(now, "no path running");
            return List.of();
        }
        List<BetterBlockPos> positions = current.getPath().positions();
        int from = Math.max(0, current.getPosition());
        int seen = 0;
        List<BlockPos> sea = new java.util.ArrayList<>();
        for (int i = from; i < positions.size() && i <= from + LOOKAHEAD; i++) {
            BetterBlockPos p = positions.get(i);
            seen++;
            if (CliffAvoidance.overSea(cells, p.x, p.y, p.z) || deep > 0 && CliffAvoidance.deepUnder(cells, p.x, p.y, p.z, deep)) sea.add(new BlockPos(p.x, p.y, p.z));
        }
        // (why the crossing was never seen in the drill: what the path held, once a few seconds with a ghast near)
        if (sea.isEmpty()) say(now, "path ahead " + seen + " nodes, none over the sea" + (deep > 0 ? " or a deep drop" : "") + ", the last " + (positions.isEmpty() ? "-" : positions.get(positions.size() - 1).toString()));
        return sea;
    }

    private static void say(long now, String what) {
        if (_lastSaid != Long.MIN_VALUE && now >= _lastSaid && now - _lastSaid < 100) return;
        _lastSaid = now;
        Debug.logInternal("ghast shelter: a ghast near, " + what);
    }

    private void reset() {
        _stand.reset();
        _cover = null;
        _go = null;
        _since = Long.MIN_VALUE;
        _clearSince = Long.MIN_VALUE;
        _before = false;
        _fightCorner = false;
        _stayingSeen = false;
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
