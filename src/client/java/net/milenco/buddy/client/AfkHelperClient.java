package net.milenco.buddy.client;

import com.google.gson.JsonObject;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.milenco.buddy.version.Game;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AfkHelperClient implements ClientModInitializer {
	private boolean ownNameChecked = false;   // altName checked against the game's own name this start
	static AfkHelperClient INSTANCE;

	// zones
	private String zone = "elsewhere";          // elders | home:<name>
	private String zoneServer = "";          // the server the zone was read on
	private Vec3 zoneAnchor = null;

	// scheduling
	private record Job(long due, Runnable run) {}
	private final List<Job> jobs = new ArrayList<>();
	private long lastInput = 0;              // last time a person did something by hand (key, mouse, looking around)
	private Vec3 lastPos = null; private float lastYaw, lastPitch;
	private long ignoreLookUntil = 0;
	private boolean afkCrouching = false;
	private static long combatUntil = 0;          // until when the server considers you 'in combat'
	private static long combatTagMs = 25_000;     // how long the tag lasts after a hit: the server's seconds plus a margin
	private static boolean combatReported = false;
	private static boolean reconnectPlanned = false;
	private static int failedConnections = 0;     // in a row; JOIN resets it to 0
	private static boolean panicStopped = false;  // logged out ourselves to avoid dying: then no automatic return
	private static net.minecraft.client.multiplayer.ServerData lastServer = null;

	// looking around (AFK): smooth turn toward a player, with noise
	private final Look look = new Look();
	private final Map<String, Long> lastLookAt = new HashMap<>();
	private long nextWander = 0;
	private int eatTicks = 0, eatRestoreSlot = -1;
	private boolean eatBitten = false;   // the bite under way has begun (the use key is down)
	private boolean eatSneak = false;   // we are sneaking now so that 'use' takes the food and not the block
	private boolean shiftBefore = false;
	private long screenSince = 0, screenCloseAfter = 0;
	private double screenMouseX, screenMouseY;
	private long lastHungerWarn = 0, lastEat = 0;

	/** Smooth turn of the view direction toward a point (15-35 ticks, ease in-out, small noise). */
	static class Look {
		int ticks = 0, total = 0; float fromYaw, fromPitch, toYaw, toPitch;
		// different per turn: how sharply it accelerates and slows down, and how much your hand trembles. A fixed
		// curve becomes recognisable over time, even when the duration does vary.
		float curve = 2f, jitter = 0.4f;
		boolean busy() { return ticks > 0; }
		/** Abort a turn halfway: when someone visits you do not want to still turn toward that player. */
		void stop() { ticks = 0; }
		void start(LocalPlayer p, Vec3 target, float spread) { start(p, target, spread, 15, 36); }
		/**
		 * Duration by the size of the turn: a correction of a few degrees happens in a flash, a half turn
		 * takes well over a second. A fixed duration made big turns unnaturally fast.
		 */
		void startByAngle(LocalPlayer p, Vec3 target, float spread) {
			Vec3 eye = p.getEyePosition();
			double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z, dh = Math.sqrt(dx * dx + dz * dz);
			float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz)), pitch = (float) -Math.toDegrees(Math.atan2(dy, dh));
			float angle = Math.max(Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw - p.getYRot())), Math.abs(pitch - p.getXRot()));
			// one time you snap over quickly, another time you turn calmly: well over a third faster or slower
			double pace = ThreadLocalRandom.current().nextDouble(0.7, 1.6);
			int t = (int) net.minecraft.util.Mth.clamp((4 + angle / 9f) * pace, 3, 42);
			start(p, target, spread, t, t + 1 + ThreadLocalRandom.current().nextInt(6));
		}
		/** With its own duration (in ticks): a flash from log to log is shorter than a glance around the surroundings. */
		void start(LocalPlayer p, Vec3 target, float spread, int minTicks, int maxTicks) {
			Vec3 eye = p.getEyePosition();
			double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z, dh = Math.sqrt(dx * dx + dz * dz);
			toYaw = (float) Math.toDegrees(Math.atan2(-dx, dz)) + (float) ThreadLocalRandom.current().nextDouble(-spread, spread);
			toPitch = (float) -Math.toDegrees(Math.atan2(dy, dh)) + (float) ThreadLocalRandom.current().nextDouble(-spread * 0.75, spread * 0.75);
			fromYaw = p.getYRot(); fromPitch = p.getXRot();
			curve = (float) ThreadLocalRandom.current().nextDouble(1.5, 3.2);
			jitter = (float) ThreadLocalRandom.current().nextDouble(0.12, 0.75);
			total = ticks = ThreadLocalRandom.current().nextInt(minTicks, Math.max(minTicks + 1, maxTicks));
		}
		/** Returns true if we turned this tick. */
		boolean tick(LocalPlayer p) {
			if (ticks <= 0) return false;
			ticks--;
			float t = 1f - (float) ticks / total;
			float ease = t < 0.5f ? (float) Math.pow(2 * t, curve) / 2 : 1 - (float) Math.pow(2 - 2 * t, curve) / 2;
			float noise = (float) (Math.sin(System.nanoTime() / 5e7) * jitter);
			MouseTurn.toward(p, fromYaw + net.minecraft.util.Mth.wrapDegrees(toYaw - fromYaw) * ease + noise,
				fromPitch + (toPitch - fromPitch) * ease + noise * 0.5f);
			return true;
		}
	}

	// players at home
	private final Map<String, Long> alerted = new HashMap<>();
	private String lastPlayersKey = "";

	private int tick = 0;

	/** Public chat: "[Rank] [Tag] Name: text" or "<Name> text" -> group 1 name, group 2 text. */
	private static final Pattern P_SAID = Pattern.compile("^(?:.*?\\] )?([A-Za-z0-9_.]{3,16}): (.*)$|^<([A-Za-z0-9_.]{3,16})> (.*)$");

	@Override
	public void onInitializeClient() {
		INSTANCE = this;
		AfkConfig.load();
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STARTED.register(mc -> applySteadyFps());
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> Yield.flush());
		QuickExit.register();
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> { if (AfkConfig.get().stayOffline) { logOut("stays away"); return; } joinedAt = System.currentTimeMillis(); newServer(); Watchdog.joined(); CropFarmer.stop("new connection"); WheatFarm.newConnection(); Builder.stop("new connection"); Builder.resumeLater(); Alt.newConnection(); Follow.newConnection(); Obtain.newConnection(); Profiles.newConnection(); panicStopped = false; panicArmed = false; panicHomeAt = 0; panicHomeArrived = false; panicHomeTries = 0; combatUntil = 0; combatReported = false; reconnectPlanned = false; failedConnections = 0; });
		// the Telegram bridge shows from this how long the connection has been up
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> Exchange.emit("connection", "action", "connected", "server", serverIp()));
		Exchange.init();
		Modules.load();
		Alt.register();

		ClientReceiveMessageEvents.GAME.register((msg, overlay) -> { Recorder.shown(overlay ? "actionbar" : "chat", msg); if (overlay) Details.actionBar(msg); if (!overlay) { Profiles.clicks(msg); onIncoming(msg, "game"); } });
		ClientReceiveMessageEvents.CHAT.register((msg, signed, profile, type, ts) -> { Recorder.shown("chat", msg); onIncoming(msg, "chat"); });
		ClientSendMessageEvents.CHAT.register(this::onOwnChat);
		ClientSendMessageEvents.COMMAND.register(this::onOwnCommand);
		ClientTickEvents.START_CLIENT_TICK.register(this::onStartTick);
		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
		registerReconnect();
		net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			// Esc menu (also automatically on focus loss): everything just keeps running
			if (screen instanceof net.minecraft.client.gui.screens.PauseScreen) return;
			// A chest or other container screen is opened by the server itself: so it sees it, and hitting with such
			// a screen open is a classic anti-cheat report. Always pause.
			boolean serverSeesIt = screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>;
			// Chat and your own menus are unknown to the server; those may keep running if you want.
			if (!serverSeesIt && AfkConfig.get().farmThroughMenus) return;
			Grinder.setChatOpen(true);
			net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.remove(screen).register(s -> Grinder.setChatOpen(false));
		});

		Exchange.emit("mod_started", "version", net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("minecraft-buddy")
			.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?"));
	}

	// ---------- helpers ----------
	static Minecraft mc() { return Minecraft.getInstance(); }

	/**
	 * Is this a player the tab list shows? A figure the server put there is not: FancyNpcs gives one a place in the player
	 * list, unlisted, for the two seconds after it appears (its skin), and none after that, so a test on the player list
	 * alone took a figure for a player in those two seconds, with its profile name of colour codes.
	 */
	static boolean listed(Player o) {
		var conn = mc().getConnection();
		if (conn == null) return true;
		var info = conn.getPlayerInfo(o.getUUID());
		return info != null && conn.getListedOnlinePlayers().contains(info);
	}

	/** Current zone (home:name, elders). */
	public static String zone() { return INSTANCE == null ? "elsewhere" : INSTANCE.zone; }

	/** The home of the last /home or click in /homes, so that the teleport after it is known as that home. */
	private static String homeAsked = null;
	private static long homeAskedAt = 0;

	/** A /home or a click in /homes is on its way: the "Je bent geteleporteerd!" after it is that home. */
	static void homeAsked(String name) { homeAsked = name; homeAskedAt = System.currentTimeMillis(); }

	/** Address of the current server ("" in singleplayer). */
	public static String serverIp() {
		var sd = mc().getCurrentServer();
		if (sd != null) lastServer = sd;
		return sd == null ? "" : sd.ip;
	}

	/**
	 * A connection attempt starts (ConnectScreenMixin). Reconnecting went to the last server you had actually
	 * been on: with the server down and a local test server visited earlier, a failed attempt made the mod
	 * connect to the test server. The server you last tried is the one to try again.
	 */
	public static void connectingTo(net.minecraft.client.multiplayer.ServerData sd) { if (sd != null) lastServer = sd; }

	/**
	 * To a home by its name: by the server's homes menu where the profile has one (a click there teleports at once),
	 * otherwise by the home command with the name; nothing where the profile has neither.
	 */
	static void goHome(String name) {
		String homes = Profiles.cmd("homes", null), home = Profiles.cmd("home", null);
		if (homes != null) MenuPick.go(homes, name);
		else if (home != null) { homeAsked(name); sendChat(name == null || name.isBlank() ? home : home + " " + name); }
		else Exchange.emit("error", "msg", "no home command in the profile " + Profiles.key());
	}

	/** Someone starts or stops breaking a block (BlockProgressMixin): for the tap that steers the alt. */
	public static void blockProgress(int id, net.minecraft.core.BlockPos pos, int progress) { Tap.progress(id, pos, progress); }

	/** The panel holds the left button on a block (ContinueAttackMixin leaves the game's own stop out then). */
	public static boolean panelBreaking() { return Steer.breakingNow(); }

	/** A player near us died (DeathEventMixin): the corpse guard notes it for its next tick. */
	public static void playerDied(net.minecraft.world.entity.player.Player player) { Corpse.deathEvent(player.getGameProfile().name(), player.position(), player.getKillCredit()); }

	/**
	 * A mob took a blow (DamageEventMixin): for the pen's gesture, the bare-hand punch of the followed player on a
	 * monster in a mob farm. What it needs is copied for the next tick.
	 */
	public static void damageEvent(net.minecraft.world.entity.LivingEntity e, net.minecraft.world.damagesource.DamageSource s) {
		if (!active()) return;
		Pen.damage(e, s);
	}

	/** A mob near us died (DeathEventMixin): a kill of the pen when it was one the buddy hit. */
	public static void mobDied(int id) { Pen.mobDied(id); }

	/** The hunger guard's own look is turning (away from a hopper, a door or a trapdoor before it eats). */
	static boolean lookBusy() { return INSTANCE != null && INSTANCE.look.busy(); }

	/**
	 * Whether to take the resource pack a server pushes (ResourcePackMixin): by the server's profile, so that nothing ever
	 * waits on the question.
	 */
	public static Boolean takePack(boolean required, String ip) {
		boolean take = Profiles.takePack(required, ip);
		Exchange.emit("profile", "action", take ? "resource pack taken" : "resource pack declined", "required", required, "server", ip);
		return take;
	}

	/** Acting automatically (tpaccept, /tpa, chat from the watcher) only on an allowed server. */
	public static boolean active() { AfkConfig c = AfkConfig.get(); return c.modEnabled && c.serverAllowed(serverIp()); }
	/**
	 * True while the mod itself right-clicks a block on purpose, to plant what it holds or to open a chest. A carrot
	 * or a potato is food, and with food in hand GameModeMixin turns every block click into eating in AFK mode: on
	 * 4 Oct the field mode held a stack of carrots over bare farmland for eight minutes and planted none.
	 */
	public static boolean ownUse = false;

	/** Right-click a block with what is in this hand, also when that is something you can eat. */
	static net.minecraft.world.InteractionResult useOn(LocalPlayer p, net.minecraft.world.InteractionHand hand, net.minecraft.world.phys.BlockHitResult hit) {
		ownUse = true;
		try { return mc().gameMode.useItemOn(p, hand, hit); } finally { ownUse = false; }
	}

	/** Busy eating or drinking: then keep off the selected slot and the keys. */
	/** A bite or a sip of the mod's own under way (not a shield held up, which isEating counts as a use too). */
	static boolean biting() { return INSTANCE != null && (INSTANCE.eatTicks > 0 || INSTANCE.drinkTicks > 0); }

	/**
	 * A bite of what lies in this hotbar slot, now, the way the hunger guard starts one: for healing in a fight (Follow.heal,
	 * 24 Oct 2026), a golden apple too, which the hunger guard never eats. False when a bite is under way already.
	 */
	static boolean eatNow(LocalPlayer p, int slot, String why) {
		AfkHelperClient a = INSTANCE;
		if (a == null || a.eatTicks > 0 || a.drinkTicks > 0 || p.isUsingItem() || slot < 0 || slot > 8) return false;
		int cur = p.getInventory().getSelectedSlot();
		if (slot != cur) { a.eatRestoreSlot = cur; p.getInventory().setSelectedSlot(slot); }
		a.pendingUse = true;
		a.eatTicks = 45;
		a.eatBitten = false;
		a.lastEat = System.currentTimeMillis();
		Exchange.emit("eat", "action", "start", "item", itemId(p.getInventory().getItem(slot)), "food", p.getFoodData().getFoodLevel(), "hp", p.getHealth(), "reason", why);
		return true;
	}

	public static boolean isEating() { LocalPlayer p = mc().player; return (INSTANCE != null && (INSTANCE.eatTicks > 0 || INSTANCE.drinkTicks > 0)) || (p != null && p.isUsingItem()); }

	/**
	 * The buddy's own bite under way ended, as a player lets go of the button: the use key up, which ends the use
	 * (Minecraft.handleKeybinds), and the slot it was taken from back in hand. Getting away from a creeper (Pen): eating,
	 * a player walks at a fifth of the pace.
	 */
	static void stopEating(String why) {
		AfkHelperClient a = INSTANCE;
		LocalPlayer p = mc().player;
		if (a == null || p == null || a.eatTicks <= 0) return;
		a.eatTicks = 0;
		a.pendingUse = false;
		HeldKeys.set(mc().options.keyUse, false);
		if (a.eatRestoreSlot >= 0) { p.getInventory().setSelectedSlot(a.eatRestoreSlot); a.eatRestoreSlot = -1; }
		Exchange.emit("eat", "action", "stopped", "reason", why, "food", p.getFoodData().getFoodLevel());
	}

	/** Send as if you typed it: while farming, pause first (and possibly look at someone), only then send. */
	public static void sendTyped(String text, Entity lookAt) {
		if (Builder.isOn()) Builder.pause(300 + text.length() * 140L + 1500, "typing");
		if (Lumberjack.isOn()) { long typ = 300 + (long) (text.length() * ThreadLocalRandom.current().nextDouble(80, 140)); Lumberjack.pause(typ + 1500, "typing"); if (INSTANCE != null) INSTANCE.schedule(typ / 1000.0, () -> sendChat(text)); else sendChat(text); return; }
		if (!Grinder.active()) { sendChat(text); return; }
		long typing = 300 + (long) (text.length() * ThreadLocalRandom.current().nextDouble(80, 140));
		Grinder.interrupt(typing + ThreadLocalRandom.current().nextLong(400, 1200), lookAt, "typing");
		if (INSTANCE != null) INSTANCE.schedule(typing / 1000.0, () -> sendChat(text)); else sendChat(text);
	}

	/** Player with this name within 24 blocks, or null. */
	static Player nearbyPlayer(String name) {
		LocalPlayer me = mc().player;
		if (me == null || mc().level == null || name == null) return null;
		for (Player o : mc().level.players()) if (o != me && o.getGameProfile().name().equalsIgnoreCase(name) && o.distanceTo(me) < 24) return o;
		return null;
	}

	private String lastTalker = null;
	private static String pmPartner = null; private static long pmPartnerAt = 0;

	/** Who you talked with recently: the last PM, in or out. Remembers only one, so if you PM someone
	 *  else afterwards, that one counts until the time is up. */
	public static String lastChatPartner(long withinMs) {
		return pmPartner != null && System.currentTimeMillis() - pmPartnerAt < withinMs ? pmPartner : null;
	}

	public static void sendChat(String text) {
		LocalPlayer p = mc().player;
		if (p == null) return;
		// every line the buddy sends goes through here: nothing the server would kick for (ChatText)
		text = ChatText.sendable(text);
		if (text.isEmpty() || text.equals("/")) return;
		if (text.startsWith("/")) p.connection.sendCommand(text.substring(1)); else p.connection.sendChat(text);
	}

	private void schedule(double seconds, Runnable r) { jobs.add(new Job(System.currentTimeMillis() + (long) (seconds * 1000), r)); }
	/** Do something after a while, also from another class (simply runs on the client tick). */
	public static void later(double seconds, Runnable r) { if (INSTANCE != null) INSTANCE.schedule(seconds, r); }
	private static double rnd(double a, double b) { return a >= b ? a : ThreadLocalRandom.current().nextDouble(a, b); }

	/**
	 * A zone belongs to the server it was read on. Chat sets it and only chat of the same server clears it: a
	 * home read on one server must not stand on the next.
	 */
	private void newServer() {
		String ip = serverIp();
		if (!ip.equals(zoneServer)) { zoneServer = ip; setZone("elsewhere", null); }
	}

	private void setZone(String z, Vec3 anchor) {
		if (!z.equals(zone)) {
			Exchange.emit("zone", "from", zone, "to", z);
			zone = z;
		}
		zoneAnchor = anchor;
		if (!z.startsWith("home:")) { lastPlayersKey = ""; }
	}

	// ---------- incoming messages ----------
	/** A line as the server sent it: its colours and clicks go along to the events, for the panel. */
	private void onIncoming(Component msg, String kind) {
		List<Map<String, Object>> runs = null;
		try { runs = Details.chatRuns(msg); } catch (RuntimeException e) { /* the plain line still goes out */ }
		// the plain line without the § codes a plugin may put inside its text: the game draws them as colours, not letters
		onIncoming(net.minecraft.ChatFormatting.stripFormatting(msg.getString()), kind, runs, "game".equals(kind) ? gameLine(msg, 0) : null);
	}

	private void onIncoming(String text, String kind) { onIncoming(text, kind, null, null); }

	/**
	 * The game's own death message or advancement, known by its key: { "death" or "advancement", the player }. A translated
	 * line whose key starts with death.attack or death.fell is a death, one with chat.type.advancement an advancement, also
	 * behind a prefix the server puts in front. The key is the same on every server and in every language; a plugin that
	 * writes its own lines needs the profile's texts death and advancement. The name is the player's own (the insertion of
	 * a display name, which may carry a team's prefix), "" where there is none; null for any other line. Only the line and
	 * its siblings count, never what a translated line quotes, such as the words of a chat line.
	 */
	static String[] gameLine(Component c, int depth) {
		if (c == null || depth > 4) return null;
		if (c.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t) {
			String key = t.getKey(), is = key.startsWith("death.attack.") || key.startsWith("death.fell.") ? "death"
				: key.startsWith("chat.type.advancement.") ? "advancement" : null;
			if (is != null) {
				Object[] a = t.getArgs();
				String who = "";
				if (a.length > 0 && a[0] instanceof Component named) {
					String own = named.getStyle().getInsertion();
					who = own != null && !own.isBlank() ? own : named.getString();
				} else if (a.length > 0) who = String.valueOf(a[0]);
				return new String[] { is, who };
			}
		}
		for (Component s : c.getSiblings()) { String[] d = gameLine(s, depth + 1); if (d != null) return d; }
		return null;
	}

	/**
	 * A chat line's kind ("pm", "chat", "relay", "join", "leave", "death", "advancement" or "server"), the player it names
	 * and what was said: by the active profile's texts pm and pmOut, join and leave, relay (a line the server passes on from
	 * a chat outside the game, such as IRC or Discord), chat (a player's public line), and death and advancement (the lines
	 * a plugin writes; the game's own are known by their key, see gameLine); a line the game itself signed as a player's is
	 * chat, and without a chat text the vanilla forms "<Name> text" and "Name: text" count too.
	 */
	static Object[] lineKind(String clean, String kind) {
		for (String[] t : new String[][] { { "pm", "pm" }, { "pmOut", "pm" }, { "join", "join" }, { "leave", "leave" }, { "relay", "relay" }, { "chat", "chat" },
				{ "death", "death" }, { "advancement", "advancement" } }) {
			boolean news = t[1].equals("death") || t[1].equals("advancement");
			if (news && kind.equals("chat")) break;   // a line the game signed as a player's is never a death or an advancement
			Matcher m = Profiles.match(t[0], clean, null);
			if (m.lookingAt()) return new Object[] { t[1], m.groupCount() >= 1 ? m.group(1) : null, m.groupCount() >= 2 && !news ? m.group(2) : null };
		}
		if (kind.equals("chat") || !Profiles.knowsText("chat")) {
			Matcher said = P_SAID.matcher(clean);
			if (said.find()) return new Object[] { "chat", said.group(1) != null ? said.group(1) : said.group(3), said.group(1) != null ? said.group(2) : said.group(4) };
			if (kind.equals("chat")) return new Object[] { "chat", null, null };
		}
		return new Object[] { "server", null, null };
	}

	private void onIncoming(String text, String kind, List<Map<String, Object>> runs, String[] keyed) {
		String clean = text.replace('\n', ' ').trim();
		// what kind of line it is, so that the Telegram chat can be set to show some kinds only: a private message, a
		// player's chat line, a join, a leave, a death, an advancement, or anything else
		// the server says; with the player and the words said where the profile's text gives them
		Object[] what = keyed != null ? new Object[] { keyed[0], keyed[1].isEmpty() ? null : keyed[1], null } : lineKind(clean, kind);
		// a relayed line is players' chat for Telegram's switches; "relay" says it came from outside the game
		boolean relay = "relay".equals(what[0]);
		List<Object> ev = new ArrayList<>(List.of("kind", kind, "is", relay ? "chat" : what[0], "text", clean));
		if (relay) { ev.add("relay"); ev.add(true); }
		if (what[1] != null) { ev.add("player"); ev.add(what[1]); }
		if (what[2] != null) { ev.add("said"); ev.add(what[2]); }
		if (runs != null) { ev.add("runs"); ev.add(runs); }
		// the buddy's own death: where it happened, for the things that fell there (the body stays there until the respawn)
		LocalPlayer self = mc().player;
		if ("death".equals(what[0]) && self != null && what[1] instanceof String who && who.equalsIgnoreCase(self.getGameProfile().name())) {
			net.minecraft.core.BlockPos at = self.blockPosition();
			Collections.addAll(ev, "x", at.getX(), "y", at.getY(), "z", at.getZ(), "dim", self.level().dimension().identifier().toString());
		}
		Exchange.emit("chat", ev.toArray());
		if ("game".equals(kind)) Modules.heard(clean);
		AfkConfig c = AfkConfig.get();
		LocalPlayer p = mc().player;

		// Your own PM counts as a conversation too: if you ask someone for money, the payment often
		// arrives before any reply is typed, and otherwise we would not know who it came from.
		// a PM line counts only from the start of the line: in public chat a player's own name stands in front, so what is
		// typed there cannot pass for a PM to or from someone else, also where a profile's text has no ^
		Matcher pmOut = Profiles.match("pmOut", clean, null);
		if (pmOut.lookingAt()) {
			lastTalker = pmOut.group(1);
			pmPartner = pmOut.group(1); pmPartnerAt = System.currentTimeMillis();
			return;
		}
		// handle PMs ("[Name → jou] text") separately
		Matcher pm = Profiles.match("pm", clean, null);
		if (pm.lookingAt()) {
			lastTalker = pm.group(1);
			pmPartner = pm.group(1); pmPartnerAt = System.currentTimeMillis();
			if (Grinder.active()) Grinder.interrupt(lookAtMs(c), nearbyPlayer(pm.group(1)), "pm");
			Alt.pm(pm.group(1), pm.group(2));   // only real commands, never a guess from loose words such as "kom je?"
			return;
		}
		Matcher said = P_SAID.matcher(clean);
		if (said.find() && p != null) {
			String who = said.group(1) != null ? said.group(1) : said.group(3), msg = said.group(1) != null ? said.group(2) : said.group(4);
			String me = p.getGameProfile().name();
			if (who != null && msg != null && !who.equalsIgnoreCase(me) && msg.toLowerCase(Locale.ROOT).matches(".*\\b" + Pattern.quote(me.toLowerCase(Locale.ROOT)) + "\\b.*")) {
				lastTalker = who;
				if (Grinder.active()) Grinder.interrupt(lookAtMs(c), nearbyPlayer(who), "mention");
			}
		}
		// Signed player chat is never a server message
		if (kind.equals("chat")) return;
		Alt.serverLine(clean);
		// All server triggers match on the START of the line: typed text always has a name in front and so does not match.
		WheatFarm.chat(clean);

		// teleport coming or in progress (tpa accepted, /home, warp): stop the farm and wandering right away, otherwise a small step cancels the teleport
		// combat tag: whoever logs out during combat is killed by the server ("Je kunt 20s niet ... veilig uitloggen")
		if (Profiles.has("fightOff", clean)) { if (combatUntil > 0) combatUntil = 0; }
		else if (Profiles.has("fightOn", clean)) {
			java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s*s").matcher(clean);
			int sec = m.find() ? Integer.parseInt(m.group(1)) : 20;
			combatTagMs = (sec + 5) * 1000L;   // margin: the tag runs from the last hit
			combatUntil = System.currentTimeMillis() + combatTagMs;
		}

		// HuskHomes: "Je wordt geteleporteerd over 3 seconden", a player warp: "Je wordt over 3 seconden geteleporteerd"
		if (Profiles.has("teleportComing", clean)) {
			if (Grinder.active()) Grinder.stop("teleport");
			Lumberjack.stop("teleport");
			CropFarmer.stop("teleport");
			WheatFarm.teleport();
			Builder.stop("teleport");
			Follow.teleport();
			Wander.abort("teleport");
		}

		if (Profiles.has("teleportComing", clean) || Profiles.has("teleported", clean)) ignoreLookUntil = System.currentTimeMillis() + 4000;

		Follow.chat(clean);
		// HuskHomes after every teleport. Right after a /home or a click in /homes it is that home: from there on, who
		// comes near is at the home (players.home_radius), and the panic's teleport home came through.
		if (Profiles.has("teleported", clean)) {
			String home = homeAsked != null && System.currentTimeMillis() - homeAskedAt < 15_000 ? homeAsked : null;
			homeAsked = null;
			if (home == null) { setZone("elsewhere", null); return; }
			Exchange.emit("teleport", "kind", "home", "name", home);
			if (panicHomeAt != 0) panicHomeArrived = true;
			// arrival: take the position after 1 s (the teleport has been processed by then)
			schedule(1.0, () -> { if (mc().player != null) setZone("home:" + home, mc().player.position()); HomeWalks.arrived(home); });
			return;
		}

		Matcher m = Profiles.match("tpa", clean, null);
		if (m.find()) {
			String from = m.group(1);
			boolean trusted = c.isTrusted(from);
			if (c.afk && trusted && active()) {
				double wait = rnd(c.tpaMin, c.tpaMax);
				String accept = Profiles.cmd("tpaccept", from);
				if (accept == null) { Exchange.emit("tpa", "from", from, "trusted", true, "action", "no command"); return; }
				schedule(wait, () -> { sendTyped(accept, nearbyPlayer(from)); Exchange.emit("tpa_accepted", "from", from, "wait", wait);  });
				Exchange.emit("tpa", "from", from, "trusted", true, "action", "accept");
			} else {
				Exchange.emit("tpa", "from", from, "trusted", trusted, "action", "none");
			}
		}
	}

	private void onOwnChat(String text) {
		Exchange.emit("sent", "text", text);
	}

	private void onOwnCommand(String cmd) {
		Exchange.emit("sent", "text", "/" + cmd);
		String[] w = cmd.trim().split("\\s+");
		if (w.length == 2 && w[0].equalsIgnoreCase("home")) homeAsked(w[1]);
		MenuLog.sent(cmd);
		Recorder.command(cmd);
	}

	// ---------- start of the tick: actions vanilla also does here (clicking, using an item) ----------
	private boolean pendingUse = false;

	private void onStartTick(Minecraft mc) {
		HeldKeys.startTick(mc);
		// A bite or a sip the server has just ended (its event is handled before this tick): the key up before
		// Minecraft.handleKeybinds, which with the key down and nothing in use clicks whatever the crosshair is on. Let go
		// at the end of the tick, it was one tick late: a chest minecart ahead opened at every bite (22 Oct 2026).
		if ((eatTicks > 0 || drinkTicks > 0) && mc.player != null && !mc.player.isUsingItem()) HeldKeys.set(mc.options.keyUse, false);
		Jobs.startTick(mc);
		Alt.startTick(mc);
		MenuPick.startTick(mc);
		MenuLog.startTick(mc);
		if (pendingUse) {
			pendingUse = false;
			LocalPlayer p = mc.player;
			if (p != null && mc.gameMode != null
				&& (p.getMainHandItem().get(DataComponents.FOOD) != null || p.getMainHandItem().getItem() == net.minecraft.world.item.Items.OMINOUS_BOTTLE)) {
				mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
				// the buddy's own bite holds the use key only once it has begun: a key that is down while nothing is in use
				// is a click on whatever the crosshair is on (Minecraft.handleKeybinds), a door, a chest, a bed
				if ((eatTicks > 0 || drinkTicks > 0) && p.isUsingItem()) HeldKeys.set(mc.options.keyUse, true);
			}
		}
	}

	// ---------- tick ----------
	private void onTick(Minecraft mc) {
		tick++;
		Profiles.tick(mc);
		Details.tick(mc);
		World3d.tick(mc);

		long now = System.currentTimeMillis();
		// first take out the jobs that are due, only then run them:
		// a job may schedule a new job itself without breaking the list
		List<Job> due = null;
		Iterator<Job> it = jobs.iterator();
		while (it.hasNext()) {
			Job j = it.next();
			if (j.due <= now) { it.remove(); (due == null ? due = new ArrayList<>() : due).add(j); }
		}
		if (due != null) for (Job j : due) { try { j.run.run(); } catch (Exception e) { Exchange.emit("error", "where", "task", "notice", String.valueOf(e)); } }
		Yield.tick();

		LocalPlayer p = mc.player;
		if (p == null || mc.level == null) {
			if (tick % 20 == 0) offlineCommands();
			return;
		}
		if (!AfkConfig.get().modEnabled) { Jobs.stop("mod off"); return; }
		Modules.tick();

		// The mod's own movement (looking around/walking) first, and that does not count as presence
		Watchdog.tick(p, AfkConfig.get());
		ClickLog.ownSwing(p, !Jobs.anySteering());   // own hand as reference
		boolean selfMoving = look.tick(p) | Wander.tick(p, AfkConfig.get()) | Jobs.tick(mc, p, AfkConfig.get());
		if (selfMoving) { lastYaw = p.getYRot(); lastPitch = p.getXRot(); lastPos = p.position(); }
		if (jumpTicks > 0 && --jumpTicks == 0) HeldKeys.set(mc.options.keyJump, false);
		watchJump(p);
		if (drinkTicks > 0) {
			drinkTicks--;
			boolean bottle = p.getMainHandItem().getItem() == net.minecraft.world.item.Items.OMINOUS_BOTTLE;
			if (!p.isUsingItem() && bottle && drinkTicks > 10) pendingUse = true;   // again every tick, just like holding the button
			if (!bottle || drinkTicks == 0 || p.hasEffect(net.minecraft.world.effect.MobEffects.BAD_OMEN)) {
				drinkTicks = 0;
				HeldKeys.set(mc.options.keyUse, false);
				if (drinkRestoreSlot >= 0) { p.getInventory().setSelectedSlot(drinkRestoreSlot); drinkRestoreSlot = -1; }
				boolean omen = p.hasEffect(net.minecraft.world.effect.MobEffects.BAD_OMEN);
				Exchange.emit("raid", "action", "drunk", "omen", omen);
				// One little jump after drinking. It used to be timed from the start of drinking, so a sip that
				// took longer left barely a second before the jump: nobody reacts that fast. Now it counts from
				// the last sip, with a pause that differs each time.
				if (omen && AfkConfig.get().raidJump) schedule(2.0 + ThreadLocalRandom.current().nextDouble() * 2.5, this::jumpOnce);
			}
		}
		MenuLog.tick(p);
		Containers.tick(mc, p);
		// the account's own name as the game has it, once a start: a renamed account is followed by the panel and the bridge, which
		// read altName
		if (!ownNameChecked) {
			ownNameChecked = true;
			String own = mc.getUser().getName();
			AfkConfig cn = AfkConfig.get();
			if (own != null && !own.isBlank() && !own.equals(cn.altName)) {
				cn.altName = own;
				AfkConfig.save();
				Exchange.emit("config_set", "key", "altName", "value", own, "reason", "login");
			}
		}
		Recorder.tick(mc, p);
		if (eatTicks > 0) {
			eatTicks--;
			if (p.isUsingItem()) eatBitten = true;
			else {
				// The key comes up the tick a bite is over. It stayed down 45 ticks for a bite of 32, and vanilla clicks the
				// crosshair every 4 ticks while it is down and nothing is in use, so after a bite that filled the food bar a door, a chest,
				// a bed or a villager in front was clicked up to three times (the review of 22 Oct 2026). A bite that did not
				// begin is tried a few ticks, without the key; hunger asks again after four seconds.
				HeldKeys.set(mc.options.keyUse, false);
				if (eatBitten || eatTicks <= 40) eatTicks = 0;
				else pendingUse = true;
			}
			if (eatTicks == 0) {
				HeldKeys.set(mc.options.keyUse, false);
				if (eatRestoreSlot >= 0) { p.getInventory().setSelectedSlot(eatRestoreSlot); eatRestoreSlot = -1; }
				Exchange.emit("eat", "action", eatBitten ? "done" : "did not begin", "food", p.getFoodData().getFoodLevel());
			}
		}

		// AutoEat and our own fallback click "use". If the crosshair is on a hopper, trapdoor or chest at that
		// moment, vanilla opens that block instead of you eating: you have a screen open, the farm stands still
		// and you are still not eating (hopper on 25 Sep 2026). Sneaking disables the block interaction, so as
		// long as there is food in hand and such a block is in the crosshair, we crouch for a moment. If the farm
		// is already sneaking itself, it is covered and we keep off the key.
		boolean foodInHand = p.getMainHandItem().get(DataComponents.FOOD) != null || p.getOffhandItem().get(DataComponents.FOOD) != null;
		// only while the mod does the work: if you play yourself, nobody else decides when you crouch,
		// and you just want to be able to open that chest you are looking at
		// Only while farming and lumberjacking. In AFK mode without a farm the mod already looks away from
		// chests and doors itself (SafeLook below), and that is better than crouching: otherwise you crouched
		// every time you looked at a chest at your base.
		boolean modAtWork = Grinder.active() || Lumberjack.isBusy() || Builder.isBusy();
		// "food in your hand" alone is too broad: AutoEat often leaves the steak selected, and then you crouched
		// every time you looked at a chest or door. So only around a real eating attempt: you are already eating,
		// or hunger is close to the eating threshold and AutoEat is about to click.
		boolean aboutToEat = foodInHand && p.getFoodData().getFoodLevel() <= AfkConfig.get().eatBelow + 2;
		boolean wantEatSneak = modAtWork && (eatTicks > 0 || p.isUsingItem() || aboutToEat) && SafeLook.lookingAtInteractable(p);
		if (wantEatSneak && !eatSneak && !Grinder.isSneaking() && !afkCrouching) { HeldKeys.set(mc.options.keyShift, true); eatSneak = true; }
		else if (eatSneak && !wantEatSneak) { eatSneak = false; if (!Grinder.isSneaking() && !afkCrouching && !Builder.crouching()) HeldKeys.set(mc.options.keyShift, false); }

		// A screen opened anyway while you are farming AFK: close it. Otherwise the farm stands still
		// until someone notices, and you stand there doing nothing with an open chest screen in front of you.
		// Not after three seconds: on 3 Oct at 01:54 it shut the clan menu you had just opened yourself with /clans.
		// So at least a minute, and as long as you move the mouse in it you are there and that minute starts over.
		if (p.containerMenu != p.inventoryMenu) {
			double mx = mc.mouseHandler.xpos(), my = mc.mouseHandler.ypos();
			if (screenSince == 0 || mx != screenMouseX || my != screenMouseY) {
				screenSince = now; screenMouseX = mx; screenMouseY = my;
				screenCloseAfter = (long) (rnd(60, 90) * 1000);
			}
			if (AfkConfig.get().afk && Grinder.active() && !Jobs.anySteering(Jobs.Job.MOB_FARM) && now - screenSince > screenCloseAfter) {
				var screen = net.milenco.buddy.version.Game.openScreen(mc);
				String title = screen == null ? "container" : screen.getTitle().getString();
				screenSince = 0;
				p.closeContainer();
				Exchange.emit("screen", "action", "shut", "title", title);
			}
		} else screenSince = 0;

		// Who is pressing the sneak key? When in doubt ("I started crouching by myself") this is the answer,
		// without having to guess which function or which other mod did it.
		boolean shiftNow = mc.options.keyShift.isDown();
		if (shiftNow != shiftBefore) {
			shiftBefore = shiftNow;
			String by = Grinder.isSneaking() ? "farm" : afkCrouching ? "afk-crouch" : eatSneak ? "food" : Lumberjack.isBusy() ? "woodcutting" : Builder.crouching() ? "build" : Pen.busy() ? "pen" : Follow.target() != null ? "follow" : "not this mod";
			Exchange.emit("crouch", "on", shiftNow, "by", by);
		}

		// Presence: keys/mouse buttons, or looking around without a teleport (position jump > 2 blocks = teleport, does not count)
		Vec3 cur = p.position();
		boolean eating = eatTicks > 0 || p.isUsingItem() || p.getMainHandItem().get(DataComponents.FOOD) != null || p.getOffhandItem().get(DataComponents.FOOD) != null;
		String presenceKey = selfMoving ? null
			: mc.options.keyUp.isDown() ? "forward" : mc.options.keyDown.isDown() ? "back" : mc.options.keyLeft.isDown() ? "left" : mc.options.keyRight.isDown() ? "right"
			: (mc.options.keyJump.isDown() && jumpTicks == 0) ? "jump" : (mc.options.keyShift.isDown() && !afkCrouching) ? "sneak" : mc.options.keySprint.isDown() ? "sprint"
			: mc.options.keyAttack.isDown() ? "attack" : (mc.options.keyUse.isDown() && !eating) ? "use" : null;   // AutoEat/fallback press 'use'
		boolean keys = presenceKey != null;
		boolean looked = !selfMoving && now > ignoreLookUntil && lastPos != null && (p.getYRot() != lastYaw || p.getXRot() != lastPitch) && cur.distanceToSqr(lastPos) < 4.0;
		if (lastPos != null && cur.distanceToSqr(lastPos) >= 4.0) ignoreLookUntil = now + 2000;   // teleport: the server also sets the view direction
		lastPos = cur; lastYaw = p.getYRot(); lastPitch = p.getXRot();
		if (keys || looked) {
			if (lastInput == 0) lastInput = now;
			boolean wasIdle = now - lastInput > 2000;
			lastInput = now;
			AfkConfig c = AfkConfig.get();
			if (c.afk && c.afkAuto && c.autoAfkOffOnMove && wasIdle) {
				c.afk = false; c.afkAuto = false; AfkConfig.save();
				String why = keys ? presenceKey : "look around";
				Exchange.emit("config_set", "key", "afk", "value", false, "reason", "movement", "by", why);
			}
		} else if (lastInput == 0) lastInput = now;

		// Every tick: once a second was too slow against a player. A sword takes you from above the threshold to
		// dead within that second, so the check never saw the health in between (4 Oct 12:45).
		panicCheck(p, AfkConfig.get());
		if (mc.level == null || mc.player == null) return;   // the panic logout disconnects right away: skip the rest of the tick

		if (tick % 20 == 0) {
			AfkConfig ca = AfkConfig.get();
			if (!ca.afk && ca.autoAfk && lastInput > 0 && now - lastInput > ca.autoAfkMin * 60_000L && active()) {
				ca.afk = true; ca.afkAuto = true; AfkConfig.save();
				Exchange.emit("config_set", "key", "afk", "value", true, "reason", "auto");
			}
			Grinder.resumeTick(p, ca);
			Lumberjack.resumeTick(p, ca);
			if (ca.afk && ca.lookAround && active() && !Jobs.anySteering() && !look.busy() && !Wander.busy()
				&& ThreadLocalRandom.current().nextInt(ca.glanceEverySec) == 0) {
				// casual glance: usually small, sometimes a big turn
				boolean big = ThreadLocalRandom.current().nextInt(10) < 3;
				double yaw = Math.toRadians(p.getYRot() + (big ? ThreadLocalRandom.current().nextDouble(-150, 150) : ThreadLocalRandom.current().nextDouble(-25, 25)));
				double pitch = Math.toRadians(net.minecraft.util.Mth.clamp(p.getXRot() + ThreadLocalRandom.current().nextDouble(-12, 12), -60, 60));
				Vec3 dir = new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
				look.start(p, p.getEyePosition().add(dir.scale(6)), 1.5f);
				Exchange.emit("look", "why", big ? "look wide" : "look");
			}
			if (ca.afk && ca.wander && active() && !Jobs.anySteering() && !Grinder.waitingToResume() && !Wander.busy()) {
				if (nextWander == 0) nextWander = now + (long) (rnd(20, 70) * 1000);   // shortly after AFK or after farming, a round right away
				else if (now >= nextWander) {
					nextWander = now + (long) (rnd(ca.wanderEveryMin * 0.5, ca.wanderEveryMin * 1.5) * 60_000);
					if (!Wander.start(ca.wanderMaxBlocks)) Exchange.emit("wander", "action", "no safe path");
				}
			} else if (!ca.afk) nextWander = 0;
			if (ca.afk && !Jobs.anySteering() && !look.busy() && !Wander.busy() && eatTicks == 0 && SafeLook.lookingAtInteractable(p)) {
				Vec3 safe = SafeLook.findSafeTarget(p);
				if (safe != null) { look.start(p, safe, 1.5f); Exchange.emit("look", "why", "away from an interactive block"); }
			}
			// stay crouched in AFK mode (not while farming, the farm handles that itself)
			boolean wantCrouch = ca.afkCrouch && ca.afk && !Jobs.anySteering() && !Builder.isOn() && p.onGround();
			if (wantCrouch != afkCrouching) {
				afkCrouching = wantCrouch;
				HeldKeys.set(mc.options.keyShift, wantCrouch);
			}
			Switches.tick();
			Sidebar.tick(mc);
			hungerGuard(p, ca, now);
			if (AfkConfig.reloadIfChanged()) Exchange.emit("config_reloaded", "afk", AfkConfig.get().afk);
			for (JsonObject o : Exchange.pollCommands()) handleCommand(o);
			checkPlayers(p, AfkConfig.get());
		}
		if (tick % 100 == 0) {
			AfkConfig c = AfkConfig.get();
			Vec3 pos = p.position();
			Exchange.emit("location", "dim", p.level().dimension().identifier().toString(), "x", Math.round(pos.x), "y", Math.round(pos.y), "z", Math.round(pos.z), "zone", zone);
			ItemStack hand = p.getMainHandItem();
			// hand_slot: the item alone does not say which of several equal stacks is held, and the buddy's page marks it in the hotbar
			Exchange.emit("status", "hp", p.getHealth(), "food", p.getFoodData().getFoodLevel(), "hand", hand.isEmpty() ? "" : itemId(hand),
				"hand_slot", p.getInventory().getSelectedSlot(), "menu_open", p.containerMenu != p.inventoryMenu, "afk", c.afk, "server", serverIp(), "active", active(),
				"food_hotbar", countFood(p, 0, 9), "food_inv", countFood(p, 9, 36), "air", p.getAirSupply());
			inventoryCheck(p);
		}
	}

	/** Report players: at a home (radius homeRadius around the arrival spot) and in fixed zones from the config (same world). */
	private void checkPlayers(LocalPlayer me, AfkConfig c) {
		if (!c.playerAlerts && !(c.afk && c.lookAround)) return;
		if (mc().level == null || me == null) return;   // just logged out: no world anymore
		String dim = me.level().dimension().identifier().toString();
		List<Map<String, Object>> near = new ArrayList<>();
		StringBuilder key = new StringBuilder();
		long now = System.currentTimeMillis();
		for (Player o : mc().level.players()) {
			if (o == me) continue;
			String name = o.getGameProfile().name();
			if (name == null || name.isBlank()) continue;   // NPCs / fake players
			if (name.equalsIgnoreCase("FreeCamera") || o.getUUID().equals(me.getUUID())) continue;   // own freecam camera
			if (!listed(o)) continue;   // not in the tab list: a figure, not a real player
			maybeLook(me, c, o, name, o.distanceTo(me), c.isTrusted(name));
			String where = null; double d = 0; int cooldown = c.alertCooldownMin;
			if (zone.startsWith("home:") && zoneAnchor != null) {
				d = o.position().distanceTo(zoneAnchor);
				if (d <= c.homeRadius) where = zone.substring(5);
			}
			if (where == null) {
				for (AfkConfig.Zone z : c.zones) {
					if (!z.dim().equals(dim)) continue;
					double dz = o.position().distanceTo(new Vec3(z.x + 0.5, z.y, z.z + 0.5));
					if (dz <= z.radius) { where = z.name; d = dz; cooldown = z.cooldownMin; break; }
				}
			}
			if (where == null) continue;
			boolean trusted = c.isTrusted(name);
			near.add(Map.of("name", name, "dist", Math.round(d), "zone", where, "trusted", trusted));
			key.append(where).append('/').append(name).append(',');
			String k = (where + "/" + name).toLowerCase(Locale.ROOT);
			if (now - alerted.getOrDefault(k, 0L) > cooldown * 60_000L) {
				alerted.put(k, now);
				Exchange.emit("player_near", "name", name, "dist", Math.round(d), "zone", where, "trusted", trusted);
			}
		}
		if (!key.toString().equals(lastPlayersKey)) { lastPlayersKey = key.toString(); Exchange.emit("players", "list", near); }
	}

	private void lookAt(Vec3 target, String why) {
		LocalPlayer p = mc().player;
		if (p == null || Wander.busy()) return;
		look.start(p, target, 4f);
		Exchange.emit("look", "why", why, "yaw", Math.round(look.toYaw), "pitch", Math.round(look.toPitch));
	}

	/** Keep the background framerate steady (otherwise 10-30 fps and ticks in bunches). */
	public static void applySteadyFps() {
		var o = mc().options;
		var want = AfkConfig.get().steadyFps ? net.minecraft.client.InactivityFpsLimit.MINIMIZED : net.minecraft.client.InactivityFpsLimit.AFK;
		if (o.inactivityFpsLimit().get() != want) { o.inactivityFpsLimit().set(want); o.save(); }
	}

	/** Raid farm: when the omen has run out, drink a new ominous bottle from the hotbar. */
	private static long lastDrink = -1;   // -1 = not yet taken from the log
	/** When a bottle was last drunk (0 = not yet). */
	public static long lastBottle() { return lastDrink; }

	private static long lastOmenSeen = 0;
	/** Least time after the omen is gone before we count the bottle as lost: the bossbar comes only after it. */
	private static final long OMEN_GRACE = 30_000;
	/** Least wait after a bottle: the Raid Omen alone lasts 30 s, on top of drinking and walking into the village. */
	private static final long MIN_BOTTLE_WAIT = 90_000;

	/**
	 * Is the bottle we drank still on its way to a raid? True while an omen is on you, and for a while after
	 * a bottle or after the omen ran out. With a buffer of only 30 s after drinking, the second bottle went
	 * in 33 s after the first: the Raid Omen had just run out, the bossbar came 50 ms later.
	 */
	public static boolean bottlePending(LocalPlayer p) {
		long now = System.currentTimeMillis();
		if (p != null && (p.hasEffect(net.minecraft.world.effect.MobEffects.BAD_OMEN) || p.hasEffect(net.minecraft.world.effect.MobEffects.RAID_OMEN))) {
			lastOmenSeen = now;
			return true;
		}
		if (lastDrink < 0) lastDrink = RaidLog.lastBottle();   // the buffer survives a restart too
		if (lastOmenSeen > 0 && now - lastOmenSeen < OMEN_GRACE) return true;
		long buffer = Math.max(MIN_BOTTLE_WAIT, AfkConfig.get().raidWaitMaxSec * 1000L);
		return lastDrink > 0 && now - lastDrink < buffer;
	}

	public static void drinkOminous(LocalPlayer p) {
		AfkConfig c = AfkConfig.get();
		if (lastDrink < 0) lastDrink = RaidLog.lastBottle();
		if (RaidState.inProgress()) { lastOmenSeen = 0; return; }   // raid running: the omen has done its job
		if (bottlePending(p)) return;
		if (p.isUsingItem() || p.containerMenu != p.inventoryMenu) return;
		long now = System.currentTimeMillis();

		long pause = Math.max(5, c.raidDrinkPauseSec) * 1000L;
		if (lastDrink > 0 && !RaidState.seenSinceBottle()) {
			// the buffer is over and no raid ever appeared: that bottle is lost, try again
		} else if (RaidState.victoryAt() > 0 && now - RaidState.victoryAt() < pause) {
			return;   // the raid is over, but the waiting time is still running
		}

		int slot = -1;
		for (int i = 0; i < 9; i++) if (p.getInventory().getItem(i).getItem() == net.minecraft.world.item.Items.OMINOUS_BOTTLE) { slot = i; break; }
		if (slot < 0) {
			// alt with a full hotbar: the bottles are then in the backpack. Fetch them and try again next round.
			if (toHotbar(p, mc(), st -> st.getItem() == net.minecraft.world.item.Items.OMINOUS_BOTTLE,
					java.util.Comparator.comparingInt(ItemStack::getCount), "bottle")) return;
			Exchange.emit("raid", "action", "no bottle");
			if (Grinder.active()) Grinder.stop("out of bottles");
			lastDrink = now;
			return;
		}
		lastDrink = now;
		// remember the bottle's level: during the raid the effect is already gone, and from level 2 a wave is added
		var amp = p.getInventory().getItem(slot).get(DataComponents.OMINOUS_BOTTLE_AMPLIFIER);
		int bottleLevel = amp == null ? 1 : amp.value() + 1;
		Exchange.emit("raid", "action", "bottle level", "level", bottleLevel, "component", amp == null ? "missing" : String.valueOf(amp.value()));
		RaidState.bottleLevel(bottleLevel);
		RaidState.bottleDrunk();
		if (INSTANCE != null) INSTANCE.startDrink(p, slot);
	}

	private int drinkTicks = 0, drinkRestoreSlot = -1, jumpTicks = 0;

	/** Jump once: hold the jump key for a few ticks, like a real press. */
	private void jumpOnce() {
		LocalPlayer p = mc().player;
		// log whether the little jump really happens: the owner saw no jump after drinking and the
		// events did not show whether one was tried (a jump is shorter than the 5 s between measurements)
		if (p == null || !p.onGround()) { Exchange.emit("raid", "action", "hop skipped", "reason", p == null ? "no player" : "not on the ground"); return; }
		jumpTicks = ThreadLocalRandom.current().nextInt(2, 5);
		HeldKeys.set(mc().options.keyJump, true);
		// "sprongetje" alone still did not tell whether the buddy left the ground: the key was pressed every time,
		// yet the owner saw no jump. Measure the peak and look for a ceiling or a block that damps jumps.
		jumpStartY = jumpPeakY = p.getY();
		jumpWatch = 15;
		boolean headroom = p.level().noCollision(p, p.getBoundingBox().move(0, 0.6, 0));
		String under = BuiltInRegistries.BLOCK.getKey(p.level().getBlockState(p.getOnPos()).getBlock()).getPath();
		Exchange.emit("raid", "action", "hop", "ticks", jumpTicks, "y", Math.round(p.getY() * 100) / 100.0,
			"room_above", headroom, "below", under);
	}

	private int jumpWatch = 0;
	private double jumpStartY, jumpPeakY;

	private void watchJump(LocalPlayer p) {
		if (jumpWatch <= 0) return;
		jumpPeakY = Math.max(jumpPeakY, p.getY());
		if (--jumpWatch == 0)
			Exchange.emit("raid", "action", "hop measured", "height", Math.round((jumpPeakY - jumpStartY) * 100) / 100.0);
	}

	private void startDrink(LocalPlayer p, int slot) {
		drinkRestoreSlot = p.getInventory().getSelectedSlot();
		p.getInventory().setSelectedSlot(slot);
		pendingUse = true;   // the right mouse button is held once the sip has begun (onStartTick), as for a bite
		drinkTicks = 60;
		Exchange.emit("raid", "action", "drinks",
			"bad_omen", p.hasEffect(net.minecraft.world.effect.MobEffects.BAD_OMEN),
			"raid_omen", p.hasEffect(net.minecraft.world.effect.MobEffects.RAID_OMEN),
			"raid_busy", RaidState.inProgress(), "victory_ago_s", RaidState.victoryAt() == 0 ? -1 : (System.currentTimeMillis() - RaidState.victoryAt()) / 1000);
	}

	/**
	 * Panic: nearly dead without a totem -> teleport home; if that does not come through, log out and shut everything
	 * down. In combat it only teleports: logging out is what kills you then.
	 */
	private long joinedAt = 0;
	private static boolean panicArmed = false;       // health above the threshold seen since joining: the value is real
	private static long panicHomeAt = 0;             // the panic teleport home was sent at this moment (0 = none under way)
	private static boolean panicHomeArrived = false; // the server confirmed that teleport: home is safe, stay online
	private static int panicHomeTries = 0;           // how often that teleport went out while the health stayed low
	/** Menu, click and teleport take well under a second; no "Naar home" after this long and it is not coming. */
	private static final long PANIC_HOME_WAIT_MS = 2000;
	/** Where logging out is no way out the teleport is all there is, so it gets this many goes. */
	private static final int PANIC_HOME_TRIES = 3;

	private void panicCheck(LocalPlayer p, AfkConfig c) {
		if (!c.panicLogout || panicStopped || !active()) return;
		if (c.panicOnlyWhenBusy && !(c.afk || Jobs.unattendedOn())) return;   // you play yourself: then you decide
		long now = System.currentTimeMillis();
		if (joinedAt == 0) joinedAt = now;
		long sinceJoin = now - joinedAt;
		if (sinceJoin < 3_000) return;                                     // just logged in: health has not been sent yet
		float hp = p.getHealth();
		// Waiting a flat 15 s after every login left you unguarded: walking into lava 12 s after joining killed you
		// without a panic. A healthy reading proves the real health has arrived, so that arms it at once.
		if (hp > c.panicHealth) panicArmed = true;
		if (!panicArmed && sinceJoin < 15_000) return;
		// already dead: nothing helps any more, and dying ends the combat tag without a word from the server
		if (hp <= 0 || p.isDeadOrDying() || !p.isAlive()) { combatUntil = 0; return; }
		// The server says "Je bent in gevecht" once and lets the tag run on from every hit after it. Counted from that
		// one line, a fight of a minute looked over after 25 s, and logging out then is the death it should prevent.
		if (now < combatUntil && p.hurtTime > 0) combatUntil = Math.max(combatUntil, now + combatTagMs);
		if (hp > c.panicHealth) { panicHomeAt = 0; panicHomeArrived = false; panicHomeTries = 0; combatReported = false; return; }
		if (p.getOffhandItem().getItem() == net.minecraft.world.item.Items.TOTEM_OF_UNDYING) return;
		boolean inCombat = now < combatUntil;       // server: "je kunt 20s niet veilig uitloggen" -> then it kills you
		// Home first: logging out leaves you in the lava or beside the mob for when you come back, and it ends the
		// session. Only when the teleport does not come through is logging out still the way out.
		// It used to wait for the combat tag to end before trying anything, because the server says you cannot
		// teleport for 20 s. The click in /homes goes through all the same, and waiting meant standing there until
		// the other player had finished the job (killed at 3.8 health, a second after the warning).
		String home = c.panicHome == null ? "" : c.panicHome.trim();
		if (!home.isEmpty()) {
			if (panicHomeArrived) return;
			boolean waited = panicHomeAt != 0 && now - panicHomeAt >= PANIC_HOME_WAIT_MS;
			if (panicHomeAt == 0 || (waited && inCombat && panicHomeTries < PANIC_HOME_TRIES)) {
				if (panicHomeAt == 0) {
					Exchange.emit("emergency stop", "hp", hp, "reason", "nearly dead without a totem: going home", "home", home, "fight", inCombat);
				}
				panicHomeAt = now;
				panicHomeTries++;
				panicHomeArrived = false;
				Jobs.stop("emergency stop", Jobs.Job.FOLLOW);   // a farm at the keys would fight MenuPick for the homes menu
				Wander.abort("emergency stop");
				if (p.containerMenu != p.inventoryMenu) p.closeContainer();   // MenuPick would take an open chest for the homes menu
				goHome(home);
				return;
			}
			if (!waited) return;
		}
		if (inCombat) {
			if (inCombat && !combatReported) {
				combatReported = true;
				Jobs.stop("nearly dead", Jobs.Job.FOLLOW);
				Wander.abort("nearly dead");
				Exchange.emit("emergency stop", "hp", hp, "reason", "in a fight: not logged out yet");
			}
			return;
		}
		combatReported = false;
		panicStopped = true;
		Jobs.stop("emergency stop", Jobs.Job.FOLLOW);
		Wander.abort("emergency stop");
		AfkConfig cfg = AfkConfig.get();
		if (cfg.afk) { cfg.afk = false; cfg.afkAuto = false; AfkConfig.save(); }
		Exchange.emit("emergency stop", "hp", p.getHealth(), "reason", "nearly dead without a totem");
		lastServer = mc().getCurrentServer();
		logOut("emergency stop");
	}

	/**
	 * Log out cleanly, exactly like the "Disconnect" button.
	 * disconnectWithProgressScreen() alone does NOT close the connection: your player then stays on the server
	 * until it times out (the body stays visible, logging in again fails). ClientLevel.disconnect() does close it.
	 * And it has to happen outside the tick: from another thread Minecraft.execute queues it.
	 */
	public static void logOut(String why) {
		new Thread(() -> mc().execute(() -> {
			Minecraft m = mc();
			try {
				m.disconnectFromWorld(Component.literal("Minecraft Buddy: " + why));
			} catch (Exception e) {   // whatever goes wrong: the connection has to close
				Exchange.emit("error", "where", "log out", "notice", String.valueOf(e));
				if (m.level != null) m.level.disconnect(Component.literal("Minecraft Buddy: " + why));
				m.disconnectWithProgressScreen();
			}
			Exchange.emit("connection", "action", "logged out", "reason", why);
		}), "buddy-logout").start();
	}

	/**
	 * Off the server from the panel, and on again with "join": out
	 * cleanly, and out the buddy stays until "join", also when the game starts anew (the launcher joins by itself, so the game leaves
	 * again at once).
	 */
	static void leave() {
		AfkConfig c = AfkConfig.get();
		c.stayOffline = true;
		AfkConfig.save();
		if (mc().level != null) logOut("panel");
		Exchange.emit("connection", "action", "stays away");
	}

	/** Back to the server last played on. */
	static void join() {
		AfkConfig c = AfkConfig.get();
		c.stayOffline = false;
		AfkConfig.save();
		Minecraft m = mc();
		if (m.level != null) return;
		var server = lastServer;
		if (server == null) { Exchange.emit("error", "msg", "join: no server known"); return; }
		failedConnections = 0;
		Exchange.emit("connection", "action", "comes back", "server", server.ip);
		m.execute(() -> net.minecraft.client.gui.screens.ConnectScreen.startConnecting(new net.minecraft.client.gui.screens.TitleScreen(), m,
			net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(server.ip), server, false, null));
	}

	/** Not in a world: only the commands that make sense there (join, ping, state, the panel's profiles), the rest is old by then. */
	private void offlineCommands() {
		for (JsonObject o : Exchange.pollCommands()) {
			String cmd = o.has("cmd") ? o.get("cmd").getAsString() : "";
			if (cmd.equals("join")) join();
			else if (cmd.equals("leave")) leave();
			else if (cmd.equals("ping")) Exchange.emit("pong");
			else if (cmd.equals("state")) Details.offline();
			// the panel's profile editor needs no world: saved while the buddy is off a server (after Leave server)
			else if (cmd.equals("profiles")) Profiles.command(cmd, o);
		}
	}

	/** Reconnect automatically after a lost connection (not after a panic logout). */
	public static void registerReconnect() {
		net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (!(screen instanceof net.minecraft.client.gui.screens.DisconnectedScreen)) return;
			AfkConfig c = AfkConfig.get();
			if (!c.autoReconnect || panicStopped || reconnectPlanned) return;   // AFTER_INIT fires more than once per screen
			reconnectPlanned = true;
			String reason = "";
			try {
				var det = ((net.milenco.buddy.mixin.client.DisconnectedScreenAccessor) screen).buddy$details();
				if (det != null && det.reason() != null) reason = det.reason().getString();
			} catch (Throwable ignored) { }
			failedConnections++;
			/*
			 * The Mojang session expires after about a day. After that, reconnecting never works again,
			 * and only a fresh start of the client fetches a new session. On 24 Sep the alt was offline
			 * like that for seven hours with 731 futile attempts. So the game quits; the service
			 * restarts it right away.
			 */
			if (c.reconnectMaxAttempts > 0 && failedConnections >= c.reconnectMaxAttempts) {
				Exchange.emit("connection", "action", "quitting for a new session", "attempts", failedConnections, "reason", reason);
				client.stop();
				return;
			}
			var server = lastServer != null ? lastServer : client.getCurrentServer();
			if (server == null) return;
			lastServer = server;
			// random around the configured time (0.6 to 1.8 times), so it does not look like clockwork
			int base = Math.max(5, c.reconnectSec);
			int sec = (int) Math.round(base * ThreadLocalRandom.current().nextDouble(0.6, 1.8));
			Exchange.emit("connection", "action", "try again", "left_s", sec, "base_s", base, "server", server.ip,
				"attempt", failedConnections, "reason", reason);
			new Thread(() -> {
				try { Thread.sleep(sec * 1000L); } catch (InterruptedException ignored) { return; }
				client.execute(() -> {
					reconnectPlanned = false;
					if (client.level != null) return;   // reconnected by yourself in the meantime
					net.minecraft.client.gui.screens.ConnectScreen.startConnecting(
						new net.minecraft.client.gui.screens.TitleScreen(), client,
						net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(server.ip), server, false, null);
				});
			}, "buddy-reconnect").start();
		});
	}

	// ---------- hunger fallback ----------
	/** Warns at low hunger and eats from the hotbar itself when AutoEat leaves it (also works with an open chat screen). */

	private void hungerGuard(LocalPlayer p, AfkConfig c, long now) {
		// eating always: following nobody outside a base, the buddy stood hungry at food 3 (the scenarios of 23 Oct 2026)
		// played from the panel the buddy eats when the person does, as in the game: a bite of the buddy's own changed the item in the
		// person's hand in the middle of a raised shield (31 Oct 2026, the 3D drive)
		if (Steer.active()) return;
		int food = p.getFoodData().getFoodLevel();
		float health = p.getHealth();
		// No switch for it and no wait for another eating mod: a test left the switch off and the buddy ate nothing for a
		// day with 57 bread in the hotbar. Eating always works, unless someone drives the buddy in Take control
		boolean hungry = food <= c.eatBelow;
		boolean lowHealth = health <= c.eatBelowHealth && food < 20;   // regenerating only works with food in the belly
		if (!hungry && !lowHealth) return;
		// Not in the middle of jumps: eating slows to a walk and takes the sprint, and a jump without it falls short
		// (17 Oct 2026, the parkour course: a bite at food 16 between two blocks, and down the buddy went). After them, unless
		// the hearts run out.
		// Starving (food 0) a bite comes before a jump: at food 0 by the pen's fence the buddy went on jumping and lost ten hearts
		// (the scenarios of 23 Oct 2026)
		if (Follow.inJumps() && health > 6 && food > 0) return;
		// Not with an enemy at hand: a bite takes 1.6 s without a blow back, in which a zombie hits twice (17 Oct 2026:
		// the alt ate bread at 15.5 hearts with a zombie a block off, and died). Fight first, eat when it is over.
		Entity foe = Guard.enemy();
		if (foe != null && foe.distanceTo(p) < 6) return;
		// also with no guarding on: a monster after the buddy within ten blocks, or a shooter in sight within fifteen (a
		// drowned hit the buddy four times during two cod eaten for hearts, and killed the buddy; the distances are
		// Soprano's: a bite is 1.6 s without a blow, and a zombie covers ten blocks in about that time running). Not when
		// only a bite can save the buddy
		if (health > 4) for (var m : p.level().getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class, p.getBoundingBox().inflate(15, 5, 15))) {
			if (!m.isAlive()) continue;
			// at arm's length aggressive or not: the client did not show a zombie a block off as aggressive while it hit four
			// times, and the buddy ate through them
			if (m.distanceTo(p) < 3 && !ObtainSafe.type(m).equals("enderman")) return;
			if (!m.isAggressive()) continue;
			boolean shooter = m instanceof net.minecraft.world.entity.monster.RangedAttackMob;
			if (m.distanceTo(p) < 10 || (shooter && p.hasLineOfSight(m))) return;
		}
		// nor while a creeper swells at the buddy at the pen, or one is struck or walked away from: a bite takes the hand off
		// the blow that kills it and slows the walk out of its blast to a fifth (the third review of 22 Oct 2026)
		if (Pen.creeperDanger()) return;
		if (p.isUsingItem() && p.getUseItem().get(DataComponents.FOOD) != null) return;   // already eating: do not get in between
		// an order with AltoClef: its food chain eats (Alto), but not when it does not: at hunger 0 with seven cooked beef in
		// the bag it starved for ten minutes without a bite. Then the buddy's own bite (Alto.starving)
		if (Alto.on() && !Alto.starving(p)) return;
		if (eatTicks == 0 && now - lastEat > 4000 && p.containerMenu == p.inventoryMenu) {
			// no looking away from a sign, chest or door first: the buddy's own bite is a use of the item, no click on what is looked
			// at, and the use key goes down only once the bite has begun (22 Oct 2026); in a cellar between chests no safe
			// place to look at was found and the buddy starved facing one (the scenarios of 23 Oct 2026)
			if (look.busy() || Wander.busy()) return;
			int slot = findFood(p);
			if (slot >= 0) {
				lastEat = now;
				int cur = p.getInventory().getSelectedSlot();
				if (slot != cur) { eatRestoreSlot = cur; p.getInventory().setSelectedSlot(slot); }
				pendingUse = true;   // the use key goes down once the bite has begun (onStartTick)
				eatTicks = 45;
				eatBitten = false;
				String item = itemId(p.getInventory().getItem(slot));
				Exchange.emit("eat", "action", "start", "item", item, "food", food, "hp", health, "reason", lowHealth ? "life" : "backup");
				return;
			}
			if (foodToHotbar(p, mc())) return;   // next second it is in the hotbar and eating simply follows
			if (now - lastHungerWarn > 120_000) { lastHungerWarn = now;  Exchange.emit("hunger", "food", food, "hp", health, "food", false); }
			return;
		}
		if (now - lastHungerWarn > 600_000) { lastHungerWarn = now;  Exchange.emit("hunger", "food", food, "food", findFood(p) >= 0); }
	}

	/**
	 * No food in the hotbar, but there is in the inventory: fetch one stack to the hotbar, like the lumberjack
	 * takes the saplings (one SWAP click, no screen open). Without this an account without a screen starved
	 * with an inventory full of steak, because the eating function only searches the hotbar.
	 */
	private static String previousInventory = null;

	/**
	 * Log the whole inventory as soon as something changes: hotbar, backpack, armour and off hand, with the
	 * free slots included. Writing the same line every 5 s makes the log unreadable, so only on a difference.
	 * That way you can see from outside whether there is still room for an axe or how much food is left.
	 */
	private static void inventoryCheck(LocalPlayer p) {
		StringBuilder sb = new StringBuilder();
		int freeHotbar = 0, freeBackpack = 0;
		int n = p.getInventory().getContainerSize();
		for (int i = 0; i < n; i++) {
			ItemStack st = p.getInventory().getItem(i);
			if (st.isEmpty()) {
				if (i < 9) freeHotbar++;
				else if (i < 36) freeBackpack++;
				continue;
			}
			if (sb.length() > 0) sb.append(" ");
			sb.append(i < 36 ? String.valueOf(i) : i == n - 1 ? "hand2" : "armor" + (i - 36)).append(":").append(itemId(st));
			if (st.getCount() > 1) sb.append("x").append(st.getCount());
			var dur = st.getMaxDamage() > 0 ? (st.getMaxDamage() - st.getDamageValue()) : -1;
			if (dur >= 0) sb.append("(").append(dur).append(")");
		}
		String now = sb.toString();
		if (now.equals(previousInventory)) return;
		previousInventory = now;
		Exchange.emit("inventory", "free_hotbar", freeHotbar, "free_backpack", freeBackpack, "things", now);
	}

	/** How much food the eating function may use lies in these slots (0-8 hotbar, 9-35 inventory). */
	static int countFood(LocalPlayer p, int from, int to) {
		var allowed = AfkConfig.get().eatItems;
		int n = 0;
		for (int i = from; i < to; i++) {
			ItemStack st = p.getInventory().getItem(i);
			if (st.isEmpty() || st.get(DataComponents.FOOD) == null || BAD_FOOD.contains(itemId(st))) continue;
			if (!allowedFood(allowed, itemId(st))) continue;
			n += st.getCount();
		}
		return n;
	}

	private static final Map<String, Long> lastSwap = new HashMap<>();

	/**
	 * Fetch something from the backpack to the hotbar with one SWAP click, like the lumberjack takes the
	 * saplings. Without this the buddy stood around doing nothing with an axe or sword in the backpack as soon as
	 * the hotbar was full.
	 */
	static boolean toHotbar(LocalPlayer p, Minecraft mc, java.util.function.Predicate<ItemStack> want,
			java.util.Comparator<ItemStack> better, String why) {
		if (mc.gameMode == null || p.containerMenu != p.inventoryMenu) return false;
		// not in the middle of a bite: the swap took the food out of the hand (the scenarios of 23 Oct 2026)
		if (isEating() && !"food".equals(why)) return false;
		long now = System.currentTimeMillis();
		if (now - lastSwap.getOrDefault(why, 0L) < 10_000) return false;   // if it fails, do not click again every second
		int source = -1;
		for (int i = 9; i < 36; i++) {
			ItemStack st = p.getInventory().getItem(i);
			if (st.isEmpty() || !want.test(st)) continue;
			if (source < 0 || better.compare(st, p.getInventory().getItem(source)) > 0) source = i;
		}
		if (source < 0) return false;
		// preferably an empty slot; otherwise a slot without a weapon, tool or compass (you still need those)
		int target = -1;
		for (int i = 8; i >= 0 && target < 0; i--) if (p.getInventory().getItem(i).isEmpty()) target = i;
		// food last: it is what gets eaten from the hotbar
		for (int pass = 0; pass < 2 && target < 0; pass++) for (int i = 8; i >= 0 && target < 0; i--) {
			ItemStack st = p.getInventory().getItem(i);
			if (st.isDamageableItem() || st.getItem() == net.minecraft.world.item.Items.COMPASS) continue;
			if (pass == 0 && st.get(DataComponents.FOOD) != null) continue;
			target = i;
		}
		// food never waits for a free slot: with a hotbar of tools alone (six pairs of shears, an axe, a pickaxe, a shovel)
		// eighty-nine cooked beef lay in the backpack and the buddy starved inside the base (the scenarios of 23 Oct 2026). A second
		// one of a kind goes first, then anything but a weapon; it lands in the food's slot in the backpack
		if (target < 0 && "food".equals(why)) {
			for (int i = 8; i >= 0 && target < 0; i--) if (moreOf(p, i)) target = i;
			for (int i = 8; i >= 0 && target < 0; i--) {
				ItemStack st = p.getInventory().getItem(i);
				if (!st.is(net.minecraft.tags.ItemTags.SWORDS) && !st.is(net.minecraft.world.item.Items.MACE) && !st.is(net.minecraft.world.item.Items.TRIDENT)) target = i;
			}
		}
		if (target < 0) return false;
		String item = itemId(p.getInventory().getItem(source));
		lastSwap.put(why, now);
		Game.swapWithHotbar(mc.gameMode, p.inventoryMenu.containerId, source, target, p);
		Exchange.emit("to_hotbar", "reason", why, "item", item, "from", source, "to", target);
		return true;
	}

	/** Another slot of the inventory holds the same item as this one. */
	private static boolean moreOf(LocalPlayer p, int slot) {
		ItemStack st = p.getInventory().getItem(slot);
		if (st.isEmpty()) return false;
		for (int i = 0; i < 36; i++) if (i != slot && p.getInventory().getItem(i).is(st.getItem())) return true;
		return false;
	}

	private static boolean foodToHotbar(LocalPlayer p, Minecraft mc) {
		var allowed = AfkConfig.get().eatItems;
		java.util.function.Predicate<ItemStack> edible = st -> {
			var food = st.get(DataComponents.FOOD);
			if (food == null || BAD_FOOD.contains(itemId(st))) return false;
			return allowedFood(allowed, itemId(st));
		};
		return toHotbar(p, mc, edible, java.util.Comparator.comparingInt(st -> {
			var f = st.get(DataComponents.FOOD);
			return f == null ? 0 : f.nutrition();
		}), "food");
	}

	/** Food the eating function would eat: not on the bad list, and allowed by the setting when it names any. */
	static boolean eatable(ItemStack st) {
		if (st.isEmpty() || st.get(DataComponents.FOOD) == null || BAD_FOOD.contains(itemId(st))) return false;
		return allowedFood(AfkConfig.get().eatItems, itemId(st));
	}

	/**
	 * A food the settings let the buddy eat; on an order whatever feeds it, raw meat too: with the list's three foods only
	 * it sat four minutes at half health with a porkchop in its bag.
	 */
	static boolean allowedFood(java.util.List<String> allowed, String id) {
		return allowed.isEmpty() || mayEat(allowed, id) || (Obtain.on() && ObtainKit.ANY_FOOD.contains(id));
	}

	// golden apples are for healing in a fight (Follow.heal, 24 Oct 2026): never eaten for hunger, never counted as food
	private static final Set<String> BAD_FOOD = Set.of("rotten_flesh", "spider_eye", "poisonous_potato", "pufferfish", "chicken", "suspicious_stew", "chorus_fruit",
		"golden_apple", "enchanted_golden_apple");

	/** "minecraft:cooked_beef", "cooked_beef" and "Cooked Beef" mean the same thing. */
	private static boolean mayEat(java.util.List<String> list, String id) {
		for (String t : list) {
			String cleaned = t.trim().toLowerCase(Locale.ROOT).replace("minecraft:", "").replace(' ', '_');
			if (cleaned.equals(id)) return true;
		}
		return false;
	}

	/** Best allowed stack in the hotbar (0-8). Only what is in the setting: by default steak, porkchop and bread. */
	static int findFood(LocalPlayer p) {
		var allowed = AfkConfig.get().eatItems;
		int best = -1, bestVal = -1;
		for (int i = 0; i < 9; i++) {
			ItemStack st = p.getInventory().getItem(i);
			if (st.isEmpty()) continue;
			var food = st.get(DataComponents.FOOD);
			if (food == null || BAD_FOOD.contains(itemId(st))) continue;
			if (!allowedFood(allowed, itemId(st))) continue;
			int val = food.nutrition();
			if (val > bestVal) { bestVal = val; best = i; }
		}
		return best;
	}

	// ---------- test helpers (/buddy test ...) ----------
	/** Pretends this server message came in. */
	public static void simulate(String text) { if (INSTANCE != null) INSTANCE.onIncoming(text, "game"); }

	/** With AFK on: look at an untrusted player within lookRadius (per player once every 30-60 s), then another short glance. */
	/** How long you look at someone (configurable, default 3-10 s). */
	static long lookAtMs(AfkConfig c) {
		int lo = Math.max(1, c.lookAtMinSec), hi = Math.max(lo + 1, c.lookAtMaxSec);
		return ThreadLocalRandom.current().nextLong(lo * 1000L, hi * 1000L);
	}

	private void maybeLook(LocalPlayer me, AfkConfig c, Player o, String name, double d, boolean trusted) {
		if (Grinder.active()) {
			if (!c.afk || !c.lookAround || trusted || d > c.lookRadius) return;
			long now0 = System.currentTimeMillis();
			if (now0 - lastLookAt.getOrDefault(name.toLowerCase(Locale.ROOT), 0L) < ThreadLocalRandom.current().nextInt(30_000, 60_000)) return;
			lastLookAt.put(name.toLowerCase(Locale.ROOT), now0);
			Grinder.interrupt(lookAtMs(c), o, "player " + name);
			return;
		}
		if (!c.afk || !c.lookAround || trusted || d > c.lookRadius || look.busy() || Wander.busy()) return;
		long now = System.currentTimeMillis();
		if (now - lastLookAt.getOrDefault(name.toLowerCase(Locale.ROOT), 0L) < ThreadLocalRandom.current().nextInt(30_000, 60_000)) return;
		lastLookAt.put(name.toLowerCase(Locale.ROOT), now);
		lookAt(o.getEyePosition(), name);
		// a little later another glance: slightly beside the player, or a bit back again
		final Vec3 base = o.getEyePosition();
		schedule(rnd(2, 5), () -> { if (mc().player != null && AfkConfig.get().afk) lookAt(base.add(ThreadLocalRandom.current().nextDouble(-3, 3), ThreadLocalRandom.current().nextDouble(-1, 1), ThreadLocalRandom.current().nextDouble(-3, 3)), name + " (glance)"); });
	}

	static String itemId(ItemStack st) { return BuiltInRegistries.ITEM.getKey(st.getItem()).getPath(); }

	// ---------- commands from the watcher ----------
	private void handleCommand(JsonObject o) {
		String cmd = o.has("cmd") ? o.get("cmd").getAsString() : "";
		switch (cmd) {
			case "chat" -> {
				String t = o.get("text").getAsString();
				if (!active()) { Exchange.emit("chat_refused", "text", t, "server", serverIp());  break; }
				sendTyped(t, nearbyPlayer(lastTalker)); Exchange.emit("chat_sent", "text", t);
			}
			case "set" -> {
				String key = o.get("key").getAsString(); var v = o.get("value");
				AfkConfig c = AfkConfig.get();
				switch (key) {
					case "afk" -> { c.afk = v.getAsBoolean(); c.afkAuto = false; }
					case "playerAlerts" -> c.playerAlerts = v.getAsBoolean();
					// the alt remotely: crouching and jumping are set from Telegram with this
					case "altCrouched" -> c.altCrouched = v.getAsBoolean();
					case "altJumping" -> c.altJumping = v.getAsBoolean();
					case "altCrouchAlong" -> c.altCrouchAlong = v.getAsBoolean();
					case "pmCommands" -> c.pmCommands = v.getAsBoolean();
					// who may give the alt commands by PM: a list, or names in one string
					case "trusted" -> {
						List<String> names = new ArrayList<>();
						if (v.isJsonArray()) for (var x : v.getAsJsonArray()) names.add(x.getAsString().trim());
						else for (String x : v.getAsString().split("[,\\s]+")) names.add(x.trim());
						names.removeIf(String::isEmpty);
						c.trusted = names;
					}
					case "menuLog" -> c.menuLog = v.getAsBoolean();
					// the crop the buddy plants on the field, from Telegram; a typo would leave the field bare
					case "wheatCrop" -> {
						if (WheatFarm.cropNamed(v.getAsString()) == null) { Exchange.emit("error", "msg", "unknown crop " + v.getAsString()); return; }
						c.wheatCrop = v.getAsString();
					}
					default -> { Exchange.emit("error", "msg", "unknown key " + key); return; }
				}
				AfkConfig.save(); Exchange.emit("config_set", "key", key, "value", v.toString());
			}
			/*
			 * Switches from outside (Telegram sends them here): the same buttons as in the menu,
			 * so that mob farming and the raid farm take turns neatly instead of both being on.
			 */
			case "switch" -> {
				String what = o.has("what") ? o.get("what").getAsString().toLowerCase(Locale.ROOT) : "";
				String state = o.has("state") ? o.get("state").getAsString().toLowerCase(Locale.ROOT)
					: (o.has("on") && o.get("on").getAsBoolean() ? "on" : "off");
				boolean on = state.equals("on");
				switch (what) {
					case "mod" -> Switches.setMod(on);
					case "afk" -> Switches.setAfk(on);
					case "farm", "mobfarm" -> Switches.setMobFarm(on);
					case "wood" -> {
						if (state.equals("finish")) { if (!Switches.woodOn()) Switches.setWood(true); Lumberjack.setFinishing(true); }
						else Switches.setWood(on);
					}
					case "raid", "raidfarm" -> {
						if (state.equals("finish")) Switches.startFinishing(); else Switches.setRaid(on);
					}
					case "harvest", "crop" -> Switches.setCrop(on);
					case "wheat" -> Switches.setWheat(on);
					case "build" -> Switches.setBuild(on);
					default -> { Exchange.emit("error", "msg", "unknown switch " + what); return; }
				}
				Exchange.emit("switch", "what", what, "state", state, "mod", AfkConfig.get().modEnabled, "afk", AfkConfig.get().afk,
					"farm", Switches.mobFarmOn(), "wood", Switches.woodOn(), "raid", Switches.raidState(), "harvest", Switches.cropOn(), "wheat", Switches.wheatOn(), "build", Switches.buildOn());
			}
			/** Empty the inventory: into chests nearby, or on the ground. */
			case "unload" -> {
				if (o.has("stop") && o.get("stop").getAsBoolean()) { Unloader.stop("asked"); return; }
				String where = o.has("where") ? o.get("where").getAsString() : "chest";
				boolean all = o.has("all") && o.get("all").getAsBoolean();
				// "slots": only these inventory slots (0-8 hotbar, 9-35 backpack, 36-39 armour, 40 off hand), whatever is in them
				java.util.Set<Integer> slots = new java.util.HashSet<>();
				if (o.has("slots")) for (var s : o.getAsJsonArray("slots")) slots.add(s.getAsInt());
				Unloader.start(where, all, slots);
			}
			// the Litematica placement from outside (Telegram): put a schematic file down at a spot, list, remove
			case "placement" -> Schematic.command(o, mc().player);
			case "buildstatus" -> Exchange.emit("buildstatus", "text", Builder.summary(), "on", Builder.isOn());
			// how far the game draws and asks the server for chunks (the panel's Settings, Status), saved in options.txt; saving
			// sends the game's client information, so the server sends that many chunks at once
			case "renderdistance" -> {
				int n = Math.max(2, Math.min(32, o.get("chunks").getAsInt()));
				mc().options.renderDistance().set(n);
				mc().options.save();
				Exchange.emit("renderdistance", "chunks", mc().options.renderDistance().get());
				Details.soon();
			}
			// the tree farm's plots and chests, measured where the buddy stands (was /buddy wood scan in the game itself)
			case "woodscan" -> { LocalPlayer q = mc().player; if (q == null) Exchange.emit("error", "msg", "woodscan: not in a world"); else { Exchange.emit("woodscan", "text", Lumberjack.scan(q)); } }
			// what the farms brought in: per running farm, or the one named in "farm" (wheat, crop, mob, raid, wood, pen)
			case "farmstatus" -> Yield.status(o.has("farm") ? o.get("farm").getAsString() : null);
			case "blocks" -> BlockDump.command(o, mc());
			case "spawnscan" -> SpawnScan.run(o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("z").getAsDouble(), o.get("r").getAsDouble());
			case "serverinfo" -> { if (o.has("tree") && o.get("tree").getAsBoolean()) CommandTree.dump(mc()); else serverInfo(); }
			case "tab" -> CommandTree.complete(mc(), o.get("text").getAsString(), o.has("id") ? o.get("id").getAsString() : null);
			case "menuclick", "menuclose" -> MenuLog.command(o);
			case "follow" -> Follow.command(o);
			case "obtain" -> Obtain.command(o);
			case "pen" -> Pen.command(o);
			case "goto" -> Follow.gotoCommand(o);
			case "homewalk" -> HomeWalks.command(o);
			case "fly" -> Glide.command(o);
			case "guard" -> Guard.command(o);
			case "rest" -> { if (o.has("test")) Rest.test(o.get("test").getAsInt()); else Exchange.emit("base", "action", "phantom count", "ticks", Rest.estimate()); }
			case "help" -> Work.command(o);
			case "chests" -> Stash.command(o);
			case "fields" -> Work.fieldsCommand(o);
			case "profile", "forget" -> Profiles.command(cmd, o);
			case "state", "drop", "invclick", "select" -> Details.command(cmd, o, mc());
			case "ping" -> Exchange.emit("pong");
			case "map" -> MiniMap.command(o);
			case "world3d" -> World3d.command(o, mc());
			case "models" -> Models.command(mc());
			case "menubutton", "trade", "rename", "beacon", "slotstate" -> MenuExtra.command(cmd, o, mc());
			case "respawn", "wake", "sign", "book", "screenclose", "pickblock" -> Screens.command(cmd, o, mc());
			case "mode" -> Modes.mode(o, mc());
			case "setting" -> Modes.setting(o);
			case "leave" -> leave();
			case "join" -> join();
			case "profiles" -> Profiles.command(cmd, o);
			default -> { if (!Modules.command(cmd, o)) Exchange.emit("error", "msg", "unknown command " + cmd); }
		}
	}

	/**
	 * What the server gives away about its plugins: its brand, the plugin channels it listens on and every command
	 * it sent us (a plugin's own name is what stands before the colon). Only reads what the client already holds.
	 */
	private static void serverInfo() {
		var conn = mc().getConnection();
		if (conn == null) { Exchange.emit("error", "msg", "no connection"); return; }
		List<String> channels = new ArrayList<>(), commands = new ArrayList<>();
		for (var id : ClientPlayNetworking.getSendable()) channels.add(id.toString());
		for (var node : conn.getCommands().getRoot().getChildren()) commands.add(node.getName());
		Collections.sort(channels); Collections.sort(commands);
		Exchange.emit("serverinfo", "fire", conn.serverBrand(), "channels", channels, "commands", commands);
	}

}
