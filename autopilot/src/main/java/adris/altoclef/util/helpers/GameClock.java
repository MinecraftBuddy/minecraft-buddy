package adris.altoclef.util.helpers;

// the server's time packet drags getGameTime() back a tick or two under lag, and every "since" timer that read a negative
// number as "another world, start over" fired on it. this is the one place that tells the two apart
public final class GameClock {
    // how far back the clock may jump before it is another world and not the server correcting the client. one minute
    public static final long JITTER_TICKS = 1200;

    private GameClock() {
    }

    // ticks since `then`. a clock a little behind it is no time passed at all (0), one way behind is another world and long
    // enough ago for anything (MAX_VALUE), which is what every caller did with a negative number anyway. a clock that was
    // never set (Long.MIN_VALUE) is long enough ago for anything too: now - Long.MIN_VALUE overflowed to a negative number,
    // which read as "a moment ago" for ever (Recent)
    public static long since(long then, long now) {
        if (then == Long.MIN_VALUE) return Long.MAX_VALUE;
        if (now >= then) return now - then;
        return then - now <= JITTER_TICKS ? 0 : Long.MAX_VALUE;
    }

    // the same for an int clock (WorldHelper.getTicks()), where never is Integer.MIN_VALUE. for any other value it is the
    // long one: the difference is taken in long, so it does not overflow
    public static long since(int then, int now) {
        return then == Integer.MIN_VALUE ? Long.MAX_VALUE : since((long) then, (long) now);
    }
}
