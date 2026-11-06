package adris.altoclef.util.progresscheck;

// backstop for chasing a drop through water. the rule that skips items in water catches the lake case, this catches
// the rest: a "dry" item that is actually behind a wall of water, or us bobbing in place for whatever reason.
// swimming across to a dry item is fine, it only gives up when we are wet and the distance has not shrunk.
// pure on purpose, the caller hands in the tick and the numbers, so it can be tested without a game.
public class WaterPickupWatchdog {

    private final int _stallTicks;
    private final double _minProgress;

    private Object _target;
    private double _bestDistance;
    private int _lastProgressTick;
    private boolean _armed;

    public WaterPickupWatchdog(int stallTicks, double minProgress) {
        _stallTicks = stallTicks;
        _minProgress = minProgress;
    }

    // 4 seconds to get half a block closer
    public WaterPickupWatchdog() {
        this(80, 0.5);
    }

    public void reset() {
        _target = null;
        _armed = false;
    }

    /**
     * @return true when we should drop this target, it is on us to blacklist it and stop pathing
     */
    public boolean update(Object target, boolean playerInWater, double distance, int nowTick) {
        // a new target or dry land both restart the clock, only a continuous wet stretch counts
        if (!playerInWater || target == null || !target.equals(_target)) {
            _target = target;
            _armed = playerInWater && target != null;
            _bestDistance = distance;
            _lastProgressTick = nowTick;
            return false;
        }
        if (!_armed) {
            _armed = true;
            _bestDistance = distance;
            _lastProgressTick = nowTick;
            return false;
        }
        if (distance <= _bestDistance - _minProgress) {
            _bestDistance = distance;
            _lastProgressTick = nowTick;
            return false;
        }
        return nowTick - _lastProgressTick >= _stallTicks;
    }
}
