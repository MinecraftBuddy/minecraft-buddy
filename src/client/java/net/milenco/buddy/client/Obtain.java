package net.milenco.buddy.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.milenco.buddy.version.Game;

/**
 * Obtain items: an order of an item and a count, fetched outside the base with every step in between (docs/obtain.md
 * has the design). The way is {@link ObtainPlan}'s, asked again after every step; the steps are {@link ObtainSteps}'.
 * It runs as a step of Follow's chain, after what keeps the buddy alive there (air, fire, a creeper, a fight, getting
 * loose), and the following waits meanwhile.
 *
 * <p>Exchange: {@code {"cmd":"obtain","item":"diamond_block","count":4,"by":"Steve"}}, {@code "stop":true}, and
 * {@code "plan":true} for the plan alone (event {@code obtain} {@code plan}). Events of type {@code obtain}:
 * {@code start}, {@code step} (what it does now), {@code done}, {@code failed} (a step that did not come off, and why),
 * {@code gives up}, {@code stop}, {@code status}.
 */
final class Obtain {
	private Obtain() {}

	private static String item, by;
	private static int count, startHad;
	private static long since, stepSince;
	private static ObtainPlan.Step step;
	private static ObtainSteps.Exec exec;
	/** Ways that did not come off, until when they are left alone. */
	private static final Map<String, Long> blocked = new HashMap<>();
	/** Ways the kit was not to take because they lead as deep as the step it is for; not cleared with the blocked ones. */
	private static final Map<String, Long> tooDeep = new HashMap<>();
	/** The ores the plan will mine further on (ObtainSteps.Mine takes one that lies open in reach on the way). */
	static volatile java.util.Set<String> wantedLater = java.util.Set.of();
	private static final Map<String, Integer> fails = new HashMap<>();
	private static int steps, retries;
	/** Stations this order placed, where: picked up again before it goes on elsewhere. */
	private static final Map<BlockPos, String> placed = new HashMap<>();
	/** The kit needs reported, once each (and once per step for the ones being fetched). */
	private static final java.util.Set<String> kitNoted = new java.util.HashSet<>();
	private static long nextStatus;

	static boolean on() { return item != null || resting; }

	/**
	 * An order given up in the night: shut in until the morning all the same. It stood still in the open after giving up
	 * at dusk and phantoms killed it; a buddy without an order has nothing that digs it in.
	 */
	private static boolean resting;
	private static ObtainSafe.Shelter rest;

	private static boolean restTick(Minecraft mc, LocalPlayer p, long now) {
		if (!ObtainSafe.needsShelter(p)) {
			resting = false;
			rest = null;
			HeldKeys.releaseAll();
			Exchange.emit("obtain", "action", "rested", "why", "morning");
			return false;
		}
		if (rest == null) rest = new ObtainSafe.Shelter();
		ObtainSteps.Result r = rest.tick(mc, p, now);
		if (r == ObtainSteps.Result.DONE || r == ObtainSteps.Result.FAIL) rest = null;
		return true;
	}

	/** Gold on for the piglins: an order in the nether, or one whose step is the way there. */
	static boolean wantsGold(LocalPlayer p) {
		if (!on()) return false;
		if (p.level().dimension().equals(Level.NETHER)) return true;
		ObtainPlan.Step s = step;
		return s != null && s.act() == ObtainPlan.Act.SPECIAL && "@nether".equals(s.item());
	}

	/**
	 * No order, nobody to follow, and the last order done out in the wild: the buddy stays in Follow's chain, so that it
	 * fights what comes at it and does not stand there to be killed (a zombie killed it while it stood idle at the spawn
	 * at night). Ends with the next order or a follow.
	 */
	private static boolean watching;

	static boolean watching() { return watching && !on() && Follow.target() == null; }

	/** Watching: standing where it is; the chain before this step fights. */
	static boolean watchTick(Minecraft mc, LocalPlayer p) {
		Follow.stand();
		return false;
	}

	/** The order on disk, so that a restart of the game carries on with it. */
	private static java.nio.file.Path file() { return java.nio.file.Paths.get(AfkConfig.get().exchangeDir, "obtain.json"); }

	private static void save() {
		try {
			if (item == null) { java.nio.file.Files.deleteIfExists(file()); return; }
			JsonObject o = new JsonObject();
			o.addProperty("item", item);
			o.addProperty("count", count);
			if (by != null) o.addProperty("by", by);
			o.addProperty("since", since);
			java.nio.file.Files.writeString(file(), o.toString());
		} catch (java.io.IOException ignored) {}
	}

	private static boolean listed;

	/**
	 * What can be ordered, for the panel's list (obtain-items.json in the exchange folder): every item the planner gets to
	 * from an empty bag. Once per start of the game, off its thread (about a second).
	 */
	static void writeList() {
		if (listed) return;
		listed = true;
		Thread t = new Thread(() -> {
			try {
				List<String> items = ObtainPlan.obtainable();
				java.nio.file.Path f = java.nio.file.Paths.get(AfkConfig.get().exchangeDir, "obtain-items.json"), tmp = f.resolveSibling("obtain-items.json.tmp");
				java.nio.file.Files.writeString(tmp, new com.google.gson.Gson().toJson(Map.of("items", items, "t", System.currentTimeMillis())));
				java.nio.file.Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
			} catch (Exception e) {
				Exchange.emit("error", "msg", "obtain-items.json: " + e.getMessage());
			}
		}, "buddy-obtain-list");
		t.setDaemon(true);
		t.start();
	}

	/** On a new connection: an order that was under way when the game stopped is taken up again. */
	static void newConnection() {
		writeList();
		portalRoom = null;
		if (on()) { if (exec != null) exec.stop(); exec = null; step = null; return; }
		try {
			if (!java.nio.file.Files.exists(file())) return;
			JsonObject o = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(file())).getAsJsonObject();
			start(o.get("item").getAsString(), o.get("count").getAsInt(), o.has("by") ? o.get("by").getAsString() : null);
			if (o.has("since")) since = o.get("since").getAsLong();
			Exchange.emit("obtain", "action", "resumed", "item", item, "count", count);
		} catch (Exception ignored) {}
	}

	static boolean steering() { return on(); }

	static String item() { return item; }

	/** How the last order ended, for the panel once it is over: the item, the count, done or why not, and when. */
	private static Map<String, Object> last;

	/**
	 * The order for the panel (state.json's {@code obtain}): the item and count, how many it has of them, the step under
	 * way and how long it runs, the steps done; and when none runs, how the last one ended.
	 */
	static Map<String, Object> details(LocalPlayer p) {
		Map<String, Object> o = new java.util.LinkedHashMap<>();
		if (!on()) {
			if (last == null) return null;
			o.put("on", false);
			o.putAll(last);
			return o;
		}
		o.put("on", true);
		o.put("item", item);
		o.put("count", count);
		o.put("have", Math.max(0, ObtainSteps.count(p, item) - startHad));
		o.put("by", by == null ? "" : by);
		o.put("since", since);
		o.put("steps", steps);
		o.put("step", step == null ? "" : step.toString());
		o.put("step_s", step == null ? 0 : (System.currentTimeMillis() - stepSince) / 1000);
		if (exec instanceof ObtainSafe.Shelter sh) o.put("sheltering", sh.wound ? "to heal" : "for the night");
		// what Soprano does now, in its own words; its lines with their times are in the panel's Activity tab, where a log
		// reads well
		String d = Alto.present() ? Alto.doing() : null;
		if (d != null) o.put("doing", d);
		return o;
	}

	private static void ended(String how, String why) {
		last = new java.util.LinkedHashMap<>();
		last.put("item", item);
		last.put("count", count);
		last.put("end", how);
		last.put("why", why == null ? "" : why);
		last.put("at", System.currentTimeMillis());
		last.put("s", (System.currentTimeMillis() - since) / 1000);
	}

	static String by() { return by; }

	static void command(JsonObject o) {
		if (o.has("stop") && o.get("stop").getAsBoolean()) { stop("stopped"); return; }
		String it = o.has("item") ? VanillaData.plain(o.get("item").getAsString().trim().toLowerCase(java.util.Locale.ROOT)) : null;
		int n = o.has("count") ? Math.max(1, Math.min(64 * 36, o.get("count").getAsInt())) : 1;
		if (it == null || it.isEmpty()) { Exchange.emit("obtain", "action", "status", "item", item, "count", count, "step", step == null ? null : step.toString()); return; }
		if (!VanillaData.present()) { Exchange.emit("error", "msg", "obtain: the game's data is not on the classpath"); return; }
		if (!ObtainPlan.known(it)) { Exchange.emit("error", "msg", "obtain: no way known to " + it); return; }
		if (o.has("plan") && o.get("plan").getAsBoolean()) {
			LocalPlayer p = AfkHelperClient.mc().player;
			ObtainPlan.Plan pl = ObtainPlan.plan(it, n, p == null ? new ObtainPlan.Have() { public int inv(String x) { return 0; } } : new GameHave(p));
			Exchange.emit("obtain", "action", "plan", "item", it, "count", n, "ok", pl.ok(), "missing", pl.missing(),
				"steps", pl.steps().stream().map(ObtainPlan.Step::toString).toList());
			return;
		}
		start(it, n, o.has("by") ? o.get("by").getAsString() : null);
	}

	static void start(String it, int n, String who) {
		sopranoEnds = 0;
		deep = null;
		foodAfterHome = false;
		Alto.orderDanger = ObtainKit.Danger.DEEP;
		Alto.kitReset();
		LocalPlayer p = AfkHelperClient.mc().player;
		if (on()) stop("a new order");
		item = it;
		count = n;
		by = who;
		since = System.currentTimeMillis();
		startHad = p == null ? 0 : ObtainSteps.count(p, it);
		blocked.clear();
		tooDeep.clear();
		fails.clear();
		placed.clear();
		kitNoted.clear();
		kitUnmet = 0;
		kitRetryAt = 0;
		furnaceFull = null;
		steps = 0;
		retries = 0;
		step = null;
		exec = null;
		watching = false;
		Jobs.switchedOn(Jobs.Job.OBTAIN, "obtain");
		save();
		Exchange.emit("obtain", "action", "start", "item", it, "count", n, "by", who, "had", startHad);
	}

	static void stop(String why) {
		if (!on()) return;
		ended("stopped", why);
		if (exec != null) exec.stop();
		Exchange.emit("obtain", "action", "stop", "item", item, "count", count, "why", why, "steps", steps,
			"s", (System.currentTimeMillis() - since) / 1000);
		// given up (not stopped by whoever ordered it) in the night: shut in until the morning
		LocalPlayer me = AfkHelperClient.mc() == null ? null : AfkHelperClient.mc().player;
		resting = why != null && why.startsWith("no ") && me != null && ObtainSafe.needsShelter(me);
		rest = null;
		item = null;
		step = null;
		exec = null;
		save();
		watching = true;
		HeldKeys.releaseAll();
		Follow.release();
	}

	// ---------- the tick ----------

	static void startTick(Minecraft mc) {
		if (exec != null) exec.startTick(mc);
	}

	static boolean inMenu(LocalPlayer p) { return exec != null && exec.inMenu(p); }

	private static boolean inMenuNow(LocalPlayer p) { return p.containerMenu != p.inventoryMenu; }

	static boolean menuTick(Minecraft mc, LocalPlayer p) {
		ObtainSteps.Result r = exec.menuTick(mc, p, System.currentTimeMillis());
		return end(r, p);
	}

	/** Every tick while an order is on, from Follow's chain: the next step, or the one under way. */
	static boolean tick(Minecraft mc, LocalPlayer p) {
		long now = System.currentTimeMillis();
		if (item == null && resting) return restTick(mc, p, now);
		BlockScan.tick(mc.level, p.blockPosition());
		if (now >= nextStatus) {
			nextStatus = now + 5000;
			Exchange.emit("obtain", "action", "status", "item", item, "count", count, "have", ObtainSteps.count(p, item),
				"step", step == null ? null : step.toString(), "s", step == null ? 0 : (now - stepSince) / 1000, "at", exec == null ? "" : exec.detail(),
				"pos", p.blockPosition().toShortString(), "hp", p.getHealth(), "sees", Work.sight(mc), "hand", AfkHelperClient.itemId(p.getMainHandItem()),
				"yaw", Math.round(p.getYRot()), "pitch", Math.round(p.getXRot()),
				// what the food step goes by: it starved at 4 hp with no food step, and these say why next time
				"hunger", p.getFoodData().getFoodLevel(), "food", Alto.present() ? Alto.foodScore() : -1, "wantsfood", Alto.wantsFood(p),
				"foodago", (p.level().getGameTime() * 50 - foodTriedAt) / 1000, "eatblock", Alto.present() ? Alto.eatBlock() : "",
				// the client's ticks and the world's time, to see the game keep up with a faster tick rate (practiceFastTicks)
				"ticks", Follow.ticks(), "gametime", p.level().getGameTime());
		}
		if (ObtainSteps.count(p, item) >= count) { done(p); return false; }
		// Soprano first: an item its catalogue knows is got by it whole, and the buddy's own layer (the kit, the night's
		// hole, out of the water, digging out, throwing junk away) keeps out of its way; the order, its status, a stop and
		// the delivery stay the buddy's
		// on the world's clock (its game time in ms): its checks and waits then take the same game time at any tick rate, a
		// practice server's five times the speed included, where the wall clock made them five times as slow in the game
		// nearly full: what is worth nothing out first, between two of its clicks; for Soprano's order too (a staircase's
		// cobblestone filled 29 slots, there was no room for iron or wood, and the kit went round for three hours). Over 96
		// blocks of stone and the like go, the rest stays for steps, pillars and the night's hole
		if (Work.freeSlots(p) <= 3 && p.containerMenu == p.inventoryMenu && now >= nextThrow) {
			int j = ObtainSafe.junkSlot(p);
			if (j >= 0) {
				nextThrow = now + 400;
				String junk = AfkHelperClient.itemId(p.getInventory().getItem(j));
				// behind it and a little up, where it came from: thrown ahead into a staircase it lay on its next step, was picked
				// up two seconds later and thrown again, for minutes
				float yaw = p.getYRot(), pitch = p.getXRot();
				Game.lookOnServer(p, yaw + 180f, -30f);
				Game.drop(mc.gameMode, p.inventoryMenu.containerId, j < 9 ? 36 + j : j, p);
				Game.lookOnServer(p, yaw, pitch);
				ObtainSafe.threw(junk, p.position());
				Exchange.emit("obtain", "action", "throws away", "item", junk);
			}
		}
		if (Alto.knows(item)) return whole(mc, p, p.level().getGameTime() * 50);
		ObtainSafe.watch(p, now);
		// where it last stood under the sky in this world: the way back up without a pickaxe
		if (p.onGround() && !p.isInWater() && p.level().canSeeSky(p.blockPosition().above())) { lastSky = p.blockPosition().immutable(); lastSkyDim = p.level().dimension(); }
		// in water under the ground and not getting anywhere in it (a swim across a cave's lake is the walker's): out of it
		// first, to a dry cell across the ground, not up and down: up for air and down again is no way out of a lake
		if (wetPos == null || Math.hypot(p.getX() - wetPos.x, p.getZ() - wetPos.z) > 2) { wetPos = p.position(); wetMovedAt = now; }
		// at night at once: the drowned come up out of the water then (one killed it at sea after dusk)
		if (ObtainSafe.wetUnder(p) && (now - wetMovedAt > 8000 || ObtainSafe.night(p.level())) && !(exec instanceof ObtainSafe.Dry) && !(exec instanceof ObtainSteps.Cast) && !inMenuNow(p) && now > dryFailedAt) {
			if (exec != null) exec.stop();
			exec = new ObtainSafe.Dry();
			step = new ObtainPlan.Step(ObtainPlan.Act.SPECIAL, "@dry", 1, null, 0, List.of(), "out of the water");
			stepSince = now;
			Exchange.emit("obtain", "action", "step", "step", "out of the water", "way", "dry");
		}
		// stuck underground: 45 seconds in one place and no sky over it while a step wants to walk: a stair up first
		if (lastPos == null || p.blockPosition().distSqr(lastPos) > 4) { lastPos = p.blockPosition(); lastMovedAt = now; }
		boolean still = exec instanceof ObtainSafe.Shelter || exec instanceof ObtainSafe.Bed || exec instanceof ObtainSteps.Smelt || exec instanceof ObtainSteps.Craft
			|| exec instanceof ObtainSteps.DigOut || exec instanceof ObtainSteps.Cast || inMenuNow(p) || Bari.busy() || exec instanceof Alto.Run;
		// time standing in a shelter or at a station is no time stuck: the first look after a shelter came 45 seconds after
		// the last step and dug it out among the spiders it had walled itself in from, three times
		if (still) lastMovedAt = now;
		if (!still && now - lastMovedAt > 45_000 && !p.level().canSeeSky(p.blockPosition().above()) && !ObtainSafe.needsShelter(p)) {
			if (exec != null) exec.stop();
			exec = new ObtainSteps.DigOut(p);
			step = new ObtainPlan.Step(ObtainPlan.Act.SPECIAL, "@sky", 1, null, 0, List.of(), "dig out");
			stepSince = now;
			lastMovedAt = now;
			Exchange.emit("obtain", "action", "step", "step", "dig out: stuck underground", "way", "digout", "x", p.getBlockX(), "y", p.getBlockY(), "z", p.getBlockZ());
		}
		// night without iron armour, or hurt with no fight on: into the ground first, whatever step was under way (it starts
		// again in the morning, or healed)
		// hurt and no food to heal by: food first, not the hole (it sat in one at half health and food 14, came out after 45
		// seconds unhealed and went straight back in, again and again)
		// and not while AltoClef gathers food: the food is what heals (on normal it was sent to heal in a hole every twenty
		// seconds of the food task, healed nothing there with a raw rabbit that AltoClef does not eat, and walked at 2 hp for
		// ten minutes until a skeleton shot it)
		boolean wound = !ObtainSafe.needsShelter(p) && ObtainSafe.hurt(p) && ObtainSafe.foes(p).isEmpty() && ObtainSafe.canHeal(p)
			&& !(exec instanceof Alto.Run r && r.food());
		// a bed it carries first: the night skipped where the server lets it, the hole when it does not
		if (ObtainSafe.needsShelter(p) && !(exec instanceof ObtainSafe.Bed) && !(exec instanceof ObtainSafe.Shelter) && !(exec instanceof ObtainSafe.Dry)
				&& !inMenuNow(p) && ObtainSafe.bedNight(p)) {
			if (exec != null) exec.stop();
			step = new ObtainPlan.Step(ObtainPlan.Act.SPECIAL, "@bed", 1, null, 0, List.of(), "bed");
			stepSince = now;
			exec = new ObtainSafe.Bed();
			Exchange.emit("obtain", "action", "step", "step", "a night in bed", "way", "bed");
		}
		// out of the water first: the night's hole and the walk out of the water took the step from each other every tick
		if ((ObtainSafe.needsShelter(p) || wound) && !(exec instanceof ObtainSafe.Shelter) && !(exec instanceof ObtainSafe.Bed)
				&& !(exec instanceof ObtainSafe.Dry) && !inMenuNow(p)) {
			if (exec != null) exec.stop();
			step = new ObtainPlan.Step(ObtainPlan.Act.SPECIAL, "@shelter", 1, null, 0, List.of(), "shelter");
			stepSince = now;
			exec = new ObtainSafe.Shelter(wound);
			Exchange.emit("obtain", "action", "step", "step", wound ? "shelter to heal" : "shelter for the night", "way", "shelter", "armour", p.getArmorValue(), "hp", p.getHealth());
		}
		if (exec == null) {
			// the first round of the scan has to be in, or every block counts as far away
			if (BlockScan.age() == Long.MAX_VALUE) return false;
			// and the round of this place, after a teleport or a respawn far off (it went for oak in a birch forest it had not
			// looked at yet)
			if (!BlockScan.covers(p.blockPosition(), 64)) {
				if (scanWaitFrom == 0) scanWaitFrom = now;
				if (now - scanWaitFrom < 8000) { Follow.stand(); return false; }
			} else scanWaitFrom = 0;
			// a furnace that still holds what a smelt put in: emptied first, near or not (it is the buddy's own)
			if (furnaceFull != null && furnaceFull.distSqr(p.blockPosition()) < 64 * 64 && !badStation(furnaceFull)
					&& "furnace".equals(ObtainSteps.id(p.level().getBlockState(furnaceFull)))) {
				step = new ObtainPlan.Step(ObtainPlan.Act.SPECIAL, "furnace", 1, null, 0, List.of(), "empty the furnace");
				stepSince = now;
				exec = new ObtainSteps.Empty(furnaceFull);
				Exchange.emit("obtain", "action", "step", "step", "empty the furnace", "way", "empty");
				return exec.tick(mc, p, now) == ObtainSteps.Result.RUN;
			}
			blocked.values().removeIf(until -> until < now);
			tooDeep.values().removeIf(until -> until < now);
			ObtainPlan.Plan plan = ObtainPlan.plan(item, count, new GameHave(p));
			// what the rest of the plan will mine: taken on the way when it lies open in reach (ObtainSteps.Mine)
			{
				java.util.Set<String> later = new java.util.HashSet<>();
				for (ObtainPlan.Step x : plan.steps()) if (x.act() == ObtainPlan.Act.MINE) later.addAll(x.sources());
				later.removeIf(b -> !BlockScan.ore(b));
				wantedLater = later;
			}
			if (!plan.ok()) {
				// a way left alone for a while may be the only one: those tried again first, a few times, before giving up
				if (!blocked.isEmpty() && retries < 8) {
					if (now < retryAt) { Follow.stand(); return false; }
					retries++;
					retryAt = now + 15_000;
					Exchange.emit("obtain", "action", "no way now", "item", item, "why", plan.missing(), "blocked", new ArrayList<>(blocked.keySet()), "retry", retries);
					blocked.clear();
					fails.clear();
					return false;
				}
				Exchange.emit("obtain", "action", "gives up", "item", item, "why", plan.missing());
				stop("no way: " + plan.missing());
				last.put("end", "gave up");
				return false;
			}
			ObtainPlan.Step s = plan.next();
			if (s == null) { done(p); return false; }
			// the gear gate of where this step takes it: what the kit lacks first, by steps of a safer kind
			GameHave gh = new GameHave(p);
			gh.kit = true;
			ObtainKit.Need food = ObtainKit.hungry(p, gh);
			if (food != null) {
				ObtainPlan.Plan fp = ObtainPlan.planAny(food.any(), food.count(), gh);
				if (fp.ok() && fp.next() != null) {
					if (kitNoted.add("food@" + steps)) Exchange.emit("obtain", "action", "kit", "need", food.name(), "food", p.getFoodData().getFoodLevel());
					s = fp.next();
				}
			}
			// a bed while sheep are about and it has none: a night skipped in it is a night not spent in a hole
			if (!ObtainSafe.hasBed(p) && gh.dim() == Nature.Dim.OVERWORLD && !ObtainSafe.needsShelter(p)) {
				double sheep = gh.nearMob("sheep");
				if (sheep >= 0 && sheep < 40) {
					ObtainPlan.Plan bp = ObtainPlan.planAny(VanillaData.tag("item", "beds"), 1, gh);
					ObtainPlan.Step b = bp.ok() ? bp.next() : null;
					if (b != null && ObtainKit.of(b, gh.dim()) == ObtainKit.Danger.SURFACE) {
						if (kitNoted.add("bed@" + steps)) Exchange.emit("obtain", "action", "kit", "need", "bed", "sheep", Math.round(sheep));
						s = b;
					}
				}
			}
			// a piece of armour the iron in the bag pays for, first, whatever the step: then the rest of the kit
			{
				ObtainKit.Need a = ObtainKit.affordableArmour(gh);
				if (a != null) {
					ObtainPlan.Plan ap = ObtainPlan.planAny(a.any(), 1, gh);
					ObtainPlan.Step k = ap.ok() ? ap.next() : null;
					if (k != null && ObtainKit.of(k, gh.dim()) == ObtainKit.Danger.SURFACE) {
						if (kitNoted.add(a.name() + "@" + steps)) Exchange.emit("obtain", "action", "kit", "need", a.name(), "for", "now", "before", s.toString());
						s = k;
					}
				}
			}
			// all the kit lacks in one plan first: the gathering for every piece in one go (one mine for all the armour's iron)
			{
				ObtainKit.Danger d = ObtainKit.of(s, gh.dim());
				List<ObtainKit.Need> all = ObtainKit.missingAll(d, gh);
				if (all.size() > 1) {
					ObtainPlan.Plan kp = ObtainPlan.planAll(all.stream().map(ObtainKit.Need::any).toList(), all.stream().map(ObtainKit.Need::count).toList(), gh);
					ObtainPlan.Step k = kp == null ? null : kp.next();
					if (k != null && ObtainKit.of(k, gh.dim()).ordinal() < d.ordinal()) {
						if (kitNoted.add("all@" + steps)) Exchange.emit("obtain", "action", "kit", "need", all.stream().map(ObtainKit.Need::name).toList(), "for", d.name().toLowerCase(), "before", s.toString());
						s = k;
					}
				}
			}
			for (int i = 0; i < 6; i++) {
				ObtainKit.Danger d = ObtainKit.of(s, gh.dim());
				ObtainKit.Need need = ObtainKit.missing(d, gh);
				if (need == null) break;
				ObtainPlan.Plan kp = ObtainPlan.planAny(need.any(), need.count(), gh);
				ObtainPlan.Step k = kp.ok() ? kp.next() : null;
				// on the surface a surface step is fine for the kit (the stone sword is a craft there, as safe as the step)
				int kd = k == null ? 0 : ObtainKit.of(k, gh.dim()).ordinal();
				// a way for the kit as dangerous as the step only because the one it saw lies deep (deepslate iron beside it, where
				// iron is had higher up too): that way out for ten minutes, and the plan again, for the same thing the safe way. It
				// gave the whole order up three times over such iron
				if (k != null && (kd > d.ordinal() || kd == d.ordinal() && d != ObtainKit.Danger.SURFACE) && (k.act() == ObtainPlan.Act.MINE || k.act() == ObtainPlan.Act.KILL)
						&& tooDeep.size() < 12 && tooDeep.putIfAbsent(k.way(), now + 600_000) == null) {
					Exchange.emit("obtain", "action", "kit the safe way", "need", need.name(), "not", k.toString());
					i--;
					continue;
				}
				if (k == null || kd > d.ordinal() || kd == d.ordinal() && d != ObtainKit.Danger.SURFACE) {
					if (kitNoted.add(need.name())) Exchange.emit("obtain", "action", "kit unmet", "need", need.name(), "for", d.name().toLowerCase(), "why", kp.ok() ? "it would take a step as dangerous" : kp.missing());
					// never into the deep without its kit: a way blocked for a while left the armour unmet, and it went down to y 8
					// without it. The blocked ways once more, three times, then the order is given up: rather no item than a death
					if (d.ordinal() >= ObtainKit.Danger.DEEP.ordinal()) {
						if (now < kitRetryAt) { Follow.stand(); return false; }
						if (++kitUnmet > 3) {
							Exchange.emit("obtain", "action", "gives up", "item", item, "why", "the kit for the " + d.name().toLowerCase() + ": " + need.name());
							stop("no safe way: the kit for the " + d.name().toLowerCase() + " lacks " + need.name());
							last.put("end", "gave up");
							return false;
						}
						Exchange.emit("obtain", "action", "kit again", "need", need.name(), "for", d.name().toLowerCase(), "try", kitUnmet, "blocked", new ArrayList<>(blocked.keySet()));
						blocked.clear();
						fails.clear();
						kitNoted.remove(need.name());
						kitRetryAt = now + 15_000;
						return false;
					}
					break;
				}
				kitUnmet = 0;
				if (kitNoted.add(need.name() + "@" + steps)) Exchange.emit("obtain", "action", "kit", "need", need.name(), "count", need.count(), "for", d.name().toLowerCase(), "before", s.toString());
				s = k;
			}
			// a station it placed and this step does not use: taken along first
			String usesStation = s.recipe() == null ? null : s.act() == ObtainPlan.Act.SMELT ? "furnace" : s.act() == ObtainPlan.Act.CRAFT && !s.recipe().small() ? "crafting_table" : null;
			for (Map.Entry<BlockPos, String> e : new ArrayList<>(placed.entrySet())) {
				boolean gone = !e.getValue().equals(ObtainSteps.id(p.level().getBlockState(e.getKey())));
				if (gone) { placed.remove(e.getKey()); continue; }
				if (e.getValue().equals(usesStation) && e.getKey().distSqr(p.blockPosition()) < 12 * 12) continue;
				placed.remove(e.getKey());
				step = new ObtainPlan.Step(ObtainPlan.Act.SPECIAL, e.getValue(), 1, null, 0, List.of(), "pick up");
				stepSince = now;
				exec = new ObtainSteps.Retrieve(e.getKey(), e.getValue(), p);
				Exchange.emit("obtain", "action", "step", "step", "pick up the " + e.getValue(), "way", "pickup:" + e.getValue());
				return exec.tick(mc, p, now) == ObtainSteps.Result.RUN;
			}
			// a step on the surface while it is deep underground: a stair of its own up to the sky first, never a walk through
			// the caves (it went after cows from y 33 through a lush cave without armour, and a skeleton there killed it)
			// at night not up among the monsters: shut in down here until the morning
			if (wantsSurface(s, p) && ObtainSafe.night(p.level()) && p.getArmorValue() < 15) {
				ObtainSafe.Shelter sh = new ObtainSafe.Shelter();
				sh.tillMorning = true;
				exec = sh;
				step = new ObtainPlan.Step(ObtainPlan.Act.SPECIAL, "@shelter", 1, null, 0, List.of(), "shelter");
				stepSince = now;
				Exchange.emit("obtain", "action", "step", "step", "the morning down here, then " + s, "way", "shelter", "y", p.getBlockY());
				return exec.tick(mc, p, now) == ObtainSteps.Result.RUN;
			}
			if (wantsSurface(s, p) && now - upFailedAt > 300_000) {
				step = new ObtainPlan.Step(ObtainPlan.Act.SPECIAL, "@sky", 1, null, 0, List.of(), "up to the surface");
				stepSince = now;
				exec = new ObtainSteps.DigOut(p);
				Exchange.emit("obtain", "action", "step", "step", "up to the surface for " + s, "way", "digout", "y", p.getBlockY());
				return exec.tick(mc, p, now) == ObtainSteps.Result.RUN;
			}
			// food for the kit or a meal: AltoClef's food task, which looks for every kind at once and moves on to new ground
			// (the buddy's own hunt went after chickens alone, round and round one place)
			// and blaze rods and ender pearls: AltoClef's catalogue, the way to them and the camp included
			if (Alto.forFood(s, item) || Alto.forCatalogue(s)) {
				boolean eat = Alto.forFood(s, item);
				step = new ObtainPlan.Step(ObtainPlan.Act.SPECIAL, eat ? "@food" : "@alto:" + s.item(), s.count(), null, 0, List.of(), eat ? "food by AltoClef" : "by AltoClef");
				stepSince = now;
				steps++;
				exec = eat ? Alto.food(Math.max(20, s.count() * 6.0)) : Alto.get(s.item(), s.count());
				Exchange.emit("obtain", "action", "step", "step", (eat ? "food" : s.item()) + " by AltoClef, for " + s, "way", eat ? "alto:food" : "alto:get");
				return exec.tick(mc, p, now) == ObtainSteps.Result.RUN;
			}
			step = s;
			stepSince = now;
			steps++;
			exec = ObtainSteps.of(s, p);
			Exchange.emit("obtain", "action", "step", "step", s.toString(), "way", s.way(), "left", plan.steps().size());
			if (exec == null) { fail(s, "no step made for " + s.act()); return false; }
		}
		// a mine under way and iron in the bag now for a piece of armour it lacks: the mine ends here, and the kit makes the
		// piece first (a mine for thirty raw iron went on with six in the bag and no shield)
		if (exec instanceof ObtainSteps.Mine && now >= armourLook) {
			armourLook = now + 2000;
			ObtainKit.Need a = ObtainKit.affordableArmour(new GameHave(p));
			// once a piece: the mine for its coal is a mine too, and was stopped every two seconds
			if (a == null) armourFor = null;
			if (a != null && !a.name().equals(armourFor)) {
				armourFor = a.name();
				Exchange.emit("obtain", "action", "stops for armour", "need", a.name(), "step", step == null ? "" : step.toString());
				exec.stop();
				exec = null;
				step = null;
				return true;
			}
		}
		ObtainSteps.Result r = exec.tick(mc, p, now);
		return end(r, p);
	}

	/** The whole order by Soprano; three ends of its task without the item and the order is given up. */
	private static boolean whole(Minecraft mc, LocalPlayer p, long now) {
		// the night without a bed: Soprano's own night is a bed (its evening's wool and bed, SleepThroughNightsTask), and
		// where it has none by sunset, or its bed would not take it (put down where it had no room, then left behind), it
		// worked the night on the surface in its clothes: three creepers, zombies and skeletons killed it with three pieces
		// of iron on. Then the buddy's own hole until the morning (ObtainSafe.Shelter)
		if (exec instanceof ObtainSafe.Shelter sh) {
			ObtainSteps.Result sr = sh.tick(mc, p, System.currentTimeMillis());
			if (sr == ObtainSteps.Result.RUN || sr == ObtainSteps.Result.WAIT) return sr == ObtainSteps.Result.RUN;
			// a hole that ends the moment it starts is not tried again for a game minute: it started and ended forty times a
			// second, a hole for the night that said "morning" at once; what differed is told
			if (now - holeStarted < 2000) {
				holeOffUntil = now + 60_000;
				Exchange.emit("obtain", "action", "hole ended at once", "needs", ObtainSafe.needsShelter(p), "deep", ObtainSafe.deepUnder(p),
					"night", ObtainSafe.night(p.level()), "armour", p.getArmorValue(), "bed", ObtainSafe.hasBed(p),
					"time", Math.floorMod(p.level().getOverworldClockTime(), 24000L));
			}
			exec = null;
			step = null;
			return true;
		}
		if (now >= holeOffUntil && holeNight(p) && !inMenuNow(p)) {
			holeStarted = now;
			if (exec != null) exec.stop();
			step = new ObtainPlan.Step(ObtainPlan.Act.SPECIAL, "@shelter", 1, null, 0, List.of(), "shelter");
			stepSince = System.currentTimeMillis();   // the panel reads it on the wall clock
			exec = new ObtainSafe.Shelter();
			Exchange.emit("obtain", "action", "step", "step", "shelter for the night, Soprano has no bed", "way", "shelter", "armour", p.getArmorValue(), "hp", p.getHealth());
			return true;
		}
		// badly hurt with next to nothing to eat and the night out there: a hole till the morning before any walk for food,
		// bed or no bed. poisoned by a witch to under one heart with two points of food in the bag, it went up from y -11 at
		// dusk for food and skeletons shot it on the way. on normal the hunger stops at half a heart, the night does not
		if (now >= holeOffUntil && p.getHealth() < 8 && Alto.foodScore(false) < 4 && ObtainSafe.night(p.level())
				&& p.level().dimension().equals(net.minecraft.world.level.Level.OVERWORLD) && !inMenuNow(p)) {
			holeStarted = now;
			if (exec != null) exec.stop();
			step = new ObtainPlan.Step(ObtainPlan.Act.SPECIAL, "@shelter", 1, null, 0, List.of(), "shelter");
			stepSince = System.currentTimeMillis();   // the panel reads it on the wall clock
			ObtainSafe.Shelter sh = new ObtainSafe.Shelter();
			sh.tillMorning = true;
			exec = sh;
			Exchange.emit("obtain", "action", "step", "step", "a hole till the morning: hurt, nothing to eat, the night out there", "way", "shelter", "hp", p.getHealth());
			return true;
		}
		// a boat first where much water lies ahead (Alto.wantsBoat), or food (Alto.wantsFood), then the order again
		if (exec instanceof Alto.Run b && (b.boat() || b.food() || b.kit() || b.home())) {
			// a piece of the kit gives way to food when it is hurt or hungry with nothing to eat: at 9 hp it went on for the
			// leggings with an empty bag, never healed (no food, so the bar stayed under 18) and spiders killed it at night; the
			// piece is asked again after the meal, not counted as failed
			// (not waiting for a quiet spot: in a cave there is none, and it sat at 3 hp with monsters about for fifteen game
			// minutes until a skeleton finished it; Soprano's own mob defense keeps it while it looks for food)
			if (b.kit() && hurtAndNoFood(p) && now - foodTriedAt > 180_000) {
				exec.stop();
				exec = null;
				step = null;
				return true;
			}
			ObtainSteps.Result br = exec.tick(mc, p, now);
			if (br == ObtainSteps.Result.RUN || br == ObtainSteps.Result.WAIT) return br == ObtainSteps.Result.RUN;
			// a piece of the kit that came is followed by the next at once; one that did not come is not asked again for ten
			// game minutes, and the other pieces go on meanwhile: a wait after the shield had come sent it down to the diamonds
			// in its clothes for ten minutes, where a creeper and skeletons killed it
			if (b.food()) foodTriedAt = now;
			else if (b.home()) { homeTriedAt = now; foodAfterHome = p.level().dimension().equals(net.minecraft.world.level.Level.OVERWORLD); }
			else if (b.kit()) { Alto.Run next = Alto.kit(p, now); if (br == ObtainSteps.Result.FAIL || next != null && next.detail().equals(b.detail())) Alto.kitFailed(b, now); }
			else boatTriedAt = now;
			exec.stop();
			exec = null;
			step = null;
			return true;
		}
		// between two steps too, not only every five seconds of the whole order: the whole order started in the second
		// between two pieces of the kit went straight for diamonds it saw at y -8, by a cave spiders' spawner, without its
		// armour, and it died running from them through lava
		boolean between = exec == null;
		if (between || exec instanceof Alto.Run && now >= boatLook) {
			boatLook = now + 5000;
			// in the nether with little to eat, or without gold: home first (Alto.wantsHome), whatever is about, a blaze fight is
			// what eats it. one that did not get home is not asked again for three minutes, and the food search there is what is left
			if (now - homeTriedAt > 180_000 && Alto.wantsHome(p)) {
				if (exec != null) exec.stop();
				step = new ObtainPlan.Step(ObtainPlan.Act.SPECIAL, "@soprano:home", 1, null, 0, List.of(), "home for food by Soprano");
				stepSince = System.currentTimeMillis();   // the panel reads it on the wall clock
				exec = Alto.homeForFood();
				String why = Alto.goldOn(p) ? "home for food, " + Alto.foodScore(false) + " points in the bag" : "home for gold, nothing golden worn";
				Exchange.emit("obtain", "action", "step", "step", why, "way", "soprano:home");
				return true;
			}
			// and home, the food run at once, before the order takes it straight back through the portal: the sky rule may not
			// hold by a portal under the ground, and the monsters there are Soprano's mob defense's (see the kit's food above)
			if (foodAfterHome && p.level().dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) {
				foodAfterHome = false;
				if (exec != null) exec.stop();
				step = new ObtainPlan.Step(ObtainPlan.Act.SPECIAL, "@soprano:food", 1, null, 0, List.of(), "food by Soprano");
				stepSince = System.currentTimeMillis();   // the panel reads it on the wall clock
				exec = Alto.food(Alto.kitDanger(p).ordinal() >= ObtainKit.Danger.NETHER.ordinal() ? 96 : 60);
				Exchange.emit("obtain", "action", "step", "step", "food by Soprano after coming home, " + Alto.foodScore(true) + " points in the bag", "way", "soprano:food");
				return true;
			}
			// food before the kit: with the kit first it never got round to food while pieces were left
			if (now - foodTriedAt > 180_000 && Alto.wantsFood(p) && (ObtainSafe.foes(p).isEmpty() || hurtAndNoFood(p))) {
				if (exec != null) exec.stop();
				step = new ObtainPlan.Step(ObtainPlan.Act.SPECIAL, "@soprano:food", 1, null, 0, List.of(), "food by Soprano");
				stepSince = System.currentTimeMillis();   // the panel reads it on the wall clock
				// (to 96 for an order into the nether, while still in the overworld: in a fortress the blazes' fire keeps it
				// healing, and healing at a full bar eats; 52 points went in forty real seconds there, and the food search that
				// followed in the nether ended in lava)
				exec = Alto.food(Alto.kitDanger(p).ordinal() >= ObtainKit.Danger.NETHER.ordinal() ? 96 : 60);
				Exchange.emit("obtain", "action", "step", "step", "food by Soprano, " + Alto.foodScore(true) + " points in the bag", "way", "soprano:food");
				return true;
			}
			// the kit for the deep first, for an order whose way goes down there (Alto.kit); a piece that did not come is not
			// asked again for ten minutes, so a piece it cannot get holds nothing up for good
			if (deepOrder(p) && (between || ObtainSafe.foes(p).isEmpty())) {
				Alto.Run k = Alto.kit(p, now);
				// with the whole order under way only armour and the shield break into it: a stock (blocks, torches, a table) waits
				// for the order's own end. its portal's mold took the cobblestone under the kit's 48, the kit stopped the portal
				// for cobblestone every five seconds, and round it went
				if (k != null && exec instanceof Alto.Run run && run.detail().startsWith("AltoClef: get ")
						&& !k.detail().startsWith("AltoClef: equip ") && !k.detail().contains("spare shield")) k = null;
				if (k != null) {
					if (exec != null) exec.stop();
					step = new ObtainPlan.Step(ObtainPlan.Act.SPECIAL, "@soprano:kit", 1, null, 0, List.of(), "kit by Soprano");
					stepSince = System.currentTimeMillis();   // the panel reads it on the wall clock
					exec = k;
					Exchange.emit("obtain", "action", "step", "step", "the kit for the deep by Soprano: " + k.detail(), "way", "soprano:kit");
					return true;
				}
			}
			if (now - boatTriedAt > 600_000 && Alto.wantsBoat(p)) {
				if (exec != null) exec.stop();
				step = new ObtainPlan.Step(ObtainPlan.Act.SPECIAL, "@soprano:boat", 1, null, 0, List.of(), "a boat by Soprano");
				stepSince = System.currentTimeMillis();   // the panel reads it on the wall clock
				exec = Alto.boat();
				Exchange.emit("obtain", "action", "step", "step", "a boat by Soprano, for the water ahead", "way", "soprano:boat");
				return true;
			}
		}
		if (!(exec instanceof Alto.Run)) {
			if (exec != null) exec.stop();
			step = new ObtainPlan.Step(ObtainPlan.Act.SPECIAL, "@soprano:" + item, count, null, 0, List.of(), "by Soprano");
			stepSince = System.currentTimeMillis();   // the panel reads it on the wall clock
			steps++;
			exec = Alto.whole(item, count);
			Exchange.emit("obtain", "action", "step", "step", "the whole order by Soprano", "way", "soprano:get", "item", item, "count", count);
		}
		ObtainSteps.Result r = exec.tick(mc, p, now);
		if (r == ObtainSteps.Result.RUN || r == ObtainSteps.Result.WAIT) return r == ObtainSteps.Result.RUN;
		String why = exec.why();
		exec.stop();
		exec = null;
		step = null;
		if (ObtainSteps.count(p, item) >= count) { done(p); return false; }
		if (++sopranoEnds >= 3) {
			Exchange.emit("obtain", "action", "gives up", "item", item, "why", "Soprano's task ended three times without it" + (why == null ? "" : ": " + why));
			stop("Soprano did not get " + item + (why == null ? "" : ": " + why));
			last.put("end", "gave up");
			return false;
		}
		Exchange.emit("obtain", "action", "soprano again", "item", item, "why", why == null ? "its task ended" : why, "try", sopranoEnds);
		return true;
	}

	/**
	 * The night on the surface without a bed and without iron armour, from before sunset; a thunderstorm is night as well.
	 */
	private static long holeStarted, holeOffUntil;

	private static boolean holeNight(LocalPlayer p) {
		// (a bed Soprano has just put down counts too: it puts its bed down to sleep, the bag held none that moment, and the
		// hole shut it in for the night with the bed beside it, over a quarter of an order in holes)
		if (!ObtainSafe.needsShelter(p) || ObtainSafe.hasBed(p) || ObtainSafe.bedNear(p, 6)) return false;
		long t = Math.floorMod(p.level().getOverworldClockTime(), 24000L);
		// (from 11500, before sunset, as the buddy's own hole always did: from 12500, to give Soprano's evening its time, a
		// food step ran into the dusk and it dug in among the night's first spiders and skeletons)
		// (11520: the client's clock steps back a tick or two when the server's time comes in, and a hole begun at 11500 read
		// 11498 the next tick, was no night any more and ended at once; the hole itself keeps the night from 11500,
		// ObtainSafe.night)
		return p.level().isThundering() || t >= 11520 && t < 23300;
	}

	/**
	 * Hurt or hungry, and not enough in the bag to eat: the bar cannot get back to 18, so it does not heal. The line is
	 * the food step's own (under 12 points): with under 4 it worked on for the helmet at hunger 8 with a rotten flesh in
	 * the bag, never healed from 15 and a zombie and a skeleton finished it.
	 */
	private static boolean hurtAndNoFood(LocalPlayer p) {
		return Alto.foodScore() < 12 && (p.getHealth() < 14 || p.getFoodData().getFoodLevel() < 14);
	}

	private static int sopranoEnds;
	private static long boatLook, boatTriedAt = -600_000, foodTriedAt = -180_000, homeTriedAt = -180_000;
	// a home run for food that got home: the food run comes next, whatever wantsFood says by the portal
	private static boolean foodAfterHome;
	private static Boolean deep;

	/** The order's way goes into the deep (its plan, by the buddy's own planner, has a step of that danger); asked once an order. */
	private static boolean deepOrder(LocalPlayer p) {
		if (deep == null) {
			GameHave gh = new GameHave(p);
			ObtainPlan.Plan pl = ObtainPlan.plan(item, count, gh);
			// and how far it goes, for Soprano's kit: an order of blaze rods went into the nether with the deep's kit, no golden
			// helmet and little food, looked for food in a crimson forest and the piglins killed it
			ObtainKit.Danger most = ObtainKit.Danger.SURFACE;
			if (pl != null && pl.steps() != null)
				for (ObtainPlan.Step s : pl.steps()) { ObtainKit.Danger sd = ObtainKit.of(s, gh.dim()); if (sd.ordinal() > most.ordinal()) most = sd; }
			boolean d = most.ordinal() >= ObtainKit.Danger.DEEP.ordinal();
			deep = d;
			Alto.orderDanger = d ? most : ObtainKit.Danger.DEEP;
			Exchange.emit("obtain", "action", "order danger", "item", item, "deep", d, "danger", most.name());
		}
		return deep;
	}

	private static long armourLook;
	private static String armourFor;

	/** A step's outcome: on with the next, or the way blocked for a while. */
	private static boolean end(ObtainSteps.Result r, LocalPlayer p) {
		if (r == ObtainSteps.Result.RUN) return true;
		if (r == ObtainSteps.Result.WAIT) return false;
		ObtainPlan.Step s = step;
		if (exec != null) { BlockPos at = exec.placedAt(); if (at != null) placed.put(at, exec.placedBlock()); }
		String why = exec == null ? null : exec.why();
		ObtainSteps.Exec was = exec;
		if (exec != null) exec.stop();
		exec = null;
		// the furnace is empty once a smelt or an emptying is done (this ran after exec was nulled, so it never held, and it
		// emptied an empty furnace once a second for twenty minutes); one it cannot get at any more is left
		if (r == ObtainSteps.Result.DONE && (was instanceof ObtainSteps.Smelt || was instanceof ObtainSteps.Empty)) furnaceFull = null;
		if (r == ObtainSteps.Result.FAIL && was instanceof ObtainSteps.DigOut) upFailedAt = System.currentTimeMillis();
		if (r == ObtainSteps.Result.FAIL && was instanceof ObtainSafe.Dry) dryFailedAt = System.currentTimeMillis() + 60_000;
		if (r == ObtainSteps.Result.FAIL && was instanceof ObtainSteps.Empty && furnaceFull != null) {
			Exchange.emit("obtain", "action", "furnace left", "x", furnaceFull.getX(), "y", furnaceFull.getY(), "z", furnaceFull.getZ(), "why", why);
			furnaceFull = null;
		}
		if (r == ObtainSteps.Result.DONE) {
			Exchange.emit("obtain", "action", "step done", "step", s.toString(), "s", (System.currentTimeMillis() - stepSince) / 1000);
			fails.remove(s.way());
		} else fail(s, why);
		return false;
	}

	private static void fail(ObtainPlan.Step s, String why) {
		int n = fails.merge(s.way(), 1, Integer::sum);
		// once again at once (a mob gone, a block not reached); twice: that way left for five minutes, another taken
		long rest = n >= 2 ? 300_000 : 0;
		if (rest > 0) blocked.put(s.way(), System.currentTimeMillis() + rest);
		Exchange.emit("obtain", "action", "failed", "step", s.toString(), "way", s.way(), "why", why, "times", n, "rest_s", rest / 1000);
		exec = null;
	}

	private static void done(LocalPlayer p) {
		ended("done", null);
		Exchange.emit("obtain", "action", "done", "item", item, "count", count, "have", ObtainSteps.count(p, item), "steps", steps,
			"s", (System.currentTimeMillis() - since) / 1000);
		String it = item, who = by;
		item = null;
		step = null;
		exec = null;
		save();
		HeldKeys.releaseAll();
		deliver(p, it, who);
	}

	/**
	 * To whoever ordered: the stacks of the item handed over the way a dump does (Dump: thrown at their feet, watched,
	 * picked up again and thrown once more, three tries), with the buddy following them for it. Nobody named, or the buddy
	 * follows someone else: it keeps them and watches where it is.
	 */
	private static void deliver(LocalPlayer p, String it, String who) {
		if (who == null || who.isBlank()) { watching = true; return; }
		java.util.Set<Integer> slots = new java.util.HashSet<>();
		var in = p.getInventory();
		for (int i = 0; i < 36; i++) if (!in.getItem(i).isEmpty() && AfkHelperClient.itemId(in.getItem(i)).equals(it)) slots.add(i);
		if (slots.isEmpty()) { watching = true; return; }
		// not on the server: kept, and it watches where it is (a follow of nobody walked off to look)
		var conn = AfkHelperClient.mc().getConnection();
		if (conn == null || conn.getPlayerInfo(who) == null) {
			watching = true;
			Exchange.emit("obtain", "action", "keeps", "item", it, "for", who, "why", "not online");
			return;
		}
		Dump.giveBack(slots, who);
		if (!who.equalsIgnoreCase(Follow.target())) {
			JsonObject f = new JsonObject();
			f.addProperty("cmd", "follow");
			f.addProperty("player", who);
			Follow.command(f);
		}
		watching = Follow.target() == null;
		Exchange.emit("obtain", "action", "deliver", "item", it, "to", who, "stacks", slots.size());
	}

	// ---------- what the planner may know, from the game ----------

	/** The world as the buddy sees it, for {@link ObtainPlan}. */
	static final class GameHave implements ObtainPlan.Have {
		final LocalPlayer p;
		final Level level;
		final Map<String, Integer> inv = new HashMap<>();

		GameHave(LocalPlayer p) {
			this.p = p;
			this.level = p.level();
			var in = p.getInventory();
			for (int i = 0; i < 36; i++) add(in.getItem(i));
			add(p.getOffhandItem());
		}

		private void add(ItemStack s) {
			// a tool or armour piece near its end counts as gone already, so a new one is made while the old still works (its
			// last two stone pickaxes wore through on the way down and left it shut in with three uses)
			if (s.isEmpty() || Work.lowWear(s)) return;
			inv.merge(AfkHelperClient.itemId(s), s.getCount(), Integer::sum);
		}

		public int inv(String item) { return inv.getOrDefault(item, 0); }

		public int worn(String item) {
			int n = 0;
			for (net.minecraft.world.entity.EquipmentSlot sl : new net.minecraft.world.entity.EquipmentSlot[] { net.minecraft.world.entity.EquipmentSlot.HEAD,
					net.minecraft.world.entity.EquipmentSlot.CHEST, net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET,
					// the off hand too: the shield held there was not counted, and it made a second one of its iron
					net.minecraft.world.entity.EquipmentSlot.OFFHAND }) {
				ItemStack s = p.getItemBySlot(sl);
				if (!s.isEmpty() && !Work.lowWear(s) && AfkHelperClient.itemId(s).equals(item)) n++;
			}
			return n;
		}

		/** What the base's known chests hold of it in this dimension: an order may take everything. */
		public int base(String item) {
			int n = 0;
			for (BlockPos c : Stash.chestsWith(item, level.dimension().identifier().toString(), p.blockPosition())) n++;
			return n == 0 ? 0 : Stash.inChests(item);
		}

		public boolean station(String block) {
			for (BlockPos q : BlockScan.places(block))
				if (q.distSqr(p.blockPosition()) <= 12 * 12 && !badStation(q) && block.equals(ObtainSteps.id(level.getBlockState(q)))) return true;
			return false;
		}

		public Nature.Dim dim() {
			String d = level.dimension().identifier().getPath();
			return d.equals("the_nether") ? Nature.Dim.NETHER : d.equals("the_end") ? Nature.Dim.END : Nature.Dim.OVERWORLD;
		}

		/**
		 * A place seen far away is no better than one not seen: after a teleport or a death all it knew was 18,000 blocks
		 * off, stone cost more than any way round it, and a stone pickaxe gave up as "too deep".
		 */
		public double near(String block) {
			double d = BlockScan.near(block, p.blockPosition());
			return d > 256 ? -1 : d;
		}

		/** A nether portal in view: the way to the nether from the overworld, or back from the nether. */
		public boolean portal(Nature.Dim to) {
			Nature.Dim here = dim();
			if (here == to || here == Nature.Dim.END) return false;
			for (BlockPos q : BlockScan.places("nether_portal"))
				if (q.distSqr(p.blockPosition()) <= 96 * 96 && "nether_portal".equals(ObtainSteps.id(level.getBlockState(q)))) return true;
			return false;
		}

		/** The stronghold's portal room in view, or found before on this server. */
		public boolean known(String place) {
			if (!place.equals("@stronghold") || dim() != Nature.Dim.OVERWORLD) return false;
			if (portalRoom != null) return true;
			for (BlockPos q : BlockScan.places("end_portal_frame")) { portalRoom = q.immutable(); return true; }
			return false;
		}

		public double nearMob(String mob) {
			double best = -1;
			for (Entity e : level.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(48, 24, 48))) {
				if (!e.isAlive() || ((LivingEntity) e).isBaby()) continue;
				if (!BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath().equals(mob)) continue;
				double d = e.distanceTo(p);
				if (best < 0 || d < best) best = d;
			}
			return best;
		}

		/**
		 * Planning for the kit and the other things fetched beside the order: the ways too deep for the kit are left out
		 * then, and only then (left out of the order's own plan too, they made it give up for want of a water bucket's iron).
		 */
		boolean kit;

		public boolean blocked(String way) {
			long now = System.currentTimeMillis();
			Long u = blocked.get(way), t = kit ? tooDeep.get(way) : null;
			return u != null && u > now || t != null && t > now;
		}

		public boolean silk() {
			var in = p.getInventory();
			for (int i = 0; i < 36; i++) {
				ItemStack s = in.getItem(i);
				if (!s.isEmpty() && s.getEnchantments().keySet().stream().anyMatch(h -> h.is(Enchantments.SILK_TOUCH))) return true;
			}
			return false;
		}
	}

	/** For the panel and the bridge: the order, the step, how long. */
	static Map<String, Object> state() {
		Map<String, Object> m = new java.util.LinkedHashMap<>();
		if (!on()) return m;
		m.put("item", item);
		m.put("count", count);
		m.put("by", by);
		m.put("step", step == null ? null : step.toString());
		m.put("since", since);
		return m;
	}

	static List<String> blockedWays() { return new ArrayList<>(blocked.keySet()); }

	private static final ObtainSafe.Flee flee = new ObtainSafe.Flee();

	/** A furnace of this order with something of it inside, put in by a smelt that was broken off. */
	private static BlockPos furnaceFull;

	static void furnaceHolds(BlockPos at) { furnaceFull = at.immutable(); }

	/** Stations it could not get at, for a while: the planner counts them as not there. */
	private static final Map<BlockPos, Long> badStations = new HashMap<>();

	static void stationOutOfReach(BlockPos at) {
		badStations.put(at.immutable(), System.currentTimeMillis() + 600_000);
		Exchange.emit("obtain", "action", "station out of reach", "x", at.getX(), "y", at.getY(), "z", at.getZ());
	}

	static boolean badStation(BlockPos at) { Long u = badStations.get(at); return u != null && u > System.currentTimeMillis(); }

	private static long retryAt, nextThrow, lastMovedAt, upFailedAt, dryFailedAt, scanWaitFrom, kitRetryAt;
	private static int kitUnmet;

	/**
	 * A step only the surface has (trees, the animals of the fields), none of it near, and the buddy deep under the ground
	 * in the overworld: more than six blocks of it overhead and no sky.
	 */
	static boolean wantsSurface(ObtainPlan.Step s, LocalPlayer p) {
		Level level = p.level();
		if (!level.dimension().equals(Level.OVERWORLD) || s.sources().isEmpty()) return false;
		BlockPos f = p.blockPosition();
		if (level.canSeeSky(f.above())) return false;
		int sky = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, f.getX(), f.getZ());
		if (sky - f.getY() <= 6) return false;
		if (s.act() == ObtainPlan.Act.MINE) {
			for (String src : s.sources()) {
				Nature.Place pl = Nature.block(src);
				if (pl == null || pl.where() != Nature.Where.SURFACE) return false;
				for (BlockPos q : BlockScan.places(src)) if (q.distSqr(f) < 24 * 24) return false;
			}
			return true;
		}
		if (s.act() == ObtainPlan.Act.KILL) {
			for (String src : s.sources()) {
				Nature.Place pl = Nature.mob(src);
				if (pl == null || pl.where() != Nature.Where.SURFACE) return false;
				for (var e : level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, p.getBoundingBox().inflate(24)))
					if (e.isAlive() && ObtainSafe.type(e).equals(src)) return false;
			}
			return true;
		}
		return false;
	}
	private static BlockPos lastPos, lastSky;
	private static net.minecraft.resources.ResourceKey<Level> lastSkyDim;

	/** Where it last stood under the open sky in the world it is in, or null. */
	static BlockPos lastSky() {
		LocalPlayer p = AfkHelperClient.mc().player;
		return p != null && p.level().dimension().equals(lastSkyDim) ? lastSky : null;
	}
	private static net.minecraft.world.phys.Vec3 wetPos;
	private static long wetMovedAt;

	/** The stronghold's portal room, once a frame of it was seen (kept for the order; a new connection forgets it). */
	static BlockPos portalRoom;

	/**
	 * Before the fight of Follow's chain: monsters it cannot win against are run from (ObtainSafe.outmatched), and the
	 * step under way waits. True while it runs.
	 */
	static boolean flee(Minecraft mc, LocalPlayer p) {
		// no order (resting till the morning after one given up): the rest's own shelter, nothing of an order's
		if (item == null) return false;
		var foes = ObtainSafe.foes(p);
		long now = System.currentTimeMillis();
		// shooters only: into the ground where it stands, before any run or fight (the flight and the fight took the keys
		// back every second, and two skeletons killed it in three arrows each). The shelter runs here, in the flight's place
		// in the chain, until the shooters are gone
		// walled in, the walls hold off the melee ones too; and under the ground monsters on their way are walled out first
		// hit by one at arm's length while it holds: whatever the walls look like, they hold nothing; the fight, not the wait
		// (a zombie hit it four times in a box it took for shut, and it stood and ate)
		if (p.hurtTime > 0 && p.getLastHurtByMob() instanceof net.minecraft.world.entity.monster.Monster && ObtainSafe.meleeNear(p)) beatenAt = now;
		boolean beaten = now - beatenAt < 4000 && ObtainSafe.meleeNear(p);
		if (beaten && exec instanceof ObtainSafe.Shelter bs && bs.duck) {
			if (bs.sealed) Exchange.emit("obtain", "action", "shelter", "what", "hit inside: fights", "hp", p.getHealth());
			bs.sealed = false;
			return false;
		}
		boolean holding = exec instanceof ObtainSafe.Shelter sh && sh.duck && (sh.sealed || !ObtainSafe.meleeNear(p));
		// a skeleton at arm's length while the walls are not shut: the fight (one stood in the cell a wall went into, and the
		// buddy kept trying that wall while it shot five times)
		boolean shut = exec instanceof ObtainSafe.Shelter sh0 && sh0.sealed;
		if (!shut && ObtainSafe.shooterClose(p)) return false;
		boolean seal = !holding && !ObtainSafe.duck(p, foes) && ObtainSafe.seal(p, foes);
		if (holding || seal || ObtainSafe.duck(p, foes)) {
			if (!(exec instanceof ObtainSafe.Shelter sh2 && sh2.duck)) {
				if (exec != null) exec.stop();
				ObtainSafe.Shelter s = new ObtainSafe.Shelter(true);
				s.duck = true;
				s.walls = seal;
				exec = s;
				step = new ObtainPlan.Step(ObtainPlan.Act.SPECIAL, "@shelter", 1, null, 0, List.of(), "shelter");
				stepSince = now;
				Exchange.emit("obtain", "action", "step", "step", seal ? "walled in from monsters" : "dig in from shooters", "way", "shelter", "foes", foes.size(), "hp", p.getHealth());
			}
			ObtainSteps.Result r = exec.tick(mc, p, now);
			if (r == ObtainSteps.Result.DONE || r == ObtainSteps.Result.FAIL) end(r, p);
			lastMovedAt = now;
			return true;
		}
		// under the ground a run is down a tunnel it is caught in: the fight, or the walls above
		boolean run = now >= standUntil && ObtainSafe.outmatched(p, foes) && ObtainSafe.roomToRun(p);
		// a run that does not shake it off: hit again by one at arm's length a second and a half into the flight, it stands
		// and fights (a burning zombie hit it five times from behind down a slope, 20 to 1)
		if (run) {
			if (fleeFrom == 0) { fleeFrom = now; fleeHp = p.getHealth(); }
			else if (now - fleeFrom > 1500 && p.getHealth() < fleeHp - 1 && ObtainSafe.meleeNear(p)) {
				Exchange.emit("obtain", "action", "stands and fights", "hp", p.getHealth(), "foes", foes.size());
				standUntil = now + 10_000;
				fleeFrom = 0;
				run = false;
			}
		} else fleeFrom = 0;
		// standing to fight: no running on (the flight ran two seconds more after it stood, and took two hits)
		if (now < standUntil) flee.reset();
		// a flight with no way away is no flight: the fight takes over (it returned true all the same and stood there)
		return flee.tick(mc, p, run ? foes : List.of(), now);
	}

	private static long fleeFrom, standUntil, beatenAt;
	private static float fleeHp;

	/** The screen of a step shut from outside (a fight): the step starts that part again. */
	static void menuClosed() { if (exec != null) exec.menuClosed(); }
}
