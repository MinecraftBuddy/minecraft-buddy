package net.milenco.buddy.client;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.core.BlockPos;

/**
 * His own best time per walk, a personal best to test and improve the pathfinding (no parkour feature of its own). A
 * {@code ga} that arrives gives its time, from the command to the last spot; the route is the dimension, the block he
 * stood on when the command came (in steps of two, so that a start a block off counts as the same) and the last spot.
 * Kept in the exchange folder as {@code routes.json}; the event {@code ga} {@code aangekomen} carries {@code ms},
 * {@code pb} (a new best), {@code best} and {@code runs}.
 */
final class Routes {
	private Routes() {}

	static void arrived(String dim, BlockPos from, BlockPos to, long ms) {
		if (from == null || to == null) { Exchange.emit("goto", "action", "arrived", "ms", ms); return; }
		String key = (dim == null ? "?" : dim) + " " + (from.getX() >> 1 << 1) + "," + from.getY() + "," + (from.getZ() >> 1 << 1)
			+ " -> " + to.getX() + "," + to.getY() + "," + to.getZ();
		Path f = Paths.get(AfkConfig.get().exchangeDir).resolve("routes.json");
		JsonObject all;
		try { all = Files.exists(f) ? JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject() : new JsonObject(); }
		catch (Exception e) { all = new JsonObject(); }
		JsonObject r = all.has(key) ? all.getAsJsonObject(key) : new JsonObject();
		long best = r.has("best") ? r.get("best").getAsLong() : Long.MAX_VALUE;
		int runs = (r.has("runs") ? r.get("runs").getAsInt() : 0) + 1;
		boolean pb = ms < best;
		if (pb) { best = ms; r.addProperty("best", ms); r.addProperty("bestAt", System.currentTimeMillis()); }
		r.addProperty("runs", runs);
		r.addProperty("last", ms);
		all.add(key, r);
		try {
			Path tmp = f.resolveSibling("routes.json.tmp");
			Files.writeString(tmp, all.toString(), StandardCharsets.UTF_8);
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception e) { Exchange.emit("error", "msg", "routes.json: " + e.getMessage()); }
		Exchange.emit("goto", "action", "arrived", "ms", ms, "pb", pb, "best", best, "runs", runs, "route", key);
	}
}
