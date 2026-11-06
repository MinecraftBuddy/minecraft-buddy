package net.milenco.buddy.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The difference between two positions frames (the JSON of {@code world3d-ent.json}), for the run recording's D records
 * ({@link RunRecorder}), and its applying: {@code apply(a, encode(a, b))} equals {@code b} as JSON (objects compared
 * without their key order, lists with it).
 * <p>
 * A delta is an object with the top-level keys whose value changed (or came), each with its new value, and {@code "-"}:
 * the names of the keys that went. {@code entities} and {@code players}, lists of objects keyed by their {@code id}, get
 * an object of their own instead of the whole list: {@code "+"} the objects that came (whole), {@code "-"} the ids that
 * went, {@code "~"} by id (as a string) the fields that changed with their new values, and in it {@code "-"} the names of
 * the fields that went; {@code "order"}, all ids in the new order, only when removing, patching in place and appending
 * the new ones at the end does not give the new order. A list value that is an array replaces the list (a list whose
 * items are not all objects with a distinct id).
 */
final class FrameDelta {
	private FrameDelta() {}

	/** The lists of objects with an id that go by their parts. */
	static final Set<String> LISTS = Set.of("entities", "players");

	/** What changed from {@code prev} to {@code cur}. */
	static JsonObject encode(JsonObject prev, JsonObject cur) {
		JsonObject d = new JsonObject();
		for (var e : cur.entrySet()) {
			String k = e.getKey();
			JsonElement v = e.getValue(), was = prev.get(k);
			if (v.equals(was)) continue;
			if (LISTS.contains(k) && was != null && was.isJsonArray() && v.isJsonArray()) {
				JsonObject ld = list(was.getAsJsonArray(), v.getAsJsonArray());
				d.add(k, ld != null ? ld : v);
				continue;
			}
			d.add(k, v);
		}
		JsonArray gone = new JsonArray();
		for (String k : prev.keySet()) if (!cur.has(k)) gone.add(k);
		if (!gone.isEmpty()) d.add("-", gone);
		return d;
	}

	/** {@code prev} with the delta applied, as a new object ({@code prev} stays as it was). */
	static JsonObject apply(JsonObject prev, JsonObject d) {
		JsonObject out = prev.deepCopy();
		for (var e : d.entrySet()) {
			String k = e.getKey();
			JsonElement v = e.getValue();
			if (k.equals("-")) continue;
			JsonElement was = out.get(k);
			if (LISTS.contains(k) && v.isJsonObject() && was != null && was.isJsonArray()) out.add(k, applyList(was.getAsJsonArray(), v.getAsJsonObject()));
			else out.add(k, v.deepCopy());
		}
		if (d.has("-")) for (JsonElement k : d.getAsJsonArray("-")) out.remove(k.getAsString());
		return out;
	}

	/** A list's objects by id (as a string), in order; null when one is not an object with an id, or two share one. */
	private static Map<String, JsonObject> byId(JsonArray a) {
		Map<String, JsonObject> out = new LinkedHashMap<>();
		for (JsonElement el : a) {
			if (!el.isJsonObject()) return null;
			JsonElement id = el.getAsJsonObject().get("id");
			if (id == null || !id.isJsonPrimitive()) return null;
			if (out.put(id.getAsString(), el.getAsJsonObject()) != null) return null;
		}
		return out;
	}

	private static JsonObject list(JsonArray was, JsonArray now) {
		Map<String, JsonObject> old = byId(was), cur = byId(now);
		if (old == null || cur == null) return null;
		JsonArray plus = new JsonArray(), minus = new JsonArray();
		JsonObject changed = new JsonObject();
		for (var e : cur.entrySet()) {
			JsonObject o = old.get(e.getKey());
			if (o == null) plus.add(e.getValue());
			else if (!o.equals(e.getValue())) changed.add(e.getKey(), fields(o, e.getValue()));
		}
		for (var e : old.entrySet()) if (!cur.containsKey(e.getKey())) minus.add(e.getValue().get("id"));
		JsonObject out = new JsonObject();
		if (!plus.isEmpty()) out.add("+", plus);
		if (!minus.isEmpty()) out.add("-", minus);
		if (!changed.isEmpty()) out.add("~", changed);
		// the order that applying gives: the old ones that stay, then the new ones
		List<String> after = new ArrayList<>();
		for (String id : old.keySet()) if (cur.containsKey(id)) after.add(id);
		for (String id : cur.keySet()) if (!old.containsKey(id)) after.add(id);
		if (!after.equals(new ArrayList<>(cur.keySet()))) {
			JsonArray order = new JsonArray();
			for (JsonObject o : cur.values()) order.add(o.get("id"));
			out.add("order", order);
		}
		return out;
	}

	/** The fields of one object that changed, with {@code "-"} for those that went. */
	private static JsonObject fields(JsonObject was, JsonObject now) {
		JsonObject out = new JsonObject();
		for (var e : now.entrySet()) if (!e.getValue().equals(was.get(e.getKey()))) out.add(e.getKey(), e.getValue());
		JsonArray gone = new JsonArray();
		for (String k : was.keySet()) if (!now.has(k)) gone.add(k);
		if (!gone.isEmpty()) out.add("-", gone);
		return out;
	}

	private static JsonArray applyList(JsonArray was, JsonObject d) {
		Set<String> minus = new HashSet<>();
		if (d.has("-")) for (JsonElement id : d.getAsJsonArray("-")) minus.add(id.getAsString());
		JsonObject changed = d.has("~") ? d.getAsJsonObject("~") : new JsonObject();
		List<JsonObject> out = new ArrayList<>();
		for (JsonElement el : was) {
			JsonObject o = el.getAsJsonObject();
			String id = o.get("id").getAsString();
			if (minus.contains(id)) continue;
			out.add(changed.has(id) ? patch(o, changed.getAsJsonObject(id)) : o.deepCopy());
		}
		if (d.has("+")) for (JsonElement el : d.getAsJsonArray("+")) out.add(el.getAsJsonObject().deepCopy());
		if (d.has("order")) {
			Map<String, JsonObject> by = new HashMap<>();
			for (JsonObject o : out) by.put(o.get("id").getAsString(), o);
			out.clear();
			for (JsonElement id : d.getAsJsonArray("order")) { JsonObject o = by.get(id.getAsString()); if (o != null) out.add(o); }
		}
		JsonArray res = new JsonArray();
		out.forEach(res::add);
		return res;
	}

	private static JsonObject patch(JsonObject o, JsonObject f) {
		JsonObject out = o.deepCopy();
		for (var e : f.entrySet()) if (!e.getKey().equals("-")) out.add(e.getKey(), e.getValue().deepCopy());
		if (f.has("-")) for (JsonElement k : f.getAsJsonArray("-")) out.remove(k.getAsString());
		return out;
	}
}
