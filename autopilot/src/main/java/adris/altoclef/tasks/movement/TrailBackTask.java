package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.Task;
import net.minecraft.core.BlockPos;

import java.util.List;

// (the buddy's fork, Tuning pickless_way_out, a fix of 14 Nov 2026, on) back up the way the buddy came down (DownTrail), a cell
// at a time, newest first: cells the feet stood in, open still where the buddy dug them, so baritone needs no pickaxe for most of
// the way. a later cell of the trail within reach of the feet is skipped to; a cell not reached in CELL_STUCK ticks is passed for
// the one before it, and MISSES of those in a row end the walk (failed): the caller climbs by hand from there
public class TrailBackTask extends Task {
    static final long CELL_STUCK = 300;
    static final int MISSES = 3;
    static final double AT = 1.5;
    // a walk's game ticks at most (10 game minutes), then the climb by hand from where it got to
    public static final long CAP = 12000;

    private final List<int[]> _way;
    private int _next;
    private int _misses;
    private boolean _failed;
    private BlockPos _target;
    private long _since;
    private Task _walk;

    // the way from DownTrail.wayBack: newest first, the open cell last
    public TrailBackTask(List<int[]> way) {
        _way = way;
    }

    public boolean failed() {
        return _failed;
    }

    // the cells of the way still to go
    public int left() {
        return Math.max(0, _way.size() - _next);
    }

    @Override
    protected void onStart(AltoClef mod) {
        _target = null;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (_failed || _next >= _way.size()) return null;
        long now = mod.getWorld().getGameTime();
        BlockPos feet = mod.getPlayer().blockPosition();
        // the farthest cell along the way the feet are at already
        for (int i = _way.size() - 1; i >= _next; i--) {
            if (at(feet, _way.get(i))) {
                _next = i + 1;
                _misses = 0;
                break;
            }
        }
        if (_next >= _way.size()) return null;
        int[] c = _way.get(_next);
        BlockPos cell = new BlockPos(c[0], c[1], c[2]);
        if (!cell.equals(_target)) {
            _target = cell;
            _since = now;
            _walk = new GetToBlockTask(cell);
        }
        if (now < _since || now - _since > CELL_STUCK) {
            _misses++;
            Debug.logInternal("way back up: the cell " + cell.toShortString() + " not reached in " + CELL_STUCK / 20 + " s, "
                    + (_misses >= MISSES ? MISSES + " in a row, the way back given up" : "the one before it") + " (pickless_way_out)");
            _next++;
            _target = null;
            if (_misses >= MISSES) _failed = true;
            return null;
        }
        setDebugState("Back up the way down, " + left() + " cells to go");
        return _walk;
    }

    // the walk begun at since is over at now: done or failed (finished), or past CAP (a clock gone back counts as past it)
    public static boolean over(boolean finished, long since, long now) {
        return finished || now < since || now - since > CAP;
    }

    private static boolean at(BlockPos feet, int[] c) {
        double dx = feet.getX() - c[0], dy = feet.getY() - c[1], dz = feet.getZ() - c[2];
        return dx * dx + dy * dy + dz * dz <= AT * AT;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return _failed || _next >= _way.size();
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
    }

    @Override
    protected boolean isEqual(Task other) {
        return other == this;
    }

    @Override
    protected String toDebugString() {
        return "Back up the way down (" + left() + " cells)";
    }
}
