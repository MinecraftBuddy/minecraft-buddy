package net.milenco.buddy.client;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Baritone (in its Soprano fork, LGPL-3.0, github.com/AverWasTaken/soprano) walks for the obtain mode when it is in the
 * mods folder: paths, digging through, building up, bridging and falls into water, where the buddy's own walker
 * stumbled. So the moving is Baritone's and the planner and the survival on top stay the buddy's. Without it everything
 * walks as before. The rest of the buddy (following, the base, AFK) never goes through here.
 *
 * <p>Only this class names Baritone's classes, and only after {@link #present()} said it is loaded, so the mod runs
 * without it. A walk asked for is held while it is asked for every tick; a tick without the ask (a fight, a shelter,
 * eating) and Baritone is stopped, so the two never press the keys at once.
 */
final class Bari {
	private Bari() {}

	private static Boolean present;
	private static boolean configured;
	private static BlockPos goal;
	private static int goalRange;
	private static long askedTick = -10, tick;
	private static baritone.api.pathing.goals.Goal ours;

	/** Baritone is loaded. */
	static boolean present() {
		if (present == null) present = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("baritone");
		return present;
	}

	/** Baritone walks for an order: loaded, and not switched off in the settings. */
	static boolean on() { return present() && AfkConfig.get().obtainBaritone; }

	/**
	 * The settings for a hardcore world on a server with an anti-xray plugin: an ore only where it is seen (Soprano's
	 * patched block tracker follows the same setting), no jump over a gap, no fall over three without water, around
	 * monsters, and no commands of its own from the chat.
	 */
	private static void configure() {
		if (configured) return;
		configured = true;
		var s = baritone.api.BaritoneAPI.getSettings();
		s.legitMine.value = true;
		s.allowBreak.value = true;
		s.allowPlace.value = true;
		s.allowParkour.value = false;
		s.allowParkourPlace.value = false;
		s.maxFallHeightNoWater.value = 3;
		s.avoidance.value = true;
		s.chatControl.value = false;
		s.antiCheatCompatibility.value = true;
		s.disconnectOnArrival.value = false;
		// looking where it walks (Alto, the same for an order)
		s.freeLook.value = false;
		Exchange.emit("baritone", "action", "configured");
	}

	/** What AltoClef's settings scope changes when its runner comes up, put back for the buddy's own mine. */
	private static void afterAlto() {
		var s = baritone.api.BaritoneAPI.getSettings();
		if (!s.mineScanDroppedItems.value) s.mineScanDroppedItems.value = true;
	}

	private static baritone.api.IBaritone baritone() { return baritone.api.BaritoneAPI.getProvider().getPrimaryBaritone(); }

	/** Walk to within {@code range} of this place; asked again every tick it is still wanted. */
	static void goTo(Vec3 to, int range) {
		configure();
		BlockPos at = BlockPos.containing(to);
		askedTick = tick;
		if (at.equals(goal) && range == goalRange && busy()) return;
		// the buddy's own walker lets go of its keys first: Baritone presses its own
		if (goal == null && mining == null) HeldKeys.releaseAll();
		mining = null;
		goal = at;
		goalRange = range;
		ours = new baritone.api.pathing.goals.GoalNear(at, range);
		baritone().getCustomGoalProcess().setGoalAndPath(ours);
	}

	private static String mining;

	/**
	 * Mine these blocks, searching at height {@code y} for ones it can see (legitMine: an ore counts once something opens it
	 * up, which an anti-xray plugin needs): Baritone's own mine, which walks a tunnel at that height, takes what it opens and
	 * picks up what falls. The step counts the items and lets go when it has enough. False when the mine stopped by itself.
	 */
	static boolean mine(java.util.Collection<String> blocks, int y) {
		configure();
		askedTick = tick;
		afterAlto();
		String key = String.join(",", new java.util.TreeSet<>(blocks)) + "@" + y;
		if (key.equals(mining)) return baritone().getMineProcess().isActive();
		if (goal == null && mining == null) HeldKeys.releaseAll();
		goal = null;
		mining = key;
		baritone.api.BaritoneAPI.getSettings().legitMineYLevel.value = y;
		baritone().getMineProcess().mineByName(0, blocks.toArray(new String[0]));
		Exchange.emit("baritone", "action", "mines", "blocks", key);
		return true;
	}

	/** Baritone mines for a step now. */
	static boolean mining() { return mining != null; }

	/** Baritone has a path, works one out, or mines. */
	static boolean busy() {
		if (!present() || goal == null && mining == null) return false;
		var pb = baritone().getPathingBehavior();
		return pb.isPathing() || pb.getInProgress().isPresent() || baritone().getCustomGoalProcess().isActive() || baritone().getMineProcess().isActive();
	}

	/**
	 * Standing still for a click in the inventory, asked at the start of every tick it is wanted: Baritone holds its walk
	 * for it the way it does for its own inventory moves (inventoryMoveOnlyIfStationary), and says yes once the buddy has
	 * stood still for two ticks.
	 */
	static boolean stillForClick() {
		return ((baritone.Baritone) baritone()).getInventoryPauserProcess().stationaryForInventoryMove();
	}

	/** Once a tick, after the jobs: a walk no longer asked for is stopped. */
	static void tick() {
		tick++;
		// a walk not asked for in a tick stops; a mine has a second, for a step can pass a tick without asking (the chain's
		// own steps stop it at once through Arbiter)
		if (goal != null && tick - askedTick > 1 || mining != null && tick - askedTick > 20) stop();
	}

	static void stop() {
		if (goal == null && mining == null || !present()) return;
		goal = null;
		mining = null;
		baritone().getPathingBehavior().cancelEverything();
	}

	/**
	 * AltoClef takes the wheel ({@link Alto}): the buddy's own walk and mine let go without cancelling everything, for
	 * AltoClef has set its own path in this tick already (its tick comes first).
	 */
	static void yieldTo() {
		if (goal == null && mining == null || !present()) return;
		var b = baritone();
		if (mining != null) b.getMineProcess().cancel();
		if (goal != null && b.getCustomGoalProcess().getGoal() == ours) b.getCustomGoalProcess().onLostControl();
		goal = null;
		mining = null;
	}
}
