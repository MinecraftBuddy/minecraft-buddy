package adris.altoclef.trace;

// first and second place of the task wheel, found in the one pass the runner already makes. no second getPriority: some
// chains do real work inside it. the tie rule is the old loop's, strictly greater wins, so the earlier chain keeps a tie
public final class WheelPick<T> {

    private T winner;
    private float winnerPriority = Float.NEGATIVE_INFINITY;
    private T runnerUp;
    private float runnerUpPriority = Float.NEGATIVE_INFINITY;

    public void reset() {
        winner = null;
        winnerPriority = Float.NEGATIVE_INFINITY;
        runnerUp = null;
        runnerUpPriority = Float.NEGATIVE_INFINITY;
    }

    public void offer(T chain, float priority) {
        if (priority > winnerPriority) {
            runnerUp = winner;
            runnerUpPriority = winnerPriority;
            winner = chain;
            winnerPriority = priority;
        } else if (priority > runnerUpPriority) {
            runnerUp = chain;
            runnerUpPriority = priority;
        }
    }

    public T winner() {
        return winner;
    }

    public float winnerPriority() {
        return winnerPriority;
    }

    public T runnerUp() {
        return runnerUp;
    }

    public float runnerUpPriority() {
        return runnerUpPriority;
    }
}
