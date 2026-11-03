package net.milenco.buddy.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistent log of the raid farm: every bottle and every raid, with date.
 * events.jsonl starts over at every client start and Minecraft's logs get cleaned up,
 * so what you did yesterday can no longer be recovered from there. This file stays.
 */
public class RaidLog {
	private static final Gson GSON = new Gson();

	private static Path file() { return Path.of(AfkConfig.get().exchangeDir, "raids.jsonl"); }

	private static void write(JsonObject o) {
		o.addProperty("t", System.currentTimeMillis());
		try { Files.writeString(file(), GSON.toJson(o) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
		catch (IOException ignored) { }
	}

	public static void bottle(int level) {
		JsonObject o = new JsonObject();
		o.addProperty("kind", "bottle");
		o.addProperty("level", level);
		write(o);
	}

	public static void raid(long started, int waves, int level, boolean won) {
		JsonObject o = new JsonObject();
		o.addProperty("kind", "raid");
		o.addProperty("started", started);
		o.addProperty("duration_s", Math.max(0, (System.currentTimeMillis() - started) / 1000));
		o.addProperty("waves", waves);
		o.addProperty("level", level);
		o.addProperty("won", won);
		write(o);
	}

	private static List<JsonObject> read() {
		List<JsonObject> out = new ArrayList<>();
		try {
			if (!Files.exists(file())) return out;
			for (String ln : Files.readAllLines(file(), StandardCharsets.UTF_8)) {
				if (ln.isBlank()) continue;
				try { out.add(GSON.fromJson(ln, JsonObject.class)); } catch (Exception ignored) { }
			}
		} catch (IOException ignored) { }
		return out;
	}

	/** When a bottle was last drunk, also from before a restart. */
	public static long lastBottle() {
		long t = 0;
		for (JsonObject o : read())
			if (o.has("kind") && o.get("kind").getAsString().equals("bottle")) t = Math.max(t, o.get("t").getAsLong());
		return t;
	}

	/**
	 * How many waves a won raid with this omen level had before.
	 * More reliable than calculating from the difficulty: servers deviate from vanilla.
	 * Only the last few raids count: older ones are still in there with the readings of the
	 * broken wave counter, and those were too high. Within those few we take the highest, because
	 * a raid we missed halfway counts too low.
	 */
	public static int learnedWaves(int level) {
		List<Integer> recent = new ArrayList<>();
		for (JsonObject o : read()) {
			if (!o.has("kind") || !o.get("kind").getAsString().equals("raid")) continue;
			if (!o.has("won") || !o.get("won").getAsBoolean()) continue;
			if (!o.has("level") || o.get("level").getAsInt() != level) continue;
			if (o.has("waves")) recent.add(o.get("waves").getAsInt());
		}
		int max = 0;
		for (int i = Math.max(0, recent.size() - 5); i < recent.size(); i++) max = Math.max(max, recent.get(i));
		return max;
	}

}
