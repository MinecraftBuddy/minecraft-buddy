package adris.altoclef.tasksystem;

// notices a chain that keeps winning the wheel while running nothing. that is how a finished run held priority 80 for a
// minute with the user task locked out, and the log said nothing at all. pure (time comes in as an argument) so it can be
// tested without a game
public final class HeldWheelWatch {

    // long enough that a chain finishing one task and picking the next never trips it
    public static final long EMPTY_MS = 5_000;

    private TaskChain chain;
    private long since;
    private boolean logged;

    // call once per runner tick with whoever won the wheel (null for nobody) and whether any task ticked under it.
    // true means "log it now", and it only says so once per stretch: it re-arms when the chain changes or a task runs
    public boolean shouldLog(TaskChain winner, boolean ranSomething, long nowMs) {
        if (winner == null || ranSomething) {
            chain = null;
            logged = false;
            return false;
        }
        if (winner != chain) {
            chain = winner;
            since = nowMs;
            logged = false;
            return false;
        }
        if (!logged && nowMs - since > EMPTY_MS) {
            logged = true;
            return true;
        }
        return false;
    }
}
