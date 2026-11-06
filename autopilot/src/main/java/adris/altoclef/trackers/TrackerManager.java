package adris.altoclef.trackers;

import adris.altoclef.AltoClef;

import java.util.ArrayList;

public class TrackerManager {

    private final ArrayList<Tracker> _trackers = new ArrayList<>();

    private final AltoClef _mod;

    private boolean _wasInGame = false;

    public TrackerManager(AltoClef mod) {
        _mod = mod;
    }

    public void tick() {
        boolean inGame = AltoClef.inGame();
        if (!inGame && _wasInGame) {
            // Reset when we leave our world
            resetAll();
        }
        _wasInGame = inGame;

        for (Tracker tracker : _trackers) {
            tracker.setDirty();
        }
    }

    // everything the trackers know belongs to one world. soprano also calls this when the world goes away while we are
    // idle, tick() above never runs then
    public void resetAll() {
        for (Tracker tracker : _trackers) {
            tracker.reset();
        }
        // This is a a spaghetti. Fix at some point.
        _mod.getChunkTracker().reset(_mod);
        _mod.getMiscBlockTracker().reset();
        _mod.getLandMemory().reset();
        // a ban is about a block or a mob in this world, the next one has its own
        _mod.getBans().clearRun("world left");
        _wasInGame = false;
    }

    public void addTracker(Tracker tracker) {
        tracker._mod = _mod;
        _trackers.add(tracker);
    }
}
