package net.milenco.buddy.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the alt lacks to help, for the player in the Telegram chat: no food, no weapon, no pickaxe for a tunnel, a full
 * inventory. One event a
 * message, {@code {"type":"helper","action":"lacks","text":...,"what":...}}, which the Telegram bridge posts in his chat; never
 * more than one in five minutes, all that is missing at that moment in it. The same need again only after half an
 * hour while it lasts, and once more when it went and came back.
 */
final class Needs {
	private Needs() {}

	/** Needs a check finds now (food, weapon, room): present for as long as the check says so. */
	private static final Map<String, String> checked = new LinkedHashMap<>();
	/** Needs a job ran into (no pickaxe for this ore): present until the moment in {@code until}. */
	private static final Map<String, String> wanted = new LinkedHashMap<>();
	private static final Map<String, Long> until = new HashMap<>();
	private static final Map<String, Long> told = new HashMap<>();
	private static long lastMessage;

	static final long GAP = 5 * 60_000L, AGAIN = 30 * 60_000L;

	/** "{me}" in a text is his own name: the core names no account. */
	private static String named(String text) {
		var p = AfkHelperClient.mc().player;
		return text.replace("{me}", p != null ? p.getGameProfile().name() : "The buddy");
	}

	/** A check: present or not. */
	static void set(String key, String text, boolean present) {
		if (present) checked.put(key, named(text));
		else if (checked.remove(key) != null && !wanted.containsKey(key)) told.remove(key);
	}

	/** A job wanted something it did not have: a need for the next ten minutes, longer if it keeps wanting it. */
	static void want(String key, String text) {
		text = named(text);
		wanted.put(key, text);
		until.put(key, System.currentTimeMillis() + 10 * 60_000L);
	}

	/** It was there after all (a chest gave it): no longer a need. */
	static void solved(String key) {
		wanted.remove(key);
		until.remove(key);
		if (!checked.containsKey(key)) told.remove(key);
	}

	/** Once a second: say what is missing, when it is time to. */
	static void tick() {
		long now = System.currentTimeMillis();
		until.entrySet().removeIf(e -> {
			if (e.getValue() > now) return false;
			wanted.remove(e.getKey());
			if (!checked.containsKey(e.getKey())) told.remove(e.getKey());
			return true;
		});
		if (now - lastMessage < GAP) return;
		Map<String, String> all = new LinkedHashMap<>(checked);
		all.putAll(wanted);
		List<String> keys = new ArrayList<>(), texts = new ArrayList<>();
		for (var e : all.entrySet()) {
			Long at = told.get(e.getKey());
			if (at != null && now - at < AGAIN) continue;
			keys.add(e.getKey());
			texts.add(e.getValue());
		}
		if (keys.isEmpty()) return;
		lastMessage = now;
		for (String k : keys) told.put(k, now);
		Exchange.emit("helper", "action", "lacks", "text", String.join(" ", texts), "what", String.join(",", keys));
	}

	/** What is missing now, for the web panel: the key and the text. */
	static List<Map<String, String>> list() {
		Map<String, String> all = new LinkedHashMap<>(checked);
		all.putAll(wanted);
		List<Map<String, String>> out = new ArrayList<>();
		for (var e : all.entrySet()) out.add(Map.of("key", e.getKey(), "text", e.getValue()));
		return out;
	}

	/** What is missing now, for the status. */
	static String summary() {
		Map<String, String> all = new LinkedHashMap<>(checked);
		all.putAll(wanted);
		return String.join(",", all.keySet());
	}
}
