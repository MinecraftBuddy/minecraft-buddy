package net.milenco.buddy.client;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.milenco.buddy.version.Game;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/**
 * Protection for the alt while it follows someone: when the followed player is attacked, the alt takes its best weapon
 * and goes for the attacker, and it joins in when the player starts a fight. Whatever hits the followed player,
 * whatever hits the alt itself, and the hostile mobs the followed player hits, the alt fights with its best weapon.
 * Players only with the PvP switch on: two accounts of one player against one other is no fair fight, and the server's
 * own rules forbid players a mod that helps in a fight.
 * <p>
 * Who hit whom the client knows without listening to packets: every damage event the server sends carries the
 * attacker, and the client keeps it on the one hit ({@code getLastDamageSource}, for two seconds) with
 * {@code hurtTime} counting down from 10. An arrow names its shooter. This class decides whom to fight and does the
 * hit; walking there and the jump for a critical hit are {@link Follow}'s.
 */
final class Guard {
	/**
	 * On by default, also after a restart. Against players as the config says ({@code guardPlayers}, on since 24 Oct 2026,
	 * step 4 of the plan for fighting, which has the alt defend the player and attack along; off by default and after
	 * every restart before that); a switch by the sign, the panel or a command is kept there.
	 */
	private static boolean on = true, pvp = AfkConfig.get().guardPlayers;
	private static Entity enemy;
	/** Why he fights the one he fights: beschermt, verdedigt, helpt, onderschept. */
	private static String role = "";
	/** The enemy attacks the alt itself: then he fights on far from the followed player, and nearly dead too. */
	private static boolean self;
	private static long busyAt, nextArm;
	private static Entity hit;              // to hit at the start of the next tick, where vanilla hits
	private static boolean swing;           // an empty swing at the start of the next tick (an answer to the sign)
	/** The last blow that really went out at the start of a tick: the pen counts its own from it (Pen). */
	private static int lastHitId = -1;
	private static long lastHitAt;
	/** Foes left be in a pit or a farm's cell, by id: when that was last the case, so that it is said once a bout. */
	private static final Map<Integer, Long> leftAt = new HashMap<>();
	/**
	 * Foes he could not hit for {@link #NO_BLOW} while he went for them (on a pillar, across water, behind a fence),
	 * left alone for {@link #OUT_OF_REACH_FOR}: each arrow of a skeleton he could not get at made the fight "busy" again,
	 * and it never ended (the review of 22 Oct 2026).
	 */
	private static final Map<Integer, Long> outOfReach = new HashMap<>();
	static final long NO_BLOW = 20_000, OUT_OF_REACH_FOR = 60_000;
	/** Another one hitting him takes the fight over from a foe farther than this, or without a blow of his this long. */
	private static final double SWITCH_REACH = 3.5;
	private static final long SWITCH_AFTER = 2000;
	private static long fightFrom;
	private static final long LEFT_QUIET = 5000;
	/** Foes whose fight ended because they or the player were too far: not intercepted again for ten seconds. */
	private static final Map<Integer, Long> tooFar = new HashMap<>();
	private static final long TOO_FAR_FOR = 10_000;
	/** A foe left be that hit the alt himself, and when: one who stays takes cover from it (Pen.cover). */
	private static Entity leftHit;
	private static long leftHitAt;

	private Guard() {}

	static boolean on() { return on; }

	static Entity enemy() { return enemy; }

	// ---------- switching ----------

	/** {@code {"cmd":"guard"}} answers how it stands; {@code "on"} and {@code "pvp"} switch. */
	static void command(JsonObject o) {
		if (o.has("on")) set(o.get("on").getAsBoolean(), pvp, "asked");
		if (o.has("pvp")) set(on, o.get("pvp").getAsBoolean(), "asked");
		if (!o.has("on") && !o.has("pvp")) status("status");
	}

	static boolean toggle(String by) { set(!on, pvp, by); return on; }

	static boolean togglePvp(String by) { set(on, !pvp, by); return pvp; }

	static boolean pvp() { return pvp; }

	/** Fighting players on or off from the panel (Modes). */
	static void setPvp(boolean v, String by) { set(on, v, by); }

	/**
	 * A player he may fight: not the one he follows, not a player he trusts, not one in creative or spectator mode (no
	 * blow hurts them), and only with fighting players on.
	 */
	static boolean fightable(Player pl, Player owner) {
		if (!pvp || pl == owner || pl.isCreative() || pl.isSpectator()) return false;
		String n = pl.getGameProfile().name();
		for (String t : AfkConfig.get().trusted) if (t.equalsIgnoreCase(n)) return false;
		return true;
	}

	private static void set(boolean wantOn, boolean wantPvp, String by) {
		boolean changed = wantOn != on || wantPvp != pvp;
		on = wantOn;
		if (pvp != wantPvp) { AfkConfig.get().guardPlayers = wantPvp; AfkConfig.save(); }
		pvp = wantPvp;
		if (!on && enemy != null) stop("switched off");
		if (enemy instanceof Player && !pvp) stop("pvp off");
		status(changed ? "set" : "status", by);
	}

	private static void status(String action) { status(action, null); }

	private static void status(String action, String by) {
		Exchange.emit("guard", "action", action, "on", on, "pvp", pvp, "by", by == null ? "" : by,
			"fights", enemy == null ? "" : name(enemy));
	}

	// ---------- whom to fight ----------

	/**
	 * Every tick while following: the enemy to fight now, or null. Without the followed player in sight (owner null)
	 * only what attacks the alt itself. Keeps the one it fights until that one is dead or
	 * gone, too far from the alt, or ten seconds without a blow from either side; a fight for the followed player also
	 * ends when the alt is too far from him or nearly dead. One against the alt itself does not: on 17 Oct 2026 a
	 * zombie killed the alt in five blows while the followed player stood 30 blocks off, every fight given up again
	 * in the tick it began ("too far from the player"), and not one blow back. Picks a new one only when it fights none.
	 */
	static Entity watch(LocalPlayer p, Player owner) {
		long now = System.currentTimeMillis();
		leftAt.values().removeIf(t -> now - t > LEFT_QUIET);
		if (!on) {
			if (enemy != null) stop("switched off");
			return null;
		}
		if (enemy != null) {
			String why = enemy.isRemoved() || !enemy.isAlive() ? "dead"
				: enemy.distanceTo(p) > 24 ? "too far"
				: !self && owner != null && owner.distanceTo(p) > 24 ? "too far from the player"
				: !self && p.getHealth() < 6 ? "nearly dead"
				: enemy instanceof Player && !pvp ? "pvp off"
				: now - busyAt > 10_000 ? "rest" : null;
			if (why != null) stop(why);
		}
		// a mob of the pen he farms is the pen's, not a fight: the fight would walk him into it (Pen, 0.4.80); the one
		// punched for the gesture while it still falls down a shaft is held from a new fight only, not taken from this one
		if (enemy != null && Pen.claims(enemy) && !Pen.falling(enemy)) stop("pen");
		// one that came to lie in a pit or a farm's cell out of his reach: given up once, and then not picked again
		if (enemy != null) {
			String pit = Follow.pitFoe(p, enemy);
			if (pit != null) { leftAt.put(enemy.getId(), now); stop(pit); }
		}
		// who hit him: any mob, a player with the switch on (without him in sight, the alt only defends itself)
		Entity by = owner == null ? null : attacker(owner);
		if (by != null && by != p && by != owner) {
			if (Pen.claims(by)) Pen.threat(by);
			else if (by == enemy) busyAt = now;
			else if (enemy == null && (by instanceof Player pb ? fightable(pb, owner) : by instanceof LivingEntity) && !leftBe(p, by, now)) fight(by, "protects", now);
			// someone hits him while the alt fights another that does not (PvP point 5 of 24 Oct 2026): the one who hits him
			// first, unless the alt's own foe is a creeper that swells
			else if (enemy != null && !"protects".equals(role) && (by instanceof Player pb2 ? fightable(pb2, owner) : by instanceof Enemy)
					&& !leftAlone(by, now) && !leftBe(p, by, now)
					&& !(enemy instanceof net.minecraft.world.entity.monster.Creeper c && c.getSwellDir() > 0)) {
				Exchange.emit("guard", "action", "switches", "from", name(enemy), "to", name(by));
				stop("the player is attacked");
				fight(by, "protects", now);
			}
		}
		// who hit the alt: a hostile mob, a player with the switch on; one left be in its pit is noted, for cover
		Entity mine = attacker(p);
		if (mine != null && mine != owner) {
			if (Pen.claims(mine)) Pen.threat(mine);
			else if (mine == enemy) { busyAt = now; self = true; }
			else if (enemy == null && (mine instanceof Player pm ? fightable(pm, owner) : mine instanceof Enemy)) {
				if (!leftBe(p, mine, now)) fight(mine, "defends", now);
				else { leftHit = mine; leftHitAt = now; }
			}
			// another one hits him while the one he fights is out of his reach or has had no blow of his for two seconds:
			// he turns on the one that hits. Once a phantom hit him twice and flew off, he stayed on it, and a zombie
			// beside him killed him in eight blows without one back (23 Oct 2026, 20:41). The one he does hit keeps him.
			else if (enemy != null && (mine instanceof Player pm2 ? fightable(pm2, owner) : mine instanceof Enemy) && !leftAlone(mine, now)
					&& (enemy.distanceTo(p) > SWITCH_REACH || now - lastBlowAt(enemy) > SWITCH_AFTER || weaker(mine, enemy, p)) && !leftBe(p, mine, now)) {
				Exchange.emit("guard", "action", "switches", "from", name(enemy), "to", name(mine));
				stop("another hits the buddy");
				fight(mine, "defends", now);
			}
		}
		// ahead of a blow (step 3 of the plan for fighting, 24 Oct 2026): a monster coming at the one he follows, within six
		// blocks of him, is taken on before it hits; a creeper that swells by him and one that aims at him first
		if (enemy == null && owner != null) {
			Entity coming = threat(p, owner, 6, true);
			if (coming != null && !leftAlone(coming, now) && !leftBe(p, coming, now)) fight(coming, "intercepts", now);
		}
		// whom he hits: a hostile mob, a player with the switch on; blows of either on the enemy keep the fight going
		if (owner == null) {
			if (enemy instanceof LivingEntity le && attacker(le) == p) busyAt = now;
			return enemy;
		}
		for (LivingEntity e : p.level().getEntitiesOfClass(LivingEntity.class, owner.getBoundingBox().inflate(8),
				e -> e != owner && e != p && e.isAlive() && e.hurtTime >= 8 && !Pen.claims(e))) {
			Entity from = attacker(e);
			if (from != owner && from != p) continue;
			if (e == enemy) busyAt = now;
			else if (enemy == null && from == owner && (e instanceof Player pe ? fightable(pe, owner) : e instanceof Enemy && crowd(p) < 3) && !leftBe(p, e, now)) fight(e, "helps", now);
		}
		return enemy;
	}

	/**
	 * Not to be fought now: out of his reach in a pit or a farm's cell, where the way to it is a fall into it
	 * (Follow.pitFoe). Asked anew every time and nothing kept of the mob itself, so that one that comes out and attacks
	 * is fought at once; only why he leaves it be is said, once while it lasts (it was a fight begun and given up in
	 * the same tick, two of them a blow, while the player farmed his own pit with a sword).
	 */
	private static boolean leftBe(LocalPlayer p, Entity e, long now) {
		String why = Follow.pitFoe(p, e);
		if (why == null) return false;
		Long told = leftAt.put(e.getId(), now);
		if (told == null) Exchange.emit("guard", "action", "leaves it", "reason", why, "goal", name(e),
			"x", Math.round(e.getX()), "y", Math.round(e.getY()), "z", Math.round(e.getZ()));
		return true;
	}

	/**
	 * A foe the fight leaves be in its pit or farm's cell that hit the alt himself within the last second, or null: he
	 * does not hit back, so where he stays he gets out of its sight (Pen.cover).
	 */
	static Entity leftBeHit(long now) {
		Entity e = leftHit;
		return e != null && now - leftHitAt <= 1000 && !e.isRemoved() && e.isAlive() ? e : null;
	}

	/**
	 * The foes left be in a pit or a farm's cell within the last few seconds, by id: where he stays, cover hides from all
	 * of them, not only from the one that hit him (Pen.cover; the third review of 22 Oct 2026).
	 */
	static java.util.Set<Integer> leftBeIds() { return java.util.Set.copyOf(leftAt.keySet()); }

	/** Who hit this one in the last tick or two, as the client was told. */
	private static Entity attacker(LivingEntity e) {
		if (e.hurtTime < 8) return null;
		DamageSource s = e.getLastDamageSource();
		return s == null ? null : s.getEntity();
	}

	/**
	 * Help with a blow of the player he follows after all: a punch the pen took for a gesture and then let go (a
	 * closed space that is no farm, a hand that was not empty). As the watch would have done in that tick.
	 */
	static void assist(Entity e) {
		if (!on || enemy != null || !(e instanceof LivingEntity le) || !le.isAlive()) return;
		if (e instanceof Player pl ? !fightable(pl, null) : !(e instanceof Enemy)) return;
		LocalPlayer p = AfkHelperClient.mc().player;
		long now = System.currentTimeMillis();
		if (p == null || leftBe(p, e, now)) return;
		fight(e, "helps", now);
	}

	static int lastHitId() { return lastHitId; }

	/** When he last hit this foe, or began to fight it if he has not yet. */
	static long lastBlowAt(Entity e) { return e != null && lastHitId == e.getId() ? Math.max(lastHitAt, fightFrom) : fightFrom; }

	/** The foe he goes for but cannot hit: the fight ends, and it is left alone a minute. */
	static void outOfReach(Entity e) {
		outOfReach.put(e.getId(), System.currentTimeMillis());
		stop("unreachable");
	}

	/**
	 * Who hits him right now: a hostile mob, or a player with the switch on; none of the pen's, none left in its pit, none
	 * he gave up as out of his reach. For hitting back before a goto, a door or a chest screen.
	 */
	static Entity underAttack(LocalPlayer p) {
		if (!on) return null;
		Entity a = attacker(p);
		if (a == null || !a.isAlive() || Pen.claims(a) || leftAlone(a, System.currentTimeMillis())) return null;
		if (!(a instanceof Player pa ? fightable(pa, null) : a instanceof Enemy)) return null;
		return Follow.pitFoe(p, a) == null ? a : null;
	}

	static long lastHitAt() { return lastHitAt; }

	/** An arrow loosed at the foe he fights (Bow): a blow as far as the fight's own clocks go, so it is not given up. */
	static void noteShot() {
		if (enemy == null) return;
		busyAt = System.currentTimeMillis();
		lastHitId = enemy.getId();
		lastHitAt = busyAt;
	}

	/** Whether this foe was given up as out of his reach less than a minute ago. */
	private static boolean leftAlone(Entity e, long now) {
		outOfReach.values().removeIf(t -> now - t > OUT_OF_REACH_FOR);
		return outOfReach.containsKey(e.getId());
	}

	/**
	 * Of two players who hit him, the other has clearly less health and is within reach (PvP point 5 of 24 Oct 2026): the
	 * weaker first, so that one of them is down sooner.
	 */
	private static boolean weaker(Entity other, Entity current, LocalPlayer p) {
		return other instanceof Player o && current instanceof Player c && o.getHealth() > 0 && o.getHealth() + 4 < c.getHealth()
			&& o.distanceTo(p) <= SWITCH_REACH;
	}

	private static void fight(Entity e, String why, long now) {
		if (leftAlone(e, now)) return;
		// one already beyond the fight's reach is not begun: watch would end it as "too far" in the same tick, and a skeleton
		// shooting at the player from far started and ended a fight at every arrow (the scenarios of 24 Oct 2026, 20:45)
		LocalPlayer me = AfkHelperClient.mc().player;
		if (me != null && e.distanceTo(me) > 24) return;
		enemy = e;
		role = why;
		self = "defends".equals(why);
		busyAt = now;
		fightFrom = now;
		Exchange.emit("guard", "action", why, "goal", name(e), "x", Math.round(e.getX()), "y", Math.round(e.getY()), "z", Math.round(e.getZ()));
	}

	static void stop(String why) {
		if (enemy == null) return;
		if (why.startsWith("too far")) tooFar.put(enemy.getId(), System.currentTimeMillis());
		// the strafe keys of the fight (Follow.fight) up, whatever comes next: not every job sets them
		var keys = AfkHelperClient.mc().options;
		HeldKeys.set(keys.keyLeft, false);
		HeldKeys.set(keys.keyRight, false);
		Exchange.emit("guard", "action", "done", "reason", why, "goal", name(enemy));
		enemy = null;
		self = false;
		hit = null;
	}

	static String nameOf(Entity e) { return name(e); }

	/**
	 * A monster that threatens the one he follows (the plan for fighting, step 3: a monster is taken on once
	 * it comes within about six blocks of the player, before it hits), or null. Within {@code range} of him, in
	 * sight of either of them, of a kind that attacks of itself (not an enderman, a piglin or a zombified piglin, which
	 * a blow turns on him and their like; not a spider where it is light, which leaves him alone then; no boss, warden,
	 * shulker, creaking or guardian), none with a name, on a lead or seated in a boat or a minecart (kept on purpose: an
	 * iron farm's zombie), none of the pen's, none in a pit or a farm's cell. With {@code coming} only one that comes at him: closer than
	 * three blocks, walking his way, set on a target (the game's aggressive flag), a creeper that swells within seven, or
	 * one that aims a bow at either of them. The worst first: a creeper that swells, one that aims, then the nearest to him.
	 * Not while he plays in creative or spectator mode, where nothing attacks him, nor while the alt stays.
	 */
	static Entity threat(LocalPlayer p, Player owner, double range, boolean coming) {
		if (!on || Follow.staying() || owner.isCreative() || owner.isSpectator() || owner.distanceTo(p) > 24) return null;
		long now = System.currentTimeMillis();
		tooFar.values().removeIf(t -> now - t > TOO_FAR_FOR);
		Entity best = null;
		int bestRank = -1;
		double bestD = Double.MAX_VALUE;
		for (LivingEntity e : p.level().getEntitiesOfClass(LivingEntity.class, owner.getBoundingBox().inflate(Math.max(range, 16)), x -> x instanceof Enemy && x.isAlive())) {
			// within the fight's own reach of him (watch ends one beyond 24 at once), and not one that just ended that way:
			// a skeleton aiming at the player from 32 blocks below the alt was picked and dropped every tick (the scenario
			// of 24 Oct 2026, 20:43)
			if (e.distanceTo(p) > 20 || tooFar.containsKey(e.getId())) continue;
			if (!attacksOfItself(e) || e.hasCustomName() || e.isPassenger() || (e instanceof net.minecraft.world.entity.Mob m && m.isLeashed()) || Pen.claims(e)) continue;
			if (!p.hasLineOfSight(e) && !owner.hasLineOfSight(e)) continue;
			// one in a pit or a farm's cell, which cannot come at him (Pen.enclosedWhy, kept a second per mob)
			if (Pen.enclosed(p, e)) continue;
			double d = e.distanceTo(owner);
			int rank;
			if (e instanceof net.minecraft.world.entity.monster.Creeper c && (c.getSwellDir() > 0 || c.getSwelling(1f) > 0.05f) && d <= 7) rank = 3;
			else if (Shield.aims(e) && d <= 16) rank = 2;
			else if (d > range) continue;
			else if (!coming) rank = 1;
			else {
				Vec3 step = new Vec3(e.getX() - e.xo, 0, e.getZ() - e.zo), to = new Vec3(owner.getX() - e.getX(), 0, owner.getZ() - e.getZ());
				boolean walksTo = to.lengthSqr() > 1e-4 && step.dot(to.normalize()) > 0.03;
				boolean set = e instanceof net.minecraft.world.entity.Mob m && m.isAggressive();
				if (d > 3 && !walksTo && !set) continue;
				rank = 1;
			}
			if (rank > bestRank || (rank == bestRank && d < bestD)) { best = e; bestRank = rank; bestD = d; }
		}
		return best;
	}

	/** A kind that attacks a player of itself; see {@link #threat}. */
	private static boolean attacksOfItself(LivingEntity e) {
		if (e instanceof net.minecraft.world.entity.monster.Enderman || e instanceof net.minecraft.world.entity.monster.zombie.ZombifiedPiglin
			|| e instanceof net.minecraft.world.entity.monster.piglin.Piglin || e instanceof net.minecraft.world.entity.monster.Shulker
			|| e instanceof net.minecraft.world.entity.monster.warden.Warden || e instanceof net.minecraft.world.entity.monster.creaking.Creaking
			|| e instanceof net.minecraft.world.entity.monster.Guardian || e instanceof net.minecraft.world.entity.boss.wither.WitherBoss
			|| e instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon) return false;
		if (e instanceof net.minecraft.world.entity.monster.spider.Spider && e.getLightLevelDependentMagicValue() >= 0.5f) return false;
		return true;
	}

	/**
	 * Monsters within eight blocks of him that are no pen's (the plan for fighting: with three monsters or more
	 * the alt does not go looking for them): with three or more he does not join in on one the followed player hits.
	 */
	private static int crowd(LocalPlayer p) {
		return p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(8), e -> e instanceof Enemy && e.isAlive() && !Pen.claims(e)).size();
	}

	private static String name(Entity e) {
		return e instanceof Player pl ? pl.getGameProfile().name() : EntityType.getKey(e.getType()).getPath();
	}

	// ---------- the weapon ----------

	/** What a hit with this does, by the item's own attack modifiers: damage times speed, the fist one times four. */
	static double dps(ItemStack st) {
		double damage = 1, speed = 4;
		for (ItemAttributeModifiers.Entry e : st.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY).modifiers()) {
			if (!e.slot().test(EquipmentSlot.MAINHAND)) continue;
			if (e.attribute().value() == Attributes.ATTACK_DAMAGE.value()) damage += e.modifier().amount();
			else if (e.attribute().value() == Attributes.ATTACK_SPEED.value()) speed += e.modifier().amount();
		}
		return damage * Math.max(0.1, speed);
	}

	/** A weapon: something that hits at least four points harder than a fist (swords, axes, a mace, a trident). */
	static boolean isWeapon(ItemStack st) {
		if (st.isEmpty()) return false;
		double add = 0;
		for (ItemAttributeModifiers.Entry e : st.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY).modifiers())
			if (e.slot().test(EquipmentSlot.MAINHAND) && e.attribute().value() == Attributes.ATTACK_DAMAGE.value()) add += e.modifier().amount();
		return add >= 3;
	}

	/**
	 * A weapon for the sign (Follow.sign): a sword, a spear, a mace or a trident. A tool that hits as hard (an axe, an iron
	 * or better pickaxe or shovel, the 3x3 pickaxe) counts as no weapon there, so two crouches with it in hand are stay or
	 * follow again (with such a tool in hand, two crouches could turn following on but not off: seven signs switched the
	 * protection instead).
	 */
	static boolean isSignWeapon(ItemStack st) {
		return !st.isEmpty() && (st.is(ItemTags.SWORDS) || st.is(ItemTags.SPEARS) || st.is(Items.MACE) || st.is(Items.TRIDENT));
	}

	/**
	 * How a weapon ranks: by its damage times speed, but one about to break (three uses or fewer, Work.nearlyBroken)
	 * below every other weapon, still above the fist. Until 0.4.80 he took a sword with one use left.
	 */
	static double rank(ItemStack st) {
		double d = dps(st);
		if (st.isEmpty() || !isWeapon(st)) return d;
		return Work.nearlyBroken(st) ? dps(ItemStack.EMPTY) + d / 100 : d + 1000;
	}

	/**
	 * The best weapon in hand: from the hotbar, or brought there from the backpack first. Once a second at most. Not
	 * while the pen holds an axe in his hand for someone beside its mob (Pen.holdsAxe: a sword would sweep him), unless
	 * there is a fight.
	 */
	static void arm(Minecraft mc, LocalPlayer p, boolean now) {
		long t = System.currentTimeMillis();
		if (!now && t < nextArm) return;
		if (!now && enemy == null && Pen.holdsAxe()) return;
		// the bow in hand for a foe he shoots at (Bow): not the sword between two arrows
		if (Bow.active()) return;
		// the axe for a raised shield (Follow.fight)
		if (t < Follow.axeUntil) return;
		nextArm = t + 1000;
		int best = -1;
		double bestDps = rank(ItemStack.EMPTY);
		for (int i = 0; i < 9; i++) {
			ItemStack st = p.getInventory().getItem(i);
			if (!isWeapon(st)) continue;
			double d = rank(st);
			if (d > bestDps) { bestDps = d; best = i; }
		}
		double atLeast = bestDps;
		if (AfkHelperClient.toHotbar(p, mc, st -> isWeapon(st) && rank(st) > atLeast + 0.01, Comparator.comparingDouble(Guard::rank), "weapon")) return;
		if (best >= 0 && p.getInventory().getSelectedSlot() != best && !AfkHelperClient.isEating()) p.getInventory().setSelectedSlot(best);
	}

	/** The weapon away: a hotbar slot without one in hand, an empty one first. */
	static void putAway(LocalPlayer p) {
		if (!isWeapon(p.getMainHandItem())) return;
		int pick = -1;
		for (int i = 0; i < 9 && pick < 0; i++) if (p.getInventory().getItem(i).isEmpty()) pick = i;
		for (int i = 0; i < 9 && pick < 0; i++) if (!isWeapon(p.getInventory().getItem(i))) pick = i;
		if (pick >= 0) p.getInventory().setSelectedSlot(pick);
	}

	// ---------- the hit ----------

	/** Hit this one at the start of the next tick, if the crosshair is still on it then. */
	static void queueHit(Entity e) { hit = e; }

	static void queueSwing() { swing = true; }

	/** Start of the tick, where vanilla hits: never a blow through a wall or at someone who stepped in between. */
	static void startTick(Minecraft mc) {
		Entity e = hit;
		boolean empty = swing;
		hit = null;
		swing = false;
		LocalPlayer p = mc.player;
		if (p == null || mc.gameMode == null) return;
		// no blow while eating or using an item: a real client cannot attack then (found on the way, 0.4.80)
		if (p.isUsingItem()) return;
		if (e != null && mc.hitResult instanceof EntityHitResult h && h.getEntity() == e) {
			mc.gameMode.attack(p, e);
			Game.swingAttack(p);
			busyAt = System.currentTimeMillis();
			lastHitId = e.getId();
			lastHitAt = busyAt;
		} else if (empty) {
			Game.swingAttack(p);
		}
	}
}
