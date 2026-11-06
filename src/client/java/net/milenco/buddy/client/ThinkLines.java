package net.milenco.buddy.client;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The autopilot's log lines as the run recording's {@code think} timeline ({@link RunRecorder}): what it decided and why
 * ("rods: let the blaze go", "No path found"), without the path search's chatter, and a line that repeats counted
 * instead of written again.
 * <p>
 * A line counts as the one before when the two are the same once their numbers are blanked out, and it came within
 * {@link #SAME_MS} of that one's last time. The first of a run goes out at once with {@code n} 1; the count goes out, as a
 * line with the run's latest text, its whole count {@code n} and {@code first} (the time of the run's first line), when
 * another line comes, when the run went quiet for {@link #SAME_MS}, and every {@link #SAME_MS} while it goes on.
 * <p>
 * Pure: the time comes in, the lines go out through {@link Out}. Callers come from any thread (the autopilot's path
 * threads log too), so it is synchronized.
 */
final class ThinkLines {
	static final long SAME_MS = 10_000;
	private static final int MAX_TEXT = 300;
	private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");
	/** The autopilot's summary of a line it held back: "text x37 over 36t". */
	private static final Pattern SUMMARY = Pattern.compile("^(.*) x(\\d+) over \\d+t$");

	/**
	 * What is left out: the five-second vitals (the frames carry the same), and the path search's own steps, which come
	 * by the dozen for every path; its failures stay ("No path found", "couldn't get more than", a movement that took too
	 * long, a cancel).
	 */
	static final List<String> SKIP = List.of("vitals ", "baritone: Took ", "baritone: Starting to search", "baritone: Finished finding a path",
		"baritone: Found path segment", "baritone: A* cost coefficient", "baritone: Path almost over", "baritone: Splicing into planned next path",
		"baritone: Continuing on to planned next path", "baritone: Simplifying ", "baritone: Cutting off path at edge", "baritone: Length decreased by",
		"baritone: Path ends within loaded chunks", "baritone: Static cutoff", "baritone: slowPath is on", "baritone: Skipping forward",
		"baritone: Remembering to move", "baritone: Inventory move requested", "baritone: All done. At ", "baritone: Discarding next path",
		"STACKTRACE");

	interface Out { void line(long ms, String text, int n, long first); }

	private String key, text;
	private long first, last, toldAt;
	private int n, toldN;

	/** The text as it is kept: one line, at most {@link #MAX_TEXT} characters; null for a line left out. */
	static String clean(String raw) {
		if (raw == null) return null;
		String s = raw.replace('\n', ' ').replace('\r', ' ').trim();
		if (s.isEmpty()) return null;
		for (String p : SKIP) if (s.startsWith(p)) return null;
		return s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT - 1) + "…" : s;
	}

	static String key(String s) { return NUMBER.matcher(s).replaceAll("#"); }

	synchronized void offer(String raw, long ms, Out out) {
		String s = clean(raw);
		if (s == null) return;
		Matcher m = SUMMARY.matcher(s);
		if (m.matches()) {
			// the autopilot held repeats of a line back and now says how many: they count for that line's run (the
			// first summary of a run counts the line it printed too); one of a line that is no longer the last is dropped
			if (key != null && key.equals(key(m.group(1))) && ms - last <= SAME_MS) {
				int k = Integer.parseInt(m.group(2));
				n += n == 1 ? Math.max(0, k - 1) : k;
				last = ms;
				if (ms - toldAt >= SAME_MS) tell(out);
			}
			return;
		}
		String k = key(s);
		if (k.equals(key) && ms - last <= SAME_MS) {
			n++;
			text = s;
			last = ms;
			if (ms - toldAt >= SAME_MS) tell(out);
			return;
		}
		if (key != null && n > toldN) tell(out);
		key = k;
		text = s;
		first = last = toldAt = ms;
		n = toldN = 1;
		out.line(ms, s, 1, 0);
	}

	/** Every so often (once a second is plenty): the count of a run that went quiet or has gone on for a while. */
	synchronized void flush(long ms, Out out) {
		if (key == null) return;
		boolean quiet = ms - last > SAME_MS;
		if (n > toldN && (quiet || ms - toldAt >= SAME_MS)) tell(out);
		if (quiet) key = null;
	}

	/** The count of the run so far, at the time of its last line. */
	private void tell(Out out) {
		out.line(last, text, n, first);
		toldN = n;
		toldAt = last;
	}
}
