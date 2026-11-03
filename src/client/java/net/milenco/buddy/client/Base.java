package net.milenco.buddy.client;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

/**
 * The base: the bed the player tapped ({@link Tap}) and what stands around it. A base can grow over time through
 * chests that both the owner and the buddy can open, and a buddy left in the base without following keeps it (the
 * furnaces, the fields).
 *
 * <p>Keeping the base: the alt stands within {@link #RADIUS} of a base's bed on this server and does not follow (follow
 * off, or told to stay): then the alt works for the base's owner as if that player stood by: the fields that are on, the
 * furnaces, the chests (store what the alt carries, fetch what is lacking). Between jobs, back to the bed. Growing: a
 * chest or barrel within the base that the alt does not know yet is tried (Stash looks inside); one that does not open
 * for the alt is forgotten again, so the base is the chests both of them can open.
 */
final class Base {
	private Base() {}

	static final double RADIUS = 48, HOME_NEAR = 6;
	static final long SCAN_EVERY = 30_000;

	private static long nextScan, nextHome;
	private static BlockPos bedStand;
	private static String keeping;

	/** The owner of the base kept now, or null when none is kept. */
	static String keeper() { return keeping; }

	/** The owner of the base this spot lies in, on this server, or null. */
	static String ownerAt(Level level, Vec3 at) {
		var e = Tap.baseAt(Work.dimOf(level), at, RADIUS);
		return e == null ? null : e.owner();
	}

	/**
	 * Every tick of the follow job: keeping a base or not. In a base, keeping it or following in it, it looks for
	 * new chests now and then.
	 */
	static boolean wants(LocalPlayer p, boolean following, boolean staying) {
		String here = AfkHelperClient.active() ? ownerAt(p.level(), p.position()) : null;
		long now = System.currentTimeMillis();
		if (here != null && now >= nextScan && p.containerMenu == p.inventoryMenu) { nextScan = now + SCAN_EVERY; grow(p.level(), p); }
		String owner = !following || staying ? here : null;
		if (owner == null || (following && staying && !owner.equalsIgnoreCase(Follow.target()))) owner = null;
		if (owner == null ? keeping != null : !owner.equalsIgnoreCase(keeping)) {
			Exchange.emit("base", "action", owner == null ? "keeping off" : "keeping on", "for", owner == null ? keeping : owner);
			if (owner == null && !Carrier.busy()) Stash.forceStore(false);
			keeping = owner;
			bedStand = null;
			// a teleport out of the base: the chore under way stays there (it froze, and went on as a stale walk on the
			// return; the review of 22 Oct 2026)
			if (owner == null) Chores.leftBase(p);
		}
		return keeping != null;
	}

	/** The base's work: the alt as the anchor for Work, Stash and the furnaces; then back to the bed. */
	static boolean tick(Minecraft mc, LocalPlayer p) {
		long now = System.currentTimeMillis();
		Level level = p.level();
		// In bed until the morning or until a fight wakes the buddy: no work. A job that walked the buddy out of bed got
		// nowhere and gave its field up for ten minutes, while from outside the buddy stood behind the door. The bed's own
		// step runs while lying there: it clears the click
		// that laid the buddy down (left set, every waking was "bed refuses" and the bed shunned a minute) and ends the sleep when
		// nobody else sleeps any more (Sleepers), which in the base never ran (22 Oct 2026).
		var home = Tap.baseAt(Work.dimOf(level), p.position(), RADIUS);
		if (p.isSleeping()) { Follow.release(); if (home != null) Follow.sleepInBase(mc, p, home.bed()); return true; }
		// a bite to eat: the chores wait the second and a half, or a tool taken in hand or a click breaks it off (the buddy eats
		// in the base since the review of 22 Oct 2026)
		if (AfkHelperClient.isEating()) { Follow.stand(); return true; }
		// in the base at night: to bed until the morning, also with the followed player nearby
		if (home != null && AfkConfig.get().baseSleep && Follow.sleepInBase(mc, p, home.bed())) return true;
		// in the base all the buddy does not keep goes into its chests, a single stack too (when following, from four on)
		Stash.forceStore(true);
		Work.baseRun = true;
		try { if (Work.tick(mc, p, p)) return true; } finally { Work.baseRun = false; }
		var e = Tap.baseAt(Work.dimOf(level), p.position(), RADIUS);
		if (e == null) return false;
		// back beside the bed: on a cell next to it (the bed itself is no place to stand)
		if (bedStand == null || !e.bed().closerThan(bedStand, 3)) bedStand = Work.standCell(level, p, e.bed(), p, f -> true);
		Vec3 bed = bedStand == null ? Vec3.atBottomCenterOf(e.bed()) : Vec3.atBottomCenterOf(bedStand);
		if (p.position().distanceTo(bed) > (bedStand == null ? HOME_NEAR : 1.0) && now >= nextHome) {
			if (!Work.approach(mc, p, "base-bed", bed, now)) { nextHome = now + 60_000; bedStand = null; return false; }
			return true;
		}
		Follow.stand();
		return false;
	}

	/** A chest or barrel of the base the buddy does not know: tried as one of the owner's. */
	private static void grow(Level level, LocalPlayer p) {
		var e = Tap.baseAt(Work.dimOf(level), p.position(), RADIUS);
		if (e == null) return;
		BlockPos bed = e.bed();
		int r = (int) RADIUS;
		Set<BlockPos> found = new HashSet<>();
		for (int cx = (bed.getX() - r) >> 4; cx <= (bed.getX() + r) >> 4; cx++)
			for (int cz = (bed.getZ() - r) >> 4; cz <= (bed.getZ() + r) >> 4; cz++) {
				if (!level.hasChunk(cx, cz)) continue;
				LevelChunk chunk = level.getChunk(cx, cz);
				for (BlockEntity be : chunk.getBlockEntities().values()) {
					if (!(be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity)) continue;
					BlockPos q = be.getBlockPos();
					if (Vec3.atCenterOf(q).distanceTo(Vec3.atCenterOf(bed)) <= RADIUS) found.add(q.immutable());
				}
			}
		int added = 0;
		for (BlockPos q : found) if (Stash.tryBase(level, q, e.owner())) added++;
		if (added > 0) Exchange.emit("base", "action", "chests found", "for", e.owner(), "new", added, "in the base", found.size());
	}
}
