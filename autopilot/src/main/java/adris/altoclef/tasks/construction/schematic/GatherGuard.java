package adris.altoclef.tasks.construction.schematic;

// "is the build going anywhere" for a schematic build. pure. the only number it believes is how many cells of the
// file the world already has right (SchematicDiff.correct), and only a new best counts: a block the builder burnt
// as scaffolding, a meal, a pickup or a place/break loop never moves it. gather, build, pause, gather the same thing
// is a loop that never ends by itself, so it ends here
public final class GatherGuard {

    // counts in a row that found no more right cells than the best before them
    public static final int MAX_IDLE_ROUNDS = 3;
    // task ticks without a new best, whatever phase they were spent in. ticks and not the wall clock, so a fight or a
    // meal that interrupts the task isn't billed
    public static final int MAX_IDLE_TICKS = 15 * 60 * 20;

    private long best = -1;
    private long bestAtLastRound = -1;
    private int idleRounds;
    private int idleTicks;
    private long gatherBest = -1;

    // any diff finished (the counts, and the watch that runs while the builder works)
    public void seen(long correct) {
        if (correct > best) {
            best = correct;
            idleTicks = 0;
        }
    }

    // a new batch is on its way, its own best starts over
    public void newBatch() {
        gatherBest = -1;
    }

    // while gathering: how many of the batch the bag holds (capped at the target). a new high is the gather getting
    // somewhere and buys time on the tick clock, it's not progress for the rounds: those only believe the diff
    public void gathering(long held) {
        if (held > gatherBest) {
            gatherBest = held;
            idleTicks = 0;
        }
    }

    // walking back to the build got closer (GoBack). buys time on the tick clock like a gather high does, a long
    // honest walk home isn't the build being stuck
    public void closer() {
        idleTicks = 0;
    }

    // a count finished, that's a round. true = stuck. the first one is the baseline and always goes
    public boolean round(long correct) {
        seen(correct);
        if (best > bestAtLastRound) {
            bestAtLastRound = best;
            idleRounds = 0;
            return false;
        }
        return ++idleRounds >= MAX_IDLE_ROUNDS;
    }

    // one task tick. true = stuck
    public boolean tick() {
        return ++idleTicks > MAX_IDLE_TICKS;
    }
}
