package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.BlazeFightRules;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.SnowballAim;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.behavior.look.CombatAimMath;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
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
    // the snowballs counted at 0 this many ticks in a row before they are out (a slot being moved reads as none for a tick)
    static final int OUT_TICKS = 10;
    // a line on why nothing is thrown at the camp with blazes in range, at most this often (game ticks)
    static final long STATUS_TICKS = 400;

    static boolean on() {
        return adris.altoclef.util.helpers.Tuning.get("rod_snowball", 0) > 0;
    }

    static double range() {
        return adris.altoclef.util.helpers.Tuning.get("rod_snowball_range", RANGE);
    }

    // one per world: the rods task is made anew by its callers now and then, the strategy's clock and counts go on. a new world
    // (the nether's from the overworld, a respawn) starts it over
    private static RodSnowball instance;
    private static WeakReference<Object> instanceWorld = new WeakReference<>(null);

    static RodSnowball of(AltoClef mod) {
        Object world = mod.getWorld();
        if (instance == null || instanceWorld.get() != world) {
            instance = new RodSnowball();
            instanceWorld = new WeakReference<>(world);
        }
        return instance;
    }

    private record Shot(Blaze blaze, float yaw, float pitch, double flight, double distance) {
    }

    private final ThrowTask task = new ThrowTask(this);
    private boolean begun;
    private boolean noKitLogged;
    private long begunAt;
    private boolean firstThrown;
    private boolean twoRods;
    private boolean out;
    private int outTicks;
    private int throwsAll;
    private int kills;
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
                    kills++;
                    Debug.logInternal("strategy snowball: kill " + kills + " by snowball, " + throwsAt.getOrDefault(e.getKey(), 0) + " snowballs for it, "
                            + throwsAll + " thrown in all (rods " + rods(mod) + ", " + snowballs(mod) + " snowballs left)");
                }
            } else if (b.isRemoved()) {
                it.remove();
            }
        }
        if (!begun) return;
        int rods = rods(mod);
        if (!twoRods && rods >= 2) {
            twoRods = true;
            Debug.logInternal("strategy snowball: two rods after " + throwsAll + " throws in " + seconds(now) + " seconds (" + kills + " kills by snowball, "
                    + snowballs(mod) + " snowballs left)");
            Debug.logInternal("strategy snowball: back to melee, two rods");
        }
        outTicks = snowballs(mod) <= 0 ? outTicks + 1 : 0;
        if (!twoRods && !out && outTicks >= OUT_TICKS) {
            out = true;
            Debug.logInternal("strategy snowball: out of snowballs after " + throwsAll + " throws in " + seconds(now) + " seconds, rods " + rods + " (" + kills
                    + " kills by snowball)");
            Debug.logInternal("strategy snowball: back to melee, out of snowballs");
        }
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
        if (rods >= 2 || twoRods || out) return null;
        if (snow <= 0) {
            if (!begun && !noKitLogged) {
                noKitLogged = true;
                Debug.logInternal("strategy snowball: not begun, no snowballs in the bag");
            }
            return null;
        }
        if (!begun) {
            begun = true;
            begunAt = now;
            Debug.logInternal("strategy snowball: begun, " + snow + " snowballs (rods " + rods + ", range " + fmt(range()) + ")");
        }
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
                Debug.logInternal("strategy snowball: at the camp, no throw: " + inRange + " blazes within " + fmt(range()) + " " + why + ", " + throwsAll + " thrown");
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
        if (distance > range()) return SnowballAim.whyNot(distance, MELEE_KEEP, range(), false, false, false);
        boolean sight = clear(mod, p, eye, centre);
        double[] origin = {p.getX(), p.getEyeY() - SnowballAim.ORIGIN_DROP, p.getZ()};
        double[] solution = null;
        boolean arc = false;
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
                double[][] path = SnowballAim.path(origin, SnowballAim.velocity((float) solution[0], (float) solution[1], move), solution[2]);
                arc = true;
                // (the last segment ends in the blaze; up to it, nothing solid)
                for (int k = 0; k + 1 < path.length && arc; k++) {
                    arc = clear(mod, p, new Vec3(path[k][0], path[k][1], path[k][2]), new Vec3(path[k + 1][0], path[k + 1][1], path[k + 1][2]));
                }
            }
        }
        String no = SnowballAim.whyNot(distance, MELEE_KEEP, range(), sight, solution != null, arc);
        if (no != null) return no;
        shot = new Shot(b, (float) solution[0], (float) solution[1], solution[2], distance);
        return null;
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
        if (!mod.getSlotHandler().forceEquipItem(Items.SNOWBALL)) return;
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
        lastThrow = now;
        throwsAll++;
        throwsAt.merge(id, 1, Integer::sum);
        thrownAt.put(id, now);
        landsAt.put(id, now + (long) Math.ceil(s.flight()));
        watched.put(id, s.blaze());
        if (!firstThrown) {
            firstThrown = true;
            Debug.logInternal("strategy snowball: first throw " + seconds(now) + " seconds after begun, at a blaze " + fmt(s.distance()) + " off, flight "
                    + Math.round(s.flight()) + " ticks");
        }
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

    private long seconds(long now) {
        return Math.max(0, now - begunAt) / 20;
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
