package net.milenco.buddy.client;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Watches for signs that someone is testing whether you are still behind your keyboard: being put in
 * a boat, blocks around you, water over you, a push. Helpers often do that invisibly, so you do not
 * see them coming - what you do notice is what they do to you.
 *
 * Until now the farm hit straight through all that: a human who suddenly sits in a boat stops and says
 * something about it, the mod kept hitting blazes. That is the moment you get caught. Now the farm goes
 * off and an alarm goes to the second screen, so the owner can take over by hand.
 */
public class Watchdog {
	private static long lastAlarm = 0;
	private static long nextDrops = 0, lastDropReport = 0;
	private static int dropsAbove = 0;
	private static BlockPos prevBlock = null;
	private static int prevWalls = -1;
	private static long nextMeasure = 0;
	private static boolean wasInVehicle = false, wasWet = false;

	private static long graceUntil = 0;

	/**
	 * Just logged in: the chunks around you arrive over the first seconds, and every block that appears beside you
	 * looked like a block put against you. After a restart the buddy reported "built against" with nobody near.
	 */
	public static void joined() { graceUntil = System.currentTimeMillis() + 20_000; reset(); }

	public static void reset() {
		prevBlock = null; prevWalls = -1; wasInVehicle = false; wasWet = false;
	}

	/** Blocks enclosing you at head height; only the increase counts, so a cramped cubicle is no alarm. */
	private static int walls(LocalPlayer p, BlockPos block) {
		BlockPos head = block.above();
		int n = 0;
		for (Direction d : Direction.Plane.HORIZONTAL) if (counts(p, head.relative(d))) n++;
		if (counts(p, head.above())) n++;
		return n;
	}

	/**
	 * A tree growing next to you is not a helper walling you in. While woodcutting, logs and leaves
	 * therefore do not count; otherwise the farm paused as soon as a sapling shot up into a tree (26 Sep).
	 */
	private static boolean counts(LocalPlayer p, BlockPos pos) {
		var st = p.level().getBlockState(pos);
		if (st.isAir()) return false;
		if (Lumberjack.isOn() && (st.is(net.minecraft.tags.BlockTags.LOGS) || st.is(net.minecraft.tags.BlockTags.LEAVES))) return false;
		if (Builder.fromSchematic(pos, st)) return false;   // your own wall going up around you
		/*
		 * A crop is no wall. On farmland you stand a little lower than a whole block, so "head height" here is the
		 * height the crops grow at: every seed planted beside the buddy counted as a block against the buddy, and the second
		 * one within ten minutes switched the wheat field off while the owner stood planting right beside (3 Oct).
		 */
		if (st.getBlock() instanceof net.minecraft.world.level.block.CropBlock) return false;
		return true;
	}

	public static void tick(LocalPlayer p, AfkConfig c) {
		if (p == null || !AfkHelperClient.active() || !c.watchdogAlarm) return;
		if (!Jobs.unattendedSteering() && !c.afk) { reset(); return; }
		long now = System.currentTimeMillis();

		// put in a boat or minecart: that does not just happen to you while you stand farming
		if (p.isPassenger()) {
			if (!wasInVehicle) { wasInVehicle = true; String v = vehicle(p); alarm("put in a " + v, p); }
		} else wasInVehicle = false;

		// water or a cobweb over you is the classic test
		boolean wet = p.isInWater() || p.level().getBlockState(p.blockPosition()).is(Blocks.COBWEB);
		if (wet) {
			// water the builder just poured itself is no test
			if (!wasWet) { wasWet = true; if (!(p.isInWater() && Builder.ownWater(p))) alarm(p.isInWater() ? "suddenly standing in water" : "standing in a cobweb", p); }
		} else wasWet = false;

		// The recovery pause only makes sense once the situation is over: if you are still in that boat or
		// still in the water, the farm stays idle instead of starting to hit from a boat after three minutes.
		if (Grinder.active() && (p.isPassenger() || wet)) Grinder.interrupt(30_000, null, "situation not restored yet");

		drops(p, c, now);

		// being walled in: only compare while you stand on the same block, otherwise every nudge counts
		if (now >= nextMeasure) {
			nextMeasure = now + 1000;
			BlockPos block = p.blockPosition();
			int m = walls(p, block);
			if (block.equals(prevBlock) && prevWalls >= 0 && m > prevWalls && now >= graceUntil) alarm("being walled in", p);
			prevBlock = block; prevWalls = m;
		}
	}

	/**
	 * When the collection chests fill up, the drops stay lying around: you farm for nothing, the place
	 * starts lagging and after a clean-up you have lost everything. Counting goes per item and not per
	 * entity, because items that fall next to each other merge into stacks of 64: counting entities would
	 * only trigger at thousands of rods. Three measurements in a row above the limit (about fifteen
	 * seconds) before it trips, so a busy minute gives no false alarm. No recovery pause here: a full
	 * chest does not empty by itself, so this waits for the owner.
	 */
	private static void drops(LocalPlayer p, AfkConfig c, long now) {
		if (c.farmDropsMax <= 0 || !Grinder.active() || now < nextDrops) return;
		nextDrops = now + 5000;
		var list = p.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, p.getBoundingBox().inflate(16));
		int items = 0;
		for (var e : list) items += e.getItem().getCount();
		// to be able to calibrate the limit: record now and then what normally lies around at the farm
		if (items > c.farmDropsMax / 4 && now - lastDropReport > 60_000) {
			lastDropReport = now;
			Exchange.emit("drops", "items", items, "stacks", list.size());
		}
		if (items < c.farmDropsMax) { dropsAbove = 0; return; }
		if (++dropsAbove < 3) return;
		dropsAbove = 0;
		String what = items + " items in " + list.size() + " stacks left lying around";
		Grinder.stop("drops left lying");
		Exchange.emit("watchdog", "what", what, "player", "", "farm", true);
	}

	/** Grinder reports a displacement that did not come from the mod itself. */
	public static void pushed(LocalPlayer p, double blocks) {
		if (!AfkConfig.get().watchdogAlarm) return;
		double rounded = Math.round(blocks * 10) / 10.0;
		alarm("pushed away (" + rounded + " blocks)", p);
	}

	private static String vehicle(LocalPlayer p) {
		var v = p.getVehicle();
		return v == null ? "vehicle" : v.getType().getDescription().getString();
	}

	/** The nearest player you can see, as {name, distance}; with staff in vanish that stays empty. */
	private static Object[] who(LocalPlayer p) {
		Player best = null; double bestD = 24 * 24;
		for (Player o : p.level().players()) {
			if (o == p) continue;
			double d = o.distanceToSqr(p);
			if (d < bestD) { bestD = d; best = o; }
		}
		return best == null ? null : new Object[] { best.getGameProfile().name(), Math.round(Math.sqrt(bestD)) };
	}

	/**
	 * A single signal can also be bad luck (a fireball, a friend walking past you). Hence first a
	 * recovery pause: the farm stands still, you get the message, and if nothing else happens it
	 * carries on by itself. If a second signal comes within ten minutes, it is no coincidence and
	 * the farm really goes off until whoever looks after the buddy has a look.
	 */
	private static void alarm(String what, LocalPlayer p) {
		long now = System.currentTimeMillis();
		if (now - lastAlarm < 30_000) return;   // ringing the bell once is enough
		boolean repeat = lastAlarm > 0 && now - lastAlarm < 10 * 60_000L;
		lastAlarm = now;
		Object[] who = who(p);
		String whoCode = who == null ? null : who[0] + " at " + who[1] + "m";
		boolean busy = Jobs.unattendedSteering();
		int min = AfkConfig.get().watchdogPauseMin;
		String tail;
		if (busy && min > 0 && !repeat) {
			long ms = min * 60_000L + ThreadLocalRandom.current().nextLong(0, 45_000);
			Grinder.interrupt(ms, null, "watchdog");
			Lumberjack.pause(ms, "watchdog");
			Builder.pause(ms, "watchdog");
			WheatFarm.pause(ms, "watchdog");
			tail = " - farm idle for " + min + " min, then continues by itself";
		} else if (busy) {
			Grinder.stop("watchdog");
			Lumberjack.stop("watchdog");
			Builder.stop("watchdog");
			WheatFarm.stop("watchdog");
			tail = repeat ? " - second time: farm off" : " - farm off";
		} else tail = "";
		Exchange.emit("watchdog", "what", what, "player", whoCode == null ? "" : whoCode, "farm", busy, "action", tail.isEmpty() ? "notice" : tail.trim());
	}
}
