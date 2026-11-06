package adris.altoclef.tasks.speedrun.gamer.end;

// one line per landing: the whole fight happens in a few seconds of these, so a lost one has to say why. pure, the task feeds it
public final class LandingLog {
    // the survival chain or a fight took the wheel if we missed more ticks than this
    private static final int GAP_TICKS = 3;

    private boolean _on;
    private boolean _startedAtStand;
    private boolean _reached;
    private boolean _bedWas;
    private boolean _cloud;
    private boolean _gap;
    private int _placed;
    private int _clicks;
    private double _hpBefore;
    private double _hpNow;
    private double _pool;
    private double _self;
    private long _lastTick;

    // the finished line on the tick a landing ends, null on every other tick
    public String tick(long now, boolean landing, boolean sat, boolean atStand, boolean bedDown, boolean cloudClose, double dragonHp, double pool) {
        if (!landing && !_on) {
            return null;
        }
        if (!_on) {
            _on = true;
            _startedAtStand = atStand;
            _reached = false;
            _cloud = false;
            _gap = false;
            _placed = 0;
            _clicks = 0;
            _self = 0;
            _hpBefore = dragonHp;
            _pool = pool;
            _bedWas = bedDown;
        } else if (now - _lastTick > GAP_TICKS) {
            _gap = true;
        }
        _lastTick = now;
        // only what comes after the first click is ours, and regen doesn't pay it back. before the end check: the last blast
        // can land on the tick the landing ends
        if (_clicks > 0 && pool < _pool) _self += _pool - pool;
        _pool = pool;
        _hpNow = dragonHp;
        if (!landing) {
            return line(sat);
        }
        _reached |= atStand;
        _cloud |= cloudClose;
        if (bedDown && !_bedWas) _placed++;
        _bedWas = bedDown;
        return null;
    }

    public void clicked() {
        _clicks++;
    }

    // the task stopped in the middle of one
    public String finish() {
        return _on ? line(false) : null;
    }

    private String line(boolean sat) {
        _on = false;
        String why = _clicks > 0 ? "" : ", lost to " + lostTo(_cloud, _reached, sat, _gap);
        return String.format("landing: started at the stand %b, beds placed %d, clicks %d, dragon hp %.0f -> %.0f, self damage %.1f%s",
                _startedAtStand, _placed, _clicks, _hpBefore, _hpNow, _self, why);
    }

    static String lostTo(boolean cloud, boolean reached, boolean sat, boolean gap) {
        if (cloud) return "breath";
        if (!reached) return "never reached the stand";
        if (sat) return "sat early";
        return gap ? "something else took over" : "flew off";
    }
}
