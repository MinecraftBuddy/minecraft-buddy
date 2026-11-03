package net.milenco.buddy.client;

import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonObject;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * The buddy's three modes and the settings under each, for the web panel: the buddy's core abilities are tending the
 * base, following and building a schematic, and jobs are grouped by those. A mode is no new state of its own: it is
 * read from what the buddy does (building, keeping a
 * base, following), and switching it uses the commands that were there. The settings are switches in the config.
 * <ul>
 * <li>{@code {"cmd":"mode","mode":"base"}}: stop building, stay (when he follows someone), and walk to the nearest base
 * of this dimension when he is not in one.</li>
 * <li>{@code {"cmd":"mode","mode":"follow","player":"Name"}}: stop building and follow, or follow again after staying.</li>
 * <li>{@code {"cmd":"mode","mode":"build"}}: start the builder on its placement.</li>
 * <li>{@code {"cmd":"mode","mode":"afk"}}: AFK mode on: he stands where he is; the panel shows it as a main switch.
 * Any other mode switches it off again.</li>
 * <li>{@code {"cmd":"setting","name":"baseHarvest","value":false}}: one switch; see {@link #settings()}.</li>
 * </ul>
 */
final class Modes {
	private Modes() {}

	/** What he does, as the panel names it: afk, build, base, follow, stay or idle. */
	static String current() {
		if (AfkConfig.get().afk) return "afk";
		if (Builder.isOn()) return "build";
		if (Base.keeper() != null && (Follow.target() == null || Follow.staying())) return "base";
		if (Follow.target() != null) return Follow.staying() ? "stay" : "follow";
		return "idle";
	}

	static Map<String, Object> settings() {
		AfkConfig c = AfkConfig.get();
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("baseHarvest", c.baseHarvest);
		m.put("baseFurnaces", c.baseFurnaces);
		m.put("baseStore", c.baseStore);
		m.put("baseFight", c.baseFight);
		m.put("baseSleep", c.baseSleep);
		m.put("baseSleepOthers", c.baseSleepOthers);
		m.put("baseShear", c.baseShear);
		m.put("baseHerd", c.baseHerd);
		m.put("basePenGates", c.basePenGates);
		m.put("baseRenew", c.baseRenew);
		m.put("keepIron", c.keepIron);
		m.put("keepGold", c.keepGold);
		m.put("assistDig", Work.dig);
		m.put("assistChop", Work.chop);
		m.put("assistFarm", Work.farm);
		m.put("followGuard", Guard.on());
		m.put("followGuardPlayers", Guard.pvp());
		m.put("followCarry", c.followCarry);
		m.put("followMobFarm", c.followMobFarm);
		m.put("pathDig", c.pathDig);
		m.put("pathPlace", c.pathPlace);
		m.put("stayOffline", c.stayOffline);
		// AFK mode and what he does in it, each a switch in the panel
		m.put("afk", c.afk);
		m.put("lookAround", c.lookAround);
		m.put("wander", c.wander);
		m.put("afkCrouch", c.afkCrouch);
		m.put("wanderMaxBlocks", c.wanderMaxBlocks);
		m.put("wanderEveryMin", c.wanderEveryMin);
		// crouching when a trusted player crouches beside him (Alt), and the !crouch command; "Crouch" under Follow a player
		m.put("crouchAlong", c.altCrouchAlong);
		// the recording of menus and of what the server sends him (MenuLog, Recorder), for the panel's logs
		m.put("menuLog", c.menuLog);
		return m;
	}

	static void setting(JsonObject o) {
		String name = o.has("name") ? o.get("name").getAsString() : "";
		if (!o.has("value")) { Exchange.emit("error", "msg", "setting: no value"); return; }
		AfkConfig c = AfkConfig.get();
		// the amounts, set in the panel: ingots of iron and gold he leaves (Chores)
		// and how far and how often he walks about in AFK mode, against a server's own AFK check
		if (name.equals("keepIron") || name.equals("keepGold") || name.equals("wanderMaxBlocks") || name.equals("wanderEveryMin")) {
			int n;
			try { n = o.get("value").getAsInt(); } catch (RuntimeException e) { Exchange.emit("error", "msg", "setting: " + name + " is a number"); return; }
			switch (name) {
				case "keepIron" -> c.keepIron = n = Math.max(0, Math.min(10_000, n));
				case "keepGold" -> c.keepGold = n = Math.max(0, Math.min(10_000, n));
				case "wanderMaxBlocks" -> c.wanderMaxBlocks = n = Math.max(1, Math.min(8, n));
				default -> c.wanderEveryMin = n = Math.max(1, Math.min(60, n));
			}
			AfkConfig.save();
			Exchange.emit("setting", "name", name, "value", n);
			return;
		}
		boolean v = o.get("value").getAsBoolean();
		switch (name) {
			case "baseHarvest" -> c.baseHarvest = v;
			case "baseFurnaces" -> c.baseFurnaces = v;
			case "baseStore" -> c.baseStore = v;
			case "baseFight" -> c.baseFight = v;
			case "baseSleep" -> c.baseSleep = v;
			case "baseSleepOthers" -> c.baseSleepOthers = v;
			case "baseShear" -> c.baseShear = v;
			case "baseHerd" -> c.baseHerd = v;
			case "basePenGates" -> c.basePenGates = v;
			case "baseRenew" -> c.baseRenew = v;
			case "assistDig" -> { c.assistDig = Work.dig = v; if (!v) Work.stopDigging("setting"); }
			case "assistChop" -> c.assistChop = Work.chop = v;
			case "assistFarm" -> c.assistFarm = Work.farm = v;
			case "followGuard" -> { if (Guard.on() != v) Guard.toggle("panel"); }
			case "followGuardPlayers" -> Guard.setPvp(v, "panel");
			case "followCarry" -> c.followCarry = v;
			case "followMobFarm" -> { c.followMobFarm = v; if (!v) Pen.stop("switched off"); }
			case "pathDig" -> c.pathDig = v;
			case "pathPlace" -> c.pathPlace = v;
			case "stayOffline" -> c.stayOffline = v;
			case "afk" -> Switches.setAfk(v);
			case "lookAround" -> c.lookAround = v;
			case "wander" -> { c.wander = v; if (!v) Wander.abort("switched off"); }
			case "afkCrouch" -> c.afkCrouch = v;
			case "crouchAlong" -> c.altCrouchAlong = v;
			case "menuLog" -> c.menuLog = v;
			default -> { Exchange.emit("error", "msg", "setting: unknown " + name); return; }
		}
		AfkConfig.save();
		Exchange.emit("setting", "name", name, "value", v);
	}

	static void mode(JsonObject o, Minecraft mc) {
		String want = o.has("mode") ? o.get("mode").getAsString() : "";
		LocalPlayer p = mc.player;
		// a mode chosen ends a pen he farms: his newest instruction wins
		if (want.equals("base") || want.equals("follow") || want.equals("build") || want.equals("afk")) Pen.stop("mode");
		// AFK mode is a mode of its own on the panel: choosing another one ends it
		if (want.equals("base") || want.equals("follow") || want.equals("build")) Switches.setAfk(false);
		switch (want) {
			case "afk" -> {
				if (Builder.isOn()) Builder.stop("mode");
				Switches.setAfk(true);
			}
			case "build" -> {
				if (!Builder.isOn()) Builder.start();
			}
			case "follow" -> {
				if (Builder.isOn()) Builder.stop("mode");
				String who = o.has("player") ? o.get("player").getAsString().trim() : "";
				JsonObject f = new JsonObject();
				if (!who.isEmpty() && !who.equalsIgnoreCase(Follow.target())) f.addProperty("player", who);
				else if (Follow.target() != null) f.addProperty("stay", false);
				else { Exchange.emit("error", "msg", "mode follow: name a player"); return; }
				Follow.command(f);
			}
			case "base" -> {
				if (Builder.isOn()) Builder.stop("mode");
				if (Follow.target() != null && !Follow.staying()) {
					JsonObject f = new JsonObject();
					f.addProperty("stay", true);
					Follow.command(f);
				}
				if (p == null) return;
				String dim = Work.dimOf(p.level());
				if (Tap.baseAt(dim, p.position(), Base.RADIUS) != null) return;
				var home = Tap.baseAt(dim, p.position(), 100_000);
				if (home == null) { Exchange.emit("error", "msg", "mode base: no base in this dimension"); return; }
				JsonObject g = new JsonObject();
				g.addProperty("x", home.bed().getX());
				g.addProperty("y", home.bed().getY());
				g.addProperty("z", home.bed().getZ());
				Follow.gotoCommand(g);
			}
			default -> { Exchange.emit("error", "msg", "mode: base, follow, build or afk"); return; }
		}
		Exchange.emit("mode", "mode", want);
	}
}
