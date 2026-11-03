package net.milenco.buddy.client;

import net.milenco.buddy.version.Game;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Measures the gaps between two hits and writes them to clicks.csv in the exchange folder
 * (time;ms;hit). Without that measurement we talk about the click pattern by gut feeling; with
 * klikanalyse.py you see what we really produce: the spread, the tail and whether fast and slow
 * hits come in clusters like with a hand.
 *
 * Gaps longer than five seconds belong to a pause and say nothing about the clicking itself; we
 * skip those, otherwise they ruin every distribution.
 */
public class ClickLog {
	private static final StringBuilder buf = new StringBuilder();
	private static long previous = 0, lastWrite = 0;
	private static final StringBuilder own = new StringBuilder();
	private static long previousOwn = 0;
	private static boolean wasSwinging = false;

	public static void swing(boolean hit) {
		if (!AfkConfig.get().clickLog) return;
		long now = System.currentTimeMillis();
		long gap = previous == 0 ? 0 : now - previous;
		previous = now;
		if (gap > 0 && gap < 5000) buf.append(now).append(';').append(gap).append(';').append(hit ? 1 : 0).append('\n');
		if (buf.length() > 4000 || (buf.length() > 0 && now - lastWrite > 10_000)) flush();
	}

	/** Series ended (pause, farm off): the next gap is no click pace any more. */
	public static void interruption() { previous = 0; }

	/**
	 * The player's own hits on this account, while the farm is off: clicks-own.csv. Without that measurement the mod's
	 * numbers say little - there is no table of "this is how a human clicks", but his own hand is the
	 * only reference point that really matters.
	 */
	public static void ownSwing(net.minecraft.client.player.LocalPlayer p, boolean measure) {
		if (p == null) return;
		boolean swinging = Game.swinging(p);
		if (!AfkConfig.get().clickLog || !measure) { wasSwinging = swinging; return; }
		if (swinging && !wasSwinging) {
			long now = System.currentTimeMillis();
			long gap = previousOwn == 0 ? 0 : now - previousOwn;
			previousOwn = now;
			if (gap > 0 && gap < 5000) own.append(now).append(';').append(gap).append(";1\n");
			if (own.length() > 2000) write("clicks-own.csv", own);
		}
		wasSwinging = swinging;
	}

	public static void flush() {
		lastWrite = System.currentTimeMillis();
		write("clicks.csv", buf);
		write("clicks-own.csv", own);
	}

	private static void write(String name, StringBuilder sb) {
		if (sb.length() == 0) return;
		try {
			Files.writeString(Path.of(AfkConfig.get().exchangeDir, name), sb.toString(),
				StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			sb.setLength(0);
		} catch (IOException ignored) { }
	}
}
