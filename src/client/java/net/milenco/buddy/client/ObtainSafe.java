package net.milenco.buddy.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.milenco.buddy.version.Game;

/**
 * Staying alive where an order takes the buddy, on top of what Follow's chain does (a fight, a creeper, fire, air): the
 * night, which AltoClef leaves to its fights and which kills an unarmoured buddy (idle at the spawn, or on the ground
 * without armour, fighting zombies with dirt in its hand). Without iron armour the buddy spends a night in the ground:
 * three down under the open sky and the top shut against the wall, or the open sides of where it stands shut
 * underground, and out again in the morning.
 */
final class ObtainSafe {
	private ObtainSafe() {}

	/** From a little before the dark, when monsters start on the surface, until the light. */
	static boolean night(Level level) {
		if (level.isThundering()) return true;
		long t = Math.floorMod(level.getOverworldClockTime(), 24000L);
		// from before sunset, so that the hole is dug before the first monster stands up (about 13000) and before the drowned
		// come up in the dusk (two trident throws killed it at a furnace on the shore at 12300); until full day, so that the
		// night's skeletons and zombies have burnt: out at dawn it stepped among skeletons, spiders and creepers on a
		// mountain and was shot dead
		return t >= 11500 || t < 1000;
	}

	/**
	 * Armour of iron all over is 15: below it a night outside is not taken. Nor in iron with phantoms coming: past three
	 * days without a bed they come down at night on whoever stands under the open sky, and a night out in iron among them and
	 * the ground's monsters went from full health to dead. A roof keeps them off.
	 */
	static boolean needsShelter(LocalPlayer p) {
		String dim = p.level().dimension().identifier().getPath();
		// deep under the ground the night changes nothing: monsters stand up in the dark there at any hour, and a player
		// mines on through the night (at y 33 it walled itself in for a night for nothing). A step that wants the surface
		// waits down there for the morning (Obtain)
		return dim.equals("overworld") && night(p.level()) && (p.getArmorValue() < 15 || phantomsDue()) && !deepUnder(p);
	}

	/** The server's phantoms come for a player whose time since rest is past three game days (PhantomSpawner). */
	static final int PHANTOM_TICKS = 72_000;

	static boolean phantomsDue() { return Rest.estimate() >= PHANTOM_TICKS; }

	/**
	 * Whether Soprano's night round an order (SleepThroughNightsTask: the evening's bed, the trip to it, the sleep) is slept:
	 * only outside in the overworld. Deep under the ground the night changes nothing and phantoms come only to a player under
	 * the open sky, so a pillar up to a bed there every night was time for nothing. Back outside at night, the bed goes down
	 * at once, which also sets the phantoms' count back.
	 */
	static boolean sopranoSleeps() {
		LocalPlayer p = AfkHelperClient.mc().player;
		// (and not while a nether portal is built: it is the way out of the night, and the night's trip broke the climb to the
		// surface and the search for a spot, attempt 224)
		return p == null || !deepUnder(p) && !Alto.buildingPortal();
	}

	/** In the overworld with no sky overhead and eight blocks or more of ground above. */
	static boolean deepUnder(LocalPlayer p) {
		Level level = p.level();
		if (!level.dimension().equals(Level.OVERWORLD)) return false;
		BlockPos f = p.blockPosition();
		if (level.canSeeSky(f.above())) return false;
		// leaves left out: under a tall tree it counted as deep under the ground, flipped between that and the surface as it
		// moved, and its night's hole started and ended forty times a second
		return level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, f.getX(), f.getZ()) - f.getY() >= 8;
	}

	// ---------- fight or flight ----------

	/** What a monster takes off an unarmoured player a second on hard, about (their blows, an arrow a second). */
	static double hits(net.minecraft.world.entity.Entity e) {
		String id = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
		return switch (id) {
			case "zombie", "husk", "drowned", "zombie_villager" -> 4.5;
			case "skeleton", "stray", "bogged" -> 4;
			case "spider", "cave_spider" -> 3;
			case "enderman" -> 10.5;
			case "zombified_piglin" -> 13;
			case "wither_skeleton" -> 12;
			case "vindicator" -> 19.5;
			case "piglin_brute" -> 19.5;
			case "blaze", "ghast" -> 6;
			case "slime", "magma_cube" -> 4;
			case "witch" -> 6;
			case "pillager" -> 5;
			case "creeper" -> 0;   // its blast is the creeper step's (Follow), not a fight
			default -> 4;
		};
	}

	/**
	 * The monsters that matter now: alive, and coming at the buddy or close. A client is not told what a monster targets
	 * ({@code getTarget} is the server's), but it is told that it is aggressive, which is a melee monster's raised arms
	 * and a shooter's drawn bow: within ten such, a shooter within sixteen (a pillager shot the buddy dead from ten blocks
	 * while this list saw nobody).
	 */
	static List<net.minecraft.world.entity.monster.Monster> foes(LocalPlayer p) {
		List<net.minecraft.world.entity.monster.Monster> out = new ArrayList<>();
		for (var m : p.level().getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class, p.getBoundingBox().inflate(16, 6, 16))) {
			if (!m.isAlive()) continue;
			double d = m.distanceTo(p);
			boolean shooter = shoots(m);
			boolean at = m.getTarget() == p || d < 4 || m.isAggressive() && d < (shooter ? 16 : 10);
			if (m instanceof net.minecraft.world.entity.monster.Enderman em && !em.isCreepy()) continue;
			if (at && p.hasLineOfSight(m)) out.add(m);
		}
		return out;
	}

	/**
	 * A live blaze within 24 blocks: a blaze fight is on. Blazes are the rods task's own and not in {@link #foes} (a client is
	 * not told that a blaze is aggressive), so with six of them shooting the order saw nobody and made a spare shield in
	 * the middle of them: it stood in the corridor looking for a table and burned to death (the blazeroom drill, 4 Nov 2026).
	 */
	static boolean blazesAbout(LocalPlayer p) {
		return !p.level().getEntitiesOfClass(net.minecraft.world.entity.monster.Blaze.class, p.getBoundingBox().inflate(24),
				net.minecraft.world.entity.monster.Blaze::isAlive).isEmpty();
	}

	/**
	 * The hardcore rule against a fight that is lost (AltoClef fights a zombie barehanded: on easy that is fine, on hard
	 * one zombie takes 4.5 a blow): how long its best weapon takes to kill them against how long they take to bring it to
	 * two hearts, its armour counted and their blows halved for the knockback. Outmatched is under twice as quick.
	 */
	static boolean outmatched(LocalPlayer p, List<net.minecraft.world.entity.monster.Monster> foes) {
		if (foes.isEmpty()) return false;
		// three wither skeletons together are a pack: away from them, whatever the sums say (Soprano backs off sixteen blocks
		// from a pack within twelve; one alone in contact is fought)
		int withers = 0;
		for (var m : p.level().getEntitiesOfClass(net.minecraft.world.entity.monster.skeleton.WitherSkeleton.class, p.getBoundingBox().inflate(12, 4, 12)))
			if (m.isAlive()) withers++;
		if (withers >= 3) return true;
		double best = Guard.dps(net.minecraft.world.item.ItemStack.EMPTY);
		var in = p.getInventory();
		for (int i = 0; i < 9; i++) best = Math.max(best, Guard.dps(in.getItem(i)));
		double life = 0, dps = 0;
		for (var m : foes) { life += m.getHealth(); dps += hits(m); }
		double armour = Math.min(0.8, p.getArmorValue() * 0.04);
		dps *= (1 - armour) * 0.6;
		double kill = life / Math.max(0.1, best), die = (p.getHealth() - 4) / Math.max(0.1, dps);
		return kill > die / 2;
	}

	static String type(net.minecraft.world.entity.Entity e) {
		return net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
	}

	/** What shoots from afar: no fight with them without armour and a shield, and their place is kept away from. */
	static final Set<String> SHOOTERS = Set.of("skeleton", "stray", "bogged", "pillager", "witch", "blaze", "ghast", "breeze");

	/**
	 * A shooter, and a drowned with a trident: it throws for twelve on hard, and two throws from the water killed the
	 * buddy at a furnace on the shore at dusk.
	 */
	static boolean shoots(net.minecraft.world.entity.Entity m) {
		String t = type(m);
		return SHOOTERS.contains(t) || t.equals("drowned") && m instanceof net.minecraft.world.entity.LivingEntity l
			&& l.getMainHandItem().is(net.minecraft.world.item.Items.TRIDENT);
	}

	/** A patrol or an outpost: more than one, and a crossbow each. Seen at all, its place is left alone a long while. */
	static final Set<String> ILLAGERS = Set.of("pillager", "vindicator", "evoker", "ravager", "illusioner");

	// ---------- places to keep away from ----------

	/** A place and until when it is avoided; a search leg, a target to mine or kill and a flight keep 48 blocks off. */
	record Mark(String dim, BlockPos at, long until) {}
	private static final List<Mark> DANGER = new ArrayList<>();
	static final int DANGER_RADIUS = 48;
	private static long nextWatch;

	/**
	 * Twice a second: an illager in sight (a patrol or an outpost) marks its place for twenty minutes unless the buddy has
	 * iron armour and a shield; a shooter that hit it, for ten. AltoClef has no such memory: it went back to the same
	 * skeletons. Without this the buddy fled a pillager patrol and walked back into it on its search for cows.
	 */
	static void watch(LocalPlayer p, long now) {
		if (now < nextWatch) return;
		nextWatch = now + 500;
		boolean armed = p.getArmorValue() >= 15 && (p.getOffhandItem().is(net.minecraft.world.item.Items.SHIELD));
		String dim = p.level().dimension().identifier().getPath();
		for (var m : p.level().getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class, p.getBoundingBox().inflate(40, 20, 40))) {
			if (!m.isAlive()) continue;
			String id = type(m);
			if (ILLAGERS.contains(id) && !armed && p.hasLineOfSight(m)) mark(dim, m.blockPosition(), now + 20 * 60_000, id);
			else if (shoots(m) && m.isAggressive() && p.hurtTime > 0 && m.distanceTo(p) < 24) mark(dim, m.blockPosition(), now + 10 * 60_000, id);
		}
	}

	static synchronized void mark(String dim, BlockPos at, long until, String why) {
		long now = System.currentTimeMillis();
		DANGER.removeIf(m -> m.until() < now);
		// one mark for a group: a mark within sixteen is moved here and lasts the longer
		for (int i = 0; i < DANGER.size(); i++) {
			Mark m = DANGER.get(i);
			if (m.dim().equals(dim) && Math.hypot(m.at().getX() - at.getX(), m.at().getZ() - at.getZ()) < 16) {
				DANGER.set(i, new Mark(dim, at.immutable(), Math.max(until, m.until())));
				return;
			}
		}
		DANGER.add(new Mark(dim, at.immutable(), until));
		Exchange.emit("obtain", "action", "danger", "what", why, "x", at.getX(), "y", at.getY(), "z", at.getZ(), "min", (until - now) / 60_000);
	}

	/** Within the radius of a place to keep away from (horizontally: a patrol walks over a cave too). */
	static boolean dangerous(BlockPos q) {
		return danger(q) != null;
	}

	static synchronized BlockPos danger(BlockPos q) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return null;
		String dim = mc.level.dimension().identifier().getPath();
		long now = System.currentTimeMillis();
		for (Mark m : DANGER) {
			if (m.until() < now || !m.dim().equals(dim)) continue;
			if (Math.hypot(m.at().getX() - q.getX(), m.at().getZ() - q.getZ()) < DANGER_RADIUS) return m.at();
		}
		return null;
	}

	static synchronized void forget() { DANGER.clear(); }

	// ---------- hurt ----------

	/**
	 * Hurt and no fight on: under half health without iron armour, or under a third with it. Then into the ground as at
	 * night, until it has healed (when the food bar lets it), and only then on with the step.
	 */
	static boolean hurt(LocalPlayer p) {
		if (p.isInWater() || p.isInLava() || !p.onGround()) return false;
		float hp = p.getHealth();
		return hp <= (p.getArmorValue() >= 15 ? 7 : 10);
	}

	/**
	 * Shot at and better off in the ground than on the run: every foe a shooter, no iron armour and shield, and ground
	 * underfoot to dig into (or no sky overhead). A skeleton shot the buddy dead in four arrows while it fled through
	 * jungle vines: three blocks down is out of its sight in a second or two, a flight over rough ground is not.
	 */
	static boolean duck(LocalPlayer p, List<net.minecraft.world.entity.monster.Monster> foes) {
		// in the air too: the shelter lands first; asked on the ground only, a jump made it run, and two arrows killed it
		if (foes.isEmpty() || p.isInWater()) return false;
		for (var m : foes) if (!shoots(m)) return false;
		// in iron with a shield it fights them, down to seven hearts (Soprano runs from a blaze in sight at 14 health; a
		// blaze fight took it to five)
		if (p.getArmorValue() >= 15 && p.getOffhandItem().is(net.minecraft.world.item.Items.SHIELD) && p.getHealth() > 14) return false;
		Level level = p.level();
		BlockPos f = p.blockPosition();
		// into the ground, or walled in with blocks it carries: a run in their sight is never the way (on a floor of planks
		// it ran, over an edge, and three arrows and a fall killed it)
		return !level.canSeeSky(f) || Shelter.safeToDig(level, f.below()) || throwaways(p) >= 8;
	}

	/**
	 * Under the ground with blocks to spare and the monsters not at arm's length yet: walled in where it stands, which no
	 * zombie gets through, rather than a flight down a tunnel it would be caught in.
	 */
	static boolean seal(LocalPlayer p, List<net.minecraft.world.entity.monster.Monster> foes) {
		if (foes.isEmpty() || p.isInWater() || underSky(p) || throwaways(p) < 8) return false;
		if (p.getArmorValue() >= 15 && p.getOffhandItem().is(net.minecraft.world.item.Items.SHIELD)) return false;
		for (var m : foes) if (m.distanceToSqr(p) < 2.5 * 2.5) return false;
		return true;
	}

	/** A shooter at arm's length: a blow is quicker than a wall, and a wall cell it stands in cannot be filled. */
	static boolean shooterClose(LocalPlayer p) {
		for (var m : p.level().getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class, p.getBoundingBox().inflate(2.5, 1.5, 2.5)))
			if (m.isAlive() && shoots(m) && !(m instanceof net.minecraft.world.entity.monster.Witch)) return true;
		return false;
	}

	/** The sky overhead: no ground above the buddy. */
	static boolean underSky(LocalPlayer p) { return p.level().canSeeSky(p.blockPosition().above()); }

	/**
	 * Room to run: the sky overhead, or an open place round the buddy (a fortress' hall, the nether's open ground), not a
	 * tunnel. Counted as free cells two high within two blocks, of the 25 columns round it.
	 */
	static boolean roomToRun(LocalPlayer p) {
		if (underSky(p)) return true;
		Level level = p.level();
		BlockPos f = p.blockPosition();
		int free = 0;
		for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
			BlockPos c = f.offset(dx, 0, dz);
			if (level.getBlockState(c).canBeReplaced() && level.getBlockState(c.above()).canBeReplaced() && level.getFluidState(c).isEmpty()) free++;
		}
		return free >= 15;
	}

	/** A monster that is no shooter at arm's length: that is a fight or a flight, no hole. */
	static boolean meleeNear(LocalPlayer p) {
		for (var m : p.level().getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class, p.getBoundingBox().inflate(3, 2, 3)))
			if (m.isAlive() && !shoots(m) && !(m instanceof net.minecraft.world.entity.monster.Creeper)) return true;
		return false;
	}

	/**
	 * A shooter within 24 blocks, seeing the buddy or not: in the hole none sees it, and out of it they shoot at once (out
	 * after eight seconds, three arrows, 20 to 4.5).
	 */
	static boolean shooterNear(LocalPlayer p) {
		for (var m : p.level().getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class, p.getBoundingBox().inflate(24, 12, 24)))
			if (m.isAlive() && shoots(m)) return true;
		return false;
	}

	/** The food bar heals the buddy (18 or more) or food in the bag can make it so. */
	static boolean canHeal(LocalPlayer p) {
		if (p.getFoodData().getFoodLevel() >= 18) return true;
		var in = p.getInventory();
		for (int i = 0; i < 36; i++) if (in.getItem(i).has(net.minecraft.core.component.DataComponents.FOOD)) return true;
		return false;
	}

	/** Running from what it cannot fight: away from them over the ground, sprinting, until none is within ten. */
	static final class Flee {
		BlockPos goal;
		long since, until;

		/** Given up on the spot: the fight is now, not after the flight's four seconds of running on. */
		void reset() { goal = null; until = 0; }

		boolean tick(Minecraft mc, LocalPlayer p, List<net.minecraft.world.entity.monster.Monster> foes, long now) {
			if (foes.isEmpty() && now > until) { goal = null; return false; }
			if (goal == null || now - since > 6000 || p.blockPosition().distSqr(goal) < 4) {
				double x = 0, z = 0;
				for (var m : foes) { x += m.getX() - p.getX(); z += m.getZ() - p.getZ(); }
				BlockPos dz0 = danger(p.blockPosition());
				if (foes.isEmpty() && dz0 != null) { x = dz0.getX() - p.getX(); z = dz0.getZ() - p.getZ(); }
				double a = foes.isEmpty() && dz0 == null ? Math.toRadians(p.getYRot() + 90) : Math.atan2(-z, -x);
				goal = null;
				for (int t = 0; t < 8 && goal == null; t++) {
					double b = a + (t % 2 == 0 ? 1 : -1) * ((t + 1) / 2) * Math.PI / 6;
					goal = ObtainSteps.ground(p.level(), (int) Math.floor(p.getX() + Math.cos(b) * 14), (int) Math.floor(p.getZ() + Math.sin(b) * 14), p.getBlockY());
				}
				since = now;
				until = now + 4000;
				if (goal == null) return false;
				Exchange.emit("obtain", "action", "flees", "foes", foes.size(), "hp", p.getHealth(), "x", goal.getX(), "z", goal.getZ());
			}
			HeldKeys.set(mc.options.keySprint, true);
			// no way away: a flight that goes nowhere is a beating without a blow back (in a stair under the ground it ran at a
			// cell 43 blocks up while a zombie hit it six times): the fight takes over
			if (!Work.approach(mc, p, "flee-" + goal.toShortString(), Vec3.atBottomCenterOf(goal), now)) { goal = null; until = 0; return false; }
			return true;
		}
	}

	// ---------- out of the water ----------

	/**
	 * Water in its feet or head cell, where no station goes down and every step refuses wet cells: under the ground (a
	 * flooded stair) and in the open too (it floated in a lake after the night and could put no crafting table down).
	 */
	/**
	 * The sky over the buddy seen through the water it is in: the first cell overhead that holds no water, and the sky
	 * over that. canSeeSky in the water's top cell is false (the water takes a level of the light), so deep in the sea it
	 * counted as under the ground, and its dry cell was the water's top overhead, where it bobbed.
	 */
	static boolean skyThroughWater(Level level, BlockPos f) {
		BlockPos c = f.above();
		for (int i = 0; i < 64 && level.getFluidState(c).is(net.minecraft.tags.FluidTags.WATER); i++) c = c.above();
		return level.canSeeSky(c);
	}

	static boolean wetUnder(LocalPlayer p) {
		Level level = p.level();
		BlockPos f = p.blockPosition();
		return !level.getFluidState(f).isEmpty() || !level.getFluidState(f.above()).isEmpty();
	}

	/**
	 * Out of water under the ground to the nearest dry cell, two high, on a floor, that the walker reaches (it swims and
	 * digs through earth): nothing of an order goes on standing in water.
	 */
	static final class Dry extends ObtainSteps.Exec {
		BlockPos goal;
		final long since = System.currentTimeMillis();
		final java.util.Set<BlockPos> tried = new java.util.HashSet<>();
		final Placer placer = new Placer();
		final java.util.Set<BlockPos> placedHere = new java.util.HashSet<>();
		int plugged;
		long pluggedAt;

		ObtainSteps.Result tick(Minecraft mc, LocalPlayer p, long now) {
			// dry on the ground, not a moment out of it while bobbing at the top (at night the hole and the walk out of the
			// water took turns every tick)
			if (!wetUnder(p) && p.onGround() && !p.isInWater()) return ObtainSteps.Result.DONE;
			if (now - since > 120_000) return fail("two minutes in the water");
			Level level = p.level();
			if (goal == null) {
				BlockPos f = p.blockPosition();
				double bd = 1e9;
				boolean sky = skyThroughWater(level, f);
				int rad = sky ? 16 : 6;
				for (int dx = -rad; dx <= rad; dx++) for (int dz = -rad; dz <= rad; dz++) for (int dy = -3; dy <= 8; dy++) {
					BlockPos c = f.offset(dx, dy, dz);
					if (tried.contains(c) || placedHere.contains(c.below())) continue;
					if (!level.getBlockState(c).canBeReplaced() || !level.getBlockState(c.above()).canBeReplaced()) continue;
					if (!level.getFluidState(c).isEmpty() || !level.getFluidState(c.above()).isEmpty()) continue;
					// on a floor, or on the water's top under the sky, where it swims up to (a flooded stair can come up under a
					// lake): the sky over it ends the step
					boolean floor = level.getBlockState(c.below()).isFaceSturdy(level, c.below(), Direction.UP);
					boolean top = level.getFluidState(c.below()).is(net.minecraft.tags.FluidTags.WATER) && level.canSeeSky(c) && !sky;
					if (!floor && !top) continue;
					double d = c.distSqr(f) + (top && !floor ? 16 : 0);
					if (d < bd) { bd = d; goal = c; }
				}
				// in open water: the nearest land, by the columns' tops, as far as 64 out (an ocean has no dry cell within 16)
				if (goal == null && sky) {
					for (int ring = 4; ring <= 64 && goal == null; ring += 4) {
						for (int k = 0; k < ring * 4 && goal == null; k++) {
							double a = Math.PI * 2 * k / (ring * 4);
							int x = (int) Math.floor(p.getX() + Math.cos(a) * ring), z = (int) Math.floor(p.getZ() + Math.sin(a) * ring);
							if (!level.hasChunk(x >> 4, z >> 4)) continue;
							BlockPos land = ObtainSteps.surface(level, x, z);
							if (land == null || tried.contains(land) || !level.getFluidState(land.below()).isEmpty() || !level.getFluidState(land).isEmpty()) continue;
							if (!level.getBlockState(land.below()).isFaceSturdy(level, land.below(), Direction.UP)) continue;
							goal = land;
						}
					}
				}
				if (goal == null) {
					// none near: the leaks shut, as a player stops water running into a stair, and then look again (it came up under a
					// lake and stood in its flooded stair)
					BlockPos me = p.blockPosition();
					List<BlockPos> leaks = new ArrayList<>();
					leaks.add(me.below());
					// in open water with no land near: one block underfoot to stand on, no more (the leaks' blocks made a floor that
					// the next look took for a dry cell, and it built a bridge out to sea a block at a time)
					if (!skyThroughWater(level, me)) {
						leaks.add(me.above(2));
						for (Direction d : Direction.Plane.HORIZONTAL) { leaks.add(me.relative(d)); leaks.add(me.above().relative(d)); }
					} else if (plugged > 0) {
						if (p.onGround()) return ObtainSteps.Result.DONE;
						return now - pluggedAt < 4000 ? ObtainSteps.Result.RUN : fail("no land near and no block to stand on");
					}
					for (BlockPos c : leaks) {
						if (level.getFluidState(c).isEmpty() || !level.getBlockState(c).canBeReplaced()) continue;
						ObtainSteps.Result r = placer.tick(mc, p, c);
						if (r == ObtainSteps.Result.RUN) return r;
						if (r == ObtainSteps.Result.DONE) { plugged++; placedHere.add(c.immutable()); if (pluggedAt == 0) pluggedAt = now; return ObtainSteps.Result.RUN; }
					}
					if (plugged > 0 && now - pluggedAt < 4000) return ObtainSteps.Result.RUN;
					if (plugged > 0 && pluggedAt == 0) { pluggedAt = now; return ObtainSteps.Result.RUN; }
					return fail("no dry cell near");
				}
				Exchange.emit("obtain", "action", "out of the water", "x", goal.getX(), "y", goal.getY(), "z", goal.getZ());
			}
			if (!Work.approach(mc, p, "dry-" + goal.toShortString(), Vec3.atBottomCenterOf(goal), now)) { tried.add(goal); goal = null; }
			return ObtainSteps.Result.RUN;
		}

		String detail() { return goal == null ? "a dry cell" : "to " + goal.toShortString(); }

		/** The leaks' blocks are placed by the click at the start of the tick: without it the placer only aimed. */
		void startTick(Minecraft mc) { placer.startTick(mc); }
	}

	// ---------- a night in a bed ----------

	/** Any bed it carries. */
	static boolean hasBed(LocalPlayer p) {
		var in = p.getInventory();
		for (int i = 0; i < 36; i++) if (AfkHelperClient.itemId(in.getItem(i)).endsWith("_bed")) return true;
		return false;
	}

	/** A bed standing within r blocks (one Soprano has just put down to sleep in). */
	static boolean bedNear(LocalPlayer p, int r) {
		BlockPos f = p.blockPosition();
		for (BlockPos q : BlockPos.betweenClosed(f.offset(-r, -3, -r), f.offset(r, 3, r)))
			if (p.level().getBlockState(q).getBlock() instanceof net.minecraft.world.level.block.BedBlock) return true;
		return false;
	}

	/**
	 * A slime within r blocks every way (not a magma cube), or a slime ball lying on the ground within r: one met
	 * on the way, taken along for the brew's cream (12 Nov 2026: the gold and the slime ball fetched in one stretch).
	 */
	/**
	 * How far a slime counts as met in M3.3 (13 Nov 2026: 24 before; the goudslijm smoke 2 saw twelve slimes in its gold mine,
	 * the nearest 41 blocks off, and took none). A server tracks monsters to about 48 blocks, so one further is not seen.
	 */
	static final int SLIME_SIGHT = 40;

	static boolean slimeNear(LocalPlayer p, int r) {
		// (as far up and down as across, and no line of sight: the slimes of the goudslijm smoke were 10 to 37 blocks under the
		// buddy in the rock, never in sight; the kill task digs its way to one it knows of)
		var box = p.getBoundingBox().inflate(r, r, r);
		// (a magma cube is no Slime in 26.3: the class asked holds the slimes alone)
		for (var e : p.level().getEntitiesOfClass(net.minecraft.world.entity.monster.cubemob.Slime.class, box))
			if (e.isAlive()) return true;
		for (var e : p.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, box))
			if ("slime_ball".equals(AfkHelperClient.itemId(e.getItem()))) return true;
		return false;
	}

	/**
	 * A bed lying on the ground within r blocks: one Soprano has just broken to take back. In that moment the bag held none
	 * and no bed stood, and the night's hole stopped the whole order (a gold order of the overworld route, after a night
	 * that did not pass: ten game minutes with no order and no hole on an iceberg, 10 Nov 2026).
	 */
	static boolean bedDropNear(LocalPlayer p, int r) {
		var box = p.getBoundingBox().inflate(r, 3, r);
		for (var e : p.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, box))
			if (AfkHelperClient.itemId(e.getItem()).endsWith("_bed")) return true;
		return false;
	}

	/** A night it may try a bed: one in the bag, the overworld, nothing hostile about, and the server lets a night pass. */
	static boolean bedNight(LocalPlayer p) {
		return System.currentTimeMillis() > bedRefusedUntil && hasBed(p) && p.level().dimension().equals(Level.OVERWORLD)
			&& !p.level().isThundering() && foes(p).isEmpty() && Sleepers.allows(p);
	}

	/** A bed that did not take it, or a night that did not pass: the hole until the morning. */
	static long bedRefusedUntil;

	/**
	 * The night in a bed it carries, as a player skips it (a night in the hole is seven minutes of waiting with one player
	 * on the server, nearly half of every day): put down on two free cells in front of it at dusk, lain in once the server
	 * lets it, and picked up again in the morning. Monsters about, or a night that does not pass in twenty seconds (others
	 * awake): up again, and the hole for the rest of the night.
	 */
	static final class Bed extends ObtainSteps.Exec {
		BlockPos foot;
		Direction dir;
		final long since = System.currentTimeMillis();
		long sleptAt;
		BlockPos clickOn;

		ObtainSteps.Result tick(Minecraft mc, LocalPlayer p, long now) {
			Level level = p.level();
			if (p.isSleeping()) {
				if (sleptAt == 0) { sleptAt = now; Exchange.emit("obtain", "action", "sleeps", "x", p.getBlockX(), "y", p.getBlockY(), "z", p.getBlockZ()); }
				if (now - sleptAt > 20_000) {
					p.connection.send(new net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket(p,
						net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action.STOP_SLEEPING));
					return refused("the night does not pass");
				}
				return ObtainSteps.Result.RUN;
			}
			if (sleptAt > 0) {
				// (woken by the morning is at 0, which the hole's night still counts; the bed's night ends at 23300)
				long tod = Math.floorMod(level.getOverworldClockTime(), 24000L);
				if (tod >= 12500 && tod < 23300) return refused("woken in the night");
				Exchange.emit("obtain", "action", "slept", "s", (now - sleptAt) / 1000);
				return ObtainSteps.Result.DONE;
			}
			if (!foes(p).isEmpty()) return refused("monsters about");
			if (now - since > 90_000) return refused("no bed for the night");
			// down first: two free cells in front, a sturdy floor under both
			if (foot == null || !isBed(level, foot)) {
				if (foot == null || (!level.getBlockState(foot).canBeReplaced() && !isBed(level, foot))) { foot = null; spot(level, p); }
				if (foot == null) return refused("no room for a bed");
				if (!ObtainSteps.hold(mc, p, st -> AfkHelperClient.itemId(st).endsWith("_bed"), "bed")) return ObtainSteps.Result.RUN;
				Follow.stand();
				Follow.aimExact(p, Vec3.atCenterOf(foot.below()).add(0, 0.5, 0));
				clickOn = foot.below();
				return ObtainSteps.Result.RUN;
			}
			clickOn = null;
			// in it once the server takes one; till then by it
			if (!Follow.bedtime(level)) { Follow.stand(); return ObtainSteps.Result.RUN; }
			if (!Follow.sleepAt(mc, p, foot) && now - since > 30_000) return refused("the bed does not take it");
			return ObtainSteps.Result.RUN;
		}

		ObtainSteps.Result refused(String w) {
			bedRefusedUntil = System.currentTimeMillis() + 10 * 60_000;
			Exchange.emit("obtain", "action", "no bed tonight", "why", w);
			return fail(w);
		}

		static boolean isBed(Level level, BlockPos q) { return level.getBlockState(q).getBlock() instanceof net.minecraft.world.level.block.BedBlock; }

		/** The cell in front of it and the one beyond, both free, on sturdy ground, the second for the head. */
		void spot(Level level, LocalPlayer p) {
			BlockPos f = p.blockPosition();
			Direction looks = p.getDirection();
			List<Direction> ds = new ArrayList<>(List.of(looks, looks.getClockWise(), looks.getCounterClockWise(), looks.getOpposite()));
			for (Direction d : ds) {
				BlockPos a = f.relative(d), b = a.relative(d);
				if (!free(level, a) || !free(level, b)) continue;
				if (!level.getBlockState(a.below()).isFaceSturdy(level, a.below(), Direction.UP) || !level.getBlockState(b.below()).isFaceSturdy(level, b.below(), Direction.UP)) continue;
				foot = a; dir = d; return;
			}
		}

		static boolean free(Level level, BlockPos c) {
			BlockState st = level.getBlockState(c);
			return st.canBeReplaced() && st.getFluidState().isEmpty() && level.getBlockState(c.above()).getFluidState().isEmpty();
		}

		/** The click that puts it down: facing its way, on the top of the floor under the foot. */
		void startTick(Minecraft mc) {
			LocalPlayer p = mc.player;
			BlockPos c = clickOn;
			if (c == null || p == null || mc.gameMode == null) return;
			if (!(mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult bh) || bh.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK
					|| !bh.getBlockPos().equals(c) || bh.getDirection() != Direction.UP || p.getDirection() != dir) return;
			long now = System.currentTimeMillis();
			if (now - clickAt < 600) return;
			clickAt = now;
			var r = AfkHelperClient.useOn(p, net.minecraft.world.InteractionHand.MAIN_HAND, bh);
			if (r.consumesAction()) Game.swingUse(p, net.minecraft.world.InteractionHand.MAIN_HAND);
		}

		long clickAt;

		BlockPos placedAt() { return foot != null && AfkHelperClient.mc().player != null && isBed(AfkHelperClient.mc().player.level(), foot) ? foot : null; }

		String placedBlock() {
			LocalPlayer p = AfkHelperClient.mc().player;
			return foot == null || p == null ? null : net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(p.level().getBlockState(foot).getBlock()).getPath();
		}

		String detail() { return sleptAt > 0 ? "in bed" : foot == null ? "a place for the bed" : "by the bed"; }
	}

	/** A block to spend: what the mod spends on a path (dirt, cobblestone and the like). */
	static boolean throwaway(net.minecraft.world.item.ItemStack s) {
		if (s.isEmpty()) return false;
		String id = AfkHelperClient.itemId(s);
		return PathShape.THROWAWAY.contains(id) || id.endsWith("terracotta") && !id.contains("glazed");
	}

	/** One of these that still has more than its last uses. */
	static boolean fresh(net.minecraft.world.entity.player.Inventory in, String id) {
		for (int i = 0; i < 36; i++) {
			var st = in.getItem(i);
			if (!st.isEmpty() && AfkHelperClient.itemId(st).equals(id) && !Work.nearlyBroken(st)) return true;
		}
		return false;
	}

	/**
	 * What fills the inventory as it digs and is worth nothing to an order: blocks beyond the stack and a half it keeps
	 * to build with, leaf litter, seeds, saplings beyond a few. Thrown out when the inventory is nearly full, one stack a
	 * tick at most (AltoClef throws its junk the same way when it needs room).
	 */
	static int junkSlot(LocalPlayer p) { return junkSlot(p, id -> false); }

	/** The same, with kinds that are to stay in the bag for now ({@code held}) left where they are. */
	static int junkSlot(LocalPlayer p, java.util.function.Predicate<String> held) {
		var in = p.getInventory();
		int blocks = 0;
		boolean deepJunk = deepJunkOn(p);
		for (int i = 0; i < 36; i++) {
			var st = in.getItem(i);
			if (st.isEmpty()) continue;
			String id = AfkHelperClient.itemId(st);
			if (held.test(id)) {
				if (throwaway(st) && !id.equals(Obtain.item())) blocks += st.getCount();
				continue;
			}
			if (id.equals("leaf_litter") || id.equals("wheat_seeds") || id.equals("poisonous_potato") || id.equals("rotten_flesh")
					|| id.equals("short_grass") || id.equals("dead_bush") || id.equals("pointed_dripstone")) return i;
			if (deepJunk && DEEP_JUNK.contains(id) && !id.equals(Obtain.item())) return i;
			if (id.endsWith("_sapling") && st.getCount() > 4 && Obtain.item() != null && !Obtain.item().equals(id)) return i;
			// a tool worn to its last uses: the plan counts it as gone already (four of them filled a bag); thrown only when one
			// that still works is there, for the last uses are its way out of a hole
			if (st.isDamageableItem() && Work.nearlyBroken(st) && !id.equals(Obtain.item()) && fresh(in, id)) return i;
			if (throwaway(st) && !id.equals(Obtain.item())) blocks += st.getCount();
		}
		// over 96 blocks: the first stack that is no cobblestone, then cobblestone above a stack. cobblestone is what
		// Soprano's kit asks for (blocks, its stone tools), so a stack of it thrown was picked up again by its own pickup the
		// next second, and round it went, thrown and picked up
		if (blocks > 96) {
			int cobble = 0, cobbleSlot = -1;
			for (int i = 0; i < 36; i++) {
				var st = in.getItem(i);
				if (!throwaway(st)) continue;
				String id = AfkHelperClient.itemId(st);
				if (id.equals(Obtain.item()) || held.test(id)) continue;
				if (!id.equals("cobblestone")) return i;
				cobble += st.getCount();
				cobbleSlot = i;
			}
			if (cobble > 64) return cobbleSlot;
		}
		return -1;
	}

	/**
	 * What the deep mine fills the bag with and nothing on the route's deep stretch wants (the copper armour is made before it,
	 * M2.1): thrown like the leaf litter above with the autopilot's bag_junk_first (a fix of 13 Nov 2026, on). With a nearly
	 * full bag of these the autopilot's own full-bag throw went for the bed and the furnace first (M3.3's smoke 5).
	 */
	static final java.util.Set<String> DEEP_JUNK = java.util.Set.of("cinnabar", "sulfur", "clay_ball", "bone", "raw_copper");

	static boolean deepJunkOn(LocalPlayer p) {
		return Alto.present() && adris.altoclef.util.helpers.Tuning.get("bag_junk_first", 0) > 0 && Alto.routeDeep(p);
	}

	private record Thrown(String id, net.minecraft.world.phys.Vec3 at, long when) {}
	private static final java.util.List<Thrown> THROWN = new java.util.ArrayList<>();

	/** The bed refused and the junk thrown away, kept past an order (RunMemories). */
	static synchronized int remembered() {
		return THROWN.size() + (bedRefusedUntil > System.currentTimeMillis() ? 1 : 0);
	}

	static synchronized void forgetRun() {
		THROWN.clear();
		bedRefusedUntil = 0;
	}

	/** Junk the buddy threw away here: no step picks it up again. */
	static void threw(String id, net.minecraft.world.phys.Vec3 at) {
		long now = System.currentTimeMillis();
		THROWN.removeIf(t -> now - t.when() > 120_000);
		THROWN.add(new Thrown(id, at, now));
	}

	/** An item on the ground of a kind the buddy threw away within six blocks of it in the last two minutes. */
	static boolean thrown(net.minecraft.world.entity.item.ItemEntity it) {
		long now = System.currentTimeMillis();
		String id = AfkHelperClient.itemId(it.getItem());
		for (Thrown t : THROWN) if (now - t.when() <= 120_000 && t.id().equals(id) && it.position().distanceToSqr(t.at()) < 36) return true;
		return false;
	}

	static int throwaways(LocalPlayer p) {
		int n = 0;
		var in = p.getInventory();
		for (int i = 0; i < 36; i++) if (throwaway(in.getItem(i))) n += in.getItem(i).getCount();
		return n;
	}

	/**
	 * What the ground is made of where the buddy digs: Work.ground's, and the terracotta of the badlands (a spawn there).
	 */
	static boolean earth(BlockState st) {
		return Work.ground(st) || st.is(net.minecraft.tags.BlockTags.TERRACOTTA) || st.is(net.minecraft.world.level.block.Blocks.TERRACOTTA)
			|| st.is(net.minecraft.world.level.block.Blocks.RED_SAND);
	}

	/** How many cells of nothing to stand on lie under this one, itself counted, up to sixteen. */
	static int drop(Level level, BlockPos c) {
		int n = 0;
		for (BlockPos q = c; n < 16; q = q.below(), n++) {
			BlockState st = level.getBlockState(q);
			if (!st.getCollisionShape(level, q).isEmpty() || !st.getFluidState().isEmpty()) return n;
		}
		return n;
	}

	/**
	 * Hanging over a deep drop: on the ground, held up by the edge of a block while its middle is over more than three
	 * cells of nothing. Whatever else wants the keys (getting loose, a step that gave up), it stays crouched and steps
	 * back onto the block it stands on first: letting go of the crouch there is a fall (once of 41 blocks).
	 */
	static boolean edge(Minecraft mc, LocalPlayer p) {
		if (!p.onGround() || p.isInWater() || p.getAbilities().flying) return false;
		Level level = p.level();
		BlockPos mid = p.blockPosition();
		if (!level.getBlockState(mid.below()).getCollisionShape(level, mid.below()).isEmpty() || drop(level, mid.below()) <= 3) {
			if (edging) { HeldKeys.set(mc.options.keyShift, false); HeldKeys.set(mc.options.keyDown, false); HeldKeys.set(mc.options.keyUp, false); edging = false; }
			return false;
		}
		BlockPos on = p.getOnPos();
		Vec3 back = Vec3.atBottomCenterOf(on.above());
		edging = true;
		HeldKeys.set(mc.options.keyShift, true);
		HeldKeys.set(mc.options.keySprint, false);
		Follow.aim(p, new Vec3(back.x, p.getEyeY() - 1.2, back.z));
		HeldKeys.set(mc.options.keyUp, true);
		return true;
	}

	private static boolean edging;

	/**
	 * Crouched at a brink, as a player crosses one: on the ground, walking, and the ground half a block ahead in the way
	 * it moves (or faces, with the key down) drops more than three with no water at the bottom, the crouch held over
	 * whatever else pressed the keys; vanilla then does not let it walk off. A bridge that would not start walked off a
	 * cliff of 15 blocks, 19 to 6. The crouch it holds it lets go of itself, the tick the brink is behind.
	 */
	static void brink(Minecraft mc, LocalPlayer p) {
		boolean want = false;
		if (p.onGround() && !p.isInWater() && !p.getAbilities().flying && p.getVehicle() == null) {
			Vec3 v = p.getDeltaMovement();
			double h = Math.hypot(v.x, v.z);
			double dx, dz;
			if (h > 0.02) { dx = v.x / h; dz = v.z / h; }
			else if (mc.options.keyUp.isDown()) { double a = Math.toRadians(p.getYRot()); dx = -Math.sin(a); dz = Math.cos(a); }
			else { dx = 0; dz = 0; }
			if (dx != 0 || dz != 0) {
				Level level = p.level();
				BlockPos ahead = BlockPos.containing(p.getX() + dx * 0.6, p.getY() - 0.5, p.getZ() + dz * 0.6);
				if (!ahead.equals(p.getOnPos()) && level.getBlockState(ahead).getCollisionShape(level, ahead).isEmpty()) {
					int d = drop(level, ahead);
					BlockPos bottom = ahead.below(d);
					want = d > 3 && level.getFluidState(bottom).isEmpty() && level.getFluidState(bottom.above()).isEmpty();
				}
			}
		}
		// and no jump out over it: a crouch keeps it from walking off, not from jumping off
		if (want) { HeldKeys.set(mc.options.keyShift, true); HeldKeys.set(mc.options.keySprint, false); HeldKeys.set(mc.options.keyJump, false); braced = true; }
		else if (braced) { braced = false; if (!edging) HeldKeys.set(mc.options.keyShift, false); }
	}

	private static boolean braced;

	/**
	 * A cell beside its feet, on its own level, with more than three cells of nothing under it and no water at the bottom.
	 */
	static boolean brinkNear(LocalPlayer p) {
		if (!p.onGround() || p.isInWater()) return false;
		Level level = p.level();
		BlockPos f = p.blockPosition();
		for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
			if (dx == 0 && dz == 0) continue;
			BlockPos c = f.offset(dx, -1, dz);
			if (!level.getBlockState(c).getCollisionShape(level, c).isEmpty()) continue;
			int d = drop(level, c);
			BlockPos bottom = c.below(d);
			if (d > 3 && level.getFluidState(bottom).isEmpty() && level.getFluidState(bottom.above()).isEmpty()) return true;
		}
		return false;
	}

	static boolean solid(Level level, BlockPos c) {
		BlockState st = level.getBlockState(c);
		return st.isCollisionShapeFullBlock(level, c) && st.getFluidState().isEmpty();
	}

	/**
	 * A block placed into a cell from a solid face beside it: the crosshair on that face within reach and in sight, the
	 * click at the start of the tick. One placement at a time.
	 */
	static final class Placer {
		BlockPos cell, on;
		Direction face;
		long clickAt;
		int centred;
		/** What it places: a block to spend, or what the caller says (obsidian for a portal). */
		java.util.function.Predicate<net.minecraft.world.item.ItemStack> item = ObtainSafe::throwaway;
		String itemName = "blocks";

		Placer() {}

		Placer(java.util.function.Predicate<net.minecraft.world.item.ItemStack> item, String name) { this.item = item; this.itemName = name; }

		/** RUN while it aims or clicks, DONE once the cell is solid, FAIL when it cannot. */
		ObtainSteps.Result tick(Minecraft mc, LocalPlayer p, BlockPos target) {
			Level level = p.level();
			if (solid(level, target)) { cell = null; return ObtainSteps.Result.DONE; }
			if (!level.getBlockState(target).canBeReplaced()) return ObtainSteps.Result.FAIL;
			if (!target.equals(cell)) { cell = target.immutable(); on = null; }
			if (!has(p)) return ObtainSteps.Result.FAIL;
			// its own body in the cell (a step off the middle of its own, in a one-wide tunnel): the game refuses the block
			// there (the useOn failed for a minute in a tunnel at night), so to the middle first, crouched
			if (p.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(target))) {
				Vec3 mid = Vec3.atBottomCenterOf(p.blockPosition());
				if (Math.hypot(mid.x - p.getX(), mid.z - p.getZ()) > 0.04 && centred++ < 60) {
					Follow.aim(p, new Vec3(mid.x, p.getEyeY() - 1.2, mid.z));
					HeldKeys.set(mc.options.keyShift, true);
					HeldKeys.set(mc.options.keyUp, true);
					return ObtainSteps.Result.RUN;
				}
			}
			if (centred > 0) { HeldKeys.set(mc.options.keyUp, false); HeldKeys.set(mc.options.keyShift, false); centred = 0; }
			if (!ObtainSteps.hold(mc, p, item, itemName)) return ObtainSteps.Result.RUN;
			Vec3 eye = p.getEyePosition();
			if (on == null) {
				double best = 1e9;
				for (Direction d : Direction.values()) {
					BlockPos n = target.relative(d);
					if (!solid(level, n) || level.getBlockEntity(n) != null) continue;
					Vec3 pt = Vec3.atCenterOf(n).add(Vec3.atLowerCornerOf(d.getOpposite().getUnitVec3i()).scale(0.5));
					double dist = eye.distanceTo(pt);
					if (dist > 4.3) continue;
					BlockHitResult hit = level.clip(new net.minecraft.world.level.ClipContext(eye, pt.add(Vec3.atLowerCornerOf(d.getUnitVec3i()).scale(0.02)),
						net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, p));
					if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(n) || hit.getDirection() != d.getOpposite()) continue;
					if (dist < best) { best = dist; on = n; face = d.getOpposite(); }
				}
				if (on == null) return ObtainSteps.Result.FAIL;
			}
			Vec3 pt = Vec3.atCenterOf(on).add(Vec3.atLowerCornerOf(face.getUnitVec3i()).scale(0.5));
			Follow.stand();
			Follow.aimExact(p, pt);
			return ObtainSteps.Result.RUN;
		}

		void startTick(Minecraft mc) {
			LocalPlayer p = mc.player;
			if (cell == null || on == null || p == null || mc.gameMode == null) return;
			if (!(mc.hitResult instanceof BlockHitResult bh) || bh.getType() != HitResult.Type.BLOCK || !bh.getBlockPos().equals(on) || bh.getDirection() != face) return;
			if (!item.test(p.getMainHandItem())) return;
			long now = System.currentTimeMillis();
			if (now - clickAt < 300) return;
			clickAt = now;
			InteractionResult r = AfkHelperClient.useOn(p, InteractionHand.MAIN_HAND, bh);
			if (r.consumesAction()) Game.swingUse(p, InteractionHand.MAIN_HAND);
			if (++clicks % 8 == 1) Exchange.emit("obtain", "action", "place click", "x", cell.getX(), "y", cell.getY(), "z", cell.getZ(),
				"on", on.toShortString(), "face", face.getName(), "result", r.toString(), "clicks", clicks);
		}

		int clicks;

		boolean has(LocalPlayer p) {
			var in = p.getInventory();
			for (int i = 0; i < 36; i++) if (item.test(in.getItem(i))) return true;
			return false;
		}
	}

	/**
	 * The night in the ground. Under the open sky: the three blocks under the buddy broken one by one (each only when what
	 * lies under it holds it and no lava or water is near), so that it stands at the bottom of a hole one wide and three
	 * deep; then every open side of its two cells and the cell over its head shut with blocks, the top against the walls.
	 * Underground the same without the digging. Then still until the morning, and out again by a pillar (the walker's).
	 */
	static final class Shelter extends ObtainSteps.Exec {
		final ObtainSteps.Breaker breaker = new ObtainSteps.Breaker();
		final Placer placer = new Placer();
		BlockPos bottom;
		int dug, extra, elsewhere;
		/** Cells where it could not shut itself in: no site within two blocks of them. */
		final java.util.Set<BlockPos> bad = new java.util.HashSet<>();
		boolean sealed, open;
		long since = System.currentTimeMillis(), noted, stuckSince;
		/** The cells it shut with a block of its own: the morning breaks the roof only when it is one of them. */
		final java.util.Set<BlockPos> placed = new java.util.HashSet<>();

		/** For a wound and not the night: done once healed, or after a while when the food bar does not heal. */
		final boolean wound;
		/** Dug in from shooters: done once none has seen the buddy for a few seconds. */
		boolean duck;
		/** From monsters on their way under the ground: the walls at once, no digging down first. */
		boolean walls;
		long lastShooter = System.currentTimeMillis();

		Shelter() { this(false); }

		Shelter(boolean wound) { this.wound = wound; }

		/** Where it is: for the status (five minutes of a night shelter that never shut said nothing). */
		String phase = "";

		String detail() { return phase + ", dug " + dug + (sealed ? ", shut" : "") + (bottom != null ? ", bottom " + bottom.toShortString() : ""); }

		/** Down under the ground at night, with a step that wants the surface: until the morning. */
		boolean tillMorning;

		boolean needed(LocalPlayer p, long now) {
			if (needsShelter(p)) return true;
			if (tillMorning && night(p.level())) return true;
			// shut in for the night and monsters still by the hole in the morning: stay in until they are gone, till the middle
			// of the morning at most. out at 1000 it met two skeletons and a creeper at the hole's mouth, in the shade where
			// they do not burn, and was blown up
			if (!wound && sealed && (shooterNear(p) || !foes(p).isEmpty())) {
				long t = Math.floorMod(p.level().getOverworldClockTime(), 24000L);
				if (t < 4000) return true;
			}
			if (!wound) return false;
			// a shooter still about that sees it: not out of the hole yet, for three minutes at most. Ducked from shooters it
			// stays down a few seconds after the last one is out of sight (a skeleton's aggression only shows while it draws)
			if (shooterNear(p) && (now - since < 180_000 || duck)) { lastShooter = now; return true; }
			// walled in from monsters under the ground: while one is about (a zombie stands by the wall a while)
			if (duck && !foes(p).isEmpty()) { lastShooter = now; return true; }
			if (duck && now - lastShooter < 6000) return true;
			// and healed nearly full before it goes back out to them, when it can heal (Soprano: until 18)
			if (duck && p.getHealth() < 18 && canHeal(p) && now - since < 240_000) return true;
			if (duck) return false;
			if (p.getHealth() >= p.getMaxHealth() - 2) return false;
			return now - since < (canHeal(p) ? 240_000 : 45_000);
		}

		/** Out of the water before the hole, when the night finds the buddy swimming. */
		Dry dry;

		/** Out under the ground, away from shooters that stayed by the hole: 24 blocks, then the next step takes over. */
		ObtainSteps.Tunnel away;
		final ObtainSteps.Pickup awayPickup = new ObtainSteps.Pickup();

		ObtainSteps.Result tick(Minecraft mc, LocalPlayer p, long now) {
			Level level = p.level();
			// three minutes down and the shooters still by the hole: no climb out among them, a tunnel away under the ground
			// or twenty seconds that a side would not shut, under the ground: away rather than wait in the open cell
			boolean stuck = stuckSince != 0 && now - stuckSince > 20_000 && !level.canSeeSky(p.blockPosition());
			if (duck && (now - since >= 180_000 || stuck) && (shooterNear(p) || !foes(p).isEmpty()) && !needsShelter(p)) {
				uncrouch(mc);
				if (away == null) {
					double x = 0, z = 0;
					for (var m : level.getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class, p.getBoundingBox().inflate(24, 12, 24)))
						if (m.isAlive()) { x += m.getX() - p.getX(); z += m.getZ() - p.getZ(); }
					double len = Math.max(1e-6, Math.hypot(x, z));
					BlockPos goal = BlockPos.containing(p.getX() - x / len * 24, p.getY(), p.getZ() - z / len * 24);
					away = new ObtainSteps.Tunnel(p, p.getBlockY(), awayPickup, goal);
					Exchange.emit("obtain", "action", "shelter", "what", "tunnels away from shooters", "x", goal.getX(), "z", goal.getZ());
				}
				if (away.steps >= 24) return ObtainSteps.Result.DONE;
				ObtainSteps.Result r = away.tick(mc, p, now);
				return r == ObtainSteps.Result.FAIL ? ObtainSteps.Result.DONE : ObtainSteps.Result.RUN;
			}
			if (!needed(p, now)) {
				uncrouch(mc);
				// morning: the roof off, and the walk out is the next step's (the walker pillars up out of the hole)
				BlockPos roof = p.blockPosition().above(2);
				// never a roof with water or lava over or beside it: it once opened its roof into an aquifer in the morning and
				// stood in the water, where no station goes down and no tunnel starts; the walls lead out instead
				if (sealed && !open && (ObtainSteps.wet(level, roof) || !level.getFluidState(roof).isEmpty())) open = true;
				// and only a roof it placed itself: the rock over a shelter under the ground stays, as does whatever stands over
				// a cell it was not shut in (moved, or respawned under someone's build)
				if (sealed && !open && placed.contains(roof) && solid(level, roof) && level.getBlockEntity(roof) == null) {
					ObtainSteps.Result r = breaker.tick(mc, p, roof, now);
					if (r == ObtainSteps.Result.RUN) return r;
					open = true;
				}
				Exchange.emit("obtain", "action", "shelter", "what", wound ? "healed" : "morning", "s", (now - since) / 1000, "hp", p.getHealth());
				return ObtainSteps.Result.DONE;
			}
			// in the water: to land first, the hole is dug on a floor. at sea after dusk it waited for a floor under it that
			// never came, a minute without a word, until a drowned killed it in four blows (an order of Soprano's, whose route
			// never looks at the water the way the buddy's own steps do)
			if (!sealed && (p.isInWater() || wetUnder(p))) {
				if (dry == null) {
					dry = new Dry();
					Exchange.emit("obtain", "action", "shelter", "what", "to land first");
				}
				phase = "to land";
				ObtainSteps.Result r = dry.tick(mc, p, now);
				if (r == ObtainSteps.Result.RUN) return r;
				dry = null;
			}
			if (!p.onGround()) return ObtainSteps.Result.RUN;
			BlockPos f = p.blockPosition();
			// from shooters: three down where it stands when the ground is good, or else walled in where it stands, never a walk
			// to a site (on the slope of a cave the walk and the arrows' knock-back took it down four blocks and three arrows)
			if (duck && bottom == null && dug == 0 && (walls || !diggable(level, f))) bottom = f;
			// under the open sky: to a spot within a few steps with three blocks of ground under it, and dig there
			if (bottom == null && dug == 0 && open(level, f) && !diggable(level, f)) {
				if (site == null || now - siteSince > 15_000) { site = site(level, p, bad); siteSince = now; }
				// a block off counts as there: the walker calls a cell beside it arrived and stops, and it stood two minutes a
				// block from its site on the sand at dusk; and a site not reached in twenty seconds is given up for walls where it
				// stands
				if (site != null && !f.equals(site) && f.distManhattan(site) > 1 && now - since < 20_000) {
					phase = "to a site " + site.toShortString();
					if (!Work.approach(mc, p, "shelter-" + site.toShortString(), Vec3.atBottomCenterOf(site), now)) site = null;
					return ObtainSteps.Result.RUN;
				}
				// a block off on the same level: a step onto it by hand, for the walker plans no walk of one cell
				if (site != null && !f.equals(site) && f.distManhattan(site) <= 1 && now - since < 25_000) {
					phase = "steps onto the site " + site.toShortString();
					Vec3 mid = Vec3.atBottomCenterOf(site);
					Follow.aim(p, new Vec3(mid.x, p.getEyeY() - 1.0, mid.z));
					HeldKeys.set(mc.options.keyShift, false);
					HeldKeys.set(mc.options.keyUp, true);
					stepping = true;
					return ObtainSteps.Result.RUN;
				}
				if (site == null && now - noted > 30_000) { noted = now; Exchange.emit("obtain", "action", "shelter", "what", "no ground to dig in near"); }
			}
			// on the edge of a hole under its own cell (its block went, a toe on the next block holds it): into it, not
			// crouched, for crouched it stays on the edge, and the roof over the edge touches nothing (five minutes of "cannot
			// shut" on open grass, and a skeleton's arrows)
			if (bottom == null && !walls && hole(level, f)) {
				phase = "steps into the hole";
				Vec3 mid = Vec3.atBottomCenterOf(f);
				Follow.aim(p, new Vec3(mid.x, p.getEyeY() - 1.2, mid.z));
				HeldKeys.set(mc.options.keyShift, false);
				HeldKeys.set(mc.options.keyUp, true);
				stepping = true;
				return ObtainSteps.Result.RUN;
			}
			if (stepping) { HeldKeys.set(mc.options.keyUp, false); stepping = false; }
			// down: three under the open sky
			if (bottom == null && dug < 3 && open(level, f)) {
				BlockPos under = f.below();
				if (!safeToDig(level, under)) {
					if (dug == 0) bottom = f;   // here it does not dig: shut where it stands
					else bottom = f;
				} else {
					phase = "digs " + under.toShortString();
					ObtainSteps.Result r = breaker.tick(mc, p, under, now);
					if (r == ObtainSteps.Result.DONE) dug++;
					else if (r == ObtainSteps.Result.FAIL) bottom = f;
					return ObtainSteps.Result.RUN;
				}
			}
			if (bottom == null) bottom = f;
			if (!f.equals(bottom) && f.distSqr(bottom) > 2) bottom = f;
			// every open side shut; from shooters the side they shoot from first
			for (BlockPos c : duck ? facing(level, p, f) : around(f)) {
				if (solid(level, c)) continue;
				// no block to shut it with: one more from underfoot, where the walls stay whole
				if (throwaways(p) == 0 && safeToDig(level, f.below()) && extra < 3) {
					ObtainSteps.Result d = breaker.tick(mc, p, f.below(), now);
					if (d == ObtainSteps.Result.DONE) { extra++; bottom = null; }
					else if (d == ObtainSteps.Result.FAIL) extra = 3;
					return ObtainSteps.Result.RUN;
				}
				// something in the cell that is no whole block and takes no block in its place (its own bed, put down for a night
				// the monsters about refused it: seven minutes it tried to shut the bed's cell): broken first, the bed back in the
				// bag, then the block
				BlockState in = level.getBlockState(c);
				if (!in.isAir() && !in.canBeReplaced() && in.getDestroySpeed(level, c) >= 0) {
					phase = "clears " + c.toShortString();
					ObtainSteps.Result b = breaker.tick(mc, p, c, now);
					if (b == ObtainSteps.Result.RUN) return b;
				}
				phase = "shuts " + c.toShortString();
				placed.add(c.immutable());
				ObtainSteps.Result r = placer.tick(mc, p, c);
				if (r == ObtainSteps.Result.RUN) return r;
				// no face of a neighbour in sight (the cell behind it over the step of a staircase, with a hole under it): the hole
				// first, as a player does, and its top is the face for this one
				if (r == ObtainSteps.Result.FAIL) {
					BlockPos under = c.below();
					if (!solid(level, under) && level.getBlockState(under).canBeReplaced() && !under.equals(f) && !under.equals(f.above())
							&& level.getFluidState(under).isEmpty()) {
						ObtainSteps.Result u = placer.tick(mc, p, under);
						if (u != ObtainSteps.Result.FAIL) return ObtainSteps.Result.RUN;
					}
					// the roof of a box on open ground touches nothing: a block on top of one of the walls first, and the roof against
					// its side (else eight walls and no roof)
					if (c.equals(f.above(2))) {
						for (Direction d : Direction.Plane.HORIZONTAL) {
							BlockPos n = c.relative(d);
							if (!solid(level, n.below()) || !level.getBlockState(n).canBeReplaced() || !level.getFluidState(n).isEmpty()) continue;
							ObtainSteps.Result u = placer.tick(mc, p, n);
							if (u != ObtainSteps.Result.FAIL) return ObtainSteps.Result.RUN;
						}
					}
				}
				// walled in on open ground from shooters: the roof is there only when something touches it (from inside, the top of
				// a wall is out of sight). So once the walls stand, one down: the old head cell is the roof's cell then, the walls
				// round it are what the roof goes against, and the ground round the new feet is wall. Walls alone let a skeleton on
				// the slope beside it shoot in from above, at one block
				if (r == ObtainSteps.Result.FAIL && duck && c.equals(f.above(2)) && wallsUp(level, f) && safeToDig(level, f.below()) && extra < 3) {
					phase = "one down, for a roof";
					ObtainSteps.Result d = breaker.tick(mc, p, f.below(), now);
					if (d == ObtainSteps.Result.DONE) { extra++; bottom = null; }
					else if (d == ObtainSteps.Result.FAIL) extra = 3;
					return ObtainSteps.Result.RUN;
				}
				if (r == ObtainSteps.Result.FAIL && duck && c.equals(f.above(2))) continue;
				// the roof of a night shelter on open ground touches nothing: down after all, where the ground will be dug
				if (r == ObtainSteps.Result.FAIL && !duck && c.equals(f.above(2)) && dug == 0 && diggable(level, f)) { bottom = null; dug = 0; return ObtainSteps.Result.RUN; }
				if (r == ObtainSteps.Result.FAIL) {
					// a side with nothing to put a block against (an open cave round it): one down, and the floor round it is the wall
					// (three minutes it waited in a cave with a skeleton about)
					if (safeToDig(level, f.below()) && extra < 3 && !c.equals(f.above(2))) {
						phase = "one down, for a wall " + c.toShortString();
						ObtainSteps.Result d = breaker.tick(mc, p, f.below(), now);
						if (d == ObtainSteps.Result.DONE) { extra++; bottom = null; }
						else if (d == ObtainSteps.Result.FAIL) extra = 3;
						return ObtainSteps.Result.RUN;
					}
					if (stuckSince == 0) stuckSince = now;
					if (now - noted > 30_000) { noted = now; Exchange.emit("obtain", "action", "shelter", "what", "cannot shut", "x", c.getX(), "y", c.getY(), "z", c.getZ(), "blocks", throwaways(p)); }
					// twenty seconds and still not shut: another site, not this one again (on a mountain's slope it waited seven
					// minutes for a roof that would not go on, and a skeleton shot it from 13 to 1)
					if (!duck && now - stuckSince > 20_000 && elsewhere < 3) {
						elsewhere++;
						bad.add(f.immutable());
						bottom = null; site = null; siteSince = 0; dug = 0; extra = 0; stuckSince = 0; since = now;
						Exchange.emit("obtain", "action", "shelter", "what", "tries elsewhere", "x", f.getX(), "y", f.getY(), "z", f.getZ(), "try", elsewhere);
						return ObtainSteps.Result.RUN;
					}
					return ObtainSteps.Result.WAIT;
				}
			}
			stuckSince = 0;
			if (!sealed) { sealed = true; Exchange.emit("obtain", "action", "shelter", "what", "shut in", "x", f.getX(), "y", f.getY(), "z", f.getZ(), "dug", dug); }
			Follow.stand();
			// crouched while it waits: a crouching player's name shows through no block and not past 32 blocks, so another player
			// who comes by in the dark does not see where it hides. (only once shut in: crouched it stays on the edge of the hole)
			HeldKeys.set(mc.options.keyShift, true);
			crouched = true;
			return ObtainSteps.Result.WAIT;
		}

		/** Holding the crouch of the wait in the hole. */
		boolean crouched;

		void uncrouch(Minecraft mc) {
			if (crouched) HeldKeys.set(mc.options.keyShift, false);
			crouched = false;
		}

		@Override
		void stop() { uncrouch(AfkHelperClient.mc()); }

		BlockPos site;
		long siteSince;

		/**
		 * Standing in the open: nothing overhead and nothing beside the cell over its head to shut it against (under the sky,
		 * and under a tree's leaves too, where the sky is not to be seen and the roof still has no wall).
		 */
		static boolean open(Level level, BlockPos f) {
			BlockPos roof = f.above(2);
			if (solid(level, roof)) return false;
			for (Direction d : Direction.Plane.HORIZONTAL) if (solid(level, roof.relative(d))) return false;
			return true;
		}

		/** Three blocks of ground to dig under this standing cell, and solid under them. */
		static boolean diggable(Level level, BlockPos f) {
			return safeToDig(level, f.below()) && safeToDig(level, f.below(2)) && safeToDig(level, f.below(3));
		}

		/**
		 * The nearest standing cell under the open sky within sixteen blocks where the buddy can dig in: two deep leaves the
		 * top without a wall to shut it against (as on a buried ruin of terracotta).
		 */
		static BlockPos site(Level level, LocalPlayer p) { return site(level, p, java.util.Set.of()); }

		static BlockPos site(Level level, LocalPlayer p, java.util.Set<BlockPos> bad) {
			BlockPos f = p.blockPosition(), best = null;
			double bd = 1e9;
			for (int dx = -16; dx <= 16; dx++) for (int dz = -16; dz <= 16; dz++) {
				BlockPos c = ObtainSteps.surface(level, f.getX() + dx, f.getZ() + dz);
				if (c == null || Math.abs(c.getY() - f.getY()) > 4 || !diggable(level, c)) continue;
				if (bad.stream().anyMatch(b -> Math.abs(b.getX() - c.getX()) <= 2 && Math.abs(b.getZ() - c.getZ()) <= 2)) continue;
				double d = dx * dx + dz * dz + Math.abs(c.getY() - f.getY());
				if (d < bd) { bd = d; best = c; }
			}
			return best;
		}

		boolean stepping;

		/** Nothing under its cell, and a floor one to three below it: a hole the buddy is on the edge of. */
		static boolean hole(Level level, BlockPos f) {
			BlockPos u = f.below();
			if (!level.getBlockState(u).canBeReplaced() || !level.getFluidState(u).isEmpty()) return false;
			for (int d = 2; d <= 4; d++) {
				BlockPos q = f.below(d);
				if (!level.getFluidState(q).isEmpty()) return false;
				if (!level.getBlockState(q).canBeReplaced()) return true;
			}
			return false;
		}

		/**
		 * The cells round the buddy, those towards the nearest shooter first (head before feet), the roof first only from
		 * above.
		 */
		static List<BlockPos> facing(Level level, LocalPlayer p, BlockPos f) {
			net.minecraft.world.entity.Entity near = null;
			double nd = 1e9;
			for (var m : level.getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class, p.getBoundingBox().inflate(24, 12, 24))) {
				if (!m.isAlive() || !shoots(m)) continue;
				double d = m.distanceToSqr(p);
				if (d < nd) { nd = d; near = m; }
			}
			List<BlockPos> cells = around(f);
			if (near == null) return cells;
			double sx = near.getX() - p.getX(), sz = near.getZ() - p.getZ(), len = Math.max(1e-6, Math.hypot(sx, sz));
			double sy = near.getEyeY() - p.getEyeY();
			final double ux = sx / len, uz = sz / len, up = sy;
			cells.sort(java.util.Comparator.comparingDouble((BlockPos c) -> {
				int dx = c.getX() - f.getX(), dz = c.getZ() - f.getZ();
				if (dx == 0 && dz == 0) return up > 3 ? -0.9 : 2.0;   // the roof
				return -(dx * ux + dz * uz) - (c.getY() > f.getY() ? 0.01 : 0);
			}));
			return cells;
		}

		/** The cells to be solid round the buddy: the four sides of its feet and its head, and over its head. */
		/** The eight sides of its two cells are blocks. */
		static boolean wallsUp(Level level, BlockPos f) {
			for (Direction d : Direction.Plane.HORIZONTAL) if (!solid(level, f.relative(d)) || !solid(level, f.above().relative(d))) return false;
			return true;
		}

		static List<BlockPos> around(BlockPos f) {
			List<BlockPos> out = new ArrayList<>();
			out.add(f.above(2));
			for (Direction d : Direction.Plane.HORIZONTAL) { out.add(f.above().relative(d)); out.add(f.relative(d)); }
			return out;
		}

		/** Breaking this block under the buddy: it is ground, and under it is ground too, with no lava or water about. */
		static boolean safeToDig(Level level, BlockPos b) {
			BlockState st = level.getBlockState(b);
			if (st.getDestroySpeed(level, b) < 0 || level.getBlockEntity(b) != null || !earth(st)) return false;
			if (!solid(level, b.below())) return false;
			for (Direction d : Direction.values()) if (!level.getFluidState(b.relative(d)).isEmpty() || !level.getFluidState(b.below().relative(d)).isEmpty()) return false;
			return true;
		}

		/** The walls' click, and the way to land's (its leaks): each clicks only on the face it aims at. */
		void startTick(Minecraft mc) { placer.startTick(mc); if (dry != null) dry.startTick(mc); }
	}
}
