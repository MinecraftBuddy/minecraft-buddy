package adris.altoclef.util.helpers;

// a clock that may never have been set: Long.MIN_VALUE for "never". now - Long.MIN_VALUE overflows to a negative number, so
// "now >= last && now - last < span" holds for a clock that was never set, and a look once a second never looked again: the
// gold mine's gold_rock_iron (BranchMineTask.rockPick) returned early on every tick of every run since it went in
public final class Recent {
    private Recent() {
    }

    // set, not in the future, and less than span ago
    public static boolean within(long now, long last, long span) {
        return last != Long.MIN_VALUE && now >= last && now - last < span;
    }
}
