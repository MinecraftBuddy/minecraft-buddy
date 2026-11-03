package net.milenco.buddy.tweakcraft;

import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.milenco.buddy.client.Buddy;
import net.milenco.buddy.client.BuddyModule;

/**
 * Parkour on Tweakcraft at a word, also as {@code !parkour} from Telegram. {@code {"cmd":"parkour"}} joins a Parkour
 * that players voted open and walks the buddy to the finish; {@code {"cmd":"parkour","on":false}} stops the walk and
 * leaves the game.
 * <ul>
 * <li>Only a voted game, which has no prize: a game of the half-hourly draw pays its winner, and the buddy is
 * faster than the players.</li>
 * <li>No Parkour open, or one of the draw: a refusal that says why, and nothing else.</li>
 * <li>The walk is the core's goto with a dimension ({@link Buddy#walkTo}): in the waiting room the buddy waits, the
 * game's teleport onto the start sets the walk going, and once the buddy is out of the dimension again the walk ends.</li>
 * </ul>
 * The course is the server's, so where it is comes from the server's profile:
 * <pre>"modules": {"parkour": {"dim": "events:arenas", "finish": [1676, 104, 1]}}</pre>
 * with, when the server ever words its lines or commands otherwise, {@code "join"} (the command, "/event join") and
 * regular expressions {@code "opened"}, {@code "voted"} and {@code "calledOff"}. The defaults are Tweakcraft's own lines
 * (the server's own event rules, in either language). Leaving is the profile's {@code eventLeave} command.
 *
 * <p>Every game of Parkour the buddy is in is recorded, to set the buddy's run against the players' and learn where a
 * run can be faster: from the game's start line ({@code "started"}) until the buddy leaves the dimension or two minutes
 * are up, each tick where the server put every player within 200 blocks and the buddy, to {@code parkour-runs.jsonl} in the exchange
 * folder: {@code run} (the start's time), {@code p}, {@code x}, {@code y}, {@code z}, {@code g} (on the ground),
 * {@code sp} (sprinting), {@code yaw}.
 */
public final class Parkour implements BuddyModule {
	private static final String OPENED = "Event: Parkour\\.";
	private static final String VOTED = "(Gekozen door.*voor de lol|Chosen by.*for fun)";
	private static final String CALLED_OFF = "Parkour (gaat niet door|is cancel+ed)";
	/** BattleArena's line when a game begins: "O---[{=====> Start <=====}]---O". */
	private static final String STARTED = "> Start <";
	private static final String RUNS = "parkour-runs.jsonl";
	private static final long RECORD_MS = 120_000;
	/** A game can be joined in the minute after it opened; a little less, so that the join is not too late. */
	private static final long OPEN_MS = 55_000;
	/** The vote line follows the open line in the same tick; a few seconds is room enough. */
	private static final long VOTE_WITHIN = 3000;

	private long openedAt, votedAt, calledOffAt, recordingSince;
	private int recordTick;

	@Override public String id() { return "parkour"; }

	@Override public void heard(String line) {
		JsonObject s = Buddy.settings(id());
		long now = System.currentTimeMillis();
		if (find(s, "opened", OPENED, line)) { openedAt = now; votedAt = 0; calledOffAt = 0; }
		else if (find(s, "voted", VOTED, line)) votedAt = now;
		else if (find(s, "calledOff", CALLED_OFF, line)) calledOffAt = now;
		else if (find(s, "started", STARTED, line) && inDim(s)) { recordingSince = now; recordTick = 0; }
	}

	@Override public void tick() {
		if (recordingSince == 0) return;
		JsonObject s = Buddy.settings(id());
		if (!inDim(s) || System.currentTimeMillis() - recordingSince > RECORD_MS) { recordingSince = 0; return; }
		recordTick++;
		Buddy.Other me = Buddy.self();
		if (me != null) write(me);
		for (Buddy.Other o : Buddy.others(200)) write(o);
	}

	private void write(Buddy.Other o) {
		Buddy.record(RUNS, "run", recordingSince, "tick", recordTick, "p", o.name(), "x", round(o.x()), "y", round(o.y()), "z", round(o.z()),
			"g", o.ground(), "sp", o.sprinting(), "yaw", Math.round(o.yaw() * 10) / 10.0);
	}

	private static double round(double v) { return Math.round(v * 1000) / 1000.0; }

	private static boolean inDim(JsonObject s) {
		String dim = s != null && s.has("dim") ? s.get("dim").getAsString() : "";
		return !dim.isEmpty() && dim.equals(Buddy.dimension());
	}

	@Override public boolean command(String name, JsonObject o) {
		if (!id().equals(name)) return false;
		JsonObject s = Buddy.settings(id());
		if (s == null) { refuse("not on this server"); return true; }
		boolean inDim;
		String dim = s.has("dim") ? s.get("dim").getAsString() : "";
		inDim = !dim.isEmpty() && dim.equals(Buddy.dimension());
		if (isOff(o)) {
			Buddy.stopWalk();
			String leave = Buddy.profileCommand("eventLeave");
			if (inDim && leave != null) Buddy.send(leave);
			Buddy.emit("parkour", "action", "stop", "out of the game", inDim && leave != null);
			return true;
		}
		int[] finish = finish(s);
		if (finish == null || dim.isEmpty()) { refuse("no finish set"); return true; }
		long now = System.currentTimeMillis();
		boolean open = openedAt > 0 && now - openedAt < OPEN_MS && calledOffAt < openedAt;
		boolean voted = openedAt > 0 && votedAt >= openedAt && votedAt - openedAt < VOTE_WITHIN;
		// in the game already (joined by hand): only the walk, and only when the last Parkour opened was voted open
		if (!inDim) {
			if (!open) { refuse(openedAt > 0 && now - openedAt < 600_000 && calledOffAt < openedAt ? "already begun" : "no parkour open"); return true; }
			if (!voted) { refuse("with a prize"); return true; }
			Buddy.send(s.has("join") ? s.get("join").getAsString() : "/event join");
		} else if (!voted) { refuse("with a prize"); return true; }
		Buddy.walkTo(dim, finish[0], finish[1], finish[2]);
		Buddy.emit("parkour", "action", "joins", "already in the game", inDim,
			"starts in", inDim ? 0 : Math.max(0, (60_000 - (now - openedAt)) / 1000));
		return true;
	}

	private static boolean isOff(JsonObject o) {
		if (o.has("on") && !o.get("on").getAsBoolean()) return true;
		return false;
	}

	private static int[] finish(JsonObject s) {
		if (!s.has("finish") || !s.get("finish").isJsonArray()) return null;
		JsonArray a = s.getAsJsonArray("finish");
		if (a.size() != 3) return null;
		return new int[] { a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt() };
	}

	/** A line of the server's, by the profile's own expression for it when it gives one. */
	private static boolean find(JsonObject s, String key, String fallback, String line) {
		String re = s != null && s.has(key) ? s.get(key).getAsString() : fallback;
		return Pattern.compile(re).matcher(line).find();
	}

	private static void refuse(String why) {
		Buddy.emit("parkour", "action", "refused", "reason", why);
	}
}
