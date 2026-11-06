package adris.altoclef.chains;

// counts ticks between asking to respawn and being allowed to run the death command.
// respawning isn't instant: the server answers with a respawn packet and the client swaps in a brand new
// player entity, so the command has to wait for that (plus a beat so the server is done with us)
final class RespawnWait {
    // the beat after the new player shows up
    static final int SETTLE_TICKS = 10;
    // 15 seconds. if the respawn hasn't happened by then it never will (kicked, lag spike, etc)
    static final int TIMEOUT_TICKS = 20 * 15;

    enum Action {
        WAIT, SEND, DROP
    }

    private int _sinceRequest = 0;
    private int _sinceRespawn = -1;

    // call once per game tick. respawned = the player entity is a living one that isn't the one that died
    Action tick(boolean connected, boolean respawned) {
        _sinceRequest++;
        if (!connected) {
            return Action.DROP;
        }
        if (_sinceRespawn < 0) {
            if (!respawned) {
                return _sinceRequest > TIMEOUT_TICKS ? Action.DROP : Action.WAIT;
            }
            _sinceRespawn = 0;
        }
        _sinceRespawn++;
        return _sinceRespawn >= SETTLE_TICKS ? Action.SEND : Action.WAIT;
    }
}
