package adris.altoclef.tasks.speedrun.gamer.tasks;

// the timers around CollectBlazeRodsTask, which never gives up on its own. pure: the phase feeds it the clock, the rod
// count, "are we parked next to a spawner" and "have we got a spawner at all", it answers keep going / try another fortress / forget the rods
public final class RodsWatch {
    public enum Verdict {
        KEEP_GOING,
        // camped at the spawner for too long without a rod, or worked it (camping or not) for even longer without one
        GIVE_UP_SPAWNER,
        // the whole rods step took too long
        GIVE_UP_RODS
    }

    private final double campSeconds;
    private final double drySeconds;
    private final double budgetSeconds;

    private double startedAt = Double.NaN;
    private double lastGain;
    private double campSince = Double.NaN;
    // first tick this spawner was ours. walking around looking for the fortress is not the spawner's fault
    private double spawnerSince = Double.NaN;
    private int lastRods = -1;

    public RodsWatch(double campSeconds, double budgetSeconds) {
        this(campSeconds, Double.POSITIVE_INFINITY, budgetSeconds);
    }

    public RodsWatch(double campSeconds, double drySeconds, double budgetSeconds) {
        this.campSeconds = campSeconds;
        this.drySeconds = drySeconds;
        this.budgetSeconds = budgetSeconds;
    }

    public Verdict update(double now, int rods, boolean camping) {
        return update(now, rods, camping, false);
    }

    public Verdict update(double now, int rods, boolean camping, boolean atSpawner) {
        return update(now, rods, camping, atSpawner, true);
    }

    // the camp timer alone misses a spawner whose blazes keep us busy (hiding, walking out to them) and never die, that
    // is never "camping". so a second, longer timer runs the whole time we have a spawner, whatever we are doing at it
    // budgetApplies off skips the budget and only the budget: if the phase just ignored the verdict, GIVE_UP_RODS would
    // return first every tick and the dry and camp clocks would be dead for good
    public Verdict update(double now, int rods, boolean camping, boolean atSpawner, boolean budgetApplies) {
        if (Double.isNaN(startedAt)) {
            startedAt = now;
            lastGain = now;
        }
        if (rods > lastRods && lastRods >= 0) {
            lastGain = now;
            campSince = camping ? now : Double.NaN;
        }
        lastRods = rods;
        if (atSpawner && Double.isNaN(spawnerSince)) {
            spawnerSince = now;
        }
        if (budgetApplies && now - startedAt >= budgetSeconds) {
            return Verdict.GIVE_UP_RODS;
        }
        if (atSpawner && now - Math.max(spawnerSince, lastGain) >= drySeconds) {
            return Verdict.GIVE_UP_SPAWNER;
        }
        if (!camping) {
            campSince = Double.NaN;
            return Verdict.KEEP_GOING;
        }
        if (Double.isNaN(campSince)) {
            campSince = now;
        }
        return now - Math.max(campSince, lastGain) >= campSeconds ? Verdict.GIVE_UP_SPAWNER : Verdict.KEEP_GOING;
    }

    // time spent on a food trip does not count against the budget. only the budget moves: the dry and camp timers exist for
    // blazes that keep us busy, and a gap in the fighting still counts toward those
    public void pause(double seconds) {
        if (!Double.isNaN(startedAt) && seconds > 0) {
            startedAt += seconds;
        }
    }

    // a spawner we cannot get to was banned on its own. another one known = go there, the fortress it sits in is not the
    // spawner's fault. none = the whole fortress goes, nothing else is left to try in it
    public static Verdict afterNoWay(boolean otherSpawnerKnown) {
        return otherSpawnerKnown ? Verdict.KEEP_GOING : Verdict.GIVE_UP_SPAWNER;
    }

    // the task dropped its spawner and picked another by itself, the new one gets its own dry clock
    public void spawnerChanged() {
        spawnerSince = Double.NaN;
    }

    // we left that spawner, the next one starts a fresh camp
    public void spawnerGivenUp(double now) {
        campSince = Double.NaN;
        spawnerSince = Double.NaN;
        lastGain = now;
    }
}
