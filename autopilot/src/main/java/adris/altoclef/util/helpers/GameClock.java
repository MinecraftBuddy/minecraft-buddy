package adris.altoclef.util.helpers;

// the server's time packet drags getGameTime() back a tick or two under lag, and every "since" timer that read a negative
// number as "another world, start over" fired on it. this is the one place that tells the two apart
public final class GameClock {
    // how far back the clock may jump before it is another world and not the server correcting the client. one minute
    public static final long JITTER_TICKS = 1200;

    private GameClock() {
    }

    // ticks since `then`. a clock a little behind it is no time passed at all (0), one way behind is another world and long
    // enough ago for anything (MAX_VALUE), which is what every caller did with a negative number anyway
    public static long since(long then, long now) {
        if (now >= then) return now - then;
        return then - now <= JITTER_TICKS ? 0 : Long.MAX_VALUE;
    }
}
