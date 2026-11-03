package net.milenco.buddy.client;

/**
 * Which step of the buddy's chain has the hands this tick, by name (21 Oct 2026: the first point of the look at other bots,
 * Player2's chains and Botcraft's tree, which show what runs and why). The chain itself is the order of
 * {@link Follow#tick}: staying alive (air, fire, getting loose), the menus open, a fight, the corpse guard, a tap,
 * the carrier, the base, sleeping, riding, the work along, and following. The first that wants the tick takes it, and
 * whatever was going on carries on as soon as nothing above it wants the tick any more. Here that order gets names:
 * {@link #doing()} for the panel, and the event {@code doing} once a step has held for a second.
 */
final class Arbiter {
	private Arbiter() {}

	private static String now = "idle", shown = "", candidate = "";
	private static boolean seen;
	private static int stableTicks;

	/** This step took the tick (call it when a step returns that it moved the buddy). */
	static boolean at(String step, boolean took) {
		if (took) { now = step; seen = true; }
		// any step of the chain before the order's own (a fight, a flight, a meal, getting loose) takes the keys: Baritone,
		// walking or mining for the order, stops at once (Bari)
		if (took && !step.equals("obtain") && !step.equals("alto")) Bari.stop();
		return took;
	}

	/** A job outside the chain (a farm, the builder, a flight) that steers, when no step of the chain took the tick. */
	static void job(String name) { if (!seen) { now = name; seen = true; } }

	/** The end of the tick: nothing claimed it is idle; a change is told once it held for 20 ticks. */
	static void endTick() {
		if (!seen) now = "idle";
		seen = false;
		// Soprano keeping them alive is part of the order: told as the order, or the Activity tab read "Now: alto" and
		// "Now: fetching an order" in turns near every monster
		String told = now.equals("alto") && (shown.equals("obtain") || candidate.equals("obtain")) ? "obtain" : now;
		if (!told.equals(candidate)) { candidate = told; stableTicks = 0; return; }
		if (++stableTicks == 20 && !candidate.equals(shown)) {
			shown = candidate;
			Exchange.emit("doing", "action", shown);
		}
	}

	/** What the buddy does now, for the panel. */
	static String doing() { return now; }
}
