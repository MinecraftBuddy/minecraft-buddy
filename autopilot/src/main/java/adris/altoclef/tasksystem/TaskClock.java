package adris.altoclef.tasksystem;

// time on a job, counted in ticks the job actually ran. nobody ticks it while the task is interrupted, so the gap is
// never billed, and nothing but an explicit reset() clears it, so a restart (the task system runs onStart again after
// every interrupt) can't hand out a fresh budget either. under lag a client tick is longer than 50 ms, so a laggy
// minute bills less than a minute: the budget is "this many ticks of trying", which is what a give up timer is for
public final class TaskClock {
    private static final int TICKS_PER_SECOND = 20;

    private long ticks;

    public void tick() {
        ticks++;
    }

    public double spentSeconds() {
        return ticks / (double) TICKS_PER_SECOND;
    }

    // strict, a limit of 90 means the tick that makes it 90.05 is the first one that is over
    public boolean over(double limitSeconds) {
        return spentSeconds() > limitSeconds;
    }

    public void reset() {
        ticks = 0;
    }
}
