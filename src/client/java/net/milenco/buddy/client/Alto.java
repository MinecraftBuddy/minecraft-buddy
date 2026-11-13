package net.milenco.buddy.client;

/**
 * AltoClef (MIT, in Soprano's fork, github.com/AverWasTaken/soprano) keeps the buddy alive while it gets an order: its
 * chains that fight (mob defense: the force field, the shield up against arrows and creepers, running from a crowd),
 * eat, get out of lava, fire and water, and catch a fall with a bucket. The buddy's own planner, kit, mines, nights and
 * walls stay; its own fight, flight, creeper shield and meals stand down while this is on. What holds up here is meant
 * to be taken into the buddy itself later.
 *
 * <p>AltoClef runs its chains only while its task runner is up, which {@code altoRunsWhenIdle} keeps up without a task
 * of its own; it is switched on for an order and off again after it. When one of its chains holds the wheel (its pick
 * of the chains asks above nothing), the buddy lets go of every key and of Baritone and waits; a chain that only acts
 * without the wheel (the force field) lets the order go on. Only this class names AltoClef's classes, and only after
 * {@link #present()}. And it hands their objects on only as their own type or as Object, never as a supertype: the
 * verifier loads both types to check that, when the class is linked, and without the jar the mod then failed on its
 * first tick (AutopilotLinkTest).
 */
final class Alto {
	private Alto() {}

	private static Boolean present;
	private static boolean up;
	private static String holder, told, toldWait;
	private static long toldSince;
	/** A task of AltoClef's that the buddy started for a step of its order (food) runs. */
	private static boolean delegated;
	private static boolean savedBoats, savedFreeLook = true, savedBucketFall = true, savedAvoid, savedBlast = true, savedInv, savedInvStill;
	private static java.util.List<net.minecraft.world.item.Item> savedImportant;

	/** What an order keeps whatever the bag's room: the brew, the portal, the nether's gear, what it is all for. */
	private static final net.minecraft.world.item.Item[] KEEP = {
		net.minecraft.world.item.Items.NETHER_WART, net.minecraft.world.item.Items.MAGMA_CREAM, net.minecraft.world.item.Items.BLAZE_POWDER,
		net.minecraft.world.item.Items.REDSTONE, net.minecraft.world.item.Items.POTION, net.minecraft.world.item.Items.OBSIDIAN,
		net.minecraft.world.item.Items.FLINT_AND_STEEL, net.minecraft.world.item.Items.GOLDEN_HELMET, net.minecraft.world.item.Items.SHIELD,
		net.minecraft.world.item.Items.WATER_BUCKET, net.minecraft.world.item.Items.BLAZE_ROD, net.minecraft.world.item.Items.ENDER_PEARL,
		net.minecraft.world.item.Items.GOLDEN_APPLE };

	private static java.util.List<net.minecraft.world.item.Item> withKeep(java.util.List<net.minecraft.world.item.Item> base) {
		var out = new java.util.ArrayList<>(base);
		for (var i : KEEP) if (!out.contains(i)) out.add(i);
		return java.util.List.copyOf(out);
	}

	/** Soprano with AltoClef is loaded. */
	static boolean present() {
		if (present == null) {
			boolean p = Bari.present();
			if (p) try { Class.forName("adris.altoclef.AltoClef"); } catch (Throwable t) { p = false; }
			present = p;
		}
		return present;
	}

	/**
	 * AltoClef's one instance once it is up (its task runner and user chain made), else null. Soprano makes it on its
	 * first tick with a world, which can come after the first tick of an order: a client that starts with an order under
	 * way has one before AltoClef is there. And it is gone for the session when its start or its ticks keep failing.
	 */
	private static adris.altoclef.AltoClef instance() {
		var mod = adris.altoclef.AltoClef.getInstance();
		return mod == null || mod.getTaskRunner() == null || mod.getUserTaskChain() == null ? null : mod;
	}

	/** AltoClef keeps the buddy alive for the order under way, while the gate (Jobs.autopilot) is open. */
	static boolean on() {
		boolean on = present() && AfkConfig.get().obtainAltoSurvival && Obtain.on() && gateOpen && !gone();
		ObtainKit.sopranoBrews = on;
		return on;
	}

	/** AltoClef's instance was there this session; and whether it is gone for the session. */
	private static boolean seenUp, gone;

	/**
	 * AltoClef off for the session: its instance was up and is not any more (its bridge gives up after repeated errors and
	 * discards it), or it never came up for a task (Run). Then the buddy's own defences and planner take over again: with
	 * on() still true they stood down for good, and every Run waited for an AltoClef that never came.
	 */
	private static boolean gone() {
		if (gone) return true;
		if (instance() != null) { seenUp = true; return false; }
		if (seenUp && adris.altoclef.AltoClef.getInstance() == null) giveUp("AltoClef was switched off for the session");
		return gone;
	}

	private static void giveUp(String why) {
		if (gone) return;
		gone = true;
		Exchange.emit("alto", "action", "gone", "why", why);
	}

	/** The gate of Jobs.autopilot: the mod on, a server it may play on, the panel's hands off. */
	private static boolean gateOpen = true;
	/** How often the gate shut: a task of AltoClef's begun before the last shut was cancelled with it (Run begins it again). */
	private static int shuts;

	/**
	 * Every tick before anything returns early (Jobs.autopilot). Shut: AltoClef's runner goes down at once, its own chains
	 * and the order's task with it, and Baritone's path with them. Open: the next tick brings it up again for the order.
	 */
	static void gate(boolean open) {
		if (open == gateOpen) return;
		gateOpen = open;
		if (open) return;
		shuts++;
		if (!present() || !up) return;
		tick();
		if (net.minecraft.client.Minecraft.getInstance().player != null)
			baritone.api.BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().cancelEverything();
	}

	/** Once a tick, before the chain: AltoClef's task runner up for an order and down after it. */
	static void tick() {
		if (!present()) return;
		if (up) watchDoing();
		scaleTimeouts();
		boolean want = on();
		if (want != up) {
			up = want;
			var s = baritone.api.BaritoneAPI.getSettings();
			// no task of AltoClef's own, ever: it is the buddy that wants the order
			s.altoIdleCommand.value = "";
			s.altoMobDefense.value = true;
			s.altoRunsWhenIdle.value = want;
			// across water in a boat when one is in the bag: Soprano's Baritone places it from the shore, rows the path's water
			// and breaks and picks it up on the far side, so a long way over water is no swim
			if (want) { savedBoats = s.allowBoats.value; s.allowBoats.value = true; } else s.allowBoats.value = savedBoats;
			// looking where it walks: with freeLook (Baritone's default) only the server got the aim and the camera stayed, so
			// it walked sideways and backwards, which looks wrong to anyone watching. A mode Soprano allows for (its AscendHop
			// knows the view lags a tick then); while a task of Soprano's runs nothing of the buddy's turns the head
			if (want) { savedFreeLook = s.freeLook.value; s.freeLook.value = false; } else s.freeLook.value = savedFreeLook;
			// no drop planned on a water bucket: Soprano's Baritone plans one while a miss leaves half a heart, and a fall of 24
			// left it at 1 hp among skeletons, dead. AltoClef's bucket for a fall it did not plan stays
			if (want) { savedBucketFall = s.allowWaterBucketFall.value; s.allowWaterBucketFall.value = false; } else s.allowWaterBucketFall.value = savedBucketFall;
			// round monsters where it can: Baritone's mob avoidance makes a path near one dearer (off by default; on in the
			// buddy's own Baritone walk already, not in Soprano's), so a way down or along goes past a cave's crowd and not
			// through it (most deaths came in caves)
			if (want) { savedAvoid = s.avoidance.value; s.avoidance.value = true; s.mobAvoidanceCoefficient.value = 2.0; } else s.avoidance.value = savedAvoid;
			// no blast furnace of its own: five iron, a furnace and three smooth stone (stone smelted twice) for faster ingots,
			// where iron is the slow part of the kit: making one ate the kit's time, which ran out with the boots' iron in the
			// bag. Soprano's #gamer does the same; one standing in a village is still used
			if (want) { savedBlast = s.altoUseBlastFurnace.value; s.altoUseBlastFurnace.value = false; } else s.altoUseBlastFurnace.value = savedBlast;
			// blocks and tools from the whole bag, not only the hotbar: with allowInventory off (Baritone's default, and Soprano
			// never sets it) a throwaway block counts only on the hotbar. with the hotbar's last deepslate placed and 32
			// cobblestone in the bag, every path in the nether came out empty: thirteen game minutes back and forth on a ledge,
			// the trip home for food among them, until a ghast's ball knocked the buddy into the lava sea. a swap into the
			// hotbar only standing still (a click mid sprint is what anti-cheats look for)
			if (want) {
				savedInv = s.allowInventory.value; savedInvStill = s.inventoryMoveOnlyIfStationary.value;
				s.allowInventory.value = true; s.inventoryMoveOnlyIfStationary.value = true;
			} else { s.allowInventory.value = savedInv; s.inventoryMoveOnlyIfStationary.value = savedInvStill; }
			// the order's own things never go as garbage: with the bag full, AltoClef makes room by throwing whatever no task
			// uses right then, and the brew's nether wart went while crafting pickaxes at home, the redstone while crafting a boat
			if (want) { savedImportant = s.altoImportantItems.value; s.altoImportantItems.value = withKeep(savedImportant); }
			else if (savedImportant != null) s.altoImportantItems.value = savedImportant;
			if (!want) { holder = null; cancel(); }
			Exchange.emit("alto", "action", want ? "up" : "down");
		}
		takeAlong();
	}

	// ---------- taking things along on the way (ObtainWish, Soprano's TakeAlongChain) ----------

	/** Game ticks between two workings out of the wishes with the same bag: five game seconds. */
	private static final long WISH_TICKS = 100;

	/**
	 * What one food point saves, in seconds: what a food run costs a point. Runs that gathered some sixty points took three
	 * to seven game minutes (attempts 104 to 172), five minutes for sixty is five seconds a point. A cow in sight (sixteen
	 * points) is then worth 80 s while a food run is near, against some twelve for the walk and the kill.
	 */
	static final double FOOD_POINT_SECONDS = 5;

	/**
	 * Food as Soprano's take-along weighs it: the bag count under which a food run comes (the sky rule's, three quarters of
	 * NETHER_FOOD for an order into the nether, 24 otherwise) in full, up to what a food run gathers (NETHER_FOOD or 60)
	 * at the lower urgency, half as much again as the reserve.
	 */
	static adris.altoclef.takealong.TakeAlong.Food takeAlongFood(net.minecraft.client.player.LocalPlayer p) {
		boolean nether = kitDanger(p).ordinal() >= ObtainKit.Danger.NETHER.ordinal();
		return new adris.altoclef.takealong.TakeAlong.Food(nether ? NETHER_FOOD * 3 / 4 : 24, nether ? NETHER_FOOD : 60, FOOD_POINT_SECONDS);
	}
	private static boolean wishing;
	private static long wishedAt;
	private static int wishedBag;
	private static long saidStart = -1, saidEnd = -1;

	/**
	 * Once a tick: while Soprano is up for an order and obtainTakeAlong is on, the wishes go to Soprano every five game
	 * seconds and when the bag changes, the buddy's own reasons to leave detours alone with them (Obtain.takeAlongHold),
	 * and each detour is an event when it starts and when it ends. Junk the buddy threw away is never taken along as a
	 * drop (ObtainSafe.thrown), or it would go round, thrown and picked up again.
	 */
	private static void takeAlong() {
		var mc = AfkHelperClient.mc();
		var p = mc == null ? null : mc.player;
		if (!up || p == null || !AfkConfig.get().obtainTakeAlong) {
			if (wishing) { adris.altoclef.takealong.TakeAlong.off(); wishing = false; }
			return;
		}
		long t = p.level().getGameTime();
		int bag = bag(p);
		if (!wishing || bag != wishedBag || t - wishedAt >= WISH_TICKS || t < wishedAt) {
			if (!wishing) {
				// a detour of an earlier order is not this one's to report
				var c = adris.altoclef.takealong.TakeAlong.current();
				var l = adris.altoclef.takealong.TakeAlong.last();
				saidStart = c == null ? -1 : c.id();
				saidEnd = l == null ? -1 : l.id();
			}
			wishing = true;
			adris.altoclef.takealong.TakeAlong.thrown(ObtainSafe::thrown);
			wishedAt = t;
			wishedBag = bag;
			try {
				var w = wishes(p);
				adris.altoclef.takealong.TakeAlong.wishes(w.now(), w.later(), w.values());
				adris.altoclef.takealong.TakeAlong.food(takeAlongFood(p));
			} catch (RuntimeException e) {
				adris.altoclef.takealong.TakeAlong.off();
				Exchange.emit("obtain", "action", "take along", "state", "error", "why", String.valueOf(e));
			}
		}
		adris.altoclef.takealong.TakeAlong.hold(Obtain.takeAlongHold(p));
		var cur = adris.altoclef.takealong.TakeAlong.current();
		if (cur != null && cur.id() != saidStart) {
			saidStart = cur.id();
			Exchange.emit("obtain", "action", "take along", "state", "start", "of", cur.drops() ? "drops" : "blocks", "item", cur.items(),
				"blocks", cur.blocks(), "value", cur.value(), "cost", cur.cost());
		}
		var last = adris.altoclef.takealong.TakeAlong.last();
		if (last != null && last.id() != saidEnd) {
			saidEnd = last.id();
			Exchange.emit("obtain", "action", "take along", "state", last.state(), "why", last.why(), "of", last.drops() ? "drops" : "blocks",
				"item", last.items(), "blocks", last.blocks(), "value", last.value(), "cost", last.cost(), "seconds", last.seconds(), "got", last.got());
		}
	}

	/** The wishes now (ObtainWish): the kit for the order's gate, the order, the buddy's own step, the planner's prices. */
	static ObtainWish.Wishes wishes(net.minecraft.client.player.LocalPlayer p) {
		var gh = new Obtain.GameHave(p);
		java.util.List<ObtainKit.Need> missing = ObtainKit.missingAll(kitDanger(p), gh);
		ObtainPlan.Step s = Obtain.step();
		boolean own = s != null && s.act() != ObtainPlan.Act.SPECIAL;
		return ObtainWish.of(missing, Obtain.item(), Obtain.count(), own ? s.item() : null, own ? s.count() : 0, tableAndPlanks(p, missing),
			copperPieces(p, missing), gh);
	}

	/** The copper pieces kit() makes meanwhile: a slot empty, its iron piece missing and its iron not in the bag, no copper one in the bag. */
	private static java.util.List<String> copperPieces(net.minecraft.client.player.LocalPlayer p, java.util.List<ObtainKit.Need> missing) {
		java.util.List<String> out = new java.util.ArrayList<>();
		java.util.Set<String> names = new java.util.HashSet<>();
		for (ObtainKit.Need n : missing) names.add(n.name());
		for (String slot : new String[] { "chestplate", "leggings", "helmet", "boots" }) {
			if (!names.contains(slot) || !worn(p, slot).isEmpty() || ironFor(p, slot) || ObtainSteps.count(p, "copper_" + slot) > 0) continue;
			out.add("copper_" + slot);
		}
		return out;
	}

	/**
	 * The kit fetches a table and planks before any iron: with a piece of armour still missing, near the surface, and no
	 * table or under eight planks in the bag (as kit() asks for them).
	 */
	/**
	 * Near the surface of the overworld, where the trees are: the pieces made of wood (stone pickaxes, a table and planks)
	 * are asked only there. In the nether there is no wood at all: an order for quartz with an iron pickaxe in hand
	 * wandered 96 seconds for a stick before 14.5 seconds of quartz, the same kit again the next run.
	 */
	static boolean woodLand(net.minecraft.client.player.LocalPlayer p) {
		return p.level().dimension().equals(net.minecraft.world.level.Level.OVERWORLD) && !ObtainSafe.deepUnder(p);
	}

	private static boolean tableAndPlanks(net.minecraft.client.player.LocalPlayer p, java.util.List<ObtainKit.Need> missing) {
		boolean armour = false;
		for (ObtainKit.Need n : missing)
			if (java.util.Set.of("helmet", "chestplate", "leggings", "boots", "shield").contains(n.name())) armour = true;
		return armour && woodLand(p) && (ObtainSteps.count(p, "crafting_table") < 1 || planks(p) < 8);
	}

	/** The bag in one number: a change of any stack changes it. */
	private static int bag(net.minecraft.client.player.LocalPlayer p) {
		int h = 1;
		for (net.minecraft.world.item.ItemStack st : p.getInventory().getNonEquipmentItems())
			h = 31 * h + (st.isEmpty() ? 0 : System.identityHashCode(st.getItem()) * 67 + st.getCount());
		return h;
	}

	private static String lastDoing;
	private static long doingLook;

	/**
	 * What Soprano does now, in its own words: the chain at the wheel when it is not the order's own, the order's outer task
	 * and its deepest, with their states ("Collect 60.0 units of food. › Killing entity.minecraft.cow: Approaching target").
	 * Null while nothing of Soprano's runs.
	 */
	static String doing() {
		if (!up) return null;
		var mod = instance();
		if (mod == null || mod.getTaskRunner() == null) return null;
		var chain = mod.getTaskRunner().getCurrentTaskChain();
		if (chain == null) return null;
		var tasks = chain.getTasks();
		if (tasks.isEmpty()) return null;
		StringBuilder b = new StringBuilder();
		if (!(chain instanceof adris.altoclef.chains.UserTaskChain)) b.append(chain.getClass().getSimpleName().replace("Chain", "")).append(": ");
		// the outer task, past our night's wrapper, and the deepest
		int outer = tasks.size() > 1 && tasks.get(0) instanceof adris.altoclef.tasks.misc.SleepThroughNightsTask ? 1 : 0;
		var first = tasks.get(outer);
		var deepest = tasks.get(tasks.size() - 1);
		b.append(first.getDebugName());
		if (deepest != first) {
			b.append(" › ").append(deepest.getDebugName());
			String st = deepest.getDebugState();
			if (st != null && !st.isBlank()) b.append(": ").append(st);
		} else {
			String st = first.getDebugState();
			if (st != null && !st.isBlank()) b.append(": ").append(st);
		}
		String out = b.toString().replace('\n', ' ');
		return out.length() > 200 ? out.substring(0, 197) + "..." : out;
	}

	/**
	 * Soprano's plan for the panel, as rows of {@code t} (the task) and {@code s} (its state): {@code tasks}, the order's own
	 * chain from its outer task to its deepest; and while another chain has the wheel (monsters, food, lava, a fall), that
	 * chain's name in {@code by} and its rows in {@code by_tasks}. A chain that loses the wheel keeps its last tasks until
	 * it ticks again, so the rows of the order's chain are the plan that waits. Null while nothing of Soprano's runs.
	 */
	static java.util.Map<String, Object> plan() {
		if (!up) return null;
		var mod = instance();
		if (mod == null || mod.getTaskRunner() == null) return null;
		java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
		var user = mod.getUserTaskChain();
		if (user != null && !user.getTasks().isEmpty()) out.put("tasks", rows(user.getTasks()));
		var chain = mod.getTaskRunner().getCurrentTaskChain();
		if (chain != null && chain != user) {
			out.put("by", chain.getClass().getSimpleName());
			if (!chain.getTasks().isEmpty()) out.put("by_tasks", rows(chain.getTasks()));
		}
		return out.isEmpty() ? null : out;
	}

	/** A chain's tasks as the panel's rows, past our night's wrapper; a stack deeper than twelve keeps its outer four and deepest eight. */
	private static java.util.List<java.util.Map<String, String>> rows(java.util.List<adris.altoclef.tasksystem.Task> tasks) {
		int from = tasks.size() > 1 && tasks.get(0) instanceof adris.altoclef.tasks.misc.SleepThroughNightsTask ? 1 : 0;
		java.util.List<java.util.Map<String, String>> rows = new java.util.ArrayList<>();
		for (int i = from; i < tasks.size(); i++) {
			if (tasks.size() - from > 12 && i == from + 4) { rows.add(java.util.Map.of("t", "…")); i = tasks.size() - 9; continue; }
			var t = tasks.get(i);
			java.util.Map<String, String> r = new java.util.LinkedHashMap<>();
			r.put("t", cut(t.getDebugName()));
			String st = t.getDebugState();
			if (st != null && !st.isBlank()) r.put("s", cut(st));
			rows.add(r);
		}
		return rows;
	}

	private static String cut(String s) {
		s = String.valueOf(s).replace('\n', ' ').trim();
		return s.length() > 140 ? s.substring(0, 137) + "..." : s;
	}

	/**
	 * Once a second: a line for the panel's Activity tab when the kinds of Soprano's tasks change, not their places or their
	 * state (a staircase down is one line, not one a step).
	 */
	private static void watchDoing() {
		long now = System.currentTimeMillis();
		if (now < doingLook) return;
		doingLook = now + 1000;
		var mod = instance();
		if (mod == null || mod.getTaskRunner() == null || mod.getTaskRunner().getCurrentTaskChain() == null) return;
		var tasks = mod.getTaskRunner().getCurrentTaskChain().getTasks();
		if (tasks.isEmpty()) return;
		StringBuilder kinds = new StringBuilder();
		for (var t : tasks) kinds.append(t.getClass().getSimpleName()).append('>');
		String key = kinds.toString();
		if (key.equals(lastDoing)) return;
		lastDoing = key;
		String d = doing();
		if (d != null) Exchange.emit("alto", "action", "doing", "what", d);
	}

	private static float scaledFor;

	/**
	 * A practice server at a faster tick rate (practiceFastTicks): Baritone's path search runs on the wall clock (half a
	 * second, two when it struggles, five to plan ahead), so at five times the speed it stood still five times as long in
	 * game time while it worked a path out, mostly when it had to run, and died far more often for it. Its budget is cut
	 * by the rate, the same game time as at 20 ticks a second. AltoClef puts the defaults back when its runner comes up,
	 * so this is looked at every tick.
	 */
	private static void scaleTimeouts() {
		var mc = net.minecraft.client.Minecraft.getInstance();
		if (mc.level == null) return;
		float rate = AfkConfig.get().practiceFastTicks ? mc.level.tickRateManager().tickrate() : 20f;
		var s = baritone.api.BaritoneAPI.getSettings();
		float f = Math.max(1f, rate / 20f);
		long primary = Math.round(500 / f), failure = Math.round(2000 / f), ahead = Math.round(5000 / f);
		if (f == scaledFor && s.primaryTimeoutMS.value == primary && s.failureTimeoutMS.value == failure) return;
		scaledFor = f;
		s.primaryTimeoutMS.value = primary;
		s.failureTimeoutMS.value = failure;
		s.planAheadPrimaryTimeoutMS.value = (long) Math.round(4000 / f);
		s.planAheadFailureTimeoutMS.value = ahead;
		if (f > 1f) Exchange.emit("alto", "action", "timeouts", "rate", rate, "primary_ms", primary, "failure_ms", failure);
	}

	/** One of AltoClef's chains holds the wheel now: the buddy lets go. */
	static boolean inControl() {
		if (!up) return false;
		var mod = instance();
		if (mod == null) return false;
		var runner = mod.getTaskRunner();
		var chain = runner == null || !runner.isActive() ? null : runner.getCurrentTaskChain();
		boolean holds = chain != null && !(chain instanceof adris.altoclef.chains.UserTaskChain) && runner.winnerPriority() > 0;
		String name = holds ? chain.getClass().getSimpleName() : null;
		if (!java.util.Objects.equals(name, holder)) {
			if (name != null && holder == null) { HeldKeys.releaseAll(); Bari.yieldTo(); }
			holder = name;
		}
		// told once it held for a second: the mob defence takes and lets go several times a second near a monster, which
		// filled the Activity tab with pairs of lines
		long now = System.currentTimeMillis();
		if (!java.util.Objects.equals(name, toldWait)) { toldWait = name; toldSince = now; }
		else if (!java.util.Objects.equals(name, told) && now - toldSince >= 1000) {
			Exchange.emit("alto", "action", name != null ? "takes" : "lets go", "chain", name != null ? name : told);
			told = name;
		}
		return holds;
	}

	/** AltoClef is at work on something: a task of the user's chain, or one of its own chains holding the wheel. */
	static boolean working() {
		if (!up) return false;
		var mod = instance();
		if (mod == null) return false;
		if (mod.getUserTaskChain().isActive()) return true;
		var runner = mod.getTaskRunner();
		return runner != null && runner.isActive() && runner.winnerPriority() > 0;
	}

	/**
	 * A task of AltoClef's runs for a step of the order: it walks and works, the buddy's chain lets it and only its
	 * order's safety (the night, a wound, out of the water) may stop it.
	 */
	static boolean delegating() {
		if (!up || !delegated) return false;
		var mod = instance();
		if (mod != null && mod.getUserTaskChain().isActive()) return true;
		delegated = false;
		return false;
	}

	private static void cancel() {
		if (!delegated) return;
		delegated = false;
		var mod = instance();
		if (mod != null && mod.getUserTaskChain().isActive()) mod.cancelUserTask();
	}

	/** AltoClef's food task said it was done at once, twice (its count of food is not the planner's): the buddy's own for a while. */
	private static long foodOffUntil;
	private static int quickDone;

	/** The food task held off, or a quick end counted (RunMemories). */
	static int foodOffKept() { return (foodOffUntil > System.currentTimeMillis() ? 1 : 0) + quickDone; }

	static void forgetFoodOff() { foodOffUntil = 0; quickDone = 0; }

	// ---------- the run's memory (the autopilot's RunMemory) ----------

	/**
	 * The autopilot's fresh_run switch (each run a first visit, the practice rig) on and naming a run not yet started fresh:
	 * an order left open at a disconnect belongs to an earlier run. Off without the autopilot.
	 */
	static boolean freshNewRun() {
		return present() && adris.altoclef.util.helpers.RunMemory.newRun();
	}

	/** The autopilot's tick_log switch: the client's tick time written as events (ClientTicks, the practice rig). */
	static boolean tickLog() {
		return present() && adris.altoclef.util.helpers.Tuning.get("tick_log", 0) > 0;
	}

	/** The autopilot's sight_log switch: things seen on the way written as events (Sightings, the practice rig). */
	static boolean sightLog() {
		return present() && adris.altoclef.util.helpers.Tuning.get("sight_log", 0) > 0;
	}

	private static boolean memoriesIn;

	/**
	 * An obtain order starts: the buddy's own memories go into the autopilot's registry (the first time), its measure line
	 * becomes the event run_memory, and on a fresh run everything registered is forgotten before the order's first tick.
	 * Without the autopilot nothing happens.
	 */
	static void orderStart() {
		if (!present()) return;
		if (!memoriesIn) {
			memoriesIn = true;
			for (RunMemories.Memory m : RunMemories.ALL) adris.altoclef.util.helpers.RunMemory.register(m.name(), m.size(), m.clear(), m.fields());
		}
		Exchange.emit("run_memory", adris.altoclef.util.helpers.RunMemory.orderStart("obtain"));
	}

	/** The things a step for food gathers: AltoClef's food task does that better than a hunt for one kind. */
	private static final java.util.Set<String> FOODISH = new java.util.HashSet<>(ObtainKit.ANY_FOOD);
	static {
		FOODISH.addAll(java.util.List.of("chicken", "wheat", "beetroot", "melon", "pumpkin", "glow_berries", "hay_block"));
	}

	/** This step only gathers food for the kit or a meal, while the order is no food itself. */
	static boolean forFood(ObtainPlan.Step s, String order) {
		return on() && System.currentTimeMillis() >= foodOffUntil && (s.act() == ObtainPlan.Act.KILL || s.act() == ObtainPlan.Act.MINE) && FOODISH.contains(s.item())
			&& !FOODISH.contains(order);
	}

	/**
	 * Soprano first: an item AltoClef's catalogue knows is got by its #get, which plans, walks, mines, crafts, smelts,
	 * eats and fights for itself. Its navigating, staying alive and obtaining are better than the buddy's own, which is
	 * used only where Soprano has no answer.
	 */
	static boolean knows(String item) {
		return on() && AfkConfig.get().obtainSopranoFirst && adris.altoclef.TaskCatalogue.taskExists(item);
	}

	/**
	 * The route through the nether (the autopilot's RouteRules): an order of blaze rods that the autopilot plays whole is not
	 * done with its count in the bag while the buddy is still in the nether; its rods task goes back through a portal first.
	 * Done all the same once that way out is given up (no portal known, or its cap). Out of the nether the arrival is said
	 * once, here or by the rods task, whichever sees it first.
	 */
	static boolean routeExitHolds(net.minecraft.client.player.LocalPlayer p, String item) {
		String it = item == null ? "" : item.startsWith("minecraft:") ? item.substring("minecraft:".length()) : item;
		if (!"blaze_rod".equals(it) || !knows(it)) return false;
		boolean nether = p.level().dimension().equals(net.minecraft.world.level.Level.NETHER);
		if (!nether) adris.altoclef.util.helpers.RouteRules.out();
		return adris.altoclef.util.helpers.RouteRules.exitHolds(adris.altoclef.util.helpers.RouteRules.exitOn(), nether,
			adris.altoclef.util.helpers.RouteRules.exitGaveUp());
	}

	/** A step of the buddy's own plan (for an order AltoClef does not know whole: the elytra) for an item it does know. */
	static boolean forCatalogue(ObtainPlan.Step s) {
		return (s.act() == ObtainPlan.Act.KILL || s.act() == ObtainPlan.Act.MINE || s.act() == ObtainPlan.Act.CRAFT
			|| s.act() == ObtainPlan.Act.SMELT) && s.item() != null && knows(s.item());
	}

	/**
	 * Food by AltoClef's CollectFoodTask in Soprano's version: the animals, a village's hay and fields, berries, food on
	 * the ground, cooked in a smoker it makes, and a walk on to new ground in one direction when nothing is in sight. Its
	 * units are food points, raw meat counted as cooked; done when the bag holds that much.
	 */
	static Run food(double units) {
		// (fifteen game minutes: a run to 96 points took three to seven, and the one before the nether goes to 160)
		return new Run("collect food, " + Math.round(units) + " points", 15 * 60_000, () -> night(new adris.altoclef.tasks.resources.CollectFoodTask(units)));
	}

	/**
	 * Out of the nether for food, by AltoClef's way to the overworld (the portal it came through, or a new one). Food in
	 * the nether is a hoglin among piglins or a stew by the blazes: a fortress fight with eight blazes about ate 89 points
	 * in four and a half game minutes, the food search that followed went for the chickens and mushrooms by the spawner
	 * and the fire burnt it down from twelve hp. The food run follows in the overworld (Obtain).
	 */
	// (thirty game minutes: the way back to its portal across the nether, digging and bridging, took more than the ten it
	// had, and in the three minutes before it was asked again it looked for food in the nether with none in the bag)
	static Run homeForFood() {
		return new Run("home for food", 30 * 60_000,
			() -> new adris.altoclef.tasks.movement.DefaultGoToDimensionTask(baritone.api.utils.Dimension.OVERWORLD));
	}

	/** In the nether, eatable food under this sends it home for more: two or three minutes of a blaze fight's healing. */
	static final int NETHER_FOOD_RESERVE = 36;

	/**
	 * Food points gathered before the nether (and after a trip home from it). It was 96, which left 60 points above the
	 * reserve: some ten game minutes of a blaze fight's healing, and each trip home took about as long again (attempts 104
	 * to 172: 29 trips in 522 nether minutes, one of them ending in the lava sea). One that went in with 271 points
	 * needed none. 160 is twenty steaks, one slot.
	 */
	static final int NETHER_FOOD = 160;

	/**
	 * The food points a food run gathers: NETHER_FOOD for an order into the nether while in the overworld, 60 otherwise.
	 * On the route (ObtainRoute) the nether's only from its portal phase on, where the food for the nether is topped up;
	 * the deep's before it: 96 on the deep stretch (routeDeep), ONE_TRIP_FOOD with the autopilot's food_one_trip.
	 */
	static int foodUnits(net.minecraft.client.player.LocalPlayer p) {
		if (kitDanger(p).ordinal() >= ObtainKit.Danger.NETHER.ordinal() && !routeBefore(p, ObtainRoute.M3_4)) return NETHER_FOOD;
		// (on the route's deep stretch twelve steaks: a run to 60 from its floor of 40 would bring two and a half, and the next
		// climb soon after)
		if (!routeDeep(p)) return 60;
		return oneTrip() ? ONE_TRIP_FOOD : 96;
	}

	/**
	 * The food points one trip up from the deep stretch gathers with the autopilot's food_one_trip (a fix of 13 Nov 2026, on):
	 * twenty-four steaks, from the floor of 40 about an hour of mining and fighting. Up to 96 the gold phase climbed three or
	 * four times, 8 to 12 game minutes each, some 35 minutes of the 146 of M3.3's smoke 5 run 3 (and 19 of run 1's 64); one
	 * trip brings the rest of the phase, cooked in one batch at the end of the hunt (CollectFoodTask).
	 */
	static final int ONE_TRIP_FOOD = 192;

	/** The autopilot's food_one_trip switch (on by default); off without the autopilot. */
	static boolean oneTrip() {
		return present() && adris.altoclef.util.helpers.Tuning.get("food_one_trip", 0) > 0;
	}

	/**
	 * The night on the route's deep stretch with no bed in the bag, under food_one_trip: the mine is the night's shelter. No
	 * hole at the surface (one shut the buddy in for 15 minutes after a full bag had thrown its bed, M3.3's smoke 5), a food
	 * run under way gives way and the order's staircase takes the buddy down; the next food run waits for the morning unless
	 * it is hurt with nothing to eat. With a bed the night is slept where it is, as before.
	 */
	static boolean deepNight(net.minecraft.client.player.LocalPlayer p) {
		return oneTrip() && routeDeep(p) && Obtain.dark(p) && !ObtainSafe.hasBed(p);
	}

	/**
	 * A nether portal being built: a portal build anywhere in Soprano's task stack, or a frame half built nearby (a whole
	 * one by the way home is a portal, not a build). doing() sees
	 * only the outer task and the deepest, and the deepest is a click or a block placed, so portalUnderWay() missed it: the
	 * boat rule broke a frame off twice for a boat (attempt 224, 4 Nov 2026).
	 */
	static boolean buildingPortal() {
		if (!up) return false;
		var mod = instance();
		if (mod == null || mod.getTaskRunner() == null || mod.getPlayer() == null) return false;
		if (frameUnderWay()) return true;
		var chain = mod.getTaskRunner().getCurrentTaskChain();
		if (chain == null) return false;
		for (var t : chain.getTasks()) {
			if (t instanceof adris.altoclef.tasks.construction.compound.ConstructNetherPortalObsidianTask
				|| t instanceof adris.altoclef.tasks.construction.compound.ConstructNetherPortalBucketTask) return true;
		}
		return false;
	}

	/** A frame of obsidian begun nearby and not whole yet: one to nine of its ten blocks stand. */
	static boolean frameUnderWay() {
		if (!up) return false;
		var mod = instance();
		if (mod == null || mod.getPlayer() == null) return false;
		int n = adris.altoclef.tasks.construction.compound.ConstructNetherPortalObsidianTask.obsidianInLastFrame(mod);
		return n > 0 && n < 10;
	}

	/** Soprano's task under way is a nether portal's (a frame cast or built, or the walk into one). */
	static boolean portalUnderWay() {
		String d = doing();
		return d != null && d.toLowerCase(java.util.Locale.ROOT).contains("portal");
	}

	/**
	 * In the nether with little to eat, or with nothing golden worn any more: go home (homeForFood) rather than stay. A
	 * golden helmet has 77 points of durability, and a nether order's fights wore one through: from then every piglin
	 * was on it, with a hoglin, and a crossbow bolt knocked it off a ledge into the lava sea. Home, the kit puts a new one on.
	 */
	static boolean wantsHome(net.minecraft.client.player.LocalPlayer p) {
		return on() && p.level().dimension().equals(net.minecraft.world.level.Level.NETHER)
			&& (foodScore(false) < NETHER_FOOD_RESERVE || wantsFood(p) || !goldOn(p) && ObtainSteps.count(p, "golden_helmet") == 0);
	}

	/** In the nether with nothing golden worn and the spare helmet in the bag: that one on, at once (wantsHome otherwise). */
	static boolean wantsSpareGold(net.minecraft.client.player.LocalPlayer p) {
		return on() && p.level().dimension().equals(net.minecraft.world.level.Level.NETHER) && !goldOn(p)
			&& ObtainSteps.count(p, "golden_helmet") > 0;
	}

	static Run spareGold() {
		return new Run("equip golden_helmet", 60_000L, () -> new adris.altoclef.tasks.misc.EquipArmorTask(new adris.altoclef.util.ItemTarget("golden_helmet", 1)));
	}

	/** An item by AltoClef's catalogue, the way its #get takes: done when the bag holds {@code count}. */
	static Run get(String item, int count) {
		return new Run("get " + count + " " + item, 40 * 60_000, () -> adris.altoclef.TaskCatalogue.getItemTask(item, count));
	}

	/** A boat by AltoClef's catalogue (its planks of whatever wood is near), for the water ahead. */
	static Run boat() {
		return new Run("get 1 boat", 3 * 60_000, () -> adris.altoclef.TaskCatalogue.getItemTask("boat", 1));
	}

	/**
	 * Water ahead on its way and no boat or raft in the bag (and none under it), in the overworld: more than 32 blocks of
	 * it in Baritone's path, or a run of more than 32 water columns straight ahead towards the path's far end, as far as
	 * the world is loaded. The client sees that far, so it knows how much water lies ahead, and a boat is made before the
	 * swim. The rowing from eight blocks on is Baritone's own (boatMinWaterLength).
	 */
	static boolean wantsBoat(net.minecraft.client.player.LocalPlayer p) {
		if (!on() || p.getVehicle() != null || !p.level().dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) return false;
		// the portal is the way out of here: no water lies ahead of it
		if (buildingPortal()) return false;
		// on the surface only: under the ground a path through a cave's water asked for a boat at y -28, and the walk up for
		// wood brought it among the night's zombies and a skeleton, dead
		if (!p.level().canSeeSky(p.blockPosition().above()) || p.getY() < p.level().getSeaLevel() - 4) return false;
		var in = p.getInventory();
		for (int i = 0; i < 36; i++) {
			String id = AfkHelperClient.itemId(in.getItem(i));
			if (id.endsWith("_boat") || id.endsWith("_raft")) return false;
		}
		// the water keeps turning it back: twice in five game minutes its explore swam out and Soprano's open water rule
		// brought it ashore again (LandFinder). the food search did that 98 times on one coast and found nothing in 74 game
		// minutes, and the night found it at sea; with a boat the explore crosses, and a boat is no open water to be turned
		// back from
		if (adris.altoclef.util.helpers.LandFinder.steersSince(p.level().getGameTime() - 6000) >= 2) return true;
		var path = baritone.api.BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().getPath();
		if (path.isEmpty()) return false;
		var level = p.level();
		// as plain BlockPos: Baritone's own kind of it, handed to the game, makes the verifier load Baritone with this class
		java.util.List<?> nodes = path.get().positions();
		int wet = 0;
		for (Object n : nodes) if (level.getFluidState((net.minecraft.core.BlockPos) n).is(net.minecraft.tags.FluidTags.WATER)) wet++;
		if (wet > 32) return true;
		var end = (net.minecraft.core.BlockPos) nodes.get(nodes.size() - 1);
		double dx = end.getX() + 0.5 - p.getX(), dz = end.getZ() + 0.5 - p.getZ(), len = Math.hypot(dx, dz);
		if (len < 4) return false;
		dx /= len; dz /= len;
		int run = 0;
		for (int d = 1; d <= 256; d++) {
			int x = (int) Math.floor(p.getX() + dx * d), z = (int) Math.floor(p.getZ() + dz * d);
			if (!level.hasChunkAt(new net.minecraft.core.BlockPos(x, p.getBlockY(), z))) break;
			int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
			if (level.getFluidState(new net.minecraft.core.BlockPos(x, y, z)).is(net.minecraft.tags.FluidTags.WATER)) {
				if (++run > 32) return true;
			} else if (run > 0) run = 0;
		}
		return false;
	}

	/**
	 * The whole order by AltoClef's catalogue, with the gamer's night round it (SleepThroughNightsTask, our fork): at dusk a
	 * bed, a sleep, on in the morning. Hours, for an order from nothing goes through the whole early game.
	 */
	static Run whole(String item, int count) {
		return new Run("get " + count + " " + item, 6 * 3600_000L, () -> {
			var get = adris.altoclef.TaskCatalogue.getItemTask(item, count);
			return get == null ? null : night(get);
		});
	}

	/**
	 * The kit before the deep, for an order whose way goes down there: what the buddy's own kit rule says the deep takes
	 * (ObtainKit: the iron armour and a shield, a good sword, torches, a water bucket, blocks), got by Soprano. A #get
	 * from nothing went down to the diamonds at once and skeletons shot it dead in its clothes, four a hit; Soprano's
	 * #gamer plans a kit first, a #get does not. The armour by its own #equip (EquipArmorTask, billed: the whole set's
	 * iron in one go, put on, the shield in the other hand), the rest by its GatherBillTask. Food is a step of its own
	 * (wantsFood); a table and planks come first, in the bag; a spare pickaxe is left to Soprano. Null when nothing is
	 * missing.
	 */
	/** Three wool in the bag, or a sheep within 48 blocks: the bed is near at hand. */
	static boolean woolAbout(net.minecraft.client.player.LocalPlayer p, Obtain.GameHave gh) {
		int wool = 0;
		for (String w : VanillaData.tag("item", "wool")) wool += ObtainSteps.count(p, w);
		if (wool >= 3) return true;
		double sheep = gh.nearMob("sheep");
		return sheep >= 0 && sheep <= 48;
	}

	/** Kit pieces that did not come, by the run's name, and when (the order's game clock): not asked again for ten minutes. */
	private static final java.util.Map<String, Long> KIT_FAILED = new java.util.HashMap<>();

	static void kitFailed(Run r, long now) {
		KIT_FAILED.put(r.detail(), now);
		// (Tuning kit_failed_skip, a fix of 13 Nov 2026, on) a piece of the route that ran out of its time or failed waits ten
		// minutes too, while the phase's next piece goes on, as in the old kit: the route asked it again at once, and "equip
		// copper_leggings" ran its 20 minutes and came straight back, in the same flooded cave
		if (r.piece == null || !routeSkipOn()) return;
		ObtainRoute.failed(r.piece, now);
		var pc = ObtainRoute.piece(r.piece);
		int tries = pc == null ? ObtainRoute.TRIES : pc.tries(), n = ObtainRoute.tries(r.piece);
		String why = r.why() != null ? r.why() : "still wanted when its task ended";
		String line = "route: piece " + r.piece + " failed (" + why + "), " + (n < tries ? "skipped for 10 minutes, " : "given up, ")
			+ "try " + n + " of " + tries;
		if (present()) adris.altoclef.Debug.logInternal(line);
		Exchange.emit("obtain", "action", "route piece failed", "piece", r.piece, "why", why, "try", n, "tries", tries);
	}

	/** The autopilot's kit_failed_skip switch (a fix of 13 Nov 2026, on by default); off without the autopilot. */
	static boolean routeSkipOn() {
		return present() && adris.altoclef.util.helpers.Tuning.get("kit_failed_skip", 0) > 0;
	}

	/** A new order: every piece may be asked again. */
	static void kitReset() { KIT_FAILED.clear(); }

	/** How far the order under way goes (Obtain.deepOrder): the deep at least, for the kit. */
	static volatile ObtainKit.Danger orderDanger = ObtainKit.Danger.DEEP;

	/**
	 * The kit's danger: the order's, while still in the overworld, the deep's once out of it. In the nether the overworld's
	 * pieces (a diamond sword's diamonds, a bow's string) are not to be had, and they were asked anyway every ten minutes.
	 */
	static ObtainKit.Danger kitDanger(net.minecraft.client.player.LocalPlayer p) {
		boolean overworld = p.level().dimension().equals(net.minecraft.world.level.Level.OVERWORLD);
		return overworld && orderDanger.ordinal() > ObtainKit.Danger.DEEP.ordinal() ? orderDanger : ObtainKit.Danger.DEEP;
	}

	/**
	 * The next piece of the kit that was not tried in vain in the last ten game minutes, or null. The redstone is tried
	 * once an order: a gain for the brew, no gate, and its eight minutes a time down at the ore were a fifth of an order
	 * that never left the overworld (attempt 167, four times, never any).
	 */
	static Run kit(net.minecraft.client.player.LocalPlayer p, long now) {
		return kit(p, now, k -> { Long at = KIT_FAILED.get("AltoClef: " + k); return at != null && (now - at < 600_000 || k.equals("kit redstone")); });
	}

	static Run kit(net.minecraft.client.player.LocalPlayer p) { return kit(p, Long.MIN_VALUE, k -> false); }

	/**
	 * The kit's spare shield, while the one in the off hand is at its last uses and no other is in the bag; or null. Outside the
	 * overworld only with the makings in the bag: with no iron in the nether, Soprano's bill for the shield built a portal of its
	 * own, went home for iron ore and came back, six game minutes out of a blaze run (8 Nov 2026).
	 */
	private static Run spareShield(net.minecraft.client.player.LocalPlayer p, java.util.function.Predicate<String> skip) {
		var off = p.getOffhandItem();
		if (!off.is(net.minecraft.world.item.Items.SHIELD) || !adris.altoclef.util.helpers.ToolWear.wornOut(off.getDamageValue(), off.getMaxDamage())
				|| ObtainSteps.count(p, "shield") >= 2 || skip.test("kit a spare shield")) return null;
		if (!p.level().dimension().equals(net.minecraft.world.level.Level.OVERWORLD) && !shieldMakings(p)) return null;
		return new Run("kit a spare shield", pieceMinutes("shield") * 60_000L, () -> night(new adris.altoclef.tasks.squashed.GatherBillTask(
			new adris.altoclef.util.ItemTarget("shield", 2))));
	}

	/**
	 * A shield again on the route's way in the overworld, when none is in the off hand and the bag holds one or its makings:
	 * the route never goes back to M2.1's shield, and one worn through in M3.3's gold mine left the buddy without one for a
	 * creeper's blast at full health, twice in the goudslijm smoke (12 Nov 2026), with iron and logs in the bag. Null otherwise.
	 */
	private static Run shieldAgain(net.minecraft.client.player.LocalPlayer p, java.util.function.Predicate<String> skip) {
		// (only past the armour's phase, M2.1, whose first piece is the shield)
		if (ObtainRoute.phase() <= ObtainRoute.M2_1) return null;
		if (p.getOffhandItem().is(net.minecraft.world.item.Items.SHIELD) || skip.test("equip shield again")) return null;
		if (!p.level().dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) return null;
		if (ObtainSteps.count(p, "shield") < 1 && !shieldMakings(p)) return null;
		return new Run("equip shield again", pieceMinutes("shield") * 60_000L, () -> night(new adris.altoclef.tasks.misc.EquipArmorTask(
			new adris.altoclef.util.ItemTarget("shield", 1))));
	}

	/**
	 * Whether the spare shield goes before the food run: the shield in hand at its last uses and the makings in the bag (an
	 * iron, raw or smelted, and wood), so it is a minute at a table and no trip. A food run that went first dug into a cave
	 * of skeletons with seven uses left on the shield, and it broke at 40 s (attempt 166).
	 */
	static boolean spareShieldFirst(net.minecraft.client.player.LocalPlayer p, long now) {
		if (!on() || spareShield(p, k -> { Long at = KIT_FAILED.get("AltoClef: " + k); return at != null && now - at < 600_000; }) == null) return false;
		return shieldMakings(p);
	}

	/** Whether "equip shield" can do something here: anywhere in the overworld; elsewhere a shield in the bag beside the one in hand, or the makings. */
	private static boolean shieldToEquip(net.minecraft.client.player.LocalPlayer p) {
		if (p.level().dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) return true;
		int inHand = p.getOffhandItem().is(net.minecraft.world.item.Items.SHIELD) ? 1 : 0;
		return ObtainSteps.count(p, "shield") > inHand || shieldMakings(p);
	}

	/** Whether a shield's makings are in the bag: an iron, raw or smelted, and six planks of wood (logs and stems count four). */
	private static boolean shieldMakings(net.minecraft.client.player.LocalPlayer p) {
		if (ObtainSteps.count(p, "raw_iron") + ObtainSteps.count(p, "iron_ingot") < 1) return false;
		int wood = 0;
		var inv = p.getInventory();
		for (int i = 0; i < 36; i++) {
			var s = inv.getItem(i);
			if (s.isEmpty()) continue;
			String id = AfkHelperClient.itemId(s);
			if (id.endsWith("_planks")) wood += s.getCount();
			else if (id.endsWith("_log") || id.endsWith("_stem")) wood += 4 * s.getCount();
		}
		return wood >= 6;
	}

	private static Run kit(net.minecraft.client.player.LocalPlayer p, long now, java.util.function.Predicate<String> skip) {
		// an order into the nether begun in the overworld fetches its kit by the route's phases (ObtainRoute) until it is out
		// of the overworld; the old kit from then on, as for any order
		if (ObtainRoute.on() && routeWalks(p)) return routeKit(p, now, skip);
		var gh = new Obtain.GameHave(p);
		java.util.List<String> armour = new java.util.ArrayList<>();
		java.util.List<adris.altoclef.util.ItemTarget> rest = new java.util.ArrayList<>();
		java.util.List<String> names = new java.util.ArrayList<>();
		int blocks = 0;
		boolean bottles = false, redstone = false;
		// (no torches or water bucket out of the overworld: ObtainKit.skip, which the order's own check of the kit reads too.
		// the torches sent Soprano home through a portal it had no obsidian for)
		for (ObtainKit.Need n : ObtainKit.missingAll(kitDanger(p), gh)) {
			switch (n.name()) {
				case "helmet", "chestplate", "leggings", "boots" -> { armour.add("iron_" + n.name()); names.add(n.name()); }
				case "shield" -> { armour.add("shield"); names.add("shield"); }
				case "good sword" -> { rest.add(new adris.altoclef.util.ItemTarget("iron_sword", 1)); names.add("iron sword"); }
				case "torches" -> { rest.add(new adris.altoclef.util.ItemTarget("torch", 16)); names.add("torches"); }
				// (anywhere in the bag: the buddy's kit rule wants it in the hotbar, and Soprano, holding it in the bag, was asked
				// for it again every ten minutes)
				case "water bucket" -> { if (ObtainSteps.count(p, "water_bucket") < 1) armour.add("water_bucket"); }
				case "blocks" -> blocks = Math.max(blocks, 16);
				// the nether's, for an order that goes there and only in the overworld (kitDanger): the piglins leave the buddy be
				// in a golden helmet, a wither skeleton is fewer blows with a diamond sword, a blaze over a fortress' floor wants a
				// bow (ObtainKit's own reasons). its food is wantsFood's
				case "gold helmet" -> armour.add("golden_helmet");
				// (Soprano counts the bag and not what is worn, so one in the bag is the spare)
				// (Soprano counts the bag, not what is worn: the bag's count and the one missing. a bill for one, with one in the
				// bag, was met at once and came back seven times in one order, attempt 167)
				case "spare gold helmet" -> { rest.add(new adris.altoclef.util.ItemTarget("golden_helmet", ObtainSteps.count(p, "golden_helmet") + n.count())); names.add("a spare golden helmet"); }
				case "more blocks" -> blocks = Math.max(blocks, 48);
				case "flint and steel" -> { rest.add(new adris.altoclef.util.ItemTarget("flint_and_steel", 1)); names.add("flint and steel"); }
				// (the diamond sword only with its two diamonds in the bag, and no bow or arrows: Soprano's blaze task fights them
				// close, an iron sword takes a blaze in four blows, and the diamonds, the string and the feathers kept the kit
				// fourteen real minutes in the caves, where skeletons knocked it into lava and a witch poisoned it)
				case "nether sword" -> { if (ObtainSteps.count(p, "diamond") >= 2) { rest.add(new adris.altoclef.util.ItemTarget("diamond_sword", 1)); names.add("diamond sword"); } }
				case "water bottles" -> bottles = true;
				case "redstone" -> redstone = true;
				default -> { }
			}
		}
		if (blocks > 0) { rest.add(new adris.altoclef.util.ItemTarget("cobblestone", blocks)); names.add("blocks"); }
		// a table and planks in the bag before anything of iron: without them Soprano walks to any table it sees within 128
		// blocks rather than make one, and it went 55 blocks through the caves to a table left behind at y -2, where a zombie
		// knocked it into lava. with one in the bag it puts it down where it stands
		// (only while a piece of armour is still missing: the planks the craft uses up sent it from y -14 back up for wood
		// for the sword and the torches, with its armour on already)
		// (and only near the surface, with twenty planks, five tables' worth, to take down: asked again deep under the ground
		// it climbed from y 18 to the trees at y 120 for a table's planks, twice in one order, and its kit took 146 game
		// minutes. down there Soprano makes do with what it carries or walks back to its own table)
		if (!armour.isEmpty() && woodLand(p) && (ObtainSteps.count(p, "crafting_table") < 1 || planks(p) < 8) && !skip.test("kit a table and planks"))
			return new Run("kit a table and planks", 10 * 60_000, () -> night(new adris.altoclef.tasks.squashed.GatherBillTask(
				new adris.altoclef.util.ItemTarget("crafting_table", 1), new adris.altoclef.util.ItemTarget("planks", 20))));
		// a bed before the first iron, near the surface: a night in a bed is seconds where one passes for one sleeper, and
		// without one the night's hole took about two thirds of every day, from 11520 to the morning's last monster at 4000,
		// over a quarter of an order shut in. three sheep and three planks
		// (a bed standing beside it is its own, put down for the night and not picked up yet)
		// (and only with a sheep within 48 blocks or three wool in the bag: asked with none about, it went 350 blocks through
		// the open in its clothes, and skeletons in the shade of a forest shot it dead on the way. without a bed the night is
		// a hole)
		if (!ObtainSafe.hasBed(p) && !ObtainSafe.bedNear(p, 6) && !ObtainSafe.deepUnder(p) && woolAbout(p, gh) && !skip.test("kit a bed"))
			return new Run("kit a bed", 8 * 60_000, () -> night(adris.altoclef.TaskCatalogue.getItemTask("bed", 1)));
		// a stone sword before the first iron, for two cobblestone and a stick: on its way down for the shield's ingot, in
		// its clothes with a wooden sword, four zombies in a cave at y 26 killed it; the stone one takes a zombie in four
		// blows where the wooden one takes five
		if (!armour.isEmpty() && !blade(p) && !skip.test("kit a stone sword"))
			return new Run("kit a stone sword", 5 * 60_000, () -> night(new adris.altoclef.tasks.squashed.GatherBillTask(
				new adris.altoclef.util.ItemTarget("stone_sword", 1))));
		// three stone pickaxes, a table and sticks near the surface: the rock goes to them (Soprano's wear chain) and the
		// iron pickaxe is kept for the ore. without them an iron one wore out on every staircase down, it climbed to its
		// furnace for the iron of the next, and down there no stone one could be made with no table and three planks
		// (before the first iron too, not only once the armour is on: with one at a time each wore out on the staircase down
		// for the shield's ingot, every new one and its table took planks, and at y 16 with the raw iron in the bag there was
		// nothing left to burn. it went up a staircase for coal, the shield's time ran out, and it went for copper in its
		// clothes among skeletons)
		// (twelve sticks, handles for six more: the branch mines for copper and iron went through five stone pickaxes in
		// twenty game minutes, with eight stacks of cobble in the bag, and with the sticks and the table spent it had to
		// climb from y 37 to the trees for planks)
		if (woodLand(p) && !skip.test("kit stone pickaxes")) {
			int cheap = cheapPicks(p);
			if (cheap < 3 || ObtainSteps.count(p, "crafting_table") < 1 || ObtainSteps.count(p, "stick") < 4) {
				int stone = ObtainSteps.count(p, "stone_pickaxe") + Math.max(0, 3 - cheap);
				return new Run("kit stone pickaxes", 8 * 60_000, () -> night(new adris.altoclef.tasks.squashed.GatherBillTask(
					new adris.altoclef.util.ItemTarget("stone_pickaxe", stone), new adris.altoclef.util.ItemTarget("crafting_table", 1),
					new adris.altoclef.util.ItemTarget("stick", 12))));
			}
		}
		// one piece at a time, worn at once, the most protection per ingot first (a shield of one ingot, then the
		// chestplate): the whole set's thirty raw iron in one go took it into the caves in its clothes, from 20 to 2 hp among
		// skeletons, the same lesson the buddy's own kit has (AS_IT_COMES)
		// the water bucket right after the chestplate, a piece of its own: in the rest of the kit with the sword and the
		// torches it ran out of time, and on fire beside lava at y -14 with nothing to put it out, in full iron, it burned to
		// death
		if (armour.contains("water_bucket") && !armour.contains("shield") && !armour.contains("iron_chestplate") && !skip.test("kit water bucket"))
			return new Run("kit water bucket", 15 * 60_000, () -> night(adris.altoclef.TaskCatalogue.getItemTask("water_bucket", 1)));
		// a shield at its last uses counts as the kit's shield, and broke on the first arrows: a creeper's blast then caught
		// it with none, 30 through iron (7 uses of 336 left at the start). a spare in the bag, which the mob defense takes
		// into the other hand once the worn one is gone. before "equip shield": the kit counts the worn one as missing, and
		// equipping saw the shield already held and ended at once, a step that did nothing until it was skipped for ten
		// minutes, and the food run went first meanwhile (attempt 166: broken at 40 s among skeletons)
		Run spare = spareShield(p, skip);
		if (spare != null) return spare;
		// outside the overworld only a shield to put on, or its makings: in the nether the step saw the worn one in hand as
		// missing, set the order again (the route back to its first phase, the brew errand's clock afresh: phase 4 had 51 s)
		// and, once that shield broke, went home through the portal for iron (route-10 run 2, 9 Nov 2026)
		if (armour.contains("shield") && !skip.test("equip shield") && shieldToEquip(p))
			return new Run("equip shield", pieceMinutes("shield") * 60_000L, () -> night(new adris.altoclef.tasks.misc.EquipArmorTask(new adris.altoclef.util.ItemTarget("shield", 1))));
		// copper before the iron, a piece for every empty slot whose iron is not in the bag yet: iron came at an ore in three
		// to eight game minutes and the chestplate's eight timed out twice in one order, in its clothes all the while, and
		// the deaths of the kit were in the caves without armour (zombies, a witch, skeletons). copper is common and an ore
		// gives two to five; a set is ten points of armour where nothing is none, and the iron takes its place piece by piece
		// (the kit still asks for iron, ObtainKit's IRON_UP)
		for (String slot : new String[] { "chestplate", "leggings", "helmet", "boots" }) {
			if (!armour.contains("iron_" + slot) || !worn(p, slot).isEmpty() || ironFor(p, slot)) continue;
			if (ObtainSteps.count(p, "copper_" + slot) > 0 || skip.test("equip copper_" + slot)) continue;
			String piece = "copper_" + slot;
			return new Run("equip " + piece, (6 + 2 * ingots(slot)) * 60_000L, () -> night(new adris.altoclef.tasks.misc.EquipArmorTask(new adris.altoclef.util.ItemTarget(piece, 1))));
		}
		for (String piece : new String[] { "iron_chestplate", "iron_leggings", "iron_helmet", "iron_boots" })
			if (armour.contains(piece) && !skip.test("equip " + piece))
				return new Run("equip " + piece, pieceMinutes(piece) * 60_000L, () -> night(new adris.altoclef.tasks.misc.EquipArmorTask(new adris.altoclef.util.ItemTarget(piece, 1))));
		// the golden helmet for an order into the nether, worn in place of the iron one (as much armour, and the piglins
		// leave the buddy be): five ingots of gold, the time of five of iron
		// (and never given up while nothing golden is worn: after twenty-five game minutes for three raw gold the piece
		// counted as failed, the order went through the portal in iron, and the piglins shot it with six rods in the bag. the
		// order waits for its gold: slow beats a piglin pack)
		// (and one in the bag while nothing golden is worn goes on too: the kit counts it as the helmet, so the piece is not
		// missing, and the order went on in an iron helmet; a bill for the spare had taken the worn one off, attempt 167)
		boolean goldInBag = kitDanger(p).ordinal() >= ObtainKit.Danger.NETHER.ordinal() && !goldOn(p) && ObtainSteps.count(p, "golden_helmet") > 0;
		if ((armour.contains("golden_helmet") || goldInBag) && (!skip.test("equip golden_helmet") || !goldOn(p)))
			return new Run("equip golden_helmet", (10 + 3 * 5) * 60_000L, () -> night(new adris.altoclef.tasks.misc.EquipArmorTask(new adris.altoclef.util.ItemTarget("golden_helmet", 1))));
		String what = "kit " + String.join(", ", names.stream().filter(n -> !armour.contains(n) && !armour.contains("iron_" + n)).toList());
		if (!rest.isEmpty() && !skip.test(what))
			return new Run(what, 20 * 60_000, () -> night(new adris.altoclef.tasks.squashed.GatherBillTask(
				rest.toArray(adris.altoclef.util.ItemTarget[]::new))));
		// the brew's water bottles and redstone (ObtainKit), last and each a piece of its own with its own time: sand for the
		// glass or a dust that does not come holds none of the rest up, and the order goes without (the brew is a gain, no gate)
		if (bottles && !skip.test("kit water bottles"))
			return new Run("kit water bottles", 10 * 60_000, () -> night(adris.altoclef.TaskCatalogue.getItemTask("water_bottle", 3)));
		if (redstone && !skip.test("kit redstone"))
			return new Run("kit redstone", 8 * 60_000, () -> night(adris.altoclef.TaskCatalogue.getItemTask("redstone", 1)));
		return null;
	}

	// ---------- the route through the overworld (ObtainRoute) ----------

	/** The order's danger is known (Obtain.deepOrder): the route on for an order into the nether begun in the overworld. */
	static void routeBegin(net.minecraft.client.player.LocalPlayer p, ObtainKit.Danger danger) {
		ObtainRoute.begin(danger == ObtainKit.Danger.NETHER, routeBag(p), Alto::routeSay);
	}

	/** The route still walks: out of the overworld it is behind the buddy for the rest of the order. */
	private static boolean routeWalks(net.minecraft.client.player.LocalPlayer p) {
		if (!p.level().dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) ObtainRoute.out();
		return ObtainRoute.walking();
	}

	/** On the route, in the overworld, and not yet at that phase (the food rules). */
	private static boolean routeBefore(net.minecraft.client.player.LocalPlayer p, int phase) {
		return ObtainRoute.before(phase) && p.level().dimension().equals(net.minecraft.world.level.Level.OVERWORLD);
	}

	/**
	 * A phase's line: in the autopilot's log, where the rig reads the nether's route lines too ("route: phase m2.1 armour";
	 * the nether's "route: phase N" never matches it), and as an event.
	 */
	private static void routeSay(int phase, String state) {
		String line = ObtainRoute.BEGUN.equals(state) ? ObtainRoute.line(phase) : ObtainRoute.shortLine(phase);
		if (present()) adris.altoclef.Debug.logInternal(line);
		Exchange.emit("obtain", "action", "route phase", "phase", ObtainRoute.PHASES.get(phase), "word", ObtainRoute.WORDS.get(phase),
			"state", state);
	}

	/** The bag as the route reads it: carried with the off hand (every wear), and what is worn. */
	private static ObtainRoute.Bag routeBag(net.minecraft.client.player.LocalPlayer p) {
		return new ObtainRoute.Bag() {
			public int inv(String item) { return ObtainSteps.count(p, item); }

			public String wornIn(String slot) {
				var s = worn(p, slot);
				return s.isEmpty() ? "" : AfkHelperClient.itemId(s);
			}

			public int invEnding(String suffix) {
				int n = 0;
				var in = p.getInventory();
				for (int i = 0; i < 36; i++) { var s = in.getItem(i); if (!s.isEmpty() && AfkHelperClient.itemId(s).endsWith(suffix)) n += s.getCount(); }
				var o = p.getOffhandItem();
				if (!o.isEmpty() && AfkHelperClient.itemId(o).endsWith(suffix)) n += o.getCount();
				return n;
			}

			public int cheapPicks() { return Alto.cheapPicks(p); }

			public boolean bedNear() { return ObtainSafe.bedNear(p, 6); }

			public boolean deepUnder() { return ObtainSafe.deepUnder(p); }

			public boolean slimeNear() { return ObtainSafe.slimeNear(p, ObtainSafe.SLIME_SIGHT); }

			public boolean restMissing() { return !routeRest(p, new java.util.ArrayList<>()).isEmpty(); }
		};
	}

	/**
	 * The kit by the route's phases: the next piece of the phase under way, each the task the old kit has for it, and null
	 * only once the portal's phase has nothing left to ask, when the whole order builds the frame from the bag, lights it
	 * and goes through. A piece that did not come waits ten minutes while the phase's next piece goes on (kit_failed_skip),
	 * three tries in all (ObtainRoute); when every piece left in the phase waits, the one that failed first is asked again
	 * at once: a null between two pieces would start the whole order, which goes its own way from the bag it has. A spare
	 * shield while the one in hand is at its last uses comes first, as in the old kit. Long.MIN_VALUE for now: no piece waits.
	 */
	private static Run routeKit(net.minecraft.client.player.LocalPlayer p, long now, java.util.function.Predicate<String> skip) {
		Run spare = spareShield(p, skip);
		if (spare != null) return spare;
		Run again = shieldAgain(p, skip);
		if (again != null) return again;
		ObtainRoute.Bag bag = routeBag(p);
		ObtainRoute.Piece pc = ObtainRoute.next(bag, routeSkipOn() ? now : Long.MIN_VALUE, Alto::routeSay);
		return pc == null ? null : routePiece(p, bag, pc.name()).piece(pc.key());
	}

	/** The task of a piece of the route. */
	private static Run routePiece(net.minecraft.client.player.LocalPlayer p, ObtainRoute.Bag bag, String name) {
		switch (name) {
			case "wood": {
				// a table and a wooden pickaxe when there are none, and sixteen logs while the wood is short: the bill crafts the
				// first two of its logs
				java.util.List<adris.altoclef.util.ItemTarget> bill = new java.util.ArrayList<>();
				if (ObtainSteps.count(p, "crafting_table") < 1) bill.add(new adris.altoclef.util.ItemTarget("crafting_table", 1));
				if (!ObtainRoute.met(ObtainRoute.M1_1, bag, false)) bill.add(new adris.altoclef.util.ItemTarget("wooden_pickaxe", 1));
				if (ObtainRoute.wood(bag) < ObtainRoute.WOOD) bill.add(new adris.altoclef.util.ItemTarget("log", 16));
				return new Run("kit wood", 10 * 60_000, () -> night(new adris.altoclef.tasks.squashed.GatherBillTask(
					bill.toArray(adris.altoclef.util.ItemTarget[]::new))));
			}
			case "stone pickaxes": {
				int stone = ObtainSteps.count(p, "stone_pickaxe") + Math.max(0, 3 - cheapPicks(p));
				return new Run("kit stone pickaxes", 8 * 60_000, () -> night(new adris.altoclef.tasks.squashed.GatherBillTask(
					new adris.altoclef.util.ItemTarget("stone_pickaxe", stone), new adris.altoclef.util.ItemTarget("crafting_table", 1),
					new adris.altoclef.util.ItemTarget("stick", 12))));
			}
			case "stone sword":
				return new Run("kit a stone sword", 5 * 60_000, () -> night(new adris.altoclef.tasks.squashed.GatherBillTask(
					new adris.altoclef.util.ItemTarget("stone_sword", 1))));
			case "furnace":
				return new Run("kit a furnace", 5 * 60_000, () -> night(new adris.altoclef.tasks.squashed.GatherBillTask(
					new adris.altoclef.util.ItemTarget("furnace", 1))));
			case "food":
				// eight steaks' worth: the phase is met with eight food in the bag
				return food(64);
			case "bed":
				// (asked with no sheep about too: the route's bed is the first night's, wherever the sheep are)
				return new Run("kit a bed", 8 * 60_000, () -> night(adris.altoclef.TaskCatalogue.getItemTask("bed", 1)));
			case "sand":
				return new Run("kit sand", 5 * 60_000, () -> night(adris.altoclef.TaskCatalogue.getItemTask("sand", 3)));
			case "shield":
				return new Run("equip shield", pieceMinutes("shield") * 60_000L, () -> night(new adris.altoclef.tasks.misc.EquipArmorTask(
					new adris.altoclef.util.ItemTarget("shield", 1))));
			case "copper_chestplate", "copper_leggings", "copper_boots":
				return new Run("equip " + name, (6 + 2 * ingots(name.substring("copper_".length()))) * 60_000L,
					() -> night(new adris.altoclef.tasks.misc.EquipArmorTask(new adris.altoclef.util.ItemTarget(name, 1))));
			case "iron_chestplate", "iron_leggings", "iron_boots":
				return new Run("equip " + name, pieceMinutes(name) * 60_000L,
					() -> night(new adris.altoclef.tasks.misc.EquipArmorTask(new adris.altoclef.util.ItemTarget(name, 1))));
			case "water bucket":
				return new Run("kit water bucket", 15 * 60_000, () -> night(adris.altoclef.TaskCatalogue.getItemTask("water_bucket", 1)));
			case "iron pickaxes": {
				// to two of iron or better: three ingots each
				int up = ObtainSteps.count(p, "iron_pickaxe") + ObtainSteps.count(p, "diamond_pickaxe") + ObtainSteps.count(p, "netherite_pickaxe");
				int more = Math.max(1, 2 - up), iron = ObtainSteps.count(p, "iron_pickaxe") + more;
				return new Run("kit iron pickaxes", (10 + 3 * 3 * more) * 60_000L, () -> night(new adris.altoclef.tasks.squashed.GatherBillTask(
					new adris.altoclef.util.ItemTarget("iron_pickaxe", iron))));
			}
			case "iron sword":
				return new Run("kit iron sword", (10 + 3 * 2) * 60_000L, () -> night(new adris.altoclef.tasks.squashed.GatherBillTask(
					new adris.altoclef.util.ItemTarget("iron_sword", 1))));
			case "diamonds": {
				// the pickaxe and the sword still missing, in one bill: one trip down to the diamonds' depth
				java.util.List<adris.altoclef.util.ItemTarget> bill = new java.util.ArrayList<>();
				java.util.List<String> names = new java.util.ArrayList<>();
				if (ObtainSteps.count(p, "diamond_pickaxe") + ObtainSteps.count(p, "netherite_pickaxe") < 1) {
					bill.add(new adris.altoclef.util.ItemTarget("diamond_pickaxe", 1));
					names.add("diamond pickaxe");
				}
				if (ObtainSteps.count(p, "diamond_sword") + ObtainSteps.count(p, "netherite_sword") < 1) {
					bill.add(new adris.altoclef.util.ItemTarget("diamond_sword", 1));
					names.add("diamond sword");
				}
				return new Run("kit " + String.join(", ", names), 30 * 60_000, () -> night(new adris.altoclef.tasks.squashed.GatherBillTask(
					bill.toArray(adris.altoclef.util.ItemTarget[]::new))));
			}
			case "redstone":
				return new Run("kit redstone", 8 * 60_000, () -> night(adris.altoclef.TaskCatalogue.getItemTask("redstone", 1)));
			case "obsidian":
				return new Run("kit obsidian", 30 * 60_000, () -> night(adris.altoclef.TaskCatalogue.getItemTask("obsidian", 10)));
			case "gold": {
				// the trade's gold and the two helmets' ten, so that making the helmets leaves the trade's
				int need = ObtainRoute.goldNeed(bag);
				return new Run("kit gold", 40 * 60_000, () -> night(adris.altoclef.TaskCatalogue.getItemTask("gold_ingot", need)));
			}
			case "golden helmet":
			case "golden helmet again":
				return new Run("equip golden_helmet", (10 + 3 * 5) * 60_000L, () -> night(new adris.altoclef.tasks.misc.EquipArmorTask(
					new adris.altoclef.util.ItemTarget("golden_helmet", 1))));
			case "spare golden helmet": {
				// (Soprano counts the bag, not what is worn: the bag's count and one more)
				int helmets = ObtainSteps.count(p, "golden_helmet") + 1;
				return new Run("kit a spare golden helmet", 10 * 60_000, () -> night(new adris.altoclef.tasks.squashed.GatherBillTask(
					new adris.altoclef.util.ItemTarget("golden_helmet", helmets))));
			}
			case "slime ball":
				// (the fetch as an order of one slime ball does it; bal on 9-10 Nov: 14 of 20, a median 9 game minutes)
				return new Run("kit a slime ball", 40 * 60_000, () -> night(adris.altoclef.TaskCatalogue.getItemTask("slime_ball", 1)));
			case "flint and steel":
				return new Run("kit flint and steel", 10 * 60_000, () -> night(new adris.altoclef.tasks.squashed.GatherBillTask(
					new adris.altoclef.util.ItemTarget("flint_and_steel", 1))));
			case "rest": {
				java.util.List<String> names = new java.util.ArrayList<>();
				java.util.List<adris.altoclef.util.ItemTarget> rest = routeRest(p, names);
				return new Run("kit " + String.join(", ", names), 20 * 60_000, () -> night(new adris.altoclef.tasks.squashed.GatherBillTask(
					rest.toArray(adris.altoclef.util.ItemTarget[]::new))));
			}
			case "water bottles":
				return new Run("kit water bottles", 10 * 60_000, () -> night(adris.altoclef.TaskCatalogue.getItemTask("water_bottle", 3)));
			default:
				throw new IllegalStateException("no task for the route's piece " + name);
		}
	}

	/**
	 * The rest of the nether's kit as the old kit bills it (a good sword, torches, blocks, the diamond sword with its two
	 * diamonds in the bag), less what the route asks as pieces of their own: flint and steel and the spare golden helmet.
	 */
	private static java.util.List<adris.altoclef.util.ItemTarget> routeRest(net.minecraft.client.player.LocalPlayer p, java.util.List<String> names) {
		java.util.List<adris.altoclef.util.ItemTarget> rest = new java.util.ArrayList<>();
		int blocks = 0;
		for (ObtainKit.Need n : ObtainKit.missingAll(kitDanger(p), new Obtain.GameHave(p))) {
			switch (n.name()) {
				case "good sword" -> { rest.add(new adris.altoclef.util.ItemTarget("iron_sword", 1)); names.add("iron sword"); }
				case "torches" -> { rest.add(new adris.altoclef.util.ItemTarget("torch", 16)); names.add("torches"); }
				case "blocks" -> blocks = Math.max(blocks, 16);
				case "more blocks" -> blocks = Math.max(blocks, 48);
				case "nether sword" -> { if (ObtainSteps.count(p, "diamond") >= 2) { rest.add(new adris.altoclef.util.ItemTarget("diamond_sword", 1)); names.add("diamond sword"); } }
				default -> { }
			}
		}
		if (blocks > 0) { rest.add(new adris.altoclef.util.ItemTarget("cobblestone", blocks)); names.add("blocks"); }
		return rest;
	}

	/** A piece of golden armour worn: the piglins leave the buddy be. */
	static boolean goldOn(net.minecraft.client.player.LocalPlayer p) {
		for (var slot : new net.minecraft.world.entity.EquipmentSlot[] { net.minecraft.world.entity.EquipmentSlot.HEAD,
				net.minecraft.world.entity.EquipmentSlot.CHEST, net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET }) {
			String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(p.getItemBySlot(slot).getItem()).getPath();
			if (id.startsWith("golden_")) return true;
		}
		return false;
	}

	// the stone and copper pickaxes in the bag that are not worn past Soprano's line
	private static int cheapPicks(net.minecraft.client.player.LocalPlayer p) {
		int n = 0;
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
			var st = p.getInventory().getItem(i);
			if ((st.is(net.minecraft.world.item.Items.STONE_PICKAXE) || st.is(net.minecraft.world.item.Items.COPPER_PICKAXE))
					&& !adris.altoclef.util.helpers.ToolWear.wornOut(st.getDamageValue(), st.getMaxDamage())) n++;
		}
		return n;
	}

	// a sword better than a wooden one in the bag
	private static boolean blade(net.minecraft.client.player.LocalPlayer p) {
		for (String id : new String[] { "stone_sword", "copper_sword", "iron_sword", "diamond_sword", "netherite_sword" })
			if (ObtainSteps.count(p, id) > 0) return true;
		return false;
	}

	// planks in the bag, a log counted as the four it makes
	private static int planks(net.minecraft.client.player.LocalPlayer p) {
		int n = 0;
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
			var st = p.getInventory().getItem(i);
			if (st.isEmpty()) continue;
			String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
			if (id.endsWith("_planks")) n += st.getCount();
			else if (id.endsWith("_log") || id.endsWith("_stem")) n += 4 * st.getCount();
		}
		return n;
	}

	/**
	 * Game minutes a piece of the kit may take: ten and three for each ingot it needs. Fifteen for every piece ran out on
	 * the chestplate's eight ingots twice in a row, with its trips up to the furnace at the surface.
	 */
	private static int pieceMinutes(String piece) {
		return 10 + 3 * (piece.startsWith("iron_") ? ingots(piece.substring(5)) : 1);
	}

	private static int ingots(String slot) {
		return switch (slot) { case "chestplate" -> 8; case "leggings" -> 7; case "helmet" -> 5; case "boots" -> 4; default -> 1; };
	}

	// what is worn in that armour slot
	private static net.minecraft.world.item.ItemStack worn(net.minecraft.client.player.LocalPlayer p, String slot) {
		var es = switch (slot) {
			case "chestplate" -> net.minecraft.world.entity.EquipmentSlot.CHEST;
			case "leggings" -> net.minecraft.world.entity.EquipmentSlot.LEGS;
			case "helmet" -> net.minecraft.world.entity.EquipmentSlot.HEAD;
			default -> net.minecraft.world.entity.EquipmentSlot.FEET;
		};
		return p.getItemBySlot(es);
	}

	// the bag holds the iron for that piece already, ingots and raw iron counted together
	private static boolean ironFor(net.minecraft.client.player.LocalPlayer p, String slot) {
		return ObtainSteps.count(p, "iron_ingot") + ObtainSteps.count(p, "raw_iron") >= ingots(slot)
				|| ObtainSteps.count(p, "iron_" + slot) > 0;
	}

	/**
	 * A side step of the order with Soprano's night round it, as the whole order has: the evening's wool and bed, and the
	 * night asleep. Food and the kit had none, so at night it hunted on the surface in its clothes among zombies with
	 * spears and skeletons, and was shot dead.
	 */
	private static Object night(Object t) {
		return new adris.altoclef.tasks.misc.SleepThroughNightsTask((adris.altoclef.tasksystem.Task) t, ObtainSafe::sopranoSleeps);
	}

	private static long hungrySince = -1;

	/**
	 * Soprano lets it starve: hunger at 6 or under for ten game seconds with food in the bag. Its food chain went without
	 * a bite at hunger 0 with seven cooked beef in the bag for ten minutes; the buddy's own bite then. Why its chain would
	 * not eat is in the order's status (eatblock).
	 */
	static boolean starving(net.minecraft.client.player.LocalPlayer p) {
		long t = p.level().getGameTime();
		if (p.getFoodData().getFoodLevel() > 6 || foodScore() <= 0) { hungrySince = -1; return false; }
		if (hungrySince < 0) hungrySince = t;
		return t - hungrySince > 200;
	}

	/** (meal_hand_hold) the autopilot's food chain in a meal: from its first press of the bite to the last swallow. */
	static boolean eatingNow() {
		var mod = instance();
		if (mod == null || mod.getPlayer() == null) return false;
		try {
			return mod.getFoodChain().isTryingToEat();
		} catch (RuntimeException e) {
			return false;
		}
	}

	/** What keeps Soprano's food chain from eating now, for the order's status; empty when nothing does. */
	static String eatBlock() {
		var mod = instance();
		if (mod == null || mod.getPlayer() == null) return "";
		StringBuilder b = new StringBuilder();
		try {
			if (!mod.getMLGBucketChain().doneMLG()) b.append("fall clutch ");
			if (mod.getMLGBucketChain().isFallingOhNo(mod)) b.append("falling ");
			if (mod.getMobDefenseChain().creeperBoxedIn()) b.append("creeper ");
			if (mod.getMobDefenseChain().projectileDodging(mod)) b.append("arrows ");
			if (!mod.getMobDefenseChain().getCombatStance(mod).mayEat()) b.append("stance ").append(mod.getMobDefenseChain().getCombatStance(mod)).append(' ');
			if (mod.getFoodChain().needsToEat()) b.append("wants to eat ");
		} catch (RuntimeException e) {
			b.append("? ").append(e.getClass().getSimpleName());
		}
		return b.toString().trim();
	}

	/**
	 * Food points in the bag that Soprano eats to heal: rotten flesh, raw chicken, spider eyes and the like are left out.
	 * With them counted it went on at 4 hp and hunger 7 with "36 points" of rotten flesh in the bag, which Soprano only
	 * eats when starving, and a creeper finished it.
	 */
	static int foodScore() { return foodScore(false); }

	/**
	 * With cooking: raw chicken counts as what it is once cooked, for whether to gather food at all. Soprano gathers raw
	 * chicken and cooks it, and counted as nothing it was sent to gather food every morning for four days.
	 */
	static int foodScore(boolean cooking) {
		var p = net.minecraft.client.Minecraft.getInstance().player;
		if (p == null) return 0;
		int result = 0;
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			var st = inv.getItem(i);
			if (st.isEmpty()) continue;
			String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
			if (cooking && id.equals("chicken")) { result += 6 * st.getCount(); continue; }
			var food = st.get(net.minecraft.core.component.DataComponents.FOOD);
			if (food == null || NOT_HEALING.contains(id)) continue;
			result += food.nutrition() * st.getCount();
		}
		return result;
	}

	private static final java.util.Set<String> NOT_HEALING = java.util.Set.of("rotten_flesh", "chicken", "spider_eye",
		"poisonous_potato", "pufferfish", "chorus_fruit", "suspicious_stew");


	/**
	 * Food first, before the order goes on: below 12 points anywhere, below 24 under the open sky. A task of its own,
	 * which AltoClef's mob defense may cut into; as a stock of its food chain (altoMinimumFoodAllowed) it was a chain over
	 * the fight's flight and gathered hay with a crowd round it at 4 hp, and died.
	 */
	static boolean wantsFood(net.minecraft.client.player.LocalPlayer p) {
		if (!on()) return false;
		int score = foodScore(true);
		// and hurt or hungry with too little to eat now (raw chicken is food once a food step cooks it, nobody else does)
		boolean hungryNow = foodScore(false) < 12 && (p.getHealth() < 14 || p.getFoodData().getFoodLevel() < 14);
		// on the route (ObtainRoute) before its surface phase only when hungry now: the food is a piece of that phase, after
		// the wood and the stone
		if (routeBefore(p, ObtainRoute.M1_3)) return hungryNow;
		// (48 under the sky for an order into the nether, while still in the overworld: food in the nether is a hoglin in a
		// crimson forest, and a food search there among them and the piglins killed it)
		// (72 when it gathered to 96 for the nether: under 48 it went in with some fifty and ate it in a fortress; three
		// quarters of NETHER_FOOD since, the rule for every stock of the kit)
		// (on the route the deep's stock until its portal phase, where the food for the nether is topped up)
		boolean nether = kitDanger(p).ordinal() >= ObtainKit.Danger.NETHER.ordinal() && !routeBefore(p, ObtainRoute.M3_4);
		int sky = nether ? NETHER_FOOD * 3 / 4 : 24;
		// (and at the way in, wherever the frame goes up: one in a mineshaft at y 7 never saw the sky, the buddy went into the
		// nether with 19 points, turned home for food at once under blaze fire, and died to zombies in a village at night,
		// attempt 180)
		boolean open = p.level().canSeeSky(p.blockPosition().above()) || nether && atNetherPortal();
		// (on the route's deep stretch, by day: under DEEP_FOOD_FLOOR the food comes from the surface (deepFood) while some is
		// left; at 12 in the deep the food run hunted through caves at low health, and both deaths of gold smoke 2 followed)
		if (routeDeep(p) && !open && score < DEEP_FOOD_FLOOR && !Obtain.dark(p)) return true;
		return score < 12 || score < sky && open || hungryNow;
	}

	/** The bag's food points under which the route's deep stretch goes up for food by day: five steaks. */
	static final int DEEP_FOOD_FLOOR = 40;

	/** The route's deep stretch in the overworld: from the diamonds (M3.1) to the slime ball (M3.3b), before the portal's phase. */
	static boolean routeDeep(net.minecraft.client.player.LocalPlayer p) {
		return ObtainRoute.walking() && ObtainRoute.phase() >= ObtainRoute.M3_1 && ObtainRoute.phase() < ObtainRoute.M3_4
			&& p.level().dimension().equals(net.minecraft.world.level.Level.OVERWORLD);
	}

	/**
	 * The food run a food step starts: from the surface when it begins under the ground on the route's deep stretch
	 * (SurfaceFoodTask: up a staircase first, then the hunt under the sky), else where it stands. Twenty game minutes for
	 * the climb and the hunt, thirty with food_one_trip.
	 */
	static Run foodRun(net.minecraft.client.player.LocalPlayer p, double units) {
		// (thirty game minutes for food_one_trip's twice as much)
		if (routeDeep(p) && !p.level().canSeeSky(p.blockPosition().above()))
			return new Run("collect food from the surface, " + Math.round(units) + " points", (oneTrip() ? 30 : 20) * 60_000L,
				() -> night(new adris.altoclef.tasks.resources.SurfaceFoodTask(units)));
		// (and begun under the sky on the deep stretch, twenty-five instead of fifteen for food_one_trip's twice as much)
		if (routeDeep(p) && oneTrip())
			return new Run("collect food, " + Math.round(units) + " points", 25 * 60_000L,
				() -> night(new adris.altoclef.tasks.resources.CollectFoodTask(units)));
		return food(units);
	}

	/** Soprano's order is at its way into the nether: a frame from the bag, a cast, or the walk into a portal. */
	static boolean atNetherPortal() {
		if (!up) return false;
		var mod = instance();
		if (mod == null || mod.getUserTaskChain() == null) return false;
		var t = mod.getUserTaskChain().getCurrentTask();
		return t != null && t.thisOrChildSatisfies(x -> x instanceof adris.altoclef.tasks.movement.DefaultGoToDimensionTask
			|| x instanceof adris.altoclef.tasks.movement.EnterNetherPortalTask);
	}

	/** A step of the order done by a task of AltoClef's: it runs while the buddy's chain lets it, and ends with the task. */
	static final class Run extends ObtainSteps.Exec {
		private final String what;
		private final long limit;
		private final java.util.function.Supplier<Object> make;
		private long startedAt = -1;
		/** Since when the step waits for AltoClef to come up; -1 while it does not. */
		private long waitFrom = -1;
		/** The gate's shuts when the task began: one since then cancelled it. */
		private int shutsAtStart;
		private static final long WAIT_UP = 120_000;

		/** The route's piece this step is (ObtainRoute's key), or null: its end counts one of the piece's tries. */
		private String piece;
		/** The autopilot's branch mine give-ups when the task began (mine_water_roof): a new one fails a piece of the kit. */
		private int gaveUpsAtStart;

		Run(String what, long limit, java.util.function.Supplier<Object> make) { this.what = what; this.limit = limit; this.make = make; }

		Run piece(String key) { piece = key; return this; }

		boolean food() { return what.startsWith("collect food"); }

		boolean home() { return what.equals("home for food"); }

		boolean boat() { return what.equals("get 1 boat"); }

		boolean kit() { return what.startsWith("equip ") || what.startsWith("kit "); }

		@Override
		ObtainSteps.Result tick(net.minecraft.client.Minecraft mc, net.minecraft.client.player.LocalPlayer p, long now) {
			ObtainSteps.Result r = run(mc, p, now);
			// a piece of the route counts a try when its task ends by itself, done or not: one the order stops (for food, the
			// night's hole) is asked again without one
			if (piece != null && (r == ObtainSteps.Result.DONE || r == ObtainSteps.Result.FAIL)) ObtainRoute.tried(piece);
			return r;
		}

		private ObtainSteps.Result run(net.minecraft.client.Minecraft mc, net.minecraft.client.player.LocalPlayer p, long now) {
			// the gate shut since the task began (the mod off, another server, the panel's hands): it was cancelled then, and
			// begins again now, not taken for done
			if (startedAt >= 0 && shutsAtStart != shuts) {
				startedAt = -1;
				waitFrom = -1;
				Exchange.emit("alto", "action", "task again", "task", what, "why", "the autopilot was held");
			}
			if (startedAt < 0) {
				// AltoClef up first (instance()): till then the step stands, with one event to say it waits. Two game minutes
				// without it is AltoClef off for the session, and the step fails with that
				var mod = instance();
				if (mod == null) {
					if (waitFrom < 0) Exchange.emit("alto", "action", "waits", "task", what, "why", "AltoClef is not up yet");
					if (waitFrom < 0 || now < waitFrom) waitFrom = now;
					if (now - waitFrom > WAIT_UP) { giveUp("AltoClef did not come up in two minutes"); return fail("AltoClef did not come up in two minutes"); }
					Follow.stand();
					return ObtainSteps.Result.WAIT;
				}
				startedAt = now;
				shutsAtStart = shuts;
				gaveUpsAtStart = adris.altoclef.tasks.movement.BranchMineTask.gaveUps;
				Bari.yieldTo();
				HeldKeys.releaseAll();
				Object t = make.get();
				if (t == null) return fail("AltoClef has no task for " + what);
				mod.runUserTask((adris.altoclef.tasksystem.Task) t);
				delegated = true;
				Exchange.emit("alto", "action", "task", "task", what);
				return ObtainSteps.Result.RUN;
			}
			if (delegating()) {
				if (now - startedAt > limit) { cancel(); return fail("AltoClef did not " + what + " in " + limit / 60_000 + " minutes"); }
				// (mine_water_roof) the branch mine under a piece of the kit gave up its depth where it stood: the piece fails
				// and the next one goes on, rather than its whole time in the same place
				if (kit() && adris.altoclef.tasks.movement.BranchMineTask.gaveUps != gaveUpsAtStart) {
					cancel();
					return fail(adris.altoclef.tasks.movement.BranchMineTask.gaveUpLine);
				}
				return ObtainSteps.Result.RUN;
			}
			Exchange.emit("alto", "action", "task done", "task", what, "s", (now - startedAt) / 1000);
			if (food() && now - startedAt < 5000 && ++quickDone >= 2) { quickDone = 0; foodOffUntil = now + 600_000; Exchange.emit("alto", "action", "food off", "why", "done at once twice"); }
			return ObtainSteps.Result.DONE;
		}

		@Override
		void stop() { cancel(); }

		@Override
		String detail() { return "AltoClef: " + what; }
	}
}
