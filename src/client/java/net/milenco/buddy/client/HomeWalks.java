package net.milenco.buddy.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.player.LocalPlayer;

/**
 * A walk on after a home, for a farm in another player's claim, where the buddy may set no home. The home stands just
 * outside the claim; arrived there by {@code /home <name>} or a click in the homes menu, the buddy walks on to the spot
 * kept for that home, on that server. Kept per server profile in {@code homewalks.json} in the exchange folder: a spot
 * is taught, never in the code.
 * <ul>
 * <li>{@code {"cmd":"homewalk","home":"iron","x":100,"y":64,"z":-200}}: keep it (the dimension he is in, or {@code dim});</li>
 * <li>{@code {"cmd":"homewalk","home":"iron","on":false}}: forget it;</li>
 * <li>{@code {"cmd":"homewalk"}}: the walks of this server.</li>
 * </ul>
 */
final class HomeWalks {
	private HomeWalks() {}

	private record Walk(String server, String home, String dim, int x, int y, int z) {}

	private static final List<Walk> walks = new ArrayList<>();
	private static boolean loaded;

	static void command(JsonObject o) {
		load();
		String server = Profiles.key();
		if (!o.has("home")) {
			JsonArray a = new JsonArray();
			for (Walk w : walks) if (w.server.equals(server)) a.add(w.home + " -> " + w.x + " " + w.y + " " + w.z + " (" + w.dim + ")");
			Exchange.emit("homewalk", "action", "list", "walks", a.toString());
			return;
		}
		String home = o.get("home").getAsString();
		walks.removeIf(w -> w.server.equals(server) && w.home.equalsIgnoreCase(home));
		boolean off = o.has("on") && !o.get("on").getAsBoolean();
		if (off) {
			save();
			Exchange.emit("homewalk", "action", "forgotten", "home", home);
			return;
		}
		if (!o.has("x") || !o.has("y") || !o.has("z")) { Exchange.emit("error", "msg", "homewalk: home, x, y, z"); return; }
		String dim = o.has("dim") ? o.get("dim").getAsString() : Buddy.dimension();
		Walk w = new Walk(server, home, dim, o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt());
		walks.add(w);
		save();
		Exchange.emit("homewalk", "action", "kept", "home", home, "x", w.x, "y", w.y, "z", w.z, "dim", dim);
	}

	/** Arrived at a home a second ago: walk on, when a walk is kept for it here and the home lies in its dimension. */
	static void arrived(String home) {
		load();
		LocalPlayer p = AfkHelperClient.mc().player;
		if (p == null || home == null) return;
		String server = Profiles.key(), dim = Buddy.dimension();
		for (Walk w : walks) {
			if (!w.server.equals(server) || !w.home.equalsIgnoreCase(home) || !w.dim.equals(dim)) continue;
			Buddy.walkTo(w.dim, w.x, w.y, w.z);
			Exchange.emit("homewalk", "action", "walks on", "home", home, "x", w.x, "y", w.y, "z", w.z);
			return;
		}
	}

	private static Path file() { return Paths.get(AfkConfig.get().exchangeDir).resolve("homewalks.json"); }

	private static void load() {
		if (loaded) return;
		loaded = true;
		try {
			Path f = file();
			if (!Files.exists(f)) return;
			for (JsonElement el : JsonParser.parseString(Files.readString(f)).getAsJsonArray()) {
				JsonObject o = el.getAsJsonObject();
				walks.add(new Walk(o.get("server").getAsString(), o.get("home").getAsString(), o.get("dim").getAsString(),
					o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt()));
			}
		} catch (Exception e) {
			Exchange.emit("error", "msg", "homewalks.json: " + e);
		}
	}

	private static void save() {
		JsonArray a = new JsonArray();
		for (Walk w : walks) {
			JsonObject o = new JsonObject();
			o.addProperty("server", w.server);
			o.addProperty("home", w.home);
			o.addProperty("dim", w.dim);
			o.addProperty("x", w.x);
			o.addProperty("y", w.y);
			o.addProperty("z", w.z);
			a.add(o);
		}
		try { Files.writeString(file(), a.toString()); } catch (Exception e) { Exchange.emit("error", "msg", "homewalks.json: " + e); }
	}
}
