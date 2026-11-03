package net.milenco.buddy.client;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/**
 * Whether other players sleep, so that a setting can have the buddy sleep only while others do too. Two signs, either
 * is enough:
 * <ul>
 * <li>a player in sight lies in a bed (the game sends every player's pose within view);</li>
 * <li>the line above the hotbar that counts the sleepers, "Slapen... (1/2)" on Tweakcraft (our rule {@code slapen_balk},
 * shown to everyone in the overworld from the first sleeper) or the game's own "1/2 players sleeping": a line with
 * "slap" or "sleep" and a count. That line reaches the buddy wherever in the world the sleeper lies. The count holds the
 * buddy too while the buddy sleeps, so then it takes two.</li>
 * </ul>
 * With {@code baseSleepOthers} on the buddy goes to bed only while someone else sleeps, and gets up when for
 * {@link #GRACE} ms nobody else has.
 */
final class Sleepers {
	private Sleepers() {}

	static final long GRACE = 10_000, BAR_FRESH = 2_000;
	private static final Pattern COUNT = Pattern.compile("(\\d+)\\s*/\\s*(\\d+)");
	private static long barAt, othersAt;
	private static int barCount;
	private static boolean told;

	/** A line above the hotbar: the sleepers' count, if it is one. */
	static void overlay(Component msg) {
		String s = msg.getString();
		String low = s.toLowerCase(Locale.ROOT);
		if (!low.contains("slap") && !low.contains("sleep")) return;
		Matcher m = COUNT.matcher(s);
		if (!m.find()) return;
		barCount = Integer.parseInt(m.group(1));
		barAt = System.currentTimeMillis();
	}

	/** Someone other than the buddy sleeps right now, as far as can be told. */
	static boolean now(LocalPlayer p) {
		long t = System.currentTimeMillis();
		boolean seen = false;
		for (Player o : p.level().players()) if (o != p && o.isSleeping()) { seen = true; break; }
		boolean bar = t - barAt <= BAR_FRESH && barCount >= (p.isSleeping() ? 2 : 1);
		if (seen || bar) othersAt = t;
		return seen || bar;
	}

	/** Someone else slept within the grace: stay in bed. */
	static boolean lately(LocalPlayer p) {
		return now(p) || System.currentTimeMillis() - othersAt <= GRACE;
	}

	/**
	 * Whether the buddy may go to bed (or stay in it) now, with the option on. Off: always. Says once a night that the buddy waits.
	 */
	static boolean allows(LocalPlayer p) {
		if (!AfkConfig.get().baseSleepOthers) return true;
		boolean ok = p.isSleeping() ? lately(p) : now(p);
		// against phantoms: two game days without a bed, and a moment in it sets the server's count to 0 (Rest)
		if (!ok && Rest.wantsBed(p)) {
			if (!p.isSleeping() && !told) { told = true; Exchange.emit("base", "action", "lies down briefly", "reason", "phantoms"); }
			return true;
		}
		if (ok) told = false;
		else if (!told && !p.isSleeping()) { told = true; Exchange.emit("base", "action", "waits for sleepers"); }
		return ok;
	}

	/** In the morning a new night may be told again. */
	static void day() { told = false; }
}
