package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.speedrun.gamer.GolemRules;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.PerchRules;
import baritone.behavior.CombatAim;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;

// stack up h blocks right where we stand and hit what is chasing us from the top. the chasers cannot reach a player whose feet are
// over their head (PerchRules has the numbers). this task never walks: down here it pillars, up there it swings, and the
// combat machine decides when the perch is over
public final class PerchTask extends Task {
    // knocked off the column or pushed aside: one more pillar from where we stand, then it is a failed perch
    private static final int MAX_REPILLARS = 1;
    private static final double TOP_SLACK = 1.0e-3;
    // less than this is the jump bobbing, a block under us is a whole one
    private static final double GAIN = 0.05;

    private final int h;
    private AltoClef mod;
    private PillarHereTask pillar;
    private BlockPos column;
    private double startY;
    // the absolute feet height we are after, fixed by the first column: a restart from higher ground needs fewer blocks, not h more
    private double topY = Double.NaN;
    private double bestY;
    private long lastGain;
    private int repillars;
    private boolean failed;
    private boolean onTop;
    private int swings;
    private String hud = "Pillaring up to get out of reach";

    public PerchTask(int h) {
        this.h = h;
    }

    // standing on the finished pillar: the only time a meal is safe, the pillar clicks and the chewing share the use key
    public boolean onTop() {
        return onTop;
    }

    // the pillar went nowhere, the brain feeds this into PerchRules.Facts so the perch is called off
    public boolean failed() {
        return failed;
    }

    @Override
    protected void onStart(AltoClef mod) {
        this.mod = mod;
        // a restart after a pause keeps the column it had, but the 30 tick clock starts over
        if (column == null) {
            restart(mod);
        }
        lastGain = mod.getWorld().getGameTime();
    }

    private void restart(AltoClef mod) {
        LocalPlayer player = mod.getPlayer();
        column = player.blockPosition();
        startY = player.getY();
        if (Double.isNaN(topY)) {
            topY = startY + h;
        }
        bestY = startY;
        lastGain = mod.getWorld().getGameTime();
        pillar = new PillarHereTask(column.above(Math.max(1, (int) Math.ceil(topY - startY - 1.0e-6))));
    }

    private void fail(String reason) {
        failed = true;
        Debug.logInternal("combat: pillar fail: " + reason);
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (failed) {
            return null;
        }
        LocalPlayer player = mod.getPlayer();
        long now = mod.getWorld().getGameTime();
        boolean grounded = player.onGround();
        boolean top = grounded && player.getY() >= topY - TOP_SLACK;
        onTop = top;
        BlockPos here = player.blockPosition();
        // (only on the ground: a jump of the pillar stays in the column but the knock back in the air is not ours to judge)
        if (grounded && (here.getX() != column.getX() || here.getZ() != column.getZ())) {
            if (repillars >= MAX_REPILLARS) {
                fail("left the column twice (" + here.toShortString() + " from " + column.toShortString() + ")");
                return null;
            }
            repillars++;
            Debug.logInternal("combat: pillar restart from " + here.toShortString());
            restart(mod);
            top = false;
        }
        if (top) {
            hud = "Hitting from the top of a perch";
            hit(mod, player);
            return null;
        }
        hud = "Pillaring up to get out of reach";
        if (player.getY() > bestY + GAIN) {
            bestY = player.getY();
            lastGain = now;
        } else if (now - lastGain > PerchRules.PILLAR_TICKS) {
            fail("no height gained in " + PerchRules.PILLAR_TICKS + " ticks (y=" + player.getY() + ", want " + topY + ")");
            return null;
        }
        return pillar;
    }

    // the nearest perch species in reach with a clear line, swung at with full cooldown. nothing moves up here
    private void hit(AltoClef mod, LocalPlayer player) {
        // chewing and swinging use the same hand, the meal is up here for a reason
        if (mod.getFoodChain().isTryingToEat()) {
            return;
        }
        Mob best = null;
        Vec3 bestPoint = null;
        double bestDist = Double.POSITIVE_INFINITY;
        try {
            for (Entity entity : mod.getEntityTracker().getHostiles()) {
                if (!(entity instanceof Mob mob) || !mob.isAlive()) {
                    continue;
                }
                if (!PerchRules.species(BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getPath())) {
                    continue;
                }
                double dist = mob.distanceTo(player);
                if (dist >= bestDist) {
                    continue;
                }
                Vec3 point = reachablePoint(player, mob);
                if (point != null) {
                    best = mob;
                    bestPoint = point;
                    bestDist = dist;
                }
            }
        } catch (ConcurrentModificationException ignored) {
            // the tracker rebuilds its lists on another thread sometimes, a swing late is fine
        }
        if (best == null) {
            return;
        }
        mod.getControllerExtras().aimAt(best, CombatAim.Priority.KILL_TASK, bestPoint);
        if (GolemFightTask.equipWeapon(mod)) {
            return;
        }
        // full cooldown only, same as the golem fight
        if (mod.getControllerExtras().attackReady() && mod.getControllerExtras().attack(best)) {
            swings++;
        }
    }

    // any point of its box in range with a clear ray from the eye, null when there is none (GolemFightTask has the long version)
    private static Vec3 reachablePoint(LocalPlayer player, Mob mob) {
        AABB box = mob.getBoundingBox();
        Vec3 eye = player.getEyePosition();
        List<double[]> points = new ArrayList<>(GolemRules.aimPoints(eye.x, eye.y, eye.z, box.minX, box.minY, box.minZ, box.maxX, box.maxY,
                box.maxZ, GolemRules.INTERACT_RANGE));
        // a foe hugging the pillar hides its middle behind the column's edge: the far upper corners and the top face are what we see
        double in = 0.05;
        double[] xs = {box.minX + in, box.maxX - in};
        double[] zs = {box.minZ + in, box.maxZ - in};
        double[] ys = {box.maxY - in, (box.minY + box.maxY) / 2};
        for (double y : ys) {
            for (double x : xs) {
                for (double z : zs) {
                    double dx = x - eye.x;
                    double dy = y - eye.y;
                    double dz = z - eye.z;
                    if (dx * dx + dy * dy + dz * dz < GolemRules.INTERACT_RANGE * GolemRules.INTERACT_RANGE) {
                        points.add(new double[]{x, y, z});
                    }
                }
            }
        }
        for (double[] pt : points) {
            Vec3 point = new Vec3(pt[0], pt[1], pt[2]);
            if (LookHelper.cleanLineOfSight(player, eye, point, 8)) {
                return point;
            }
        }
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        // nothing of ours to hand back, the pillar task stops itself
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof PerchTask task && task.h == h;
    }

    @Override
    protected String toDebugString() {
        return "Perch h=" + h + ", " + swings + " swings" + (failed ? ", failed" : "");
    }

    @Override
    protected String toHudString() {
        return hud;
    }
}
