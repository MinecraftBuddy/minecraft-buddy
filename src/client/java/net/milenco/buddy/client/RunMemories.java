package net.milenco.buddy.client;

/**
 * The buddy's memories that outlive an obtain order (the autopilot's RunMemory holds the fork's): a name, the size, how it
 * is forgotten, and the static fields it covers (RunMemoriesGuardTest). Alto hands them to the registry at the first order;
 * with fresh_run on (the practice rig) they are forgotten at every order's start, and the measure line counts them always.
 * No autopilot type is named here, so the guard test loads it without the autopilot.
 */
final class RunMemories {
	private RunMemories() {}

	record Memory(String name, java.util.function.LongSupplier size, Runnable clear, String... fields) {}

	private static final String C = "net.milenco.buddy.client.";

	static final java.util.List<Memory> ALL = java.util.List.of(
		// the stronghold's portal room, the last open sky, the stall's and the swim's watch
		new Memory("obtain_marks", () -> Obtain.remembered(), () -> Obtain.forgetRun(), C + "Obtain#portalRoom", C + "Obtain#lastSky",
			C + "Obtain#lastSkyDim", C + "Obtain#lastPos", C + "Obtain#lastMovedAt", C + "Obtain#retryAt", C + "Obtain#scanWaitFrom",
			C + "Obtain#nextThrow", C + "Obtain#wetPos", C + "Obtain#wetMovedAt"),
		// an order left open at a disconnect (obtain.json)
		new Memory("open_order", () -> Obtain.openOrder(), () -> Obtain.forgetOpenOrder()),
		// a bed that did not take the buddy, the junk thrown away
		new Memory("bed_and_junk", () -> ObtainSafe.remembered(), () -> ObtainSafe.forgetRun(), C + "ObtainSafe#bedRefusedUntil",
			C + "ObtainSafe#THROWN"),
		// AltoClef's food task held off after two quick ends
		new Memory("food_off", () -> Alto.foodOffKept(), () -> Alto.forgetFoodOff(), C + "Alto#foodOffUntil", C + "Alto#quickDone"),
		// what failed, for up to a day
		new Memory("failures", () -> Failures.remembered(), () -> Failures.forgetRun(), C + "Failures#COUNTS", C + "Failures#BLOCKED",
			C + "Failures#STRIKES"),
		// the cells a walk got no closer to
		new Memory("bad_stands", () -> Work.badStands(), () -> Work.forgetBadStands(), C + "Work#badStand"));
}
