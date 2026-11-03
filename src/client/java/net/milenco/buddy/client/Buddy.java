package net.milenco.buddy.client;

import com.google.gson.JsonObject;

import net.minecraft.client.player.LocalPlayer;

/**
 * What a module ({@link BuddyModule}) may use of the core. Kept small on purpose: a module asks the core to walk, say
 * or type something, and reads its own settings; the core's own classes stay its own.
 */
public final class Buddy {
	private Buddy() {}

	/** The dimension the buddy is in, as "namespace:path", or "" without a world. */
	public static String dimension() {
		LocalPlayer p = AfkHelperClient.mc().player;
		return p == null ? "" : p.level().dimension().identifier().toString();
	}

	/**
	 * This module's settings in the profile of the server the buddy is on, or null when that profile does not name the module:
	 * then the module is off here.
	 */
	public static JsonObject settings(String moduleId) {
		Profiles.Profile p = Profiles.active();
		if (p.modules == null) return null;
		var e = p.modules.get(moduleId);
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
	}

	/** One of the profile's commands ("eventLeave", "home", ...) filled in, or null when the server has none. */
	public static String profileCommand(String which) { return Profiles.cmd(which, null); }

	/** Says a line in chat, or types a command when it starts with "/". */
	public static void send(String text) { AfkHelperClient.sendChat(text); }

	/** An event for the exchange folder (events.jsonl), written as given: a module writes English. */
	public static void emit(String type, Object... kv) { Exchange.emit(type, kv); }

	/**
	 * Walks the buddy to a block in a dimension, jumping where needed (the goto): outside that dimension the walk waits up to
	 * ten minutes, a teleport into it sets it going, and once the buddy leaves it again the walk ends and the following goes on as before.
	 */
	public static void walkTo(String dim, int x, int y, int z) {
		JsonObject o = new JsonObject();
		if (dim != null && !dim.isEmpty()) o.addProperty("dim", dim);
		o.addProperty("x", x);
		o.addProperty("y", y);
		o.addProperty("z", z);
		Follow.gotoCommand(o);
	}

	/**
	 * Another player in the buddy's world: where the server last put that player (not where the client draws the player,
	 * which trails the server's positions by the interpolation's ticks), whether on the ground, sprinting, and where looking.
	 */
	public record Other(String name, double x, double y, double z, boolean ground, boolean sprinting, float yaw) {}

	/** The other players in the buddy's world within this many blocks of the buddy. */
	public static java.util.List<Other> others(double within) {
		var mc = AfkHelperClient.mc();
		LocalPlayer me = mc.player;
		java.util.List<Other> out = new java.util.ArrayList<>();
		if (me == null || mc.level == null) return out;
		for (var pl : mc.level.players()) {
			if (pl == me || pl.distanceTo(me) > within) continue;
			var at = pl.getInterpolation().target();
			net.minecraft.world.phys.Vec3 pos = at != null ? at.position() : pl.position();
			out.add(new Other(pl.getGameProfile().name(), pos.x, pos.y, pos.z, pl.onGround(), pl.isSprinting(), pl.getYRot()));
		}
		return out;
	}

	/** The buddy, as {@link Other}: the buddy's own position, which is the one sent to the server. */
	public static Other self() {
		LocalPlayer me = AfkHelperClient.mc().player;
		if (me == null) return null;
		return new Other(me.getGameProfile().name(), me.getX(), me.getY(), me.getZ(), me.onGround(), me.isSprinting(), me.getYRot());
	}

	/** A line of a file of the module's own in the exchange folder (not events.jsonl), written as given, with the time. */
	public static void record(String file, Object... kv) { Exchange.line(file, kv); }

	/** Stops a walk of {@link #walkTo}. */
	public static void stopWalk() {
		JsonObject o = new JsonObject();
		o.addProperty("on", false);
		Follow.gotoCommand(o);
	}
}
