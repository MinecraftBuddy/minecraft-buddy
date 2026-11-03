package net.milenco.buddy.client;

/**
 * Flying with an elytra, worked out tick by tick with the game's own numbers (26.3: LivingEntity.updateFallFlyingMovement,
 * travelFallFlying, handleFallFlyingCollisions, Entity.checkFallDistanceAccumulation, FireworkRocketEntity.tick), and the way
 * to fly that goes with it, so that the buddy can follow a player who flies without dying of kinetic or fall damage.
 * Two rules keep him alive, both from those sources:
 * <ul>
 * <li>a wall costs health only when it takes more than 0.3 blocks a tick of his horizontal speed ((loss * 10) - 3);</li>
 * <li>while he glides the fall distance goes back to 1 every tick he descends slower than 0.5 a tick, so a landing that
 * comes in slower than that costs nothing (the safe fall distance is 3).</li>
 * </ul>
 * So the planner never lets a path touch a block on the way, and only lets it touch the ground coming down slowly.
 * Pure: the world is a question "is this block in the way", so the tests fly it without a game.
 */
final class Flight {
	private Flight() {}

	/** Is this block in the way: solid, lava, or not known yet (a chunk that is not loaded counts as solid). */
	@FunctionalInterface
	interface World {
		boolean solid(int x, int y, int z);

		/**
		 * The surface for the height he keeps: what is solid, and water too. Water is no wall (flying into it costs
		 * nothing), but counted as air the cruise height was measured to the sea floor, and over the sea he dived in and
		 * the flight ended (18 Oct 2026).
		 */
		default boolean floor(int x, int y, int z) { return solid(x, y, z); }
	}

	static final double GRAVITY = 0.08;
	/** The glider's box: 0.6 wide and 0.6 high (Pose.FALL_FLYING). */
	static final double HALF = 0.3, HEIGHT = 0.6;
	/** Coming down slower than this, the landing costs nothing; the planner keeps a margin under the game's 0.5. */
	static final double SAFE_DESCENT = 0.4;
	/**
	 * Slower than this sideways, no collision can hurt: (0.3 * 10) - 3 is nothing, whatever stands in the way. A touchdown
	 * is only safe this slow (on 18 Oct 2026 a landing at 1.65 a tick ran into the ground's edge and cost 12 health).
	 */
	static final double SAFE_SIDEWAYS = 0.3;
	/** Nose up this far, the glide bleeds off its speed and comes down at about 0.37 a tick: the way to land slowly. */
	static final double FLARE = -30;
	/** A rocket pulls toward this speed along the look (FireworkRocketEntity: look * 0.1 + (look * 1.5 - motion) * 0.5). */
	static final double ROCKET_SPEED = 1.5;
	/** The turn the mouse makes in a tick at most, in the plan as in the game: a turn of a whole circle takes a second. */
	static final double YAW_RATE = 18, PITCH_RATE = 12;
	/** How far ahead a plan looks, and for how long the first move in it is held before it carries on to the goal. */
	static final int HORIZON = 44, HOLD = 10;

	/** Where he is and how he moves; yaw and pitch are the game's (pitch positive is looking down). */
	static final class State {
		double x, y, z, vx, vy, vz, yaw, pitch;
		int rocket;   // ticks of a rocket's pull left

		State(double x, double y, double z, double vx, double vy, double vz, double yaw, double pitch, int rocket) {
			this.x = x; this.y = y; this.z = z; this.vx = vx; this.vy = vy; this.vz = vz; this.yaw = yaw; this.pitch = pitch; this.rocket = rocket;
		}

		State copy() { return new State(x, y, z, vx, vy, vz, yaw, pitch, rocket); }

		double speed() { return Math.sqrt(vx * vx + vy * vy + vz * vz); }

		double horizontal() { return Math.hypot(vx, vz); }
	}

	/** The game's view vector for a yaw and a pitch (Entity.calculateViewVector). */
	static double[] look(double yaw, double pitch) {
		double p = Math.toRadians(pitch), y = Math.toRadians(-yaw);
		return new double[] { Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p) };
	}

	/** The yaw that looks from (x, z) toward (tx, tz). */
	static double yawTo(double x, double z, double tx, double tz) {
		return Math.toDegrees(Math.atan2(-(tx - x), tz - z));
	}

	static double wrap(double deg) {
		deg %= 360;
		if (deg >= 180) deg -= 360;
		if (deg < -180) deg += 360;
		return deg;
	}

	/** How a tick ended: on, into a block, down on the ground, or into water (which ends the glide and costs nothing). */
	enum Event { NONE, HIT, LANDED, WATER }

	/** The ticks of one way of flying: what happened and when, and how it ended. */
	static final class Result {
		Event event = Event.NONE;
		int tick = -1;
		/** At a landing: how fast he came down and how fast sideways. */
		double landVy, landHorizontal;
		/** The least room around the box on the way, in blocks (0.25, 0.75 or 1.5: rough on purpose). */
		double room = 1.5;
		State end;
	}

	/**
	 * One tick of flight: the turn toward the wanted look (as fast as the mouse turns), a rocket's pull, the glide, the move.
	 * Returns the event of this tick; on a landing the state stands where he touched down.
	 */
	static Event step(World w, State s, double wantYaw, double wantPitch) {
		s.yaw += Math.max(-YAW_RATE, Math.min(YAW_RATE, wrap(wantYaw - s.yaw)));
		s.pitch += Math.max(-PITCH_RATE, Math.min(PITCH_RATE, Math.max(-90, Math.min(90, wantPitch)) - s.pitch));
		double[] l = look(s.yaw, s.pitch);
		if (s.rocket > 0) {
			s.vx += l[0] * 0.1 + (l[0] * ROCKET_SPEED - s.vx) * 0.5;
			s.vy += l[1] * 0.1 + (l[1] * ROCKET_SPEED - s.vy) * 0.5;
			s.vz += l[2] * 0.1 + (l[2] * ROCKET_SPEED - s.vz) * 0.5;
			s.rocket--;
		}
		glide(s, l);
		// the move: height first, then across (the game moves the larger horizontal axis first; for a box this size the
		// difference never decides between a hit and none)
		double ny = s.y + s.vy;
		if (blocked(w, s.x, ny, s.z)) {
			if (s.vy >= 0) return Event.HIT;   // his head against something
			// coming down onto something: a landing where the box stands on it; in the same tick the game still moves him
			// across, so an edge or a step there is a collision while he glides, with its damage
			double top = Math.floor(ny) + 1;
			while (top <= s.y + 1e-9 && blocked(w, s.x, top, s.z)) top++;
			s.y = top;
			if (blocked(w, s.x + s.vx, s.y, s.z + s.vz)) return s.horizontal() > SAFE_SIDEWAYS ? Event.HIT : Event.LANDED;
			s.x += s.vx;
			s.z += s.vz;
			return Event.LANDED;
		}
		s.y = ny;
		if (blocked(w, s.x + s.vx, s.y, s.z + s.vz)) return Event.HIT;
		s.x += s.vx;
		s.z += s.vz;
		int fx = (int) Math.floor(s.x), fy = (int) Math.floor(s.y), fz = (int) Math.floor(s.z);
		if (w.floor(fx, fy, fz) && !w.solid(fx, fy, fz)) return Event.WATER;
		return Event.NONE;
	}

	/** LivingEntity.updateFallFlyingMovement, line for line. */
	static void glide(State s, double[] l) {
		double lean = Math.toRadians(s.pitch);
		double lookHor = Math.sqrt(l[0] * l[0] + l[2] * l[2]);
		double moveHor = Math.hypot(s.vx, s.vz);
		double lift = Math.cos(lean) * Math.cos(lean);
		s.vy += GRAVITY * (-1.0 + lift * 0.75);
		if (s.vy < 0 && lookHor > 0) {
			double convert = s.vy * -0.1 * lift;
			s.vx += l[0] * convert / lookHor;
			s.vy += convert;
			s.vz += l[2] * convert / lookHor;
		}
		if (lean < 0 && lookHor > 0) {
			double convert = moveHor * -Math.sin(lean) * 0.04;
			s.vx += -l[0] * convert / lookHor;
			s.vy += convert * 3.2;
			s.vz += -l[2] * convert / lookHor;
		}
		if (lookHor > 0) {
			s.vx += (l[0] / lookHor * moveHor - s.vx) * 0.1;
			s.vz += (l[2] / lookHor * moveHor - s.vz) * 0.1;
		}
		s.vx *= 0.99;
		s.vy *= 0.98;
		s.vz *= 0.99;
	}

	/** Does the glider's box at this place (feet at y) overlap a block that is in the way? */
	static boolean blocked(World w, double x, double y, double z) {
		return overlaps(w, x - HALF, y, z - HALF, x + HALF, y + HEIGHT, z + HALF);
	}

	private static boolean overlaps(World w, double x0, double y0, double z0, double x1, double y1, double z1) {
		int ax = (int) Math.floor(x0 + 1e-7), bx = (int) Math.floor(x1 - 1e-7);
		int ay = (int) Math.floor(y0 + 1e-7), by = (int) Math.floor(y1 - 1e-7);
		int az = (int) Math.floor(z0 + 1e-7), bz = (int) Math.floor(z1 - 1e-7);
		for (int x = ax; x <= bx; x++) for (int y = ay; y <= by; y++) for (int z = az; z <= bz; z++) if (w.solid(x, y, z)) return true;
		return false;
	}

	/**
	 * The room around the box, roughly: 1.5 when nothing is within a block beside or above it, 0.75 when nothing within half a
	 * block, else 0.25. Not below: the ground under a landing is no wall, and counting it made every landing look tight.
	 */
	static double room(World w, State s) {
		if (!overlaps(w, s.x - HALF - 1, s.y + 0.1, s.z - HALF - 1, s.x + HALF + 1, s.y + HEIGHT + 1, s.z + HALF + 1)) return 1.5;
		if (!overlaps(w, s.x - HALF - 0.5, s.y + 0.1, s.z - HALF - 0.5, s.x + HALF + 0.5, s.y + HEIGHT + 0.5, s.z + HALF + 0.5)) return 0.75;
		return 0.25;
	}

	/** Blocks of air under him down to the ground or the water, up to 64. */
	static int heightAbove(World w, double x, double y, double z) {
		int bx = (int) Math.floor(x), bz = (int) Math.floor(z), by = (int) Math.floor(y);
		for (int d = 1; d <= 64; d++) if (w.floor(bx, by - d, bz)) return d - 1;
		return 64;
	}

	/**
	 * Fly the first move (look yaw1, pitch1, a rocket now or not) for HOLD ticks, and then on toward the goal with the
	 * continuation's simple rule, to the horizon; stop at the first hit or landing.
	 */
	static Result fly(World w, State start, double yaw1, double pitch1, boolean rocket, int rocketTicks, Goal goal) {
		State s = start.copy();
		if (rocket && s.rocket == 0) s.rocket = rocketTicks;
		Result r = new Result();
		for (int t = 0; t < HORIZON; t++) {
			double yaw, pitch;
			if (t < HOLD) { yaw = yaw1; pitch = pitch1; }
			else {
				double[] g = goal.at(t);
				yaw = yawTo(s.x, s.z, g[0], g[2]);
				// landing: glide toward the spot, and close to it nose up, so that he comes down slowly both ways
				pitch = goal.landing ? (Math.hypot(g[0] - s.x, g[2] - s.z) < 16 || s.y - g[1] < 6 ? FLARE : 4)
					: s.y < g[1] - 2 && s.horizontal() > 0.7 ? -15 : 4;
			}
			Event e = step(w, s, yaw, pitch);
			if (e != Event.NONE) {
				r.event = e; r.tick = t;
				if (e == Event.LANDED) { r.landVy = s.vy; r.landHorizontal = s.horizontal(); }
				break;
			}
			if ((t & 1) == 0) r.room = Math.min(r.room, room(w, s));
		}
		r.end = s;
		return r;
	}

	/** Where to fly: a point, which may move (the player he follows); and whether the aim is to come down there. */
	static final class Goal {
		final double x, y, z, vx, vy, vz;
		final boolean landing;

		Goal(double x, double y, double z, double vx, double vy, double vz, boolean landing) {
			this.x = x; this.y = y; this.z = z; this.vx = vx; this.vy = vy; this.vz = vz; this.landing = landing;
		}

		double[] at(int t) { return new double[] { x + vx * t, y + vy * t, z + vz * t }; }
	}

	/** The way of flying chosen: the first move, and what the plan saw coming. */
	static final class Plan {
		final double yaw, pitch, score;
		final boolean rocket;
		final Result result;

		Plan(double yaw, double pitch, boolean rocket, double score, Result result) {
			this.yaw = yaw; this.pitch = pitch; this.rocket = rocket; this.score = score; this.result = result;
		}

		/** Nothing in the way, as far as the plan looked, and a touchdown slow both ways. */
		boolean safe() {
			return result.event == Event.NONE || result.event == Event.WATER
				|| result.event == Event.LANDED && result.landVy > -SAFE_DESCENT && result.landHorizontal < SAFE_SIDEWAYS;
		}
	}

	static final double[] YAWS = { 0, -15, 15, -35, 35, -60, 60, -95, 95, 135, -135 };
	static final double[] PITCHES = { -40, -30, -20, -10, -3, 6, 15, 30 };
	/** Low over the ground he keeps at least this much air under him while cruising. */
	static final int CRUISE_HEIGHT = 8;

	/**
	 * The best first move: every look of the fan around the way to the goal, with a rocket and without, flown to the
	 * horizon. A way that hits anything is never taken while a safe one exists; of the safe ways the one that ends nearest
	 * the goal with the most room, at a height above the ground while cruising and coming down slowly when landing.
	 * With no safe way at all: the one that hits latest, pulling up.
	 */
	static Plan best(World w, State s, Goal goal, boolean canRocket, int rocketTicks) {
		double[] g0 = goal.at(0);
		double toGoal = yawTo(s.x, s.z, g0[0], g0[2]);
		Plan best = null, latest = null;
		for (int r = 0; r < (canRocket && s.rocket == 0 ? 2 : 1); r++) {
			boolean rocket = r == 1;
			for (double dy : YAWS) for (double pitch : PITCHES) {
				double yaw = wrap(toGoal + dy);
				Result res = fly(w, s, yaw, pitch, rocket, rocketTicks, goal);
				double score = score(w, s, res, goal, rocket);
				Plan p = new Plan(yaw, pitch, rocket, score, res);
				if (p.safe()) { if (best == null || score > best.score) best = p; }
				else if (latest == null || res.tick > latest.result.tick || res.tick == latest.result.tick && pitch < latest.pitch) latest = p;
			}
		}
		return best != null ? best : latest;
	}

	private static double score(World w, State start, Result r, Goal goal, boolean rocket) {
		State e = r.end;
		int t = r.tick < 0 ? HORIZON : r.tick;
		double[] g = goal.at(t);
		double dist = Math.sqrt(sq(e.x - g[0]) + sq(e.y - g[1]) * 0.5 + sq(e.z - g[2]));
		double score = -dist + r.room * 4;
		if (rocket) score -= 3;   // rockets run out: one only where it makes a difference
		if (goal.landing) {
			if (r.event == Event.WATER) score += 40;   // down in water: slow or fast, it costs nothing
			else if (r.event == Event.LANDED) score += 40 - r.landHorizontal * 20 + r.landVy * 20;
			else score -= Math.max(0, e.y - goal.y) * 0.3;
		} else {
			if (r.event == Event.LANDED || r.event == Event.WATER) score -= 60;   // not the aim: safe, but down and alone
			int h = heightAbove(w, e.x, e.y, e.z);
			if (h < CRUISE_HEIGHT) score -= (CRUISE_HEIGHT - h) * 3;
			if (e.horizontal() < 0.35) score -= (0.35 - e.horizontal()) * 30;   // too slow and he stalls
		}
		return score;
	}

	private static double sq(double v) { return v * v; }
}
