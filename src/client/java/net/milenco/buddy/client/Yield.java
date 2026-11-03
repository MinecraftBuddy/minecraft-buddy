package net.milenco.buddy.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What the farms bring in, kept for good in yield.jsonl in the exchange folder: every five minutes one line per
 * farm with what came on top in that stretch. The farms' own counters start at zero at every start and every restart
 * of the game, so they cannot tell what the buddy harvested last night; this file can, and the Telegram bridge adds it up
 * per hour and per day.
 *
 * It reads the counters the farms already keep, once a minute, and books what they rose by. A counter that went down
 * started again; then all of it is new. At most the last minute before a restart is lost.
 */
public final class Yield {
	private Yield() {}

	private static final Gson GSON = new Gson();
	private static final long SAMPLE_MS = 60_000, WRITE_MS = 300_000;
	private static final String[] FARMS = { "wheat", "crop", "mob", "raid", "wood", "pen" };

	private static long nextSample = 0, periodStart = 0, lastSampleAt = 0;
	/** Last value seen per counter; keyed by where it comes from, so a raid farm that becomes a mob farm does not count twice. */
	private static final Map<String, Long> seen = new HashMap<>();
	/** What came on top since the last line, per farm and counter. */
	private static final Map<String, Map<String, Long>> pending = new TreeMap<>();
	private static final Map<String, Long> activeMs = new HashMap<>(), onSince = new HashMap<>();

	private static Path file() { return Path.of(AfkConfig.get().exchangeDir, "yield.jsonl"); }

	private static boolean raid() { return AfkConfig.get().raidMode; }

	static boolean on(String farm) {
		return switch (farm) {
			case "wheat" -> WheatFarm.isOn();
			case "crop" -> CropFarmer.isOn();
			case "mob" -> Grinder.active() && !raid();
			case "raid" -> Grinder.active() && raid();
			case "wood" -> Lumberjack.isOn();
			case "pen" -> Pen.on();
			default -> false;
		};
	}

	/** {farm, counter name, source, value}: the source keys the last value seen. */
	private static List<Object[]> counters() {
		String grinder = raid() ? "raid" : "mob";
		List<Object[]> c = new ArrayList<>();
		c.add(new Object[] { "wheat", "harvested", "wheat.harvested", (long) WheatFarm.harvested() });
		c.add(new Object[] { "wheat", "planted", "wheat.planted", (long) WheatFarm.planted() });
		c.add(new Object[] { "wheat", "hay", "wheat.hay", (long) WheatFarm.hay() });
		c.add(new Object[] { "crop", "harvested", "crop.harvested", (long) CropFarmer.harvested() });
		c.add(new Object[] { "crop", "planted", "crop.planted", (long) CropFarmer.planted() });
		c.add(new Object[] { grinder, "killed", "grinder.killed", (long) Grinder.killed() });
		c.add(new Object[] { grinder, "hits", "grinder.hits", (long) Grinder.hits() });
		c.add(new Object[] { "wood", "logs", "wood.logs", (long) Lumberjack.logs() });
		// the pen's counters run on over its starts within one game (a counter that went down would count as a new start)
		c.add(new Object[] { "pen", "killed", "pen.killed", (long) Pen.killed() });
		c.add(new Object[] { "pen", "hits", "pen.hits", (long) Pen.hits() });
		return c;
	}

	private static void sample(long now) {
		for (Object[] k : counters()) {
			String source = (String) k[2];
			long value = (Long) k[3], before = seen.getOrDefault(source, 0L);
			long delta = value >= before ? value - before : value;
			seen.put(source, value);
			if (delta > 0) pending.computeIfAbsent((String) k[0], f -> new TreeMap<>()).merge((String) k[1], delta, Long::sum);
		}
		for (String farm : FARMS) {
			if (on(farm)) {
				if (lastSampleAt > 0) activeMs.merge(farm, now - lastSampleAt, Long::sum);
				onSince.putIfAbsent(farm, now);
			} else onSince.remove(farm);
		}
		lastSampleAt = now;
	}

	/** Every client tick: a look at the counters once a minute, a line in the file every five minutes. */
	static void tick() {
		long now = System.currentTimeMillis();
		if (now < nextSample) return;
		nextSample = now + SAMPLE_MS;
		if (periodStart == 0) periodStart = now;
		sample(now);
		if (now - periodStart >= WRITE_MS) write(now);
	}

	/** On the way out of the game: what is still open goes into the file. */
	static void flush() {
		long now = System.currentTimeMillis();
		if (periodStart == 0) return;
		sample(now);
		write(now);
	}

	private static void write(long now) {
		StringBuilder out = new StringBuilder();
		for (String farm : FARMS) {
			Map<String, Long> p = pending.get(farm);
			long active = activeMs.getOrDefault(farm, 0L);
			if ((p == null || p.isEmpty()) && active == 0) continue;
			JsonObject o = new JsonObject();
			o.addProperty("t", now);
			o.addProperty("farm", farm);
			o.addProperty("sec", (now - periodStart) / 1000);
			o.addProperty("active_s", active / 1000);
			if (p != null) for (var e : p.entrySet()) o.addProperty(e.getKey(), e.getValue());
			out.append(GSON.toJson(o)).append('\n');
		}
		pending.clear();
		activeMs.clear();
		periodStart = now;
		if (out.isEmpty()) return;
		try { Files.writeString(file(), out.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
		catch (IOException ignored) { }
	}

	/**
	 * Answer to the command farmstatus (from the Telegram bridge): per running farm, or the one asked for, how long it
	 * runs, what is not in the file yet, and where it is. The bridge adds the file to that for the hour and the day.
	 */
	static void status(String which) {
		long now = System.currentTimeMillis();
		sample(now);
		List<Map<String, Object>> farms = new ArrayList<>();
		for (String farm : FARMS) {
			boolean running = on(farm);
			if (which == null ? !running : !farm.equals(which)) continue;
			Map<String, Object> f = new LinkedHashMap<>();
			f.put("farm", farm);
			f.put("on", running);
			f.put("on_since", onSince.getOrDefault(farm, 0L));
			f.put("not_in_file_yet", pending.getOrDefault(farm, Map.of()));
			f.put("open_s", (now - periodStart) / 1000);
			switch (farm) {
				case "wheat" -> f.putAll(WheatFarm.statusFields());
				case "raid" -> { f.put("round", RaidState.round()); f.put("wave", RaidState.wave()); f.put("busy", RaidState.inProgress()); }
				case "wood" -> f.put("modules", Lumberjack.modules());
				default -> { }
			}
			farms.add(f);
		}
		Exchange.emit("farmstatus", "asked", which == null ? "" : which, "farms", farms);
	}
}
