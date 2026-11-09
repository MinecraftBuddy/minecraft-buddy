package net.milenco.buddy.client;

import net.minecraft.client.Minecraft;

/**
 * The client's own tick time, for the practice rig: how long each client tick takes (the whole of Minecraft.tick, the
 * autopilot's work and the path searches it waits on included), how many ticks a second the client manages and its frames a
 * second. Every 20 ticks one event client_tick: the mean and the longest tick of those 20 in ms, ticks a second over the
 * wall time they took, and the frames a second. Written only when the autopilot's tick_log switch is on (the rig sets it);
 * a real buddy writes none. Read on the rig to tell a slow machine from slow work (the boxes against cascade).
 */
public final class ClientTicks {
	private static final int EVERY = 20;
	private static long tickStart;
	private static long sumNanos;
	private static long maxNanos;
	private static int ticks;
	private static long windowStart;

	private ClientTicks() { }

	/** Minecraft.tick begins. */
	public static void begin() {
		tickStart = System.nanoTime();
		if (windowStart == 0) windowStart = tickStart;
	}

	/** Minecraft.tick ends: counted, and every 20 ticks written when the switch is on. */
	public static void end() {
		if (tickStart == 0) return;
		long now = System.nanoTime();
		long took = now - tickStart;
		tickStart = 0;
		sumNanos += took;
		maxNanos = Math.max(maxNanos, took);
		if (++ticks < EVERY) return;
		double wall = (now - windowStart) / 1e9;
		double mean = sumNanos / 1e6 / ticks, max = maxNanos / 1e6;
		int n = ticks;
		ticks = 0;
		sumNanos = 0;
		maxNanos = 0;
		windowStart = now;
		if (!Alto.tickLog()) return;
		Exchange.emit("client_tick", "ms", round(mean), "max", round(max), "tps", round(wall > 0 ? n / wall : 0),
			"fps", Minecraft.getInstance().getFps());
	}

	private static double round(double v) { return Math.round(v * 100) / 100.0; }
}
