package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;

// budget and stall clock for one attempt at one phase. pure: the engine hands in "seconds of game time" so a test can
// drive it without a game. nothing in here knows what a tick is
public final class Watchdog {
    public enum Result {
        OK,
        // the attempt ran past cfg.budgets.minutes(phase)
        BUDGET,
        // nothing useful happened for handler.stallSeconds()
        STALL
    }

    public record Verdict(Result result, String reason) {
        static final Verdict FINE = new Verdict(Result.OK, "");

        public boolean ok() {
            return result == Result.OK;
        }
    }

    private double attemptStart;
    private double lastProgress;

    public Watchdog() {
        reset(0);
    }

    // new attempt (phase entered, or a retry). both clocks restart
    public void reset(double nowSeconds) {
        attemptStart = nowSeconds;
        lastProgress = nowSeconds;
    }

    // something useful happened, the stall timer starts over (the budget does not, that is the whole point of it)
    public void progress(double nowSeconds) {
        lastProgress = nowSeconds;
    }

    // the clocks stood still for this long (a death recovery had the wheel): neither the budget nor the stall timer may
    // bill the attempt for it
    public void pause(double seconds) {
        attemptStart += seconds;
        lastProgress += seconds;
    }

    // the budget alone stands still for this long, a walk the handler said does not count (PhaseHandler.holdsBudget)
    public void pauseBudget(double seconds) {
        attemptStart += seconds;
    }

    // only the stall timer forgives this much (another chain had the wheel). capped at now, forgiving can never be
    // better than a real progress call
    public void excuse(double nowSeconds, double seconds) {
        lastProgress = Math.min(nowSeconds, lastProgress + Math.max(0, seconds));
    }

    // the attempt began before this process did (a relog resumed it), the budget keeps counting from the real start
    public void backdate(double startSeconds) {
        attemptStart = Math.min(attemptStart, startSeconds);
    }

    public double secondsInAttempt(double nowSeconds) {
        return Math.max(0, nowSeconds - attemptStart);
    }

    public double secondsSinceProgress(double nowSeconds) {
        return Math.max(0, nowSeconds - lastProgress);
    }

    public Verdict check(double nowSeconds, GamerPhase phase, GamerConfig cfg, PhaseHandler handler) {
        return check(nowSeconds, cfg.budgets.minutes(phase), handler.stallSeconds());
    }

    // budgetMinutes <= 0 and stallSeconds <= 0 both mean "that check is off". the budget wins when both ran out, it is the
    // one that explains a slow phase best
    public Verdict check(double nowSeconds, double budgetMinutes, double stallSeconds) {
        double inAttempt = secondsInAttempt(nowSeconds);
        if (budgetMinutes > 0 && inAttempt > budgetMinutes * 60) {
            return new Verdict(Result.BUDGET, "took longer than " + trim(budgetMinutes) + " minutes");
        }
        double idle = secondsSinceProgress(nowSeconds);
        if (stallSeconds > 0 && idle > stallSeconds) {
            return new Verdict(Result.STALL, "no progress for " + (int) idle + " seconds");
        }
        return Verdict.FINE;
    }

    private static String trim(double minutes) {
        return minutes == Math.rint(minutes) ? Integer.toString((int) minutes) : Double.toString(minutes);
    }
}
