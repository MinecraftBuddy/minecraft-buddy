package adris.altoclef.util.helpers;

// the buddy's fork, Tuning chunk_search_commit, off by default: the chunk search (SearchChunksExploreTask) heads for one
// target chunk until it loads, leaves the search list or is given up, instead of scoring the list anew every tick. two chunks
// that scored about the same swapped every few seconds, each swap a new walk that Baritone began again, and the search paced
// on the tie line for minutes. the two decisions, as answers: no world in here, the search reads the list, the chunk and the
// player and asks
public final class ChunkCommitRules {

    // a target that has not been come this much closer to (blocks, XZ to its centre) in this many ticks of the search is given up
    public static final double PROGRESS_BLOCKS = 8;
    public static final long GIVE_UP_TICKS = 900;

    private ChunkCommitRules() {
    }

    // keep heading for the target, or pick anew by the search's own score. committed: there is a target. inList: it is still
    // in the search's list of chunks to visit. givenUp: it was given up before. loaded: the client has it now (it is searched
    // from here, nothing to walk to)
    public static boolean keep(boolean committed, boolean inList, boolean givenUp, boolean loaded) {
        return committed && inList && !givenUp && !loaded;
    }

    // Tuning chunk_search_keep, on by default since 9 Nov 2026: whether a restart of the chunk search keeps what it had (its searcher, the
    // chunks it explored). a fight or another chain breaking in restarts the search task, and the restart used to wipe it:
    // a fortress whose chunks had gone out of load range was searched for from nothing again. on: kept while the dimension
    // is the one it was gathered in and there is something to keep; a new order (a new instance) or another dimension
    // starts empty either way
    public static boolean keepOnRestart(boolean on, boolean sameDimension, boolean hasSearcher, boolean hasExplored) {
        return on && sameDimension && (hasSearcher || hasExplored);
    }

    public enum Clock {
        // came PROGRESS_BLOCKS closer than the best mark: the mark moves here and the clock starts again
        PROGRESS,
        // no progress yet, time is left
        WAIT,
        // GIVE_UP_TICKS without progress
        GIVE_UP
    }

    // distance: now, XZ to the target's centre. best: the distance of the last progress mark (the distance at the commit at
    // first). ticks: ticks of the search on this target since that mark, this one counted. progress first, so a step that
    // makes it on the last tick still counts
    public static Clock clock(double distance, double best, long ticks) {
        if (distance <= best - PROGRESS_BLOCKS) return Clock.PROGRESS;
        if (ticks >= GIVE_UP_TICKS) return Clock.GIVE_UP;
        return Clock.WAIT;
    }

    // XZ from a position to the centre of the chunk at chunkX, chunkZ (the centre the search's score measures from)
    public static double distanceXZ(double x, double z, int chunkX, int chunkZ) {
        double cx = chunkX * 16 + 8, cz = chunkZ * 16 + 8;
        return Math.hypot(cx - x, cz - z);
    }
}
