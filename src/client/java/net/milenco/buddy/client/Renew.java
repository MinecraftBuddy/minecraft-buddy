package net.milenco.buddy.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * A trip home for a new tool while following (after "Axe almost broken, 10 uses left, and no spare in the chests"
 * while the base held iron enough for a new one: make one ahead and carry it, but use the old one up first). In the
 * base {@link Chores} renews a worn tool by itself
 * ({@code baseRenew}); away from it the buddy never came to that. So: when every tool of a kind carried is nearly worn out
 * ({@link Work#lowWear}) and a chest of the base holds a fresh one, or the base holds iron enough with {@code keepIron}
 * left ({@link Chores#renewKind}), the buddy goes home as the carrier does (the profile's home command, or on foot when the base
 * is near), where {@link Stash} fetches the fresh one, or the iron for {@link Chores} to make one at the table, and then
 * the following takes the buddy back. The new one is carried and the old one worked with until used up ({@link Work#toolFor} takes the
 * most worn of equally fast tools).
 */
final class Renew {
	private Renew() {}

	static final long LOOK_EVERY = 5000, HOME_WAIT = 15_000, MAKE_MAX = 120_000, RETRY = 10 * 60_000;

	private static final int IDLE = 0, HOME = 1, WALK = 2, MAKE = 3;
	private static int step = IDLE;
	private static String kind;
	private static long stepSince, nextLook, notBefore;

	static boolean busy() { return step != IDLE; }

	/** This kind can be renewed by a trip home: the need for it waits (Work's "almost broken"). */
	static boolean handles(LocalPlayer p, String k) {
		if (k.equals(kind) && step != IDLE) return true;
		return AfkConfig.get().baseRenew && k.equals(Chores.renewKind(p)) && base(p) != null;
	}

	/** True while it has the keys: on its way home or making the tool there. */
	static boolean tick(Minecraft mc, LocalPlayer p) {
		long now = System.currentTimeMillis();
		BlockPos bed = base(p);
		switch (step) {
			case IDLE -> {
				if (now < nextLook || now < notBefore || !AfkConfig.get().baseRenew || bed == null) return false;
				nextLook = now + LOOK_EVERY;
				String k = Chores.renewKind(p);
				if (k == null) return false;
				double far = p.position().distanceTo(Vec3.atCenterOf(bed));
				// in the base already: Chores makes it there, as it always did
				if (far <= Base.RADIUS) return false;
				kind = k;
				String home = Profiles.cmd("home", null);
				if (home != null) {
					Follow.stand();
					AfkHelperClient.sendTyped(home, null);
					start(HOME, now, "home");
					return true;
				}
				if (far <= Carrier.WALK_MAX) { start(WALK, now, "walks home"); return true; }
				kind = null;
				return false;
			}
			case HOME -> {
				Follow.stand();
				if (bed != null && p.position().distanceTo(Vec3.atCenterOf(bed)) <= Base.RADIUS) { start(MAKE, now, "at home"); return true; }
				if (now - stepSince > HOME_WAIT) { giveUp("home failed", now); return false; }
				return true;
			}
			case WALK -> {
				if (bed == null) { giveUp("no base", now); return false; }
				if (p.position().distanceTo(Vec3.atCenterOf(bed)) <= Base.RADIUS / 2) { start(MAKE, now, "at home"); return true; }
				if (!Work.approach(mc, p, "renew-bed", Vec3.atBottomCenterOf(bed), now) || now - stepSince > 120_000) { giveUp("cannot get there", now); return false; }
				return true;
			}
			case MAKE -> {
				// made: nothing carried is worn out any more that a fresh one or the iron could replace; every worn kind in
				// one trip (a pickaxe and an axe both worn took two trips, a minute apart, in the first test)
				String still = Chores.renewKind(p);
				if (still == null) { done("made", now); return false; }
				if (!still.equals(kind)) { kind = still; Exchange.emit("renew", "action", "next", "tool", kind); }
				if (now - stepSince > MAKE_MAX) { giveUp("not made in time", now); return false; }
				// the iron and the sticks out of the chests (Stash knows what a chore wants), then the table (Chores)
				if (Stash.tick(mc, p, p)) return true;
				if (Chores.tick(mc, p, p)) return true;
				Follow.stand();
				return true;
			}
			default -> { step = IDLE; return false; }
		}
	}

	/** The bed of the base of the player worked for, in this dimension. */
	private static BlockPos base(LocalPlayer p) {
		String boss = Follow.boss();
		return boss == null ? null : Tap.base(boss, Work.dimOf(p.level()));
	}

	private static void start(int s, long now, String why) {
		step = s;
		stepSince = now;
		Exchange.emit("renew", "action", why, "tool", kind);
	}

	private static void giveUp(String why, long now) {
		Exchange.emit("renew", "action", "fails", "reason", why, "tool", kind);
		step = IDLE;
		kind = null;
		notBefore = now + RETRY;
	}

	private static void done(String why, long now) {
		Exchange.emit("renew", "action", "done", "reason", why, "tool", kind);
		step = IDLE;
		kind = null;
		notBefore = now + 60_000;
	}

	static void stop(String why) { if (step != IDLE) { Exchange.emit("renew", "action", "stopped", "reason", why, "tool", kind); step = IDLE; kind = null; } }
}
