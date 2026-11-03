package net.milenco.buddy.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** File exchange with the watcher: events.jsonl (mod -> watcher) and commands.jsonl (watcher -> mod). */
public class Exchange {
	private static final Gson GSON = new Gson();
	private static long cmdOffset = -1;

	private static Path dir() { return Paths.get(AfkConfig.get().exchangeDir); }
	public static Path events() { return dir().resolve("events.jsonl"); }
	public static Path commands() { return dir().resolve("commands.jsonl"); }

	/** At start-up: empty the events (or archive them first), set the command offset to the end (ignore old commands). */
	public static void init() {
		try {
			Files.createDirectories(dir());
			if (AfkConfig.get().keepEvents) archive();
			Files.writeString(events(), "", StandardCharsets.UTF_8);
			// what the mod wrote until it was English at the source (0.4.166): the Dutch copy of the events, with old chat
			// in it, and the table between the Dutch names and the English ones
			Files.deleteIfExists(dir().resolve("events-nl.jsonl"));
			Files.deleteIfExists(dir().resolve("protocol.json"));
			Path c = commands();
			if (!Files.exists(c)) Files.writeString(c, "", StandardCharsets.UTF_8);
			cmdOffset = Files.size(c);
		} catch (IOException ignored) { }
	}

	/**
	 * Put the previous session gzipped in events-archive/. Without this everything of the alt was gone
	 * after a restart: that way we could no longer find out what it had died of on 22 Sep.
	 */
	private static void archive() {
		Path ev = events();
		try {
			if (!Files.exists(ev) || Files.size(ev) == 0) return;
			Path folder = dir().resolve("events-archive");
			Files.createDirectories(folder);
			String name = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date(Files.getLastModifiedTime(ev).toMillis()));
			try (var in = Files.newInputStream(ev); var out = new java.util.zip.GZIPOutputStream(Files.newOutputStream(folder.resolve("events-" + name + ".jsonl.gz")))) {
				in.transferTo(out);
			}
		} catch (IOException ignored) { }
	}

	/** One event, as it is written: the code names its events, fields and reasons in English (since 0.4.166). */
	public static void emit(String type, Map<String, Object> fields) {
		long t = System.currentTimeMillis();
		JsonObject o = new JsonObject();
		o.addProperty("t", t);
		o.addProperty("type", type);
		if (fields != null) for (Map.Entry<String, Object> e : fields.entrySet()) o.add(e.getKey(), GSON.toJsonTree(e.getValue()));
		try {
			Files.writeString(events(), GSON.toJson(o) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException ignored) { }
	}

	public static void emit(String type, Object... kv) {
		java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
		for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
		emit(type, m);
	}

	/**
	 * A line of a file of its own in the exchange folder, as given (no Protocol: a module writes English), with the time:
	 * for what would drown events.jsonl, such as a position every tick.
	 */
	public static void line(String file, Object... kv) {
		JsonObject o = new JsonObject();
		o.addProperty("t", System.currentTimeMillis());
		for (int i = 0; i + 1 < kv.length; i += 2) o.add(String.valueOf(kv[i]), GSON.toJsonTree(kv[i + 1]));
		try {
			Files.writeString(dir().resolve(file), GSON.toJson(o) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException ignored) { }
	}

	/** Reads the new lines of commands.jsonl since last time. */
	public static List<JsonObject> pollCommands() {
		List<JsonObject> out = new ArrayList<>();
		Path c = commands();
		try {
			if (!Files.exists(c)) return out;
			long size = Files.size(c);
			if (cmdOffset < 0 || size < cmdOffset) cmdOffset = 0;
			if (size == cmdOffset) return out;
			try (RandomAccessFile raf = new RandomAccessFile(c.toFile(), "r")) {
				raf.seek(cmdOffset);
				byte[] buf = new byte[(int) (size - cmdOffset)];
				raf.readFully(buf);
				String chunk = new String(buf, StandardCharsets.UTF_8);
				int lastNl = chunk.lastIndexOf('\n');
				if (lastNl < 0) return out;               // incomplete line, next time
				chunk = chunk.substring(0, lastNl + 1);
				cmdOffset += chunk.getBytes(StandardCharsets.UTF_8).length;
				for (String line : chunk.split("\n")) {
					line = line.trim();
					if (line.isEmpty()) continue;
					try { out.add(JsonParser.parseString(line).getAsJsonObject()); } catch (Exception ignored) { }
				}
			}
		} catch (IOException ignored) { }
		return out;
	}
}
