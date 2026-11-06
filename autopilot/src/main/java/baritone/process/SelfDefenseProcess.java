/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process;

import adris.altoclef.util.helpers.ItemHelper;
import baritone.Baritone;
import baritone.altoclef.AltoClefBridge;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.WorldEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.behavior.CombatAim;
import baritone.behavior.LegacyCombat;
import baritone.utils.BaritoneProcessHelper;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

// plain jobs (#mine, #build, #farm, #follow, #goto) had no combat at all, alto's only ticks while alto runs and its fight
// cancels everything, which wipes a mine. so this sits just above the jobs as a temporary process: a mob bites us from
// melee range, we pause the job where it is, hit it till it's dead or gone, and hand back. temporary means nothing below
// gets onLostControl, the job and even its path segment come back untouched. the decisions live in SelfDefenseRules
public final class SelfDefenseProcess extends BaritoneProcessHelper {

    // above every -1 job and the torch placer (-0.5, a zombie beats a dark corner), under #pause and elytra (0), backfill
    // (5) and the inventory pauser (5.1)
    private static final double PRIORITY = -0.4;
    // a bare player hits for 1, see ItemHelper
    private static final float FIST = 1;

    // entity id -> game time it last hit us
    private final Map<Integer, Long> hits = new HashMap<>();
    // entity id -> {when we gave up on it, for how long}, see SelfDefenseRules.shrugFor
    private final Map<Integer, long[]> shrugs = new HashMap<>();
    // the client builds a new source for every hit and keeps it around for 40 ticks, so a new object is a new hit
    private DamageSource lastSource;

    private Mob target;
    private int ticksOutOfReach;
    private int ticksNoLine;
    // ticks since its health last went down, and what it was
    private int ticksNoProgress;
    private float lastHealth;
    // what was in hand before we pulled the sword, -1 when we didn't swap
    private int restoreSlot = -1;
    // and the slot the weapon went in, so a late restore only happens if nobody touched the hand since
    private int swordSlot = -1;

    public SelfDefenseProcess(Baritone baritone) {
        super(baritone);
        // the book has to fill while we're not active, and onTick only runs once we are. so it gets its own listener,
        // the way behaviors do it
        baritone.getGameEventHandler().registerEventListener(new AbstractGameEventListener() {
            @Override
            public void onTick(TickEvent event) {
                if (event.getType() == TickEvent.Type.IN) {
                    noteHits();
                }
            }

            @Override
            public void onWorldEvent(WorldEvent event) {
                // entity ids and game time both start over in a new world, and the zombie stayed in the old one.
                // restoreSlot stays, a portal keeps the inventory and the job still wants its pickaxe back
                hits.clear();
                shrugs.clear();
                lastSource = null;
                target = null;
            }
        });
    }

    private void noteHits() {
        LocalPlayer player = ctx.player();
        if (player == null || ctx.world() == null) {
            return;
        }
        long now = ctx.world().getGameTime();
        if (target == null && restoreSlot >= 0) {
            retryRestore();
        }
        DamageSource source = player.getLastDamageSource();
        if (source != null && source != lastSource) {
            lastSource = source;
            Entity attacker = source.getEntity();
            if (attacker != null && SelfDefenseRules.noteable(attacker instanceof Mob, attacker instanceof Enemy, typeOf(attacker))) {
                hits.put(attacker.getId(), now);
            }
        }
        Iterator<Map.Entry<Integer, Long>> it = hits.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Long> e = it.next();
            Entity entity = ctx.world().getEntity(e.getKey());
            if (SelfDefenseRules.forget(now - e.getValue(), entity != null && entity.isAlive())) {
                it.remove();
            }
        }
        shrugs.values().removeIf(s -> !SelfDefenseRules.shrugged(now - s[0], s[1]));
    }

    private static String typeOf(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath();
    }

    @Override
    public boolean isActive() {
        if (!canRun()) {
            // the job ended or we went for a swim mid fight. temporary processes get no onLostControl for just going
            // inactive, so the hand goes back here
            if (target != null) {
                release("stood down");
            }
            return false;
        }
        return target != null || pickTarget() != null;
    }

    // cheap checks first, the job list last (FollowProcess.isActive scans entities)
    private boolean canRun() {
        LocalPlayer p = ctx.player();
        if (p == null || ctx.world() == null || (target == null && hits.isEmpty())) {
            return false;
        }
        // a chest or a crafting table open means someone is at the keyboard doing something, don't yank the camera
        boolean jobActive = p.containerMenu == p.inventoryMenu && jobActive();
        return SelfDefenseRules.canRun(Baritone.settings().selfDefense.value, p.isAlive(), p.isFallFlying(), p.isInWater(),
                p.isInLava(), AltoClefBridge.isRunning(), jobActive);
    }

    private boolean jobActive() {
        IBaritoneProcess[] jobs = {baritone.getMineProcess(), baritone.getBuilderProcess(), baritone.getFarmProcess(),
                baritone.getFollowProcess(), baritone.getGetToBlockProcess(), baritone.getExploreProcess()};
        for (IBaritoneProcess job : jobs) {
            if (job.isActive()) {
                return true;
            }
        }
        // a bare #goal is active forever and only draws a box, the player is walking themselves
        return baritone.getCustomGoalProcess().isPathingToGoal();
    }

    // the nearest mob that bit us lately and is in reach right now
    private Mob pickTarget() {
        long now = ctx.world().getGameTime();
        Mob best = null;
        double bestDist = Double.MAX_VALUE;
        for (Map.Entry<Integer, Long> e : hits.entrySet()) {
            if (shrugs.containsKey(e.getKey()) || !(ctx.world().getEntity(e.getKey()) instanceof Mob mob) || !mob.isAlive()) {
                continue;
            }
            double dist = reachDistance(mob);
            if (SelfDefenseRules.engage(now - e.getValue(), dist <= reach()) && dist < bestDist) {
                best = mob;
                bestDist = dist;
            }
        }
        return best;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (target == null) {
            if (!isSafeToCancel) {
                // mid jump or mid ladder, the job finishes the move first and we try again next tick
                return new PathingCommand(null, PathingCommandType.DEFER);
            }
            Mob mob = pickTarget();
            if (mob == null) {
                return new PathingCommand(null, PathingCommandType.DEFER);
            }
            engage(mob);
        }
        // mine's shaft, the builder and the farm leave left click held, and taking over from them clears nothing. without
        // this we dig whatever the crosshair sweeps over on the way to the zombie
        baritone.getInputOverrideHandler().clearAllKeys();
        baritone.getInputOverrideHandler().getBlockBreakHelper().stopBreakingBlock();

        double dist = target.isAlive() && !target.isRemoved() ? reachDistance(target) : Double.MAX_VALUE;
        boolean inReach = dist <= reach();
        Vec3 aim = inReach ? aimPoint(target) : null;
        ticksOutOfReach = inReach ? 0 : ticksOutOfReach + 1;
        if (inReach) {
            ticksNoLine = aim == null ? ticksNoLine + 1 : 0;
        }
        float health = target.getHealth();
        ticksNoProgress = health < lastHealth ? 0 : ticksNoProgress + 1;
        lastHealth = health;
        boolean stuck = ticksNoLine >= SelfDefenseRules.NO_LINE_TICKS || SelfDefenseRules.noProgress(ticksNoProgress);
        if (stuck || SelfDefenseRules.release(!target.isAlive() || target.isRemoved(), dist, ticksOutOfReach, ticksNoLine)) {
            if (stuck) {
                shrugs.put(target.getId(), new long[]{ctx.world().getGameTime(),
                        SelfDefenseRules.shrugFor(SelfDefenseRules.noProgress(ticksNoProgress))});
            }
            release((stuck ? "giving up on " : "done with ") + typeOf(target));
            return new PathingCommand(null, PathingCommandType.DEFER);
        }
        if (aim != null) {
            // the visible point aimPoint found is where we'd like to end up, CombatAim does the turning (we pause the
            // job's path below, so nothing walks while it does)
            baritone.getLookBehavior().combat().want(target, CombatAim.Priority.SELF_DEFENSE, aim);
            maybeSwing();
        }
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private void engage(Mob mob) {
        target = mob;
        ticksOutOfReach = 0;
        ticksNoLine = 0;
        ticksNoProgress = 0;
        lastHealth = mob.getHealth();
        int held = ctx.player().getInventory().getSelectedSlot();
        int weapon = weaponSlot(held);
        if (weapon >= 0 && weapon != held && baritone.getInventoryBehavior().selectHotbar(weapon, "selfDefense")
                && restoreSlot < 0) {
            // the swap resets the attack cooldown, so the first swing is a bit late. still better than a pickaxe.
            // a restore still pending from the last fight keeps its slot, that's the one the job had
            restoreSlot = held;
            swordSlot = weapon;
        }
        logDebug("Self defense: fighting " + typeOf(mob));
    }

    private void release(String why) {
        // a failed restore stays pending, noteHits tries again
        if (restoreSlot >= 0 && baritone.getInventoryBehavior().selectHotbar(restoreSlot, "selfDefense")) {
            restoreSlot = -1;
            swordSlot = -1;
        }
        target = null;
        ticksOutOfReach = 0;
        ticksNoLine = 0;
        ticksNoProgress = 0;
        logDebug("Self defense: " + why + ", back to the job");
    }

    // the hand could not go back when we let go, which means alto had interactions paused. try again once it lets go, but
    // only while the weapon is still what we're holding and alto is off: otherwise alto or the player picked a slot since
    // and it's theirs now
    private void retryRestore() {
        boolean stillOurs = ctx.player().getInventory().getSelectedSlot() == swordSlot && !AltoClefBridge.isRunning();
        if (!stillOurs || baritone.getInventoryBehavior().selectHotbar(restoreSlot, "selfDefense")) {
            restoreSlot = -1;
            swordSlot = -1;
        }
    }

    private int weaponSlot(int held) {
        NonNullList<ItemStack> inv = ctx.player().getInventory().getNonEquipmentItems();
        int[] kind = new int[9];
        float[] damage = new float[9];
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.get(i);
            if (stack.isEmpty()) {
                damage[i] = FIST;
                continue;
            }
            kind[i] = ItemHelper.isSword(stack.getItem()) ? 2 : ItemHelper.isAxe(stack.getItem()) ? 1 : 0;
            damage[i] = ItemHelper.getAttackDamage(stack.getItem());
        }
        return SelfDefenseRules.weaponSlot(kind, damage, held, FIST);
    }

    private void maybeSwing() {
        LocalPlayer p = ctx.player();
        // the ray from the last look packet has to hit it, not just point roughly its way: our onTick runs before this
        // tick's look gets applied, the server only knows what was in the last packet
        CombatAim combat = baritone.getLookBehavior().combat();
        boolean onTarget = combat.onTarget(target, reach());
        if (LegacyCombat.active()) {
            maybeClick(combat, onTarget);
            return;
        }
        if (SelfDefenseRules.swing(p.getAttackStrengthScale(0.5f), onTarget)) {
            LegacyCombat.hit(ctx.minecraft().gameMode, p, target, InteractionHand.MAIN_HAND);
        }
    }

    // pre 1.9 the charge is faked full and means nothing, the shared clicker paces us instead (same clock as alto's swings,
    // so the two of them never click in the same tick)
    private void maybeClick(CombatAim combat, boolean onTarget) {
        long now = ctx.world().getGameTime();
        if (onTarget && combat.clicker().due(now)) {
            LegacyCombat.hit(ctx.minecraft().gameMode, ctx.player(), target, InteractionHand.MAIN_HAND);
            combat.clicker().clicked(now);
        }
    }

    private double reach() {
        return ctx.player().entityInteractionRange();
    }

    // eye to the closest bit of its box, which is what the server measures too
    private double reachDistance(Entity entity) {
        Vec3 eye = ctx.player().getEyePosition();
        return Math.sqrt(entity.getBoundingBox().distanceToSqr(eye));
    }

    // centre, top, its eyes, then the bit of the box nearest us. the first one we can reach and see wins. a baby zombie
    // in a one high gap usually only has the nearest point left
    private Vec3 aimPoint(Mob mob) {
        Vec3 eye = ctx.player().getEyePosition();
        AABB box = mob.getBoundingBox();
        Vec3 center = box.getCenter();
        Vec3[] points = {center, new Vec3(center.x, box.maxY - 0.05, center.z), mob.getEyePosition(), nearest(box, eye)};
        double reach = reach();
        for (Vec3 point : points) {
            if (point.distanceTo(eye) <= reach && clear(eye, point)) {
                return point;
            }
        }
        return null;
    }

    private static Vec3 nearest(AABB box, Vec3 from) {
        return new Vec3(Math.clamp(from.x, box.minX, box.maxX), Math.clamp(from.y, box.minY, box.maxY),
                Math.clamp(from.z, box.minZ, box.maxZ));
    }

    // outline, same as the crosshair: a torch or tall grass in the way is in the way for a real player too
    private boolean clear(Vec3 eye, Vec3 point) {
        return ctx.world().clip(new ClipContext(eye, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, ctx.player()))
                .getType() == HitResult.Type.MISS;
    }

    @Override
    public boolean isTemporary() {
        return true;
    }

    @Override
    public double priority() {
        return PRIORITY;
    }

    @Override
    public void onLostControl() {
        if (target != null) {
            release("cancelled");
        }
    }

    @Override
    public String displayName0() {
        return "Self defense" + (target == null ? "" : " vs " + typeOf(target));
    }
}
