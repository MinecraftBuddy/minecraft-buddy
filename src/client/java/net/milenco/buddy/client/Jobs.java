package net.milenco.buddy.client;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * The jobs that put their hands on the player's keys, in the order they tick. The one place that knows them all:
 * whether one is on, whether one has its hands on the keys right now, which one switches the others off, and stopping
 * them together. The rest of the mod asks here instead of naming the jobs one by one, so a new job gets its line in
 * {@link Job} and is known everywhere at once.
 */
final class Jobs {
	private Jobs() {}

	/** One move of a job in the tick; true when it moved the player itself (which is not you being there). */
	@FunctionalInterface
	interface Tick { boolean tick(Minecraft mc, LocalPlayer p, AfkConfig c); }

	private static final Tick NO_TICK = (mc, p, c) -> false;

	enum Job {
		/**
		 * The alt's water bucket against a fire on the alt and a fall that would hurt (alt only). First: what it does takes a
		 * tick, and from the pour to the scoop the others wait, the following too.
		 */
		WATER(false, Water::busy, Water::busy, Water::stop, Water::startTick, Water::tick),
		MOB_FARM(true, Grinder::active, Grinder::active, Grinder::stop, Grinder::startTick, (mc, p, c) -> Grinder.tick(p, c)),
		WOOD(true, Lumberjack::isOn, Lumberjack::isBusy, Lumberjack::stop, Lumberjack::startTick, (mc, p, c) -> Lumberjack.tick(p, c)),
		/** Clicks what your crosshair is on: it works with you at the keys. */
		CROP(false, CropFarmer::isOn, CropFarmer::isOn, CropFarmer::stop, CropFarmer::startTick, NO_TICK),
		WHEAT(true, WheatFarm::isOn, WheatFarm::isOn, WheatFarm::stop, WheatFarm::startTick, WheatFarm::tick),
		BUILD(true, Builder::isOn, Builder::isBusy, Builder::stop, Builder::startTick, (mc, p, c) -> Builder.tick(p, c)),
		/**
		 * Flying with an elytra, after the followed player or to a point. Before the following: while it flies, the
		 * following pauses, and a stop is a landing, never letting go of the keys in the air.
		 */
		GLIDE(false, Glide::active, Glide::airborne, Glide::stop, Glide::startTick, Glide::tick),
		/**
		 * An order of items (Obtain): it runs in Follow's chain, after what keeps the buddy alive there, so its tick is
		 * that chain's; here it is a job so that mod off, the panic and a farm switched on stop it too.
		 */
		OBTAIN(false, Obtain::on, Obtain::steering, Obtain::stop, mc -> {}, NO_TICK),
		/**
		 * The alt with the followed player. It gives way: a farm that is on pauses it and it carries on afterwards,
		 * and switching a farm on does not switch it off (whom the alt follows changes only by "follow").
		 */
		FOLLOW(false, Follow::isOn, Follow::walking, Follow::stop, Follow::startTick, Follow::tick);

		/** Works without the player at the keys: the watchdog and the panic look after it. */
		final boolean unattended;
		private final BooleanSupplier on, steering;
		private final Consumer<String> stop;
		private final Consumer<Minecraft> startTick;
		private final Tick tick;

		Job(boolean unattended, BooleanSupplier on, BooleanSupplier steering, Consumer<String> stop, Consumer<Minecraft> startTick, Tick tick) {
			this.unattended = unattended;
			this.on = on;
			this.steering = steering;
			this.stop = stop;
			this.startTick = startTick;
			this.tick = tick;
		}

		/** Switched on, also while it pauses or waits. */
		boolean on() { return on.getAsBoolean(); }

		/** Its hands on the keys now: looking around, wandering, crouching and auto-walk keep off them. */
		boolean steering() { return steering.getAsBoolean(); }

		/**
		 * Off, with the reason its own stop knows (some reasons let it come back after a teleport). Not only when it is
		 * on: a build or a wheat field that waits to carry on after a teleport is switched off by its own stop as well.
		 */
		void stop(String why) { stop.accept(why); }
	}

	private static final Job[] ALL = Job.values();
	private static boolean panelHeld;

	private static boolean in(Job j, Job[] list) {
		for (Job x : list) if (x == j) return true;
		return false;
	}

	/** A job other than these is switched on. */
	static boolean anyOn(Job... except) {
		for (Job j : ALL) if (!in(j, except) && j.on()) return true;
		return false;
	}

	/** A job other than these has its hands on the keys right now. */
	static boolean anySteering(Job... except) {
		for (Job j : ALL) if (!in(j, except) && j.steering()) return true;
		return false;
	}

	/** A job that works without the player is switched on. */
	static boolean unattendedOn() {
		for (Job j : ALL) if (j.unattended && j.on()) return true;
		return false;
	}

	/** A job that works without the player has its hands on the keys right now. */
	static boolean unattendedSteering() {
		for (Job j : ALL) if (j.unattended && j.steering()) return true;
		return false;
	}

	/** Another job is on that this one gives way to: every one but itself and the following, which gives way itself. */
	static boolean othersOn(Job self) { return anyOn(self, Job.FOLLOW); }

	/**
	 * This job is switched on: the others go off, the following excepted, which pauses while a farm runs. A pen being
	 * farmed goes off too (Pen, no job of its own): the newest instruction wins.
	 */
	static void switchedOn(Job self, String why) { Pen.stop("another farm"); stop(why, self, Job.FOLLOW); }

	/** Every job off but these, and the pen with them (mod off, the panic). */
	static void stop(String why, Job... keep) {
		Pen.stop(why);
		for (Job j : ALL) if (!in(j, keep)) j.stop(why);
	}

	/** The start of the tick, where vanilla clicks and hits: each job in its turn. */
	static void startTick(Minecraft mc) {
		Steer.startTick(mc);
		if (Steer.active() || Control.panel()) return;
		for (Job j : ALL) j.startTick.accept(mc);
	}

	/**
	 * The autopilot's gate, every tick before anything returns early (no world, the mod off, the panel's hands): AltoClef
	 * and Baritone act for the buddy only with the mod on, on a server it may play on, and while the panel neither steers
	 * nor holds the inventory. Shut, AltoClef's runner goes down (its own chains and the order's task with it) and the
	 * buddy's Baritone walk stops; open again, the order carries on where it is. An order on is AltoClef's own condition.
	 */
	static void autopilot(boolean open) {
		Alto.gate(open);
		if (!open && AfkHelperClient.mc().player != null) Bari.stop();
	}

	/** The tick: every job moves in its turn, also when another one already moved the player. */
	static boolean tick(Minecraft mc, LocalPlayer p, AfkConfig c) {
		boolean steers = Steer.tick(mc, p);
		autopilot(!steers && !Control.panel() && AfkHelperClient.active());
		// the web panel's hands on the keys: no job moves the buddy meanwhile, and they carry on when it lets go
		if (steers) { Arbiter.at("panel steers", true); Arbiter.endTick(); return true; }
		// the panel holds the buddy's inventory (an item moved on the page): the jobs wait, with their keys let go once
		if (Control.panel()) {
			if (!panelHeld) { panelHeld = true; HeldKeys.releaseAll(); }
			Arbiter.at("panel", true);
			Arbiter.endTick();
			return true;
		}
		panelHeld = false;
		Alto.tick();
		boolean moved = false;
		for (Job j : ALL) moved |= j.tick.tick(mc, p, c);
		// last, over whatever the jobs pressed: on an order, crouched where the ground ahead drops away (ObtainSafe.brink)
		// a walk of Baritone's not asked for this tick is stopped; while it walks, its own rules keep it off a brink
		if (Bari.present()) Bari.tick();
		if (Obtain.on() && !Bari.busy() && !Alto.delegating()) ObtainSafe.brink(mc, p);
		for (Job j : ALL) if (j != Job.FOLLOW && j.steering()) Arbiter.job(j.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' '));
		Arbiter.endTick();
		return moved;
	}
}
