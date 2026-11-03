package net.milenco.buddy.client;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.stats.Stats;

/**
 * How long since the buddy last lay in a bed, against phantoms (the plan for fighting, step 1: on 23 Oct a phantom killed
 * the buddy in the base at 03:21 and phantoms struck 109 times that day). The server spawns phantoms only
 * for a player whose statistic {@code time_since_rest} is past 72000 ticks, three game days, and the chance grows past
 * that (PhantomSpawner); it is counted every tick and set to 0 the moment the player lies down in a bed (ServerPlayer.startSleeping)
 * or dies (read in 26.3). The client knows the statistic only when it asks, as the statistics screen does
 * ({@code REQUEST_STATS}): asked ten seconds after a join and every ten minutes, and counted on from the answer.
 * <p>
 * Past two days ({@link #DUE}) the buddy lies down in the base's bed at night also when nobody else sleeps (Sleepers): a
 * moment in bed sets the count to 0, and the buddy gets up after a second, long before the server's sleep plugin would skip the
 * night (it needs a hundred ticks). Past two and a half days outside a base it is a need on the panel and in Telegram.
 */
final class Rest {
	private Rest() {}

	static final int DUE = 48_000, WARN = 60_000;
	private static final long ASK_EVERY = 10 * 60_000L, FIRST_ASK = 10_000L, ANSWER = 1_500L;

	private static int value = -1;
	private static long readAt, askedAt, joinedAt, sleptAt;
	private static boolean pending, told;
	/** {"cmd":"rest","test":N}: as if the server had said N, for the test server. */
	private static int test = -1;

	/** The connection of the last tick: a new one is a new join (a respawn keeps it, a new player object does not). */
	private static Object connection;

	/** Every tick of the alt: ask, read the answer, and 0 the moment the alt lies in a bed or is dead. */
	static void tick(LocalPlayer p) {
		long now = System.currentTimeMillis();
		if (p.connection != connection) { connection = p.connection; joinedAt = now; askedAt = 0; pending = test >= 0; value = -1; }
		if (p.isSleeping() || !p.isAlive()) {
			if (p.isSleeping() && sleptAt == 0) sleptAt = now;
			if (value != 0) { value = 0; readAt = now; told = false; }
			return;
		}
		sleptAt = 0;
		if (pending && now - askedAt >= ANSWER) {
			pending = false;
			int v = test >= 0 ? test : p.getStats().getValue(Stats.CUSTOM, Stats.TIME_SINCE_REST);
			test = -1;
			value = v;
			readAt = now;
			Exchange.emit("base", "action", "phantom count", "ticks", v);
		}
		if (!pending && now - joinedAt >= FIRST_ASK && (askedAt == 0 || now - askedAt >= ASK_EVERY)) {
			askedAt = now;
			pending = true;
			if (test < 0) p.connection.send(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.REQUEST_STATS));
		}
		if (due() && !told) { told = true; Exchange.emit("base", "action", "must sleep", "ticks", estimate()); }
	}

	/** Ticks since the last lie in a bed, counted on from the server's last answer; -1 before the first. */
	static int estimate() {
		if (value < 0) return -1;
		return (int) Math.min(Integer.MAX_VALUE, value + (System.currentTimeMillis() - readAt) / 50);
	}

	/** Two game days without a bed: time to lie down, at night, wherever the base's bed is. */
	static boolean due() { return estimate() >= DUE; }

	/**
	 * Whether to go to bed, or stay in it, for the count alone (Sleepers, with baseSleepOthers on): out of bed when it
	 * is due, and in bed for a second, by which time the server has set it to 0.
	 */
	static boolean wantsBed(LocalPlayer p) {
		if (p.isSleeping()) return sleptAt != 0 && System.currentTimeMillis() - sleptAt < 1000;
		return due();
	}

	/** The test server: as if the server answered this count now. */
	static void test(int ticks) {
		test = ticks;
		askedAt = System.currentTimeMillis() - ANSWER;
		pending = true;
	}
}
