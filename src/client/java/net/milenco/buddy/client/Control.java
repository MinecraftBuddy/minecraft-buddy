package net.milenco.buddy.client;

import java.util.EnumMap;
import java.util.Map;

/**
 * Who has the buddy's hands on what right now (after SoulFire's control leases and Player2's priority chains). A part of the buddy
 * (moving, looking, the hand, the inventory) is held by one owner at a time, for a short while that the owner renews as
 * long as it needs it; a higher priority takes it over, a lower one
 * waits. The web panel holds what it uses with the highest priority, so no job clicks in the inventory while someone
 * moves an item there, and no job walks while someone steers.
 * <p>
 * Inside the jobs the order of {@link Follow#tick} still decides (see {@link Arbiter}); a lease is for what comes from
 * outside that chain and has to win from all of it.
 */
final class Control {
	private Control() {}

	enum Part { MOVE, LOOK, HAND, INVENTORY }

	/** The web panel: the person at the controls. */
	static final int PANEL = 100;
	/** What keeps the buddy alive (water against fire, getting loose when stuck). */
	static final int SURVIVAL = 80;
	/** A job of the buddy's own. */
	static final int JOB = 50;

	private record Lease(String owner, int priority, long until) {}

	private static final Map<Part, Lease> HELD = new EnumMap<>(Part.class);

	/** Take or renew a part for this long; true when it is yours now. */
	static synchronized boolean take(Part part, String owner, int priority, long ms) {
		long now = System.currentTimeMillis();
		Lease l = HELD.get(part);
		if (l != null && l.until > now && !l.owner.equals(owner) && l.priority > priority) return false;
		HELD.put(part, new Lease(owner, priority, now + ms));
		return true;
	}

	/** Let go of a part, when it is yours. */
	static synchronized void release(Part part, String owner) {
		Lease l = HELD.get(part);
		if (l != null && l.owner.equals(owner)) HELD.remove(part);
	}

	/** Someone with a priority higher than this holds the part now. */
	static synchronized boolean heldAbove(Part part, int priority) {
		Lease l = HELD.get(part);
		return l != null && l.until > System.currentTimeMillis() && l.priority > priority;
	}

	/** Who holds the part now, or null. */
	static synchronized String holder(Part part) {
		Lease l = HELD.get(part);
		return l != null && l.until > System.currentTimeMillis() ? l.owner : null;
	}

	/** Any part held by the panel: then the jobs stand back. */
	static boolean panel() {
		for (Part p : Part.values()) if (heldAbove(p, JOB) && "panel".equals(holder(p))) return true;
		return false;
	}
}
