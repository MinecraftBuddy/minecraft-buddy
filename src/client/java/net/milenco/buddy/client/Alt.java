package net.milenco.buddy.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A second account that runs on a server without a screen. Nobody is at the controls, so the mod
 * respawns it after a death, logs what happens to it, and listens to commands sent by PM from trusted
 * players.
 *
 * Commands by PM hang on pmCommands, which is off by default.
 */
public final class Alt {
	private Alt() {}

	// ---------- crouching on request ----------

	private static final java.util.Map<String, Boolean> crouchedBefore = new java.util.HashMap<>();

	private static boolean crouchKeyHeld = false;

	/**
	 * Set the sneak key every tick, like with jumping, always while crouching is on: being seen not
	 * crouched stands out. At first the AFK loop did that, but it only crouches on the ground and let go
	 * of the key in the air; after that it waited for arrival at the home, which never came after a
	 * cancelled /home.
	 */
	private static void crouchTick(Minecraft mc) {
		AfkConfig c = AfkConfig.get();
		// no crouching while chopping wood or farming: the sneak key slows the walk and holds the alt back
		// when walking along the rows. Those modules set shift themselves when needed.
		// Nor while building: crouched the alt walked so slowly that the walk steps gave up, and a crouched click on a chest
		// places a block against it instead of opening it. The builder crouches itself at an edge.
		// The mod being on, not AFK mode: on some servers the alt runs with AFK mode off.
		// Nor while following someone: crouched the alt falls behind, and crouches again once beside the player.
		boolean want = c.altCrouched && c.altCrouchAlong && AfkHelperClient.active() && !Jobs.anyOn(Jobs.Job.FOLLOW) && !Follow.walking();
		if (want) { HeldKeys.set(mc.options.keyShift, true); crouchKeyHeld = true; }
		else if (crouchKeyHeld) { HeldKeys.set(mc.options.keyShift, false); crouchKeyHeld = false; }
	}

	private static void setCrouch(boolean on, String by) {
		AfkConfig c = AfkConfig.get();
		if (c.altCrouched == on) return;
		c.altCrouched = on; AfkConfig.save();
		Exchange.emit("alt", "action", on ? "crouch" : "get up", "by", by);
	}

	/**
	 * A trusted player nearby starts crouching: then the alt toggles, upright becomes crouched and the
	 * other way round. Only the moment of starting to crouch counts; if it kept following as long as the player
	 * stayed crouched, the alt went up and down at every sneaking step of your friend.
	 */
	private static void followCrouch(Minecraft mc, LocalPlayer me) {
		AfkConfig c = AfkConfig.get();
		if (!c.altCrouchAlong || !AfkHelperClient.active() || mc.level == null) { crouchedBefore.clear(); return; }
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (Player o : mc.level.players()) {
			if (o == me) continue;
			String name = o.getGameProfile().name();
			if (!c.isTrusted(name) || o.distanceTo(me) > 8) continue;
			seen.add(name);
			boolean now = o.isCrouching();
			Boolean was = crouchedBefore.put(name, now);
			if (was != null && !was && now) setCrouch(!c.altCrouched, name);
		}
		crouchedBefore.keySet().retainAll(seen);
	}

	// ---------- logging of damage and death ----------

	private static float previousHealth = -1;

	/**
	 * Every time the alt loses health: how much, which kind of damage, by whom or what, and what is around
	 * at that moment. On 22 Sep the alt went from 20 to 0 health in under 10 s without a death message, and afterwards
	 * it was impossible to find out why.
	 */
	private static void damageTick(Minecraft mc, LocalPlayer p) {
		float now = p.getHealth();
		float was = previousHealth;
		previousHealth = now;
		if (was < 0 || now >= was - 0.01f) return;
		var source = p.getLastDamageSource();
		String kind = source == null ? "unknown" : source.type().msgId();
		String by = source == null || source.getEntity() == null ? "" : who(source.getEntity());
		String direct = source == null || source.getDirectEntity() == null ? "" : who(source.getDirectEntity());
		List<String> surroundings = new ArrayList<>();
		if (mc.level != null) for (var e : mc.level.getEntities(p, p.getBoundingBox().inflate(16)))
			if (e instanceof net.minecraft.world.entity.LivingEntity) surroundings.add(who(e) + " " + Math.round(e.distanceTo(p)) + "m");
		Exchange.emit("damage", "from", was, "to", now, "kind", kind, "by", by, "at once", direct,
			"x", p.getBlockX(), "y", p.getBlockY(), "z", p.getBlockZ(), "air", p.getAirSupply(),
			"under_water", p.isUnderWater(), "burning", p.isOnFire(), "lava", p.isInLava(), "surroundings", String.join(", ", surroundings));
	}

	private static long nextPlayers = 0;
	private static boolean playersLastTime = false;

	/**
	 * Every 5 s who is within 48 blocks and how far, as long as someone is there; when the last one
	 * leaves, one more empty line. Player notifications are off on the alt, so without this you could
	 * not see afterwards who was with the alt when something happened.
	 */
	private static void playersTick(Minecraft mc, LocalPlayer p, long now) {
		if (now < nextPlayers || mc.level == null) return;
		nextPlayers = now + 5000;
		List<String> list = new ArrayList<>();
		for (var o : mc.level.players()) {
			if (o == p) continue;
			double d = o.distanceTo(p);
			if (d <= 48) list.add(o.getGameProfile().name() + " " + Math.round(d) + "m");
		}
		if (list.isEmpty() && !playersLastTime) return;
		playersLastTime = !list.isEmpty();
		Exchange.emit("players_near", "count", list.size(), "list", String.join(", ", list));
	}

	private static long nextTab = 0;
	private static java.util.Set<String> online = null;

	/**
	 * New connection (join, reconnect): the player list then arrives in one go. That only
	 * counts as the starting state, otherwise every restart produced 50 joins at once.
	 */
	static void newConnection() { online = null; }

	/**
	 * Who joins and leaves the server, from the player list (tab). The chat does not report that: only
	 * players with the LEGACY rank get a line, and the server does not report leaving at all.
	 */
	private static void tabTick(Minecraft mc, long now) {
		if (now < nextTab) return;
		nextTab = now + 5000;
		var con = mc.getConnection();
		if (con == null) { online = null; return; }
		java.util.Set<String> current = new java.util.HashSet<>();
		for (var info : con.getOnlinePlayers()) {
			String name = info.getProfile().name();
			if (!name.isBlank()) current.add(name);
		}
		if (current.isEmpty()) return;
		// the starting state in one line: the Telegram bridge needs to know who is online, not only who comes and goes
		if (online == null) Exchange.emit("players", "names", String.join(" ", new java.util.TreeSet<>(current)), "online", current.size());
		if (online != null) {
			for (String n : current) if (!online.contains(n)) Exchange.emit("player_join", "name", n, "online", current.size());
			for (String n : online) if (!current.contains(n)) Exchange.emit("player_leave", "name", n, "online", current.size());
		}
		online = current;
	}

	private static String who(net.minecraft.world.entity.Entity e) {
		String type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
		String name = e.getName().getString();
		return name.equalsIgnoreCase(type.replace('_', ' ')) ? type : type + "(" + name + ")";
	}

	/** The death screen opens: log the cause (AFTER_INIT fires more than once per screen, so once per screen). */
	private static Object loggedScreen = null;

	static void register() {
		net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (!(screen instanceof net.minecraft.client.gui.screens.DeathScreen) || screen == loggedScreen) return;
			loggedScreen = screen;
			var cause = ((net.milenco.buddy.mixin.client.DeathScreenAccessor) screen).buddy$causeOfDeath();
			LocalPlayer p = client.player;
			// a death screen without a cause comes when one logs in dead (the server sends no message then): not a death of its
			// own, and it filled the Activity tab with empty lines
			if (cause == null || cause.getString().isBlank()) return;
			Exchange.emit("death_cause", "text", cause.getString(),
				"x", p == null ? 0 : p.getBlockX(), "y", p == null ? 0 : p.getBlockY(), "z", p == null ? 0 : p.getBlockZ());
		});
	}

	// ---------- death: respawn ----------

	private static boolean respawnPlanned = false;

	/**
	 * Dead: respawn after 4-10 s. Without this the alt lay on the death screen for over five hours on 22 Sep:
	 * without a screen nobody presses Respawn. Not tied to AFK mode: on some servers the alt runs with AFK mode
	 * off, and a watcher outside the game had to switch it on for a moment to get the alt up again.
	 */
	private static void deathTick(LocalPlayer p) {
		// while someone plays the alt from the panel, the death screen waits for that person's Respawn, as the game's does;
		// once the panel lets go, the alt gets up unaided again
		if (respawnPlanned || !p.isDeadOrDying() || !AfkHelperClient.active() || Steer.active()) return;
		respawnPlanned = true;
		double wait = rnd(4, 10);
		Exchange.emit("alt", "action", "dead", "respawn_in_s", Math.round(wait));
		AfkHelperClient.later(wait, () -> {
			LocalPlayer q = AfkHelperClient.mc().player;
			if (q == null || !q.isDeadOrDying() || Steer.active()) { respawnPlanned = false; return; }
			q.respawn();
			respawnPlanned = false;
			Exchange.emit("alt", "action", "respawn");
		});
	}

	// ---------- jumping on request ----------

	private static boolean jumpKeyHeld = false;

	/**
	 * Hold the jump key as long as jumping is on, like someone keeping space pressed. Every tick again,
	 * because the key is also set by normal input; releasing happens only once, otherwise the alt could
	 * never stay off the key by itself again.
	 */
	private static void jumpTick(Minecraft mc) {
		AfkConfig c = AfkConfig.get();
		// the mod being on, not AFK mode, as with crouching: the alt may run with AFK mode off
		// not while following: the walk jumps where the way goes up, and a held key made the alt hop the whole way
		boolean want = c.altJumping && AfkHelperClient.active() && !Jobs.anyOn(Jobs.Job.FOLLOW) && !Follow.walking();
		if (want) { HeldKeys.set(mc.options.keyJump, true); jumpKeyHeld = true; }
		else if (jumpKeyHeld) { HeldKeys.set(mc.options.keyJump, false); jumpKeyHeld = false; }
	}

	static void startTick(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null || mc.gameMode == null) return;
		long now = System.currentTimeMillis();
		followCrouch(mc, p);
		Unloader.tick(mc, p);
		damageTick(mc, p);
		playersTick(mc, p, now);
		tabTick(mc, now);
		deathTick(p);
		jumpTick(mc);
		crouchTick(mc);
	}

	// ---------- commands by PM ----------

	/**
	 * PM from a trusted player with a command word in it. The word may sit loose in an ordinary sentence
	 * ("doe eens een sethome hier", "kan je komen?", "spring eens"): bare commands as PM stood out.
	 * If there are several, the first one in the list below wins: "sethome hier" is not a tpahere.
	 * Crouching and jumping turn on, unless the sentence clearly asks for off ("stop met springen", "sta op").
	 * Returns true if the message was handled; then the normal PM handling does nothing more with it.
	 * No free-form commands: whoever can send a PM must never make the alt /pay or /msg others.
	 */
	static boolean pm(String from, String text) {
		AfkConfig c = AfkConfig.get();
		if (!c.pmCommands || !c.isTrusted(from) || !AfkHelperClient.active()) return false;
		List<String> w = List.of(text.toLowerCase(Locale.ROOT).split("[^a-z0-9_]+"));
		String sentence = " " + String.join(" ", w) + " ";
		// A request turns on, it does not toggle: "kan je eens hurken" turned it off, because the alt had just
		// started crouching since the player crouched alongside. Only a clear "uit" in the sentence turns it off.
		boolean off = w.contains("uit") || w.contains("off") || w.contains("stop") || w.contains("niet") || w.contains("genoeg") || sentence.contains(" sta op ");
		String send;
		boolean statusLine = false;
		if (w.contains("sethome") || sentence.contains(" set home ")) {
			send = Profiles.cmd("sethome", null);
			c.altHome = ""; AfkConfig.save();
		}
		else if (w.contains("tpahere") || w.contains("haal")) send = Profiles.cmd("tpahere", target(w, from));
		else if (w.contains("tpaccept") || w.contains("accept") || w.contains("accepteer")) send = Profiles.cmd("tpaccept", from);
		else if (w.contains("tpdeny") || w.contains("weiger")) send = Profiles.cmd("tpdeny", from);
		else if (w.contains("home")) send = homeCmd(c.altHome);
		else if (w.contains("tpa") || w.contains("kom") || w.contains("komen") || w.contains("hierheen")
				|| sentence.contains(" naar mij ") || sentence.contains(" bij mij ")) send = Profiles.cmd("tpa", target(w, from));
		else if (w.contains("crouch") || w.contains("sneak") || w.contains("hurk") || w.contains("hurken") || w.contains("shift") || sentence.contains(" sta op ")) {
			if (!c.altCrouchAlong) return false;
			boolean now = !off;
			setCrouch(now, from);
			Exchange.emit("alt_cmd", "from", from, "pm", text, "cmd", now ? "crouch" : "get up");
			return true;
		}
		else if (w.contains("jump") || w.contains("spring") || w.contains("springen")) {
			boolean now = !off;
			if (c.altJumping != now) { c.altJumping = now; AfkConfig.save(); Exchange.emit("alt", "action", now ? "jump" : "still", "by", from); }
			Exchange.emit("alt_cmd", "from", from, "pm", text, "cmd", now ? "jump" : "no jump");
			return true;
		}
		else if (w.contains("status")) { send = Profiles.cmd("msg", from, status()); statusLine = true; }
		else return false;
		// a command this server's profile does not have: nothing to type
		if (send == null) { Exchange.emit("alt_cmd", "from", from, "pm", text, "cmd", "", "reason", "no command in the profile"); return true; }
		double wait = rnd(1, 3);
		boolean answer = !statusLine;
		AfkHelperClient.later(wait, () -> {
			AfkHelperClient.sendTyped(send, AfkHelperClient.nearbyPlayer(from));
			if (answer) awaitAnswer(from, send);
		});
		Exchange.emit("alt_cmd", "from", from, "pm", text, "cmd", send);
		return true;
	}

	// ---------- the answer to a PM command ----------
	// A "sethome" by PM seemed to get no reaction: the server answered the alt ("Nieuwe home aangemaakt: home"), not
	// the player who asked. So the first server line after the command goes back to whoever asked.

	private static String answerTo, answerCmd;
	private static long answerUntil;
	private static final long ANSWER_MS = 4000;

	private static void awaitAnswer(String from, String cmd) {
		answerTo = from; answerCmd = cmd; answerUntil = System.currentTimeMillis() + ANSWER_MS;
		AfkHelperClient.later(ANSWER_MS / 1000.0 + 0.2, () -> {
			if (answerTo == null || !answerTo.equals(from) || !cmd.equals(answerCmd)) return;
			answerTo = null;
			String msg = Profiles.cmd("msg", from, cmd + " sent, no answer from the server");
			if (msg != null) AfkHelperClient.sendChat(msg);
		});
	}

	/**
	 * A server line, not a player's: the first one after a PM command is its answer. Lines that start with a bracket are
	 * the server's own announcements and the chat channels ("[Tweakcraft] Je kunt weer stemmen"), no answer to anything.
	 */
	static void serverLine(String line) {
		if (answerTo == null || line.startsWith("[") || line.isBlank()) return;
		if (System.currentTimeMillis() > answerUntil) { answerTo = null; return; }
		String to = answerTo;
		answerTo = null;
		String msg = Profiles.cmd("msg", to, line);
		if (msg != null) AfkHelperClient.sendChat(msg);
		Exchange.emit("alt_cmd", "from", to, "answer", line, "cmd", answerCmd);
	}

	/** Another player who is online and named in the sentence, otherwise whoever asks. */
	private static String target(List<String> words, String from) {
		LocalPlayer me = AfkHelperClient.mc().player;
		String self = me == null ? "" : me.getGameProfile().name();
		for (String x : words) if (!x.equalsIgnoreCase(self) && !x.equalsIgnoreCase(from) && online(x)) return x;
		return from;
	}

	/**
	 * Short and in ASCII: where the alt stands and how things are going. This goes to the asking player by /msg.
	 */
	private static String status() {
		LocalPlayer p = AfkHelperClient.mc().player;
		if (p == null) return "no player";
		String zone = AfkHelperClient.zone();
		// what the alt can eat unaided: only the hotbar counts, that is where the eating routine looks
		int slot = AfkHelperClient.findFood(p);
		String food = slot < 0 ? " no food in the hotbar" : " " + p.getInventory().getItem(slot).getCount() + "x " + AfkHelperClient.itemId(p.getInventory().getItem(slot));
		return zone + " " + p.getBlockX() + " " + p.getBlockY() + " " + p.getBlockZ()
			+ " health " + Math.round(p.getHealth()) + " food " + p.getFoodData().getFoodLevel() + food
			+ " afk " + (AfkConfig.get().afk ? "on" : "off") + " home " + (AfkConfig.get().altHome.isBlank() ? "default" : AfkConfig.get().altHome) + (AfkConfig.get().altCrouched ? " crouched" : "") + (AfkConfig.get().altJumping ? " jumping" : "");
	}

	/**
	 * Is this a player who is online right now? "tpa eens" otherwise became "/tpa eens": the word after
	 * tpa is often just talk, and then the tpa belongs to whoever asks.
	 */
	private static boolean online(String name) {
		var con = AfkHelperClient.mc().getConnection();
		if (name == null || con == null) return false;
		for (var info : con.getOnlinePlayers()) if (info.getProfile().name().equalsIgnoreCase(name)) return true;
		return false;
	}

	/** Without a name just /home: then the server picks your default home, the one you set with sethome. */
	private static String homeCmd(String name) {
		String home = Profiles.cmd("home", null);
		return home == null || name == null || name.isBlank() ? home : home + " " + name;
	}

	private static double rnd(double a, double b) { return ThreadLocalRandom.current().nextDouble(a, b); }
}
