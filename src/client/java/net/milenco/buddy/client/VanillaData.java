package net.milenco.buddy.client;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The game's own data pack, read from the Minecraft jar on the classpath: the recipes, the loot tables and the tags of
 * {@code data/minecraft}. The client jar carries them for its integrated server, so the mod knows every recipe and every
 * drop without a word from the server (which since 1.21.2 sends a client only the recipes it has unlocked) and without
 * a file of Mojang's in git. The same code reads them in the tests, where the jar is on the test classpath too.
 */
final class VanillaData {
	private VanillaData() {}

	private static Path root;
	private static boolean tried;
	private static final Map<String, Map<String, JsonObject>> FOLDERS = new HashMap<>();
	private static final Map<String, Map<String, List<String>>> TAGS = new HashMap<>();

	/** {@code data/minecraft} inside the jar, or null when the classpath has no game data (logged once). */
	static synchronized Path root() {
		if (tried) return root;
		tried = true;
		try {
			URL u = VanillaData.class.getClassLoader().getResource("data/minecraft/recipe/stick.json");
			if (u == null) u = ClassLoader.getSystemResource("data/minecraft/recipe/stick.json");
			if (u == null) return null;
			URI uri = u.toURI();
			if ("jar".equals(uri.getScheme())) {
				FileSystem fs;
				try { fs = FileSystems.getFileSystem(uri); }
				catch (FileSystemNotFoundException e) { fs = FileSystems.newFileSystem(uri, Map.of()); }
				root = fs.getPath("/data/minecraft");
			} else {
				root = Paths.get(uri).getParent().getParent();
			}
		} catch (Exception e) {
			root = null;
		}
		return root;
	}

	static boolean present() { return root() != null; }

	/**
	 * Every JSON file under {@code data/minecraft/<folder>}, by its path inside that folder without {@code .json}
	 * ("iron_pickaxe", "blocks/iron_ore", "entities/sheep/white"). Read once.
	 */
	static synchronized Map<String, JsonObject> folder(String folder) {
		Map<String, JsonObject> have = FOLDERS.get(folder);
		if (have != null) return have;
		Map<String, JsonObject> out = new LinkedHashMap<>();
		Path r = root();
		if (r != null) {
			Path dir = r.resolve(folder);
			if (Files.isDirectory(dir)) {
				try (Stream<Path> files = Files.walk(dir)) {
					List<Path> list = files.filter(f -> f.toString().endsWith(".json")).sorted().toList();
					for (Path f : list) {
						String name = dir.relativize(f).toString().replace('\\', '/');
						name = name.substring(0, name.length() - 5);
						try (Reader rd = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
							JsonElement e = JsonParser.parseReader(rd);
							if (e.isJsonObject()) out.put(name, e.getAsJsonObject());
						} catch (IOException | RuntimeException ignored) {}
					}
				} catch (IOException ignored) {}
			}
		}
		Map<String, JsonObject> fixed = Collections.unmodifiableMap(out);
		FOLDERS.put(folder, fixed);
		return fixed;
	}

	/** A tag of this kind ("item", "block", "entity_type") resolved to plain ids without namespace, nested tags too. */
	static synchronized List<String> tag(String kind, String name) {
		Map<String, List<String>> tags = TAGS.computeIfAbsent(kind, k -> new HashMap<>());
		String key = plain(name.startsWith("#") ? name.substring(1) : name);
		List<String> have = tags.get(key);
		if (have != null) return have;
		Set<String> out = new java.util.LinkedHashSet<>();
		Set<String> seen = new HashSet<>();
		Deque<String> todo = new ArrayDeque<>();
		todo.add(key);
		Map<String, JsonObject> files = folder("tags/" + kind);
		while (!todo.isEmpty()) {
			String t = todo.poll();
			if (!seen.add(t)) continue;
			JsonObject f = files.get(t);
			if (f == null || !f.has("values")) continue;
			for (JsonElement v : f.getAsJsonArray("values")) {
				String id = v.isJsonObject() ? v.getAsJsonObject().get("id").getAsString() : v.getAsString();
				if (id.startsWith("#")) todo.add(plain(id.substring(1)));
				else out.add(plain(id));
			}
		}
		List<String> list = List.copyOf(out);
		tags.put(key, list);
		return list;
	}

	/** Every tag of this kind that holds the id, by name without namespace. */
	static List<String> tagsOf(String kind, String id) {
		List<String> out = new ArrayList<>();
		for (String t : folder("tags/" + kind).keySet()) if (tag(kind, t).contains(id)) out.add(t);
		return out;
	}

	/** "minecraft:iron_ingot" and "iron_ingot" both as "iron_ingot". */
	static String plain(String id) {
		return id.startsWith("minecraft:") ? id.substring(10) : id;
	}
}
