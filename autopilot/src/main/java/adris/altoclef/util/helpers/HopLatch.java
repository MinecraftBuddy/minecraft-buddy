package adris.altoclef.util.helpers;

// the lava hop, once started, runs to the end instead of being re-decided every tick. the surface flickers: on the ticks
// we bob up out of the lava the hop used to bail, baritone got a fresh path, and the next lava tick cancelled it again, so
// neither ever finished. pure, it only counts ticks
public final class HopLatch {

    // enough dry ticks in a row to know the escape worked and it is not just a flicker
    public static final int CLEAR_TICKS = 10;
    public static final int GIVE_UP_TICKS = 50;

    private boolean _active;
    private boolean _spent;
    private int _ticks;
    private int _clear;
    // touched ground since the last lava tick. onGround blinks on a lip, so this only comes down when we are back in the lava
    private boolean _landed;

    public enum Keys {
        // still getting out: jump, sprint, forward
        SWIM,
        // feet on the lip and our middle still over the lava: forward alone, a jump from here sails right over the shore
        WALK_ON,
        // over, hold nothing
        NONE
    }

    public boolean active() {
        return _active;
    }

    // true only on the tick the latch is taken, so the caller cancels baritone once and not every tick.
    // a hop that gave up never comes back, that is what lets baritone have uninterrupted turns
    public boolean begin() {
        if (_active || _spent) return false;
        _active = true;
        _ticks = 0;
        _clear = 0;
        _landed = false;
        return true;
    }

    // a block set down as a step is a new exit, so a hop that gave up on the old wall gets one more go
    public void rearm() {
        _spent = false;
    }

    public boolean spent() {
        return _spent;
    }

    // the whole hop decision for one tick. solid ground under our middle ends it on the spot, no counting: the dry ticks that
    // tick() waits for are spent jumping forward, and that is how a hop clears the block it was aiming for
    public Keys step(boolean inLava, boolean onGround, boolean lavaBelow) {
        if (!_active) return Keys.NONE;
        if (inLava) {
            _landed = false;
        } else if (onGround) {
            _landed = true;
        }
        if (_landed && !lavaBelow) {
            _active = false;
            _landed = false;
            return Keys.NONE;
        }
        tick(!inLava && !lavaBelow);
        if (!_active) return Keys.NONE;
        return _landed ? Keys.WALK_ON : Keys.SWIM;
    }

    // same question with no hop running. standing on a lip counts as out even with lava under our middle, or the jump key
    // stays down and throws us back in
    public static boolean swimKeys(boolean inLava, boolean lavaBelow, boolean onGround) {
        return inLava || (lavaBelow && !onGround);
    }

    // every tick while latched counts, lava or not. clear = out of lava and nothing lava under us
    public void tick(boolean clear) {
        if (!_active) return;
        _ticks++;
        _clear = clear ? _clear + 1 : 0;
        if (_clear >= CLEAR_TICKS) {
            _active = false;
        } else if (_ticks >= GIVE_UP_TICKS) {
            _active = false;
            _spent = true;
        }
    }
}
