package net.milenco.buddy.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * The settings: the mod writes them, and the web panel and the Telegram bridge change them through the exchange folder.
 *
 * The defaults below are the tuning the mod is run and tested with, so a fresh install is right straight away and a
 * new field in an existing buddy.json gets that same value (gson only fills in what the file lacks).
 * Places and names are not part of that: coordinates, the wood field and trusted players stay empty until they are
 * set (the panel, Telegram's !set, or buddy.json itself).
 *
 * The file is buddy.json, with the fields' names as its keys.
 */
public class AfkConfig {
	public boolean modEnabled = true;     // master switch: off = the mod does nothing at all
	public boolean afk = false;
	public boolean afkAuto = false;       // true when AFK mode was switched on by auto-AFK (only then it goes off by itself on movement)
	public boolean autoAfk = false;       // switch AFK mode on by itself after a while without input
	public int autoAfkMin = 4;            // ... after this many minutes without keys/mouse
	public boolean autoAfkOffOnMove = false;
	public int eatBelow = 16;             // eat at food points <= this (out of 20, so 16 = 8 of the 10 drumsticks)
	public int eatBelowHealth = 16;       // ... and also at this many health points or fewer (out of 20, so 16 = 8 of the 10 hearts)
	public java.util.List<String> eatItems = new java.util.ArrayList<>(java.util.List.of(
		"minecraft:cooked_beef", "minecraft:cooked_porkchop", "minecraft:bread"));
	public boolean lookAround = true;     // when AFK: look at untrusted players nearby
	public int lookAtMinSec = 3;          // how long you look at a player (from/to)
	public int lookAtMaxSec = 10;
	public int lookRadius = 6;            // ... within this many blocks
	public boolean wander = true;         // when AFK, walk a short stretch now and then (there and back, only over a safe flat path)
	public int wanderEveryMin = 4;        // on average every this many minutes (random 0.5-1.5x)
	public int wanderMaxBlocks = 2;       // per leg; a route has 2-4 legs
	public int glanceEverySec = 120;      // casual glance on average every this many seconds
	public boolean steadyFps = true;      // vanilla 'inactivity FPS limit' on Minimized: no 10/30 fps while AFK or in the Esc menu (Sodium hides this option)
	// stay crouched in AFK mode
	public boolean afkCrouch = false;
	public boolean playerAlerts = true;
	public int homeRadius = 200;
	public int alertCooldownMin = 60;
	public List<String> trusted = new ArrayList<>();
	public int tpaMin = 1;
	public int tpaMax = 3;
	public List<Zone> zones = new ArrayList<>();   // fixed places where players get reported
	// panic logout and reconnect
	public boolean panicLogout = true;
	public boolean panicOnlyWhenBusy = true;   // only during AFK mode or farming, not while you play yourself
	public int panicHealth = 5;           // log out below this many health points
	public String panicHome = "home";     // teleport to this home first (through the /homes menu); empty = log out at once
	public boolean autoReconnect = true;
	public int reconnectSec = 30;
	public int reconnectMaxAttempts = 5;  // alt: this many failed attempts in a row, then quit the game (systemd restarts it with a fresh session)
	// farm mode
	public double farmMinCps = 2.6;
	public double farmMaxCps = 4.0;
	public boolean farmWaitCooldown = true;
	public boolean farmNudge = true;
	public int farmWalkEverySec = 120;    // always walk a real stretch now and then (well over a block there and back)
	public int farmBlockMoveSec = 80;     // this long on the same block = take a real step (the server sees you as AFK otherwise)
	public boolean farmCrouch = false;    // crouch while approaching and during nudges
	public int farmCrouchMinSec = 1;      // how long you stay down during a nudge
	public int farmCrouchMaxSec = 4;
	public int farmNudgeEverySec = 10;
	public boolean farmApproach = true;   // walk as close to the mobs as possible (up to a wall/edge)
	public int farmApproachRange = 5;     // only approach mobs within this many blocks
	public boolean farmUseArea = false;   // only hit mobs above the landing spot and walk there
	public int farmAreaX1 = 0, farmAreaZ1 = 0, farmAreaX2 = 0, farmAreaZ2 = 0;   // from /buddy farm spot
	public String farmAreaDim = "minecraft:overworld";
	// raid farm: one block where the raiders land, you hit from one side
	public boolean raidMode = false;
	public int raidX = 0, raidZ = 0;
	public String raidDim = "minecraft:overworld";
	public boolean raidDrink = true;      // drink a new ominous bottle after a raid
	public int raidDrinkPauseSec = 15;    // wait after the victory before the next bottle
	public boolean farmThroughMenus = true;   // keep hitting while the chat or the Esc menu is open
	public boolean farmRecenter = true;   // return to your starting spot when you have drifted
	public int farmVisitorRadius = 12;     // untrusted player this close: farm stands still as long as they stay (0 = off)
	public int farmVisitorAfterSec = 15;     // and this long afterwards, so you do not start the moment they turn around
	public int farmSideSwitchSec = 180;   // this often move to another side of the landing spot (0 = never)
	public int farmSessionMin = 60;           // on average this often a long break of 2-8 min (0 = never)
	public boolean clickLog = true;                // intervals between hits to clicks.csv (for klikanalyse.py)
	public boolean watchdogAlarm = true;      // alarm when someone tests you: boat, blocks, water, a push
	public int watchdogPauseMin = 3;       // first signal: stand still this long and then carry on by itself (0 = stop right away)
	public int farmDropsMax = 256;        // this many loose items (not stacks) at the farm = chests full, farm off (0 = off)
	public String farmHomeBack = "";         // home to go to when you got teleported away while farming ("" = do nothing)
	public boolean raidJump = true;       // jump once after drinking
	public int raidLookY = 3;             // where to look when there is nothing to hit, hundredths of a block relative to your feet
	public int raidWaitMaxSec = 120;      // wait this long for a raid after a bottle; then try again
	// wood cutting: the field, the plots and the chests come from /buddy wood scan
	public int woodX1 = 0;
	public int woodZ1 = 0;
	public int woodX2 = 0;
	public int woodZ2 = 0;
	public int woodY = 0;                    // floor height: where you stand during the scan (y of the saplings)
	public String woodDim = "";
	public List<WoodPlot> woodPlots = new ArrayList<>();
	public List<ChestPos> woodChests = new ArrayList<>();
	public boolean woodBonemeal = true;      // use bonemeal
	public int woodBonemealThreshold = 0;   // bonemeal round only when at most this percent of the plots has a tree (0 = only on an empty field)
	public int woodBonemealMax = 10;      // per sapling; skip and report after that
	public int woodAxeMin = 100;              // stop when the axe has less durability left
	public int woodPickupEverySec = 240;  // pick up at most once per this many seconds, at least 180 (items only vanish after 5 minutes)
	public int woodKeepSaplings = 16;    // per kind to keep in the inventory
	public int woodKeepBonemeal = 64;
	public int woodTakeAlongMax = 2;      // this many logs of another kind in a row simply get chopped along (0 = never)
	public int woodPauseEveryMin = 15;   // on average this often a breather of 20-60 s (0 = never)
	public int woodPauseRadius = 16;      // player within this many blocks: stand still
	public int woodWaitAfterPlayerSec = 21;
	// building the Litematica placement (see Builder); the material chests and the progress are in build.json
	public int buildMaxPerSec = 4;              // blocks per second at most
	public int buildPauseEveryMin = 15;      // on average this often a breather of 20-60 s (0 = never)
	public int buildPauseRadius = 16;         // untrusted player within this many blocks: stand still (0 = never)
	public int buildWaitAfterPlayerSec = 20;
	public boolean buildClearAir = true;   // also break what stands where the schematic wants air
	public boolean buildUseScaffold = true;   // bridges and pillars of cheap blocks to reach and to stand right
	public boolean buildUseScaffolding = true;       // towers of scaffolding to reach up high, instead of dirt pillars
	public boolean buildRemoveScaffold = true; // break the scaffold again when the build is done
	public List<String> buildScaffold = new ArrayList<>(List.of("minecraft:dirt", "minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:netherrack"));
	public int buildToolMin = 20;          // never break with a tool that has less durability left
	public boolean buildPickup = true;              // pick up the drops around the build now and then
	// wheat field: walk the rows, harvest, replant, sell or store what comes off it (see WheatFarm)
	public boolean wheatOn = false;                  // the switch itself: it survives a restart, so after a kick or a crash he goes back to the home and carries on
	public String wheatHome = "wheat";             // the home beside the field, with the chests and the crafting table
	public String wheatCrop = "wheat_seeds";      // what goes on bare farmland (wheat_seeds, carrot, potato, beetroot_seeds); seeds of any other crop are never planted, so a field of another crop turns into this one
	public boolean wheatHoe = true;            // break the crops with the hoe that has the most Fortune and plant from the off hand; without a usable hoe he breaks by hand
	public int wheatHoeMin = 20;             // a hoe with this much durability or less is not used any more: it goes back into a chest and he takes another there
	public int wheatX1 = 0;                           // two corners of the field (x and z); the floor height is found on arrival
	public int wheatZ1 = 0;
	public int wheatX2 = 0;
	public int wheatZ2 = 0;
	public int wheatPauseRadius = 16;        // untrusted player within this many blocks: stand still (0 = never)
	public int wheatWaitAfterPlayerSec = 20;
	public int wheatPauseEveryMin = 15;     // on average this often a breather of 20-60 s (0 = never)
	// the account without a screen (see Alt)
	public String altHome = "";           // alt: home for a "home" by PM ("" = plain /home)
	public boolean pmCommands = false;        // commands by PM from trusted players (tpa, tpahere, tpaccept, home, status)
	public String altName = "";   // his account name, written when he logs in; the panel and the bridge name him by it
	public boolean altCrouchAlong = false;      // alt: crouch on/off when a trusted player nearby crouches, or by PM "crouch"
	public boolean altCrouched = false;        // alt: last requested crouch and jump state, so it survives a restart
	public boolean altJumping = false;
	public boolean keepEvents = false;      // archive events.jsonl at startup instead of clearing it (alt: nothing lost after a restart)
	public boolean pathDig = true;          // the alt digs through natural ground on his way where walking does not get him there
	public boolean pathPlace = true;        // and puts down a block he can spare to climb or to cross a gap
	public boolean obtainBaritone = true;   // an order of items walks with Baritone (Soprano) when it is in the mods folder
	public boolean obtainAltoSurvival = true;   // AltoClef's chains (Soprano) fight, eat and get out of lava for an order
	public boolean practiceFastTicks = false;   // a practice server only: the client ticks as fast as the server's /tick rate (FastTicksMixin)
	public boolean obtainSopranoFirst = true;   // an order AltoClef's catalogue knows is got by Soprano whole; the buddy's own steps only where it has none
	public boolean stayOffline = false;     // the panel took him off the server: he stays off until it says join
	// keeping a base (Modes): what he does there; each a switch on the panel's mode card
	public boolean baseHarvest = true;      // harvest and replant the base's fields
	public boolean baseFurnaces = true;     // keep its furnaces going
	public boolean baseStore = true;        // put what he carries into its chests
	public boolean baseFight = true;        // fight monsters in the base
	public boolean baseSleep = true;        // sleep in the base at night
	public boolean baseSleepOthers = false; // ... only while another player sleeps too (Sleepers)
	public boolean baseShear = true;        // shear the sheep of its pens and pick up the wool
	public boolean baseHerd = true;         // bring an animal that got out back into its pen with wheat (Herd); needs basePenGates
	public boolean basePenGates = false;    // through a pen's gate or door; off, its fence is a wall and carpets on it a way over (Herd.penGate)
	public boolean baseRenew = true;        // a new iron tool or armour piece for one nearly worn out, while iron stays over
	// what he leaves of iron and gold when he makes something of them (set in the web panel), in ingots, a block nine
	public int keepIron = 9;                // a tool, armour, shears or a shield only while this much iron stays over
	public int keepGold = 64;               // golden apples only while this much gold stays over
	// following (Modes): guarding is Guard's own switch, the assists above
	public boolean followCarry = true;      // fetch the followed player's things after a death and carry home what he is given
	// players too (step 4 of the plan for fighting: the alt defends the player and attacks along, without setting off the
	// server's anti-cheat): a player who attacks him or the alt, and one he attacks; on, kept over a restart
	public boolean guardPlayers = true;
	// farming mobs at a pen (Pen, 0.4.80)
	public boolean followMobFarm = true;    // a bare-hand punch on a monster in a pen starts the farm
	public int penSideSwitchSec = 180;      // walk round the pen this often (times 0.7 to 1.3); 0 = only to reach mobs
	public boolean penNightPause = true;    // no blows while it is night and someone sleeps, so the night can pass
	public int penOwnerAwayMin = 0;         // stop after the player is gone this long; 0 = farm on while he is offline
	public boolean menuLog = false;       // write every menu the server opens to menus.jsonl (see MenuLog)
	public List<String> servers = new ArrayList<>();   // only act automatically here, besides the servers the profiles name
	public boolean allServers = false;    // testing: active everywhere
	/** What each server is like: its commands and lines, the hop after joining, its resource pack (see Profiles). */
	public List<Profiles.Profile> profiles = Profiles.defaults();
	// the alt's help that a tap of the followed player switches (Tap): off until he taps
	public boolean assistDig = false;
	public boolean assistChop = false;
	public boolean assistFarm = false;

	/** A plot on the tree farm: the dirt is below it, the sapling or the log stands here. */
	public static class WoodPlot {
		public int x, y, z;
		public String kind = "minecraft:birch_sapling";
		public WoodPlot() {}
		public WoodPlot(int x, int y, int z, String kind) { this.x = x; this.y = y; this.z = z; this.kind = kind; }
		public net.minecraft.core.BlockPos pos() { return new net.minecraft.core.BlockPos(x, y, z); }
	}
	public static class ChestPos {
		public int x, y, z;
		public ChestPos() {}
		public ChestPos(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
		public net.minecraft.core.BlockPos pos() { return new net.minecraft.core.BlockPos(x, y, z); }
	}

	/** Zone: report players within 'radius' of (x,y,z), once per cooldownMin per player. */
	public static class Zone {
		public String name; public int x, y, z; public int radius = 200; public int cooldownMin = 60;
		public String dim = "minecraft:overworld";   // world/dimension the zone applies in
		public Zone() {}
		public Zone(String name, int x, int y, int z, int radius, int cooldownMin) { this.name = name; this.x = x; this.y = y; this.z = z; this.radius = radius; this.cooldownMin = cooldownMin; }
		public Zone(String name, int x, int y, int z, int radius, int cooldownMin, String dim) { this(name, x, y, z, radius, cooldownMin); this.dim = dim; }
		public String dim() { return dim == null || dim.isBlank() ? "minecraft:overworld" : dim; }
		/** "name x y z radius cooldown" (menu format) */
		public String format() { return name + " " + x + " " + y + " " + z + " " + radius + " " + cooldownMin + " " + dim().replace("minecraft:", ""); }
		public static Zone parse(String line) {
			String[] t = line.trim().split("\\s+");
			if (t.length < 4) return null;
			try {
				return new Zone(t[0], Integer.parseInt(t[1]), Integer.parseInt(t[2]), Integer.parseInt(t[3]),
					t.length > 4 ? Integer.parseInt(t[4]) : 200, t.length > 5 ? Integer.parseInt(t[5]) : 60);
			} catch (NumberFormatException e) { return null; }
		}
	}
	public String exchangeDir = System.getProperty("user.home") + "/Development/minecraft-afk";

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static AfkConfig instance;
	private static long lastMtime = 0;

	public static AfkConfig get() {
		if (instance == null) load();
		return instance;
	}

	public static Path file() {
		String dir = instance != null ? instance.exchangeDir : System.getProperty("user.home") + "/Development/minecraft-afk";
		return Paths.get(dir, "buddy.json");
	}

	public static synchronized void load() {
		AfkConfig def = new AfkConfig();
		Path f = Paths.get(def.exchangeDir, "buddy.json");
		try {
			if (Files.exists(f)) {
				instance = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), AfkConfig.class);
				if (instance == null) instance = def;
				lastMtime = Files.getLastModifiedTime(f).toMillis();
			} else {
				instance = def;
				save();
			}
		} catch (Exception e) {
			instance = def;
		}
	}

	/** Reloads when the watcher changed the file (e.g. 'afk on' from VS Code). Returns true on change. */
	public static synchronized boolean reloadIfChanged() {
		try {
			Path f = file();
			if (!Files.exists(f)) return false;
			long m = Files.getLastModifiedTime(f).toMillis();
			if (m != lastMtime) {
				AfkConfig fresh = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), AfkConfig.class);
				if (fresh != null) { instance = fresh; lastMtime = m; return true; }
			}
		} catch (Exception ignored) { }
		return false;
	}

	public static synchronized void save() {
		try {
			Path f = file();
			Files.createDirectories(f.getParent());
			Files.writeString(f, GSON.toJson(get()), StandardCharsets.UTF_8);
			lastMtime = Files.getLastModifiedTime(f).toMillis();
		} catch (IOException ignored) { }
	}

	/** May the mod chat/send commands automatically on this server address? */
	public boolean serverAllowed(String ip) {
		if (allServers) return true;
		if (ip == null) return false;
		if (Profiles.knows(ip)) return true;
		String l = ip.toLowerCase(java.util.Locale.ROOT);
		return servers.stream().anyMatch(s -> l.contains(s.toLowerCase(java.util.Locale.ROOT)));
	}

	public boolean isTrusted(String name) {
		for (String t : trusted) if (t.equalsIgnoreCase(name)) return true;
		return false;
	}
}
