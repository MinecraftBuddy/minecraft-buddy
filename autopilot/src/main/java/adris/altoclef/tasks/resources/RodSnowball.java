package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.BlazeFightRules;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.SnowballAim;
import adris.altoclef.util.helpers.SnowballRun;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.behavior.look.CombatAimMath;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

// the buddy's fork, Tuning rod_snowball, off by default: the first two blaze rods by thrown snowballs. with snowballs in the bag
// and fewer than two rods, the blazes are fought from where the camp stands now (the pillbox's cell, its look out or opening,
// the corner and its peek cell, the camp spot, the crowd's hiding place) with snowballs instead of the sword: a hit does 3 to a
// blaze, seven kill one, and a kill by a snowball drops rods as one by the sword. a blaze within the melee's reach is still the
// fight's; a run, a pillbox going up and the pack's back off keep the wheel; the shield (the mob defense's, for every ball on
// its way) goes up between throws, and no throw goes out while it is up. after two rods, or with the snowballs gone, the fight is
// the sword's again and the brew follows as now. the rods are fetched by the rods task's own rules
final class RodSnowball {

    // a blaze nearer than this is the melee's: the aura hits the blazes within 3.5 (CollectBlazeRodsTask's force field
    // exclusion), and a throw at one that close has it under the crosshair
    static final double MELEE_KEEP = 3.5;
    // the throws go out only within this of the camp's point (a pillbox's look out is 1.5 to 2 from its cell's middle, a
    // corner's peek cell within two steps): on the way to the camp the fight is the sword's, a stop to throw on the bridge is a
    // stand in every blaze's sight
    static final double THROW_STAND = 3;
    // Tuning rod_snowball_range: blocks from the eye to the blaze's middle. the spread is about a quarter of a block at 20
    static final double RANGE = 24;
    // at most four throws a second (game ticks between two)
    static final int MIN_GAP = 5;
    // the landings at one blaze at least this far apart: a ball that lands within ten ticks of a hit does nothing
    static final int HURT_GAP = 11;
    // the look the last tick ended on is this close to the throw's before it goes (degrees)
    static final double SETTLE_DEG = 3;
    // a blaze dead within this long of a throw at it is a kill by snowball (game ticks; a flight at 24 is about 17)
    static final long KILL_WINDOW = 60;
    // the lead: the blaze's speed over this many ticks of its points, each part capped (blocks a tick)
    static final int VEL_TICKS = 4;
    static final double LEAD_CAP = 0.5;
    // the crosshair's ray along the throw's look clear of blocks this far (the block reach is 4.5): the click is no use on a block
    static final double LOOK_CLEAR = 5;
    // no throw with a zombified piglin, a piglin or an enderman this close to the ball's way, and the way checked this many
    // ticks on past the blaze
    static final double NEUTRAL_MARGIN = 1;
    static final double PAST_TICKS = 15;
    // a line on why nothing is thrown at the camp with blazes in range, at most this often (game ticks)
    static final long STATUS_TICKS = 400;

    static boolean on() {
        return adris.altoclef.util.helpers.Tuning.get("rod_snowball", 0) > 0;
    }

    static double range() {
        return adris.altoclef.util.helpers.Tuning.get("rod_snowball_range", RANGE);
    }

    private record Shot(Blaze blaze, float yaw, float pitch, double flight, double distance) {
    }

    private final ThrowTask task = new ThrowTask(this);
    // (one per rods task, CollectBlazeRodsTask's field: each rods order runs its own course. one per world, the rig's orders
    // in one game shared it, and every order after one that got its two rods never began, the strategy trial's lane t3)
    private final SnowballRun run = new SnowballRun();
    private long lastThrow = Long.MIN_VALUE / 2;
    private long statusAt = Long.MIN_VALUE / 2;
    private boolean holdsMelee;
    private Shot shot;
    // per blaze (entity id): the throws at it, the last one's tick and landing, the blaze itself while a throw at it may still
    // turn out a kill, and its last points for the lead
    private final Map<Integer, Integer> throwsAt = new HashMap<>();
    private final Map<Integer, Long> thrownAt = new HashMap<>();
    private final Map<Integer, Long> landsAt = new HashMap<>();
    private final Map<Integer, Blaze> watched = new HashMap<>();
    private final Map<Integer, java.util.ArrayDeque<double[]>> trail = new HashMap<>();

    // with the strategy on in KILL mode this tick, the sword's target out past the melee's reach and no throw to make: the
    // chase is left out and the camp goes on (its looks bring the blaze into a line)
    boolean holdsMelee() {
        return holdsMelee;
    }

    // every tick the rods task ticks, a pickup's too: the blazes' points, the kills, and the two ends (two rods, no snowballs)
    void watch(AltoClef mod) {
        LocalPlayer p = mod.getPlayer();
        if (p == null) return;
        long now = mod.getWorld().getGameTime();
        List<Blaze> blazes = blazes(mod);
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (Blaze b : blazes) {
            if (!b.isAlive() || p.distanceTo(b) > range() + 8) continue;
            seen.add(b.getId());
            java.util.ArrayDeque<double[]> t = trail.computeIfAbsent(b.getId(), k -> new java.util.ArrayDeque<>());
            Vec3 c = b.getBoundingBox().getCenter();
            if (t.isEmpty() || t.peekLast()[0] != now) t.addLast(new double[]{now, c.x, c.y, c.z});
            // (the last VEL_TICKS ticks only: a gap while another chain had the wheel is no speed of now)
            while (t.size() > VEL_TICKS + 1 || now - t.peekFirst()[0] > VEL_TICKS) t.removeFirst();
        }
        trail.keySet().retainAll(seen);
        // a blaze thrown at that died: a kill by snowball when the last throw at it was within KILL_WINDOW
        var it = watched.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            Blaze b = e.getValue();
            if (b.getHealth() <= 0) {
                it.remove();
                Long at = thrownAt.get(e.getKey());
                if (at != null && now - at <= KILL_WINDOW && now >= at) {
                    Debug.logInternal("strategy snowball: kill " + run.kill() + " by snowball, " + throwsAt.getOrDefault(e.getKey(), 0) + " snowballs for it, "
                            + run.throwsAll() + " thrown in all (rods " + rods(mod) + ", " + snowballs(mod) + " snowballs left)");
                }
            } else if (b.isRemoved()) {
                it.remove();
            }
        }
        for (String line : run.ends(snowballs(mod), rods(mod), now)) Debug.logInternal(line);
    }

    // the throw task while there is a blaze to throw at from the camp, null when the fight goes on as without the strategy (and
    // holdsMelee says whether its chase is left out). stand is the camp's point, building a pillbox or a brew box going up
    Task step(AltoClef mod, BlazeFight fight, Task fightTask, BlockPos stand, boolean building) {
        holdsMelee = false;
        shot = null;
        LocalPlayer p = mod.getPlayer();
        if (p == null) return null;
        long now = mod.getWorld().getGameTime();
        int snow = snowballs(mod);
        int rods = rods(mod);
        String start = run.start(snow, rods, now, range());
        if (start != null) Debug.logInternal(start);
        if (!run.active(snow, rods)) return null;
        // (the run is the heal and the cover; the box first while it goes up; the pack's back off as it is)
        if (fight.mode() == BlazeFightRules.Mode.RUN || building || (fight.mode() == BlazeFightRules.Mode.CAMP && fightTask != null)) return null;
        if (stand == null || p.position().distanceTo(Vec3.atBottomCenterOf(stand)) > THROW_STAND) return null;
        List<Blaze> blazes = new ArrayList<>();
        for (Blaze b : blazes(mod)) {
            if (!b.isAlive()) continue;
            // (a blaze within the melee's reach: the sword's and the aura's, as now)
            if (p.distanceTo(b) < MELEE_KEEP) return null;
            blazes.add(b);
        }
        holdsMelee = fight.mode() == BlazeFightRules.Mode.KILL;
        Blaze on = currentTarget;
        // the one thrown at first, then the most hurt, then the nearest
        blazes.sort((a, b) -> {
            if (a == b) return 0;
            if (a == on) return -1;
            if (b == on) return 1;
            int byHealth = Float.compare(a.getHealth(), b.getHealth());
            return byHealth != 0 ? byHealth : Double.compare(p.distanceToSqr(a), p.distanceToSqr(b));
        });
        Map<String, Integer> why = new java.util.TreeMap<>();
        int inRange = 0;
        for (Blaze b : blazes) {
            String no = aim(mod, p, b);
            if (no == null) break;
            if (!"far".equals(no)) inRange++;
            why.merge(no, 1, Integer::sum);
        }
        currentTarget = shot == null ? null : shot.blaze();
        if (shot == null) {
            if (inRange > 0 && now - statusAt >= STATUS_TICKS) {
                statusAt = now;
                Debug.logInternal("strategy snowball: at the camp, no throw: " + inRange + " blazes within " + fmt(range()) + " " + why + ", " + run.throwsAll() + " thrown");
            }
            return null;
        }
        return task;
    }

    private Blaze currentTarget;

    // the throw at b from here into shot, null; else why not
    private String aim(AltoClef mod, LocalPlayer p, Blaze b) {
        Vec3 eye = p.getEyePosition();
        Vec3 centre = b.getBoundingBox().getCenter();
        double distance = eye.distanceTo(centre);
        if (distance > range()) return SnowballAim.whyNot(distance, MELEE_KEEP, range(), false, false, false, true);
        boolean sight = clear(mod, p, eye, centre);
        double[] origin = {p.getX(), p.getEyeY() - SnowballAim.ORIGIN_DROP, p.getZ()};
        double[] solution = null;
        boolean arc = false;
        boolean neutralClear = true;
        if (sight) {
            java.util.ArrayDeque<double[]> t = trail.get(b.getId());
            double[] vel = new double[]{0, 0, 0};
            if (t != null && t.size() >= 2) {
                double[] a = t.peekFirst(), z = t.peekLast();
                vel = SnowballAim.targetVelocity(new double[]{a[1], a[2], a[3]}, new double[]{z[1], z[2], z[3]}, (long) (z[0] - a[0]), LEAD_CAP);
            }
            double[] move = ownMove(p);
            solution = SnowballAim.solve(origin, new double[]{centre.x, centre.y, centre.z}, vel, move);
            if (solution != null) {
                double[] v0 = SnowballAim.velocity((float) solution[0], (float) solution[1], move);
                double[][] path = SnowballAim.path(origin, v0, solution[2]);
                arc = true;
                // (the last segment ends in the blaze; up to it, nothing solid)
                for (int k = 0; k + 1 < path.length && arc; k++) {
                    arc = clear(mod, p, new Vec3(path[k][0], path[k][1], path[k][2]), new Vec3(path[k + 1][0], path[k + 1][1], path[k + 1][2]));
                }
                neutralClear = !arc || !neutralNear(mod, p, origin, v0, solution[2]);
            }
        }
        String no = SnowballAim.whyNot(distance, MELEE_KEEP, range(), sight, solution != null, arc, neutralClear);
        if (no != null) return no;
        shot = new Shot(b, (float) solution[0], (float) solution[1], solution[2], distance);
        return null;
    }

    // a zombified piglin, a piglin or an enderman within NEUTRAL_MARGIN of the ball's way: up to the blaze, and on past it for
    // PAST_TICKS (a miss flies on) up to the first block. a ball that hits one turns it, and a zombified piglin its whole pack,
    // on the buddy (the strategy trial's lane t3: three zombified piglins killed, the buddy dead to a piglin)
    private static boolean neutralNear(AltoClef mod, LocalPlayer p, double[] origin, double[] v0, double flight) {
        double[][] path = SnowballAim.path(origin, v0, flight + PAST_TICKS);
        double[] lo = path[0].clone(), hi = path[0].clone();
        for (double[] pt : path) {
            for (int i = 0; i < 3; i++) {
                lo[i] = Math.min(lo[i], pt[i]);
                hi[i] = Math.max(hi[i], pt[i]);
            }
        }
        AABB area = new AABB(lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]).inflate(NEUTRAL_MARGIN + 1);
        List<Entity> neutrals = mod.getWorld().getEntities(p, area, e -> e.isAlive()
                && (e instanceof ZombifiedPiglin || e instanceof AbstractPiglin || e instanceof Enderman));
        if (neutrals.isEmpty()) return false;
        // (the way up to the blaze is clear of blocks already; past it, up to the segment that meets one)
        int end = path.length - 1;
        for (int k = (int) Math.floor(flight); k + 1 < path.length; k++) {
            if (!clear(mod, p, new Vec3(path[k][0], path[k][1], path[k][2]), new Vec3(path[k + 1][0], path[k + 1][1], path[k + 1][2]))) {
                end = k + 1;
                break;
            }
        }
        double[][] way = java.util.Arrays.copyOf(path, end + 1);
        for (Entity e : neutrals) {
            AABB bb = e.getBoundingBox();
            if (SnowballAim.pathNearBox(way, new double[]{bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ}, NEUTRAL_MARGIN)) return true;
        }
        return false;
    }

    // the thrower's own movement as the ball gets it (Projectile.shootFromRotation: its y only off the ground)
    private static double[] ownMove(LocalPlayer p) {
        Vec3 m = p.getDeltaMovement();
        return new double[]{m.x, p.onGround() ? 0 : m.y, m.z};
    }

    // the throw task's tick: the look at the shot, and the throw once the look has settled and the gates are open
    void aimAndThrow(AltoClef mod) {
        Shot s = shot;
        LocalPlayer p = mod.getPlayer();
        if (s == null || p == null || !s.blaze().isAlive()) return;
        // (the look the last tick ended on, which the server has; the wanted one the same way round, in whole mouse pixels from
        // there, through the look helper)
        float yawWas = p.getYRot();
        float pitchWas = p.getXRot();
        float yaw = yawWas + CombatAimMath.wrap(s.yaw() - yawWas);
        float pitch = Math.max(-90, Math.min(90, s.pitch()));
        double sensitivity = Minecraft.getInstance().options.sensitivity().get();
        yaw = CombatAimMath.quantize(yawWas, yaw, sensitivity);
        pitch = CombatAimMath.quantize(pitchWas, pitch, sensitivity);
        LookHelper.lookAt(mod, new Rotation(yaw, pitch));
        // (no snowball into the hand while the use key is held or an item is in use, the shield's above all: the held key would
        // throw it and the shield would never go up, ShieldRaise.handClear. the look stays on the blaze, the shield toward it)
        if (p.isUsingItem() || mod.getInputControls().isHeldDown(Input.CLICK_RIGHT)) return;
        if (!mod.getSlotHandler().forceEquipItem(Items.SNOWBALL)) return;
        long now = mod.getWorld().getGameTime();
        if (!SnowballAim.settled(yawWas, pitchWas, yaw, pitch, SETTLE_DEG)) return;
        // (the shield up, or anything else in use: no throw over it)
        if (p.isUsingItem() || !p.getMainHandItem().is(Items.SNOWBALL)) return;
        if (!SnowballAim.rateOk(now, lastThrow, MIN_GAP)) return;
        int id = s.blaze().getId();
        if (!SnowballAim.hurtOk(s.blaze().damageCooldownTime, s.flight(), now, landsAt.getOrDefault(id, Long.MIN_VALUE), HURT_GAP)) return;
        // (the click is a use in the air: no block within reach and no mob under the crosshair, along the throw's look too)
        HitResult crosshair = Minecraft.getInstance().hitResult;
        if (crosshair instanceof EntityHitResult || (crosshair instanceof BlockHitResult bh && bh.getType() != HitResult.Type.MISS)) return;
        Vec3 eye = p.getEyePosition();
        double[] d = CombatAimMath.direction(yaw, pitch);
        if (!clear(mod, p, eye, eye.add(d[0] * LOOK_CLEAR, d[1] * LOOK_CLEAR, d[2] * LOOK_CLEAR))) return;
        mod.getInputControls().tryPress(Input.CLICK_RIGHT);
        // (the mob defense leaves this ball alone, SnowballAim.ownThrow)
        SnowballAim.noteOwnThrow(now);
        lastThrow = now;
        throwsAt.merge(id, 1, Integer::sum);
        thrownAt.put(id, now);
        landsAt.put(id, now + (long) Math.ceil(s.flight()));
        watched.put(id, s.blaze());
        String first = run.thrown(now, s.distance(), s.flight());
        if (first != null) Debug.logInternal(first);
    }

    private static boolean clear(AltoClef mod, LocalPlayer p, Vec3 from, Vec3 to) {
        return mod.getWorld().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p)).getType() == HitResult.Type.MISS;
    }

    private static List<Blaze> blazes(AltoClef mod) {
        try {
            return new ArrayList<>(mod.getEntityTracker().getTrackedEntities(Blaze.class));
        } catch (java.util.ConcurrentModificationException e) {
            // the tracker rebuilds on another thread sometimes, the next tick has them
            return List.of();
        }
    }

    private static int snowballs(AltoClef mod) {
        return mod.getItemStorage().getItemCount(Items.SNOWBALL);
    }

    private static int rods(AltoClef mod) {
        return mod.getItemStorage().getItemCount(Items.BLAZE_ROD);
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    // the throws: a look and a click a tick, no walking. one instance, so the rods task's fresh return each tick keeps it running
    static final class ThrowTask extends Task {
        private final RodSnowball strategy;

        ThrowTask(RodSnowball strategy) {
            this.strategy = strategy;
        }

        @Override
        protected void onStart(AltoClef mod) {
        }

        @Override
        protected Task onTick(AltoClef mod) {
            strategy.aimAndThrow(mod);
            return null;
        }

        @Override
        protected void onStop(AltoClef mod, Task interruptTask) {
        }

        @Override
        public boolean isFinished(AltoClef mod) {
            return false;
        }

        @Override
        protected boolean isEqual(Task other) {
            return other instanceof ThrowTask t && t.strategy == strategy;
        }

        @Override
        protected String toHudString() {
            return "Throwing snowballs at a blaze";
        }

        @Override
        protected String toDebugString() {
            return "Throwing snowballs at a blaze";
        }
    }
}
