package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasksystem.Task;
import baritone.api.utils.Dimension;
import baritone.pathing.movement.CliffAvoidance;
import baritone.utils.BlockStateInterface;
import net.minecraft.core.BlockPos;

// the nether's usual death is a knock off a rim into the lava sea, most of all at the end of a bridge of our own with no way
// on: no path for three game minutes, the wander walked out to the stub's end again and again, and a ghast's fireball put
// the buddy thirty-four blocks down into the lava (the buddy's fork, attempt 174; 119, 135 and 147 the same). this keeps
// the last firm ground (no knock from any side ends in lava or a fall that hurts, SafeStand) and walks back to it from a rim
// with no path. the nether only: that is where the drops end in lava
public final class RimRetreat {
    // a second on the rim with no path before backing off: a path being worked out is no reason to leave
    static final int RIM_TICKS = 20;
    // further than this the way back is a trip of its own, and no safer than staying
    static final double RANGE = 64;
    // half a game minute for the walk back, then the wander has its turn again
    static final int WALK_TICKS = 600;
    private static final int LOOK_EVERY = 5;
    // a retreat that brought us back is followed by this long without one, and firm ground this close is no retreat
    static final int REST_TICKS = 200;
    static final int NEAR = 2;
    private long _restUntil = Long.MIN_VALUE;

    private static BlockPos _firm;
    private static long _lastLook = Long.MIN_VALUE;

    private long _rimSince = -1;
    private Task _back;
    private long _backSince;
    private String _what = "";

    // every tick from AltoClef: the last cell stood on in the nether with firm ground all round
    public static void note(AltoClef mod) {
        if (mod.getPlayer() == null || mod.getWorld() == null) return;
        if (WorldHelper.getCurrentDimension() != Dimension.NETHER) {
            _firm = null;
            return;
        }
        long now = mod.getWorld().getGameTime();
        if (!due(now, _lastLook)) return;
        _lastLook = now;
        if (!mod.getPlayer().onGround() || mod.getPlayer().isInLava()) return;
        BlockPos feet = mod.getPlayer().blockPosition();
        if (!(SafeStand.on() ? SafeStand.unsafe(cells(mod), feet.getX(), feet.getY(), feet.getZ())
                : CliffAvoidance.dropNear(cells(mod), feet.getX(), feet.getY(), feet.getZ()))) _firm = feet;
    }

    // a look every LOOK_EVERY ticks, the first at once, and again at once when the clock went back (a new world). the first
    // version subtracted from Long.MIN_VALUE, which overflowed, so no firm ground was ever kept and no retreat ever came
    // (attempt 176, the same knock off a stub as 174). pure
    static boolean due(long now, long last) {
        return last == Long.MIN_VALUE || now < last || now - last >= LOOK_EVERY;
    }

    // the walk back to firm ground, while we stand on a rim with no path; or null
    public Task next(AltoClef mod) {
        long now = mod.getWorld().getGameTime();
        if (_back != null) {
            if (!_back.isFinished(mod) && now >= _backSince && now - _backSince < WALK_TICKS) return _back;
            _back = null;
            _rimSince = -1;
            _restUntil = now + REST_TICKS;
        }
        if (_restUntil != Long.MIN_VALUE && now < _restUntil && now >= _restUntil - REST_TICKS) return null;
        var player = mod.getPlayer();
        if (WorldHelper.getCurrentDimension() != Dimension.NETHER || !player.onGround()
                || mod.getClientBaritone().getPathingBehavior().isPathing()) {
            _rimSince = -1;
            return null;
        }
        BlockPos feet = player.blockPosition();
        if (!(SafeStand.on() ? SafeStand.unsafe(cells(mod), feet.getX(), feet.getY(), feet.getZ())
                : CliffAvoidance.dropNear(cells(mod), feet.getX(), feet.getY(), feet.getZ()))) {
            _rimSince = -1;
            return null;
        }
        if (_rimSince < 0 || now < _rimSince) _rimSince = now;
        if (now - _rimSince < RIM_TICKS) return null;
        // (firm ground a step or two off is no retreat: walked there and back to the rim in a second, twenty times a minute,
        // on a cliff's edge in the lavasea drill, 4 Nov 2026)
        if (_firm == null || _firm.distSqr(feet) <= NEAR * NEAR || !_firm.closerToCenterThan(player.position(), RANGE)) return null;
        _what = "On a rim with no way on: back to firm ground at " + _firm.toShortString();
        _back = new GetToBlockTask(_firm);
        _backSince = now;
        return _back;
    }

    // for the debug state of whoever asked
    public String what() {
        return _what;
    }

    private static CliffAvoidance.Cells cells(AltoClef mod) {
        return CliffAvoidance.of(new BlockStateInterface(mod.getClientBaritone().getPlayerContext(), true));
    }
}
