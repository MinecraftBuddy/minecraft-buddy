package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.Subscription;
import adris.altoclef.eventbus.events.ChunkLoadEvent;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.ChunkCommitRules;
import adris.altoclef.util.helpers.Tuning;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.utils.Dimension;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiFunction;
import net.minecraft.world.level.ChunkPos;

/**
 * Searches/explores a continuous "blob" of chunks, attempting to load in ALL nearby chunks that are part of this "blob"
 * <p>
 * You must define a function that determines whether a chunk is to be included within this "blob".
 * <p>
 * For instance, if you wish to explore an entire desert, this function will return whether a chunk is a desert chunk.
 */
public abstract class SearchChunksExploreTask extends Task {

    private final Object _searcherMutex = new Object();
    private final Set<ChunkPos> _alreadyExplored = new HashSet<>();
    private ChunkSearchTask _searcher;
    // a searcher ran out of chunks and nothing new has turned up since
    private boolean _searchExhausted;
    // the wander under us is failing and that has already been counted as an exhausted search
    private boolean _failingWanderCounted;
    private AltoClef _mod;
    private Subscription<ChunkLoadEvent> _chunkLoadedSubscription;

    // (the buddy's fork, Tuning chunk_search_commit, off by default) the target chunk the search heads for, the distance of its
    // last progress mark, the search's ticks on it since that mark and since the commit, and the chunks given up. resetSearch
    // leaves all of it alone, so a task that interrupts the search (and restarts it) does not wipe it. a new instance (another
    // block or biome to search for, a new order) starts empty; a change of dimension clears it (committedPick)
    private ChunkPos _commitTarget;
    private double _commitBest;
    private long _commitTicks;
    private long _commitTotalTicks;
    private final Set<ChunkPos> _givenUp = new HashSet<>();
    private Dimension _commitDimension;

    // Virtual
    protected ChunkPos getBestChunkOverride(AltoClef mod, List<ChunkPos> chunks) {
        return null;
    }

    // (Tuning chunk_search_keep) the dimension the searcher and the explored chunks were gathered in
    private Dimension _searchDimension;

    @Override
    protected void onStart(AltoClef mod) {
        _mod = mod;

        // Listen for chunk loading
        _chunkLoadedSubscription = EventBus.subscribe(ChunkLoadEvent.class, evt -> onChunkLoad(evt.chunkPos));

        Dimension here = WorldHelper.getCurrentDimension();
        boolean keep;
        synchronized (_searcherMutex) {
            keep = ChunkCommitRules.keepOnRestart(Tuning.get("chunk_search_keep", 0) > 0, here == _searchDimension,
                    _searcher != null, !_alreadyExplored.isEmpty());
        }
        _searchDimension = here;
        if (keep) {
            // (an interruption: the searcher goes on where it was; without one, the loaded chunks may seed a new one, the
            // explored ones left out as before)
            Debug.logInternal(String.format(Locale.ROOT, "chunk search: resumed after an interruption, %s, %d chunks explored",
                    _searcher != null ? "its searcher kept" : "no searcher", _alreadyExplored.size()));
            if (_searcher == null) {
                for (ChunkPos start : mod.getChunkTracker().getLoadedChunks()) {
                    onChunkLoad(start);
                }
            }
            sayNear(mod);
            return;
        }
        resetSearch(mod);
        sayNear(mod);
    }

    // (a read of 23 Nov 2026) why a start began no searcher: what the search space test sees in the 3x3 chunks round the
    // buddy, at most once a game minute. the fortress search wandered 20 minutes on from a wart room's chunks with no
    // "New searcher" (cascade heel-200 r2, box 3 heel-215-mc3 r2), and the log said nothing of why
    private long _nearSaidAt = Long.MIN_VALUE;

    private void sayNear(AltoClef mod) {
        if (_searcher != null || mod.getPlayer() == null || mod.getWorld() == null) return;
        long now = mod.getWorld().getGameTime();
        if (_nearSaidAt != Long.MIN_VALUE && now >= _nearSaidAt && now - _nearSaidAt < 1200) return;
        _nearSaidAt = now;
        ChunkPos at = mod.getPlayer().chunkPosition();
        int loaded = 0;
        int within = 0;
        int explored = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                ChunkPos c = new ChunkPos(at.x() + dx, at.z() + dz);
                if (!mod.getChunkTracker().isChunkLoaded(c)) continue;
                loaded++;
                synchronized (_searcherMutex) {
                    if (_alreadyExplored.contains(c)) explored++;
                }
                if (isChunkWithinSearchSpace(mod, c)) within++;
            }
        }
        Debug.logInternal(String.format(Locale.ROOT, "chunk search: no searcher at [%d, %d]; round the buddy %d of 9 chunks loaded, %d in the search space%s, %d explored; %d chunks tracked as loaded",
                at.x(), at.z(), loaded, within, describeNear(mod, at), explored, mod.getChunkTracker().getLoadedChunks().size()));
    }

    // more for sayNear's line, about the 3x3 chunks round at
    protected String describeNear(AltoClef mod, ChunkPos at) {
        return "";
    }

    @Override
    protected Task onTick(AltoClef mod) {
        synchronized (_searcherMutex) {
            countFailingWander();
            if (_searcher == null) {
                setDebugState("Exploring/Searching for valid chunk");
                // Explore
                return getWanderTask(mod);
            }

            if (_searcher.isActive() && _searcher.isFinished(mod)) {
                Debug.logWarning("Target object search failed.");
                _alreadyExplored.addAll(_searcher.getSearchedChunks());
                _searchExhausted = true;
                _searcher = null;
            } else if (_searcher.finished()) {
                setDebugState("Searching for target object...");
                Debug.logMessage("Search finished.");
                _alreadyExplored.addAll(_searcher.getSearchedChunks());
                _searchExhausted = true;
                _searcher = null;
            }
            //Debug.logMessage("wtf: " + (_searcher == null? "(null)" :_searcher.finished()));
            setDebugState("Searching within chunks...");
            return _searcher;
        }
    }

    // a wander that has been exploring forever and never got out of its spot means no chunk is coming, which is the
    // same news as a searcher running out. counted once per bad wander: whoever reads failedSearch resets the search,
    // and with nothing loaded to search the wander is still the one failing, so counting it every tick would reset
    // every tick
    private void countFailingWander() {
        if (!thisOrChildWanderFailing()) {
            _failingWanderCounted = false;
        } else if (_searcher == null && !_failingWanderCounted) {
            _failingWanderCounted = true;
            _searchExhausted = true;
            Debug.logWarning("Search wander is failing, counting the search as exhausted.");
        }
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        EventBus.unsubscribe(_chunkLoadedSubscription);
    }

    // When we find a valid chunk, start our search there.
    private void onChunkLoad(ChunkPos pos) {
        if (_searcher != null) return;
        if (!this.isActive()) return;
        if (isChunkWithinSearchSpace(_mod, pos)) {
            synchronized (_searcherMutex) {
                if (!_alreadyExplored.contains(pos)) {
                    Debug.logMessage("New searcher: " + pos);
                    _searcher = new SearchSubTask(pos);
                    _searchExhausted = false;
                }
            }
        }
    }

    @Override
    protected String toHudString() {
        return "Searching the area";
    }

    @Override
    protected boolean isHudPlumbing() {
        return true;
    }

    protected Task getWanderTask(AltoClef mod) {
        return new TimeoutWanderTask(true);
    }

    protected abstract boolean isChunkWithinSearchSpace(AltoClef mod, ChunkPos pos);

    public boolean failedSearch() {
        return _searcher == null && _searchExhausted;
    }

    public void resetSearch(AltoClef mod) {
        //Debug.logMessage("Search reset");
        _searcher = null;
        _searchExhausted = false;
        _alreadyExplored.clear();
        // We want to search the currently loaded chunks too!!!
        for (ChunkPos start : mod.getChunkTracker().getLoadedChunks()) {
            onChunkLoad(start);
        }
    }

    // chunk_search_commit: the target is kept while it is still in the list, not loaded and not given up, and a new one is
    // picked by the old score (scored) only then; an equal GetToChunkTask each tick, so the walk under it goes on instead of
    // Baritone starting again. a target the search has not come ChunkCommitRules.PROGRESS_BLOCKS closer to in GIVE_UP_TICKS
    // of its own ticks is given up and left out of every later pick. only given-up chunks left: null, the search ends as
    // exhausted and the wander takes over, as when the list empties
    private ChunkPos committedPick(AltoClef mod, List<ChunkPos> chunks, BiFunction<AltoClef, List<ChunkPos>, ChunkPos> scored) {
        Dimension dimension = WorldHelper.getCurrentDimension();
        if (dimension != _commitDimension) {
            _commitDimension = dimension;
            _commitTarget = null;
            _givenUp.clear();
        }
        ChunkPos target = _commitTarget;
        boolean keep = ChunkCommitRules.keep(target != null, target != null && chunks.contains(target),
                target != null && _givenUp.contains(target), target != null && mod.getChunkTracker().isChunkLoaded(target));
        if (!keep) {
            ChunkPos pick = pickOpen(mod, chunks, scored);
            // the score can name the same chunk again (loaded but not yet searched): that is no new target
            if (pick == null || !pick.equals(target)) return commit(mod, pick);
        }
        double distance = distanceTo(mod, target);
        _commitTicks++;
        _commitTotalTicks++;
        switch (ChunkCommitRules.clock(distance, _commitBest, _commitTicks)) {
            case PROGRESS -> {
                _commitBest = distance;
                _commitTicks = 0;
            }
            case GIVE_UP -> {
                Debug.logInternal(String.format(Locale.ROOT, "chunk search: gave up chunk [%d, %d] after %d s, %.0f blocks off",
                        target.x(), target.z(), _commitTotalTicks / 20, distance));
                _givenUp.add(target);
                return commit(mod, pickOpen(mod, chunks, scored));
            }
            case WAIT -> {
            }
        }
        return target;
    }

    private ChunkPos pickOpen(AltoClef mod, List<ChunkPos> chunks, BiFunction<AltoClef, List<ChunkPos>, ChunkPos> scored) {
        List<ChunkPos> open = new ArrayList<>(chunks.size());
        for (ChunkPos chunk : chunks) {
            if (!_givenUp.contains(chunk)) open.add(chunk);
        }
        return open.isEmpty() ? null : scored.apply(mod, open);
    }

    private ChunkPos commit(AltoClef mod, ChunkPos pick) {
        _commitTarget = pick;
        _commitTicks = 0;
        _commitTotalTicks = 0;
        if (pick != null) {
            _commitBest = distanceTo(mod, pick);
            Debug.logInternal(String.format(Locale.ROOT, "chunk search: heading for chunk [%d, %d], %.0f blocks off",
                    pick.x(), pick.z(), _commitBest));
        }
        return pick;
    }

    private static double distanceTo(AltoClef mod, ChunkPos chunk) {
        return ChunkCommitRules.distanceXZ(mod.getPlayer().getX(), mod.getPlayer().getZ(), chunk.x(), chunk.z());
    }

    class SearchSubTask extends ChunkSearchTask {

        public SearchSubTask(ChunkPos start) {
            super(start);
        }

        @Override
        protected boolean isChunkPartOfSearchSpace(AltoClef mod, ChunkPos pos) {
            return isChunkWithinSearchSpace(mod, pos);
        }

        // asked once a tick, from ChunkSearchTask.onTick, and only while this search is the task ticking
        @Override
        public ChunkPos getBestChunk(AltoClef mod, List<ChunkPos> chunks) {
            if (Tuning.get("chunk_search_commit", 0) > 0) return committedPick(mod, chunks, this::scored);
            return scored(mod, chunks);
        }

        // the pick as it always was: the subclass's override, else ChunkSearchTask's score
        private ChunkPos scored(AltoClef mod, List<ChunkPos> chunks) {
            ChunkPos override = getBestChunkOverride(mod, chunks);
            if (override != null) return override;
            return super.getBestChunk(mod, chunks);
        }

        @Override
        protected boolean isChunkSearchEqual(ChunkSearchTask other) {
            // Since we're keeping track of "_searcher", we expect the subchild routine to ALWAYS be consistent!
            return other == this;//return other instanceof SearchSubTask;
        }

        @Override
        protected String toHudString() {
            return "Searching the area";
        }

        @Override
        protected boolean isHudPlumbing() {
            return true;
        }

        @Override
        protected String toDebugString() {
            return "Searching chunks...";
        }
    }

}
