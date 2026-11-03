package net.milenco.buddy.client;

/**
 * A creeper's fuse as the client can count it, and the sums of the pen's blow at a swelling creeper ({@link Pen}).
 * Pure: ticks and blocks, so that {@code FuseTest} holds it against 26.3's own rules.
 * <ul>
 * <li>{@link Count}: the server's count of one creeper's swell, as the most it can be. The client counts a swell itself
 * (Creeper.tick runs there too, on the swell direction the server sends), but what a landing adds it never sees:
 * Creeper.causeFallDamage, server-side, adds 1.5 a block fallen, up to five under the fuse. A drop farm's creeper
 * lands at 25 of its 30, which the client's own count put at 0 (the third review of 22 Oct 2026: he stayed to strike
 * one that went off five ticks after it began to swell).</li>
 * <li>{@link #cooldownWait}: a blow within ten ticks of a hurt that is no stronger than that hurt does nothing
 * (LivingEntity.hurtServer), so a blow at a creeper that just took fall damage only counts once that is over.</li>
 * <li>{@link #sum}, {@link #walkOut}, {@link #airTicks}: the ticks of the blow's way out, the walk out of a blast, a
 * fall.</li>
 * </ul>
 */
final class Fuse {
	private Fuse() {}

	/** Creeper.maxSwell: it goes off once its count reaches this. */
	static final int FUSE = 30;
	/** Creeper.causeFallDamage: a landing leaves its count at most this (maxSwell - 5). */
	static final int FALL_CAP = FUSE - 5;
	/** LivingEntity.hurtServer: while the cooldown of 20 is over this, a blow no stronger than the last hurt does nothing. */
	static final int COOLDOWN_FREE = 10;
	/**
	 * Walking out of a blast: the walker's pace on the ground without sprinting (the terminal 0.216 a tick of a player's
	 * walk, a little under it for the first steps) and the ticks it takes to start. Upright beside the pen's fence too
	 * (Pen.gettingAway): until the test of 22 Oct 2026 the walker crouched the first block (0.065 a tick), twelve ticks
	 * more, so that from a spot beside a pit a walk off never cleared him and every strike there was late at once.
	 */
	static final double WALK = 0.2;
	static final int WALK_START = 3;

	/** What a landing adds to the server's count: (int) (the height fallen x 1.5), as Creeper.causeFallDamage does. */
	static int fallBoost(double fallen) {
		return fallen <= 0 ? 0 : (int) (fallen * 1.5);
	}

	/**
	 * One creeper's swell as the server counts it, as the most it can be, from what the client sees of it every tick.
	 * Taken from the client's own count, it would be the server's of a ping before, but for a landing: that adds
	 * {@link #fallBoost} of the height it came down from (the highest it was since it last stood, or swam, or held on to
	 * a ladder: where the server's fall distance starts anew), the count left at {@link #FALL_CAP} at most. First seen,
	 * the count is not known (it may be swelling already, or falling, or have just landed), so it starts at the whole
	 * fuse and comes down a tick at a time while it does not swell, as the server's own would.
	 */
	static final class Count {
		/** The most the server's count can be. */
		int est = FUSE;
		/** The highest it was since it last stood, NaN before it was first seen. */
		double peak = Double.NaN;
		boolean air;
		/** The tick it was last seen in (Pen keeps the counts of the creepers in sight). */
		int seen;

		/**
		 * One tick of it as the client sees it: on the ground, in water or on a ladder (where a fall starts anew), its
		 * height, the way it swells (Creeper.getSwellDir), and the client's own count (Creeper.getSwelling).
		 */
		void tick(boolean onGround, boolean resets, double y, int dir, int client) {
			if (Double.isNaN(peak)) { peak = y; air = !onGround && !resets; }
			// first the count, then the landing, in the order of Creeper.tick: its swell, then super.tick's move
			est = Math.max(0, Math.min(FUSE, est + dir));
			if (resets) { air = false; peak = y; }
			else if (onGround) {
				if (air) {
					int boost = fallBoost(peak - y);
					if (boost > 0) est = Math.min(FALL_CAP, est + boost);
				}
				air = false;
				peak = y;
			} else {
				air = true;
				peak = Math.max(peak, y);
			}
			est = Math.max(est, client);
		}
	}

	/** The fuse left by the count that is the more of the two (the client's own, or {@link Count}'s). */
	static int fuse(int client, int est) {
		return FUSE - Math.max(client, est);
	}

	/** How long a blow sent now waits before it counts at all: the cooldown of the creeper's last hurt (client's count). */
	static int cooldownWait(int damageCooldownTime) {
		return Math.max(0, damageCooldownTime - COOLDOWN_FREE);
	}

	/**
	 * The latest a blow lands, in ticks from now: the slowest of the turn to it, the strength that kills it, a moment's
	 * reaction, the cooldown of its last hurt and a hitch of his own (in the air, eating, a line not clear yet), then
	 * the tick it is queued for, his ping and a margin. In time while this is under the fuse left.
	 */
	static int sum(int turn, int strength, int react, int cooldown, int hitch, int ping, int margin) {
		return Math.max(Math.max(Math.max(turn, strength), Math.max(react, cooldown)), hitch) + 1 + ping + margin;
	}

	/**
	 * Whether the blow at a swelling creeper goes ahead. A new one ({@code committed} false): when it lands within the
	 * fuse ({@code sum} under {@code fuse}) with no hitch of his own at all. One he turned to already: as long as its sum,
	 * the hitch's own ticks counted, still fits; a hitch of unknown length ({@code unseen}: a mob in the line, nothing of
	 * it in reach) only while a walk out of its blast still clears him ({@code late} false), unless that hitch began only
	 * once it no longer did ({@code unseenLate}); and once getting away no longer clears him, kept while it fits but for
	 * its {@code margin}, for then the walk off gets him nowhere clear (the third review of 22 Oct 2026: one tick in which
	 * the blow could not go out ended it, and he walked off with too little of the fuse left).
	 */
	static boolean strike(boolean committed, int hitch, boolean unseen, boolean unseenLate, int sum, int fuse, boolean late, int margin) {
		if (!committed) return hitch == 0 && !unseen && sum < fuse;
		boolean fits = sum < fuse, withinMargin = late && sum - margin < fuse;
		return unseen && !unseenLate ? fits && !late : fits || withinMargin;
	}

	/**
	 * Whether a strike begun goes on this tick (Pen.strikeOn), its creeper still swelling at him within the grace by the
	 * server's swell direction, whatever the client's own sight says: with a plan for its blow ({@code planned}) while
	 * that blow still fits ({@code ahead}: {@link #strike}), alone or, with another one swelling at him too, only while
	 * {@code late}, for a walk off then clears him of this one by nothing, where otherwise it clears him of both; with no
	 * plan for a moment (a bystander in a sword's sweep), alone and late at its last plan ({@code lastLate}) within
	 * {@code grace} ticks of it ({@code sincePlan}). A second one's swell, or a tick the client lost sight of it at the
	 * rim, dropped a late strike, and he walked off into its blast (the review of the fourth round, 22 Oct 2026).
	 */
	static boolean keeps(boolean planned, boolean ahead, boolean late, boolean alone, boolean lastLate, int sincePlan, int grace) {
		if (planned) return ahead && (alone || late);
		return alone && lastLate && sincePlan <= grace;
	}

	/**
	 * How long the death of a creeper just struck is waited for before another blow may follow, in milliseconds: his ping
	 * and two ticks (the death comes as the health the server sends after it), never less than the gap of the quickest
	 * clicks ({@code minGap}): the blow went out once a tick until the client learnt of the death (the third review).
	 */
	static long struckHold(int pingTicks, double minGap) {
		return Math.max((long) Math.ceil(minGap), (pingTicks + 2) * 50L);
	}

	/**
	 * The ticks a walk takes him out of a blast that reaches {@code blast}, from {@code dist} off it: a start, and the
	 * blocks at the walker's pace.
	 */
	static int walkOut(double dist, double blast) {
		if (dist >= blast) return 0;
		return WALK_START + (int) Math.ceil((blast - dist) / WALK);
	}

	/**
	 * The ticks until he stands again, from this far above what is under him and this speed up (a player's fall:
	 * the move first, then gravity and drag, LivingEntity.travel), at most 40.
	 */
	static int airTicks(double above, double vy) {
		double y = Math.max(0, above);
		for (int t = 1; t <= 40; t++) {
			y += vy;
			vy = (vy - 0.08) * 0.98;
			if (y <= 1e-6) return t;
		}
		return 40;
	}
}
