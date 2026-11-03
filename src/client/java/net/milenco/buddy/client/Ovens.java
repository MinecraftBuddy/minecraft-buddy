package net.milenco.buddy.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.milenco.buddy.version.Game;

/**
 * Keeping the player's furnaces going, after a tap on one ({@link Tap}): at the tap the alt looks at what is being
 * smelted and may smelt that combination itself from then on (coal with raw iron, for instance).
 *
 * <p>A tap on a furnace (or a blast furnace, a smoker) takes it and the furnaces of the same kind beside it in a row
 * (up to eight), and the alt opens the tapped one to read what is in it: what lies on top is something he may smelt, what
 * lies below is fuel he may use. Nothing in it: he shakes his head. A tap on one of them once more stops it. While it
 * is on he visits each furnace now and then when there is nothing else to do: takes out what is done (his chests or the
 * player get it as ever), puts in more of what he learned to smelt, and more of the fuel he learned. What to smelt and
 * the fuel he takes from the player's chests ({@link Stash}), and keeps on him. Kept in {@code furnaces.json}, per server
 * profile and per player.
 */
final class Ovens {
	private Ovens() {}

	static final int ROW = 8;
	static final long VISIT_EVERY = 60_000, PLAN_EVERY = 5000;

	private static final class Oven {
		String server, dim, owner;
		BlockPos pos;
		long visited;
	}

	private static final List<Oven> ovens = new ArrayList<>();
	/** What may be smelted and what may be burnt, per server and player: "server|owner" to item ids. */
	private static final Map<String, Set<String>> inputs = new HashMap<>(), fuels = new HashMap<>();
	private static final Map<String, Boolean> onFor = new HashMap<>();
	private static boolean loaded;

	private static final int IDLE = 0, WALK = 1, OPEN = 2, MENU = 3;
	private static int step = IDLE;
	private static Oven visit;
	private static boolean learning;
	private static long stepSince, nextPlan, nextClick;
	private static BlockPos stand, clickAt;
	private static int clickedAt = -1, clicks, out, in, fuel, tries;

	private static String key(String owner) { return Profiles.key() + "|" + owner.toLowerCase(Locale.ROOT); }

	static boolean busy() { return step != IDLE; }

	static boolean inMenu(LocalPlayer p) { return step == MENU || (step == OPEN && p.containerMenu != p.inventoryMenu); }

	/** A furnace of this server smelts it or burns it: it is not made into blocks below a stack (Chores). */
	static boolean uses(String id) {
		String prefix = Profiles.key() + "|";
		for (var e : inputs.entrySet()) if (e.getKey().startsWith(prefix) && e.getValue().contains(id)) return true;
		for (var e : fuels.entrySet()) if (e.getKey().startsWith(prefix) && e.getValue().contains(id)) return true;
		return false;
	}

	private static boolean on(String owner) {
		if (owner == null) return false;
		load();   // after a restart nothing read the file before this: the ovens counted as off
		return onFor.getOrDefault(key(owner), false);
	}

	// ---------- the tap ----------

	static void tapped(Level level, BlockPos pos, String owner) {
		load();
		Oven known = find(level, pos, owner);
		if (known != null && on(owner)) {
			onFor.put(key(owner), false);
			save();
			if (step != IDLE) stop("switched off");
			Exchange.emit("furnaces", "action", "off", "for", owner);
			Follow.respond(Follow.OFF);
			return;
		}
		// the tapped one and its row: furnaces of the same kind beside it, one after the other
		List<BlockPos> row = new ArrayList<>();
		row.add(pos.immutable());
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockPos q = pos.relative(d);
			while (row.size() < ROW && level.getBlockState(q).getBlock() == level.getBlockState(pos).getBlock()) { row.add(q.immutable()); q = q.relative(d); }
		}
		for (BlockPos q : row) if (find(level, q, owner) == null) {
			Oven o = new Oven();
			o.server = Profiles.key();
			o.dim = Work.dimOf(level);
			o.owner = owner.toLowerCase(Locale.ROOT);
			o.pos = q;
			ovens.add(o);
		}
		onFor.put(key(owner), true);
		save();
		Exchange.emit("furnaces", "action", "on", "for", owner, "furnaces", row.size(), "x", pos.getX(), "y", pos.getY(), "z", pos.getZ());
		// read what is in the tapped one: that is what he may smelt, with that fuel
		Oven o = find(level, pos, owner);
		if (o != null) { if (step != IDLE) stop("new tap"); go(o, true); }
	}

	private static Oven find(Level level, BlockPos pos, String owner) {
		String server = Profiles.key(), dim = Work.dimOf(level), who = owner.toLowerCase(Locale.ROOT);
		for (Oven o : ovens) if (o.server.equals(server) && o.dim.equals(dim) && o.owner.equals(who) && o.pos.equals(pos)) return o;
		return null;
	}

	// ---------- for Stash: what he fetches from the chests and keeps ----------

	/** Something he smelts or burns for the player he follows, and has less than a stack of. */
	static boolean wants(LocalPlayer p, ItemStack st) {
		String owner = Follow.boss();
		if (!on(owner) || st.isEmpty()) return false;
		String id = AfkHelperClient.itemId(st);
		if (!uses(owner, id)) return false;
		int have = 0;
		for (int i = 0; i < 36; i++) if (AfkHelperClient.itemId(p.getInventory().getItem(i)).equals(id)) have += p.getInventory().getItem(i).getCount();
		return have < 64;
	}

	/** Something he smelts or burns: he keeps it, no chest takes it back. */
	static boolean keeps(ItemStack st) {
		String owner = Follow.boss();
		return on(owner) && !st.isEmpty() && uses(owner, AfkHelperClient.itemId(st));
	}

	private static boolean uses(String owner, String id) {
		load();
		return inputs.getOrDefault(key(owner), Set.of()).contains(id) || fuels.getOrDefault(key(owner), Set.of()).contains(id);
	}

	// ---------- every tick ----------

	/** When nothing else is going on: a visit to a furnace that is due. True while busy. */
	static boolean tick(Minecraft mc, LocalPlayer p, Player t) {
		long now = System.currentTimeMillis();
		String owner = Follow.boss();
		if (step == IDLE) {
			if (!on(owner) || t == null || now < nextPlan) return false;
			nextPlan = now + PLAN_EVERY;
			load();
			Level level = p.level();
			String server = Profiles.key(), dim = Work.dimOf(level), who = owner.toLowerCase(Locale.ROOT);
			Oven due = null;
			for (Oven o : ovens) {
				if (!o.server.equals(server) || !o.dim.equals(dim) || !o.owner.equals(who) || now - o.visited < VISIT_EVERY) continue;
				Vec3 at = Vec3.atCenterOf(o.pos);
				if (at.distanceTo(t.position()) > 24 || at.distanceTo(p.position()) > 20) continue;
				// a chunk not loaded reads as air: kept, not forgotten (the review of 22 Oct 2026)
				if (!level.isLoaded(o.pos)) continue;
				if (!(level.getBlockState(o.pos).getBlock() instanceof AbstractFurnaceBlock)) { ovens.remove(o); save(); return false; }
				if (due == null || o.visited < due.visited) due = o;
			}
			if (due == null) return false;
			go(due, false);
			return true;
		}
		Level level = p.level();
		if (t != null && t.distanceTo(p) > 28) { stop("you leave"); return false; }
		Vec3 eye = p.getEyePosition();
		Vec3 point = Work.aimPoint(level, p, eye, visit.pos);
		if (point != null && eye.distanceTo(point) > Work.REACH) point = null;   // the reach his spot was chosen with (Work.standCell)
		switch (step) {
			case WALK -> {
				if (point != null) { Follow.stand(); step = OPEN; stepSince = now; clicks = 0; clickedAt = -1; return true; }
				if (now - stepSince > 20_000) { visit.visited = now; stop("unreachable"); return false; }
				if (stand == null) {
					stand = Work.standCell(level, p, visit.pos, t, f -> true);
					if (stand == null) { visit.visited = now; stop("no spot to stand"); return false; }
				}
				if (!Work.approach(mc, p, "oven-" + visit.pos, Vec3.atBottomCenterOf(stand), now)) {
					stand = Work.standCell(level, p, visit.pos, t, f -> true);
					if (stand == null) { visit.visited = now; stop("unreachable"); return false; }
				}
				return true;
			}
			case OPEN -> {
				// the crosshair on something else (the player stands in front of it): a while, then later again
				if (now - stepSince > 8000) { visit.visited = now; stop("click fails"); return false; }
				if (point == null && clickedAt >= 0 && Follow.ticks() - clickedAt <= 30) return true;
				if (point == null) { step = WALK; stepSince = now; stand = null; return true; }
				Follow.stand();
				Follow.aimExact(p, point);
				if (clickedAt >= 0 && Follow.ticks() - clickedAt > 30) {
					clickedAt = -1;
					if (++clicks >= 3) { visit.visited = now; stop("does not open"); return false; }
				}
				if (clickedAt < 0) clickAt = visit.pos;
				return true;
			}
			default -> { return true; }
		}
	}

	private static void go(Oven o, boolean learn) {
		visit = o;
		learning = learn;
		step = WALK;
		stepSince = System.currentTimeMillis();
		stand = null;
		out = in = fuel = tries = 0;
		Exchange.emit("furnaces", "action", learn ? "looks what is inside" : "to furnace", "x", o.pos.getX(), "y", o.pos.getY(), "z", o.pos.getZ());
	}

	/** Start of the tick: the click on the furnace, if the crosshair is on it. */
	static void startTick(Minecraft mc) {
		BlockPos want = clickAt;
		clickAt = null;
		LocalPlayer p = mc.player;
		if (want == null || p == null || mc.gameMode == null || mc.level == null || p.isShiftKeyDown() || step != OPEN) return;
		if (!(mc.hitResult instanceof BlockHitResult bh) || bh.getType() != HitResult.Type.BLOCK || !bh.getBlockPos().equals(want)) return;
		InteractionResult r = AfkHelperClient.useOn(p, InteractionHand.MAIN_HAND, bh);
		if (r.consumesAction()) Game.swingUse(p, InteractionHand.MAIN_HAND);
		clickedAt = Follow.ticks();
	}

	/**
	 * The furnace is open. Learning: read it and close. Tending: what is done out, more of what he may smelt in (only
	 * what lies on top already, or anything he learned when the top is empty), more of the fuel likewise, then close.
	 * The game's own shift click puts a thing to smelt on top and a fuel below.
	 */
	static boolean menuTick(Minecraft mc, LocalPlayer p) {
		long now = System.currentTimeMillis();
		AbstractContainerMenu m = p.containerMenu;
		if (step == OPEN) {
			if (m == p.inventoryMenu) return true;
			step = MENU;
			stepSince = now;
			seen = false;
			nextClick = now + ThreadLocalRandom.current().nextLong(250, 500);
			return true;
		}
		if (m == p.inventoryMenu) { finish("closed"); return false; }
		if (!(m instanceof AbstractFurnaceMenu) || now - stepSince > 20_000) { p.closeContainer(); finish("no furnace"); return false; }
		if (now < nextClick) return true;
		String owner = visit.owner;
		ItemStack top = m.slots.get(0).getItem(), below = m.slots.get(1).getItem(), done = m.slots.get(2).getItem();
		if (!seen) {
			seen = true;
			Exchange.emit("furnaces", "action", "sees", "above", describe(top), "below", describe(below), "done", describe(done), "menu", m.getClass().getSimpleName(), "slots", m.slots.size());
		}
		if (learning) {
			boolean any = false;
			if (!top.isEmpty()) { inputs.computeIfAbsent(visit.server + "|" + owner, k -> new java.util.HashSet<>()).add(AfkHelperClient.itemId(top)); any = true; }
			if (!below.isEmpty()) { fuels.computeIfAbsent(visit.server + "|" + owner, k -> new java.util.HashSet<>()).add(AfkHelperClient.itemId(below)); any = true; }
			save();
			Exchange.emit("furnaces", "action", any ? "learnt" : "nothing to learn", "smelt", top.isEmpty() ? "" : AfkHelperClient.itemId(top),
				"fuel", below.isEmpty() ? "" : AfkHelperClient.itemId(below), "smelt_known", String.join(",", inputs.getOrDefault(visit.server + "|" + owner, Set.of())),
				"fuel_known", String.join(",", fuels.getOrDefault(visit.server + "|" + owner, Set.of())));
			p.closeContainer();
			Follow.respond(any ? Follow.YES : Follow.NO);
			finish("learnt");
			return false;
		}
		// counted before the move: the slot's stack is empty after it
		if (!done.isEmpty()) { out += done.getCount(); Game.quickMove(mc.gameMode, m.containerId, 2, p); pause(now); return true; }
		Set<String> mayIn = inputs.getOrDefault(visit.server + "|" + owner, Set.of()), mayBurn = fuels.getOrDefault(visit.server + "|" + owner, Set.of());
		// at most a few moves of each a visit: a thing this kind of furnace does not take stays where it is
		int s = playerSlot(m, mayIn, top);
		if (s >= 0 && tries < 4 && top.getCount() < top.getMaxStackSize()) { tries++; in += m.slots.get(s).getItem().getCount(); Game.quickMove(mc.gameMode, m.containerId, s, p); pause(now); return true; }
		s = playerSlot(m, mayBurn, below);
		// once: a fuel that can be smelted as well goes on top when the top is empty, and that once is enough
		if (s >= 0 && fuel == 0 && below.getCount() < below.getMaxStackSize()) { fuel++; Game.quickMove(mc.gameMode, m.containerId, s, p); pause(now); return true; }
		p.closeContainer();
		finish("done");
		return false;
	}

	private static boolean seen;

	private static String describe(ItemStack st) { return st.isEmpty() ? "" : AfkHelperClient.itemId(st) + "x" + st.getCount(); }

	private static void pause(long now) { nextClick = now + ThreadLocalRandom.current().nextLong(200, 400); }

	/** A slot of his own (in the menu's numbers) with one of these items, the same as what lies there if anything does. */
	private static int playerSlot(AbstractContainerMenu m, Set<String> may, ItemStack there) {
		for (int s = 3; s < m.slots.size(); s++) {
			ItemStack st = m.slots.get(s).getItem();
			if (st.isEmpty()) continue;
			String id = AfkHelperClient.itemId(st);
			if (!may.contains(id)) continue;
			if (!there.isEmpty() && !ItemStack.isSameItemSameComponents(there, st)) continue;
			return s;
		}
		return -1;
	}

	private static void finish(String how) {
		if (visit != null) {
			visit.visited = System.currentTimeMillis();
			if (!learning) Exchange.emit("furnaces", "action", "furnace", "how", how, "x", visit.pos.getX(), "y", visit.pos.getY(), "z", visit.pos.getZ(),
				"out", out, "in", in, "fuel", fuel);
		}
		step = IDLE;
		visit = null;
		stand = null;
		clickAt = null;
		learning = false;
	}

	static void stop(String why) {
		if (step == IDLE) return;
		LocalPlayer p = AfkHelperClient.mc().player;
		if (p != null && step == MENU && p.containerMenu != p.inventoryMenu) p.closeContainer();
		Exchange.emit("furnaces", "action", "stopped", "reason", why);
		finish("stopped");
	}

	// ---------- forgetting and the file ----------

	/** Forget the furnaces and what he learned for this player on this server; how many furnaces. */
	static int forget(String owner) {
		load();
		String server = Profiles.key(), who = owner.toLowerCase(Locale.ROOT);
		int before = ovens.size();
		ovens.removeIf(o -> o.server.equals(server) && o.owner.equals(who));
		inputs.remove(key(owner));
		fuels.remove(key(owner));
		onFor.remove(key(owner));
		save();
		stop("forgotten");
		return before - ovens.size();
	}

	private static Path file() { return Paths.get(AfkConfig.get().exchangeDir).resolve("furnaces.json"); }

	private static void load() {
		if (loaded) return;
		loaded = true;
		try {
			Path f = file();
			if (!Files.exists(f)) return;
			JsonObject root = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
			for (JsonElement el : root.get("furnaces").getAsJsonArray()) {
				JsonObject o = el.getAsJsonObject();
				Oven v = new Oven();
				v.server = o.get("server").getAsString();
				v.dim = o.get("dim").getAsString();
				v.owner = o.get("owner").getAsString();
				v.pos = new BlockPos(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt());
				ovens.add(v);
			}
			for (var e : root.get("smelts").getAsJsonObject().entrySet()) {
				Set<String> s = new java.util.HashSet<>();
				for (JsonElement x : e.getValue().getAsJsonArray()) s.add(x.getAsString());
				inputs.put(e.getKey(), s);
			}
			for (var e : root.get("fuels").getAsJsonObject().entrySet()) {
				Set<String> s = new java.util.HashSet<>();
				for (JsonElement x : e.getValue().getAsJsonArray()) s.add(x.getAsString());
				fuels.put(e.getKey(), s);
			}
			for (var e : root.get("on").getAsJsonObject().entrySet()) onFor.put(e.getKey(), e.getValue().getAsBoolean());
		} catch (Exception e) {
			Exchange.emit("error", "msg", "furnaces.json: " + e.getMessage());
		}
	}

	private static void save() {
		JsonObject root = new JsonObject();
		JsonArray a = new JsonArray();
		for (Oven v : ovens) {
			JsonObject o = new JsonObject();
			o.addProperty("server", v.server);
			o.addProperty("dim", v.dim);
			o.addProperty("owner", v.owner);
			o.addProperty("x", v.pos.getX());
			o.addProperty("y", v.pos.getY());
			o.addProperty("z", v.pos.getZ());
			a.add(o);
		}
		root.add("furnaces", a);
		root.add("smelts", sets(inputs));
		root.add("fuels", sets(fuels));
		JsonObject on = new JsonObject();
		onFor.forEach(on::addProperty);
		root.add("on", on);
		try {
			Path f = file(), tmp = f.resolveSibling("furnaces.json.tmp");
			Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().create().toJson(root));
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception e) {
			Exchange.emit("error", "msg", "writing furnaces.json: " + e.getMessage());
		}
	}

	private static JsonObject sets(Map<String, Set<String>> m) {
		JsonObject o = new JsonObject();
		for (var e : m.entrySet()) {
			JsonArray a = new JsonArray();
			e.getValue().stream().sorted().forEach(a::add);
			o.add(e.getKey(), a);
		}
		return o;
	}
}
