package net.milenco.buddy.client;

import com.google.gson.JsonObject;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * What one server is like, so that the mod needs nothing of a particular server in its code: the commands are entered
 * once in the config, and after that it works semi-autonomously with in-game gestures. A profile says how the server
 * is recognised, which commands
 * it has for teleporting, a home and the AFK zone, what its lines for those look like, what to type after joining
 * (the hop from a proxy's lobby to the server itself) and whether to take its resource pack. A command or a text
 * left empty is not there: the mod then does that part on foot, or not at all.
 *
 * <p>A server is recognised when its command tree arrives: by its address, and where two servers share one address
 * behind a proxy, by a command that only one of them has. The first profile that fits wins, so the stricter one goes
 * first. Every switch of server behind a proxy sends a new tree, and the profile is chosen again.
 *
 * <p>What the alt remembers of a server (chests, fields) is kept under the profile's name, so a chest on one server
 * is never looked for on another that shares its address.
 */
public final class Profiles {
	private Profiles() {}

	/** One server. */
	public static class Profile {
		public String name = "";
		/** Part of the address you join with ("play.example.net"); empty fits every address. */
		public String address = "";
		/** A command only this server has: the profile fits only when the server sent it. */
		public String withCommand = "";
		/** A command this server does not have: the profile fits only when the server did not send it. */
		public String withoutCommand = "";
		/** "accept" or "decline"; a pack the server requires is always taken, or the server would throw him off. */
		public String resourcepack = "accept";
		/** Typed one after the other once the server is joined: the hop from a proxy's lobby, for one. */
		public List<String> afterJoin = new ArrayList<>();
		/**
		 * The commands, with {player} for a player and {text} for a message: tpa, tpahere, tpaccept, tpdeny, afk,
		 * sethome, home, msg, eventLeave.
		 */
		public Map<String, String> commands = new LinkedHashMap<>();
		/**
		 * The server's lines, as regular expressions; {player} stands for a name (a group of its own where none is
		 * given). The keys are listed in CLAUDE.md, "Server profiles".
		 */
		public Map<String, String> texts = new LinkedHashMap<>();
		/** Dimensions (or the start of their name) that are a game: there the command eventLeave takes him out. */
		public List<String> eventDims = new ArrayList<>();
		/** Dimensions (or the start of their name) where a fall into the void is part of the game (a parkour course). */
		public List<String> voidOk = new ArrayList<>();
		/**
		 * The first tick of a clear night he may lie down when others sleep; 0 is the game's own (12523, the day
		 * timeline's sky light). A server whose sleep plugin counts a sleeper later says so here (Tweakcraft: 12542).
		 */
		public long sleepFrom = 0;
		/** The modules on for this server, each with its settings ({@link BuddyModule}); a module not named is off. */
		public JsonObject modules = new JsonObject();

		Profile() {}
		Profile(String name, String address) { this.name = name; this.address = address; }
	}

	/**
	 * The profiles a fresh config starts with: none. The core knows no server: a server's commands, lines and modules
	 * are written into buddy.json beforehand. Until
	 * 0.4.105 Tweakcraft's two profiles were built in here, and filled in again by name at every read.
	 */
	static List<Profile> defaults() { return new ArrayList<>(); }

	private static final Profile NONE = new Profile("", "");

	private static Profile active;
	private static Object seenRoot;
	// the hop after joining: when to type the profile's lines next, and how long to wait after that
	private static long hopAt, hopWait;
	// a request to come along that a click in chat would accept, found where the profile has no line for it
	private static String clickAccept, clickFrom;
	private static long clickAt;

	/** The profile of the server he is on, or an empty one: then every command is missing and every text unknown. */
	static Profile active() {
		if (active != null) return active;
		Profile p = byAddress(AfkHelperClient.serverIp());
		return p != null ? p : NONE;
	}

	/** What the memory of this server is kept under: the profile's name, or the address where no profile fits. */
	static String key() {
		Profile p = active();
		return p != NONE ? p.name : AfkHelperClient.serverIp().toLowerCase(Locale.ROOT);
	}

	/** An entry kept under an address before there were profiles: under the first profile for that address. */
	static String migrate(String stored) {
		for (Profile p : list()) if (p.name.equals(stored)) return stored;
		Profile p = byAddress(stored);
		return p != null ? p.name : stored;
	}

	private static Object filledFor;

	/**
	 * The profiles of the config. A built-in one saved by an older version lacks what came since (a command, a
	 * line): that is filled in from the defaults, once per config read, and what the user set stays as it is.
	 */
	private static List<Profile> list() {
		AfkConfig c = AfkConfig.get();
		List<Profile> l = c.profiles;
		if (l == null) return List.of();
		if (filledFor != c) {
			filledFor = c;
			for (Profile p : l) {
				if (p.commands == null) p.commands = new LinkedHashMap<>();
				if (p.texts == null) p.texts = new LinkedHashMap<>();
				if (p.eventDims == null) p.eventDims = new ArrayList<>();
				if (p.voidOk == null) p.voidOk = new ArrayList<>();
				if (p.afterJoin == null) p.afterJoin = new ArrayList<>();
				if (p.modules == null) p.modules = new JsonObject();
			}
		}
		return l;
	}

	/** The first profile whose address fits, not looking at commands: before the tree is there (the resource pack). */
	private static Profile byAddress(String ip) {
		if (ip == null || ip.isEmpty()) return null;
		String l = ip.toLowerCase(Locale.ROOT);
		for (Profile p : list()) if (p.address == null || p.address.isEmpty() || l.contains(p.address.toLowerCase(Locale.ROOT))) return p;
		return null;
	}

	/** Does a profile name this address: then the mod may act there by itself, as on a server in {@code servers}. */
	static boolean knows(String ip) {
		if (ip == null || ip.isEmpty()) return false;
		String l = ip.toLowerCase(Locale.ROOT);
		for (Profile p : list()) if (p.address != null && !p.address.isEmpty() && l.contains(p.address.toLowerCase(Locale.ROOT))) return true;
		return false;
	}

	static void newConnection() {
		active = null;
		seenRoot = null;
		clickAccept = null;
	}

	/** Every tick: a new command tree chooses the profile again; the hop is typed when it is due. */
	static void tick(Minecraft mc) {
		var conn = mc.getConnection();
		if (conn == null || mc.player == null) return;
		Object root = conn.getCommands().getRoot();
		if (root != seenRoot) {
			seenRoot = root;
			choose(conn.getCommands().getRoot().getChildren());
		}
		hop(mc);
	}

	private static void choose(java.util.Collection<? extends CommandNode<?>> roots) {
		java.util.Set<String> have = new java.util.HashSet<>();
		for (CommandNode<?> n : roots) have.add(n.getName().toLowerCase(Locale.ROOT));
		String ip = AfkHelperClient.serverIp().toLowerCase(Locale.ROOT);
		Profile found = null;
		for (Profile p : list()) {
			if (p.address != null && !p.address.isEmpty() && !ip.contains(p.address.toLowerCase(Locale.ROOT))) continue;
			if (p.withCommand != null && !p.withCommand.isEmpty() && !have.contains(p.withCommand.toLowerCase(Locale.ROOT))) continue;
			if (p.withoutCommand != null && !p.withoutCommand.isEmpty() && have.contains(p.withoutCommand.toLowerCase(Locale.ROOT))) continue;
			found = p;
			break;
		}
		Profile before = active;
		active = found;
		if (found != before) {
			hopAt = found != null && found.afterJoin != null && !found.afterJoin.isEmpty() ? System.currentTimeMillis() + 3000 : 0;
			hopWait = 40_000;
		}
		Exchange.emit("profile", "action", "chosen", "name", found == null ? "" : found.name, "server", ip, "commands", have.size());
	}

	/**
	 * The lines to type after joining, one after the other; while the same profile still holds after a while (the
	 * hop did not come through: the server behind the proxy was down), again, waiting longer each time.
	 */
	private static void hop(Minecraft mc) {
		if (hopAt == 0 || active == null || System.currentTimeMillis() < hopAt) return;
		if (!AfkHelperClient.active()) { hopAt += 2000; return; }
		List<String> lines = active.afterJoin;
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			AfkHelperClient.later(i * 1.5, () -> AfkHelperClient.sendChat(line));
		}
		Exchange.emit("profile", "action", "after joining", "name", active.name, "lines", lines.size());
		hopAt = System.currentTimeMillis() + hopWait;
		hopWait = Math.min(hopWait * 2, 5 * 60_000L);
	}

	// ---------- commands ----------

	/** The profile's command for this, filled in; null where the server has none. */
	static String cmd(String which, String player) { return cmd(which, player, null); }

	/** The first tick of a clear night he lies down when others sleep, on this server ({@link Profile#sleepFrom}). */
	static long sleepFrom() {
		long t = active().sleepFrom;
		return t > 0 ? t : 12523;
	}

	static String cmd(String which, String player, String text) {
		String t = active().commands == null ? null : active().commands.get(which);
		if (t == null || t.isBlank()) return null;
		boolean named = t.contains("{player}");
		if (named) { if (player == null) return null; t = t.replace("{player}", player); }
		if (t.contains("{text}")) t = t.replace("{text}", text == null ? "" : text);
		// an accept without a name takes the server's newest request, which may be anyone's who sent one just before:
		// it always names the player
		if (!named && player != null && which.equals("tpaccept")) t = t.trim() + " " + player;
		return t.trim();
	}

	/** Is this dimension a game of the server's (eventDimensies)? */
	static boolean inEvent(String dim) { return startsWithAny(dim, active().eventDims); }

	/** May a fall into the void be part of the way in this dimension (leegteMag)? */
	static boolean voidAllowed(String dim) { return startsWithAny(dim, active().voidOk); }

	private static boolean startsWithAny(String dim, List<String> list) {
		if (dim == null || list == null) return false;
		for (String d : list) if (d != null && !d.isEmpty() && dim.startsWith(d)) return true;
		return false;
	}

	// ---------- texts ----------

	private static final Map<String, Pattern> compiled = new ConcurrentHashMap<>();
	private static final Pattern NOTHING = Pattern.compile("(?!)");

	/** The profile's line for this, read against a line of chat; {player} the given player, or any name. */
	static Matcher match(String which, String line, String player) {
		String t = active().texts == null ? null : active().texts.get(which);
		if (t == null || t.isBlank()) return NOTHING.matcher(line);
		String re = t.contains("{player}") ? t.replace("{player}", player == null ? "(\\.?[A-Za-z0-9_]{2,16})" : Pattern.quote(player)) : t;
		Pattern p = compiled.computeIfAbsent(re, r -> {
			try { return Pattern.compile(r); }
			catch (PatternSyntaxException e) { Exchange.emit("error", "msg", "profile " + active().name + ", text " + which + ": " + e.getDescription()); return NOTHING; }
		});
		return p.matcher(line);
	}

	static boolean has(String which, String line) { return match(which, line, null).find(); }

	static boolean has(String which, String line, String player) { return match(which, line, player).find(); }

	/** Does the profile know this line at all: where it does not, a click in chat may do instead. */
	static boolean knowsText(String which) {
		String t = active().texts == null ? null : active().texts.get(which);
		return t != null && !t.isBlank();
	}

	// ---------- the resource pack ----------

	/** Whether the alt takes a pack the server pushes. A required one always, or the server throws him off. */
	static boolean takePack(boolean required, String ip) {
		if (required) return true;
		// while joining there is no current server yet: the address comes with the pack's connection
		Profile p = byAddress(ip);
		if (active != null) p = active;
		return p == null || !("decline".equalsIgnoreCase(p.resourcepack) || "weiger".equalsIgnoreCase(p.resourcepack));
	}

	// ---------- a click in chat ----------

	/**
	 * A line of chat with a command behind a click. Written to the events, so that a profile for a new server can be
	 * filled in from what the server really sends; and where the profile has no line for a request to come along,
	 * a click that accepts something from the player he follows is the request (the server's own button).
	 */
	static void clicks(Component msg) {
		List<String> run = new ArrayList<>();
		msg.visit((Style style, String part) -> {
			if (style.getClickEvent() instanceof ClickEvent.RunCommand(String command) && !run.contains(command)) run.add(command);
			return Optional.empty();
		}, Style.EMPTY);
		if (run.isEmpty()) return;
		String text = msg.getString().replace('\n', ' ').trim();
		Exchange.emit("profile", "action", "clickable", "text", text, "commands", String.join(" | ", run));
		if (knowsText("tpahere")) return;
		String target = Follow.target();
		if (target == null || !names(text, target)) return;
		for (String c : run) {
			String l = c.toLowerCase(Locale.ROOT);
			if (l.contains("accept") || l.contains("tpyes") || l.matches(".*\\b(ja|yes)\\b.*")) {
				// a button with more than its command word has to name him too: "/tpaccept .Steve" (someone whose name
				// holds his) or "/pay x 1000 yes" is no request of his
				if (c.trim().split("\\s+").length > 1 && !names(c, target)) continue;
				clickAccept = c.startsWith("/") ? c : "/" + c;
				clickFrom = target;
				clickAt = System.currentTimeMillis();
				Follow.requestByClick(target);
				return;
			}
		}
	}

	/** Whether a text names this player as a name of its own, not as a piece of a longer one (".Steve", "Steve2"). */
	static boolean names(String text, String player) {
		return Pattern.compile("(?<![A-Za-z0-9_.])" + Pattern.quote(player) + "(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE).matcher(text).find();
	}

	/** The command behind the accept button of the last request of this player, if it is not too old. */
	static String clickAccept(String from) {
		if (clickAccept == null || from == null || !from.equalsIgnoreCase(clickFrom) || System.currentTimeMillis() - clickAt > 60_000) return null;
		String c = clickAccept.trim();
		clickAccept = null;
		// a bare button ("/tpaccept") takes the newest request: with the name it can only be his (clicks() made sure that
		// a button with more words names him already)
		return c.split("\\s+").length == 1 ? c + " " + from : c;
	}

	// ---------- forgetting ----------

	/**
	 * Forget what he knows of this server for one player: {@code alles}, {@code kisten} or {@code velden}. Only the
	 * server he is on: a chest on another server stays.
	 */
	static void forget(String what, String owner, String by) {
		if (owner == null) { Exchange.emit("error", "msg", "the buddy follows nobody: whose memory is to be forgotten?"); return; }
		boolean all = what == null || what.equals("all");
		int chests = 0, fields = 0, ovens = 0;
		boolean home = false, pens = false;
		if (!all && !List.of("chests", "fields", "furnaces", "home", "pens").contains(what)) { Exchange.emit("error", "msg", "forget: unknown " + what); return; }
		if (all || what.equals("chests")) chests = Stash.forget(owner);
		if (all || what.equals("fields")) fields = Work.forgetFields(owner);
		if (all || what.equals("furnaces")) ovens = Ovens.forget(owner);
		if (all || what.equals("home")) home = Tap.forgetBase(owner);
		if (all || what.equals("pens")) pens = Pen.forget(owner);
		Exchange.emit("forget", "what", all ? "all" : what, "for", owner, "profile", key(), "chests", chests, "fields", fields, "furnaces", ovens, "home", home, "pens", pens, "by", by);
	}

	/** {@code {"cmd":"profile"}}: which profile holds now. {@code {"cmd":"forget","what":...}}: forget. */
	static void command(String cmd, JsonObject o) {
		if (cmd.equals("profiles")) {
			// the panel's profile editor: the whole list at once, read back from buddy.json by the panel
			if (!o.has("set") || !o.get("set").isJsonArray()) { Exchange.emit("error", "msg", "profiles: set is a list"); return; }
			try {
				Profile[] list = new com.google.gson.Gson().fromJson(o.get("set"), Profile[].class);
				List<Profile> l = new ArrayList<>();
				for (Profile p : list) if (p != null && p.name != null && !p.name.isBlank()) l.add(p);
				AfkConfig c = AfkConfig.get();
				String was = active == null ? null : active.name;
				c.profiles = l;
				filledFor = null;
				compiled.clear();
				AfkConfig.save();
				active = null;
				if (was != null) for (Profile p : l) if (p.name.equals(was)) active = p;
				Exchange.emit("profile", "action", "list set", "count", l.size());
			} catch (RuntimeException e) {
				Exchange.emit("error", "msg", "profiles: " + e.getMessage());
			}
			return;
		}
		if (cmd.equals("forget")) {
			forget(o.has("what") ? o.get("what").getAsString() : "all", Follow.target(), "asked");
			return;
		}
		Profile p = active();
		Exchange.emit("profile", "action", "status", "name", p.name, "key", key(), "server", AfkHelperClient.serverIp(),
			"commands", p.commands == null ? 0 : p.commands.size(), "texts", p.texts == null ? 0 : p.texts.size(),
			"afterJoin", p.afterJoin == null ? 0 : p.afterJoin.size(), "resourcepack", p.resourcepack);
	}
}
