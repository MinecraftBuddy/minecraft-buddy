package adris.altoclef.util.helpers;

// a clock that may never have been set: Long.MIN_VALUE (Integer.MIN_VALUE for an int tick) for "never". now - Long.MIN_VALUE
// overflows to a negative number, so "now >= last && now - last < span" holds for a clock that was never set, and a look
// once a second never looked again: the gold mine's gold_rock_iron (BranchMineTask.rockPick) returned early on every tick of
// every run since it went in, and the log pick's dirt look and look cache (TreeReachScan) never ran since the fork came.
// GameClock.since holds the never rule (MAX_VALUE, long enough ago for anything); this is the "recent" and "due" over it.
//
// a clock in the future (time went back: lag, a relog, another world) is never recent and always due, at any distance:
// what every caller did before these were here. GameClock.since's "a tick or two back is no time at all" is for spans that
// must not restart on lag; a look or a hold that runs again on such a tick costs one look
public final class Recent {
    private Recent() {
    }

    // set, not in the future, and less than span ago
    public static boolean within(long now, long last, long span) {
        return now >= last && GameClock.since(last, now) < span;
    }

    public static boolean within(int now, int last, int span) {
        return now >= last && GameClock.since(last, now) < span;
    }

    // a clock for once every `every`: due when never set, `every` or more ago, or in the future
    public static boolean due(long now, long last, long every) {
        return !within(now, last, every);
    }

    public static boolean due(int now, int last, int every) {
        return !within(now, last, every);
    }
}
