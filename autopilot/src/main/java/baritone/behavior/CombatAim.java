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

package baritone.behavior;

import baritone.Baritone;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.behavior.look.AimSmoother;
import baritone.behavior.look.CombatAimMath;
import baritone.behavior.look.CombatWants;
import baritone.behavior.look.LegacyClicker;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.Random;

// melee aim. fighters don't write the look any more, they ask for a mob with want() every tick they care, and LookBehavior
// turns the head once per tick for whoever won (see CombatWants), a few degrees at a time like a hand on a mouse. it never
// fights a path for the head: movement steers by the look yaw, so a swing that turned us while baritone walks a path walks
// us sideways. the swing side is onTarget(): the ray the server last heard about has to actually hit the mob
public final class CombatAim {

    // higher wins. the kill task is the fight we picked, the golem is a fight we picked too, the aura is "something is
    // near us", self defense only runs when alto doesn't
    public enum Priority {
        SELF_DEFENSE,
        AURA,
        GOLEM,
        KILL_TASK
    }

    private final IPlayerContext ctx;
    private final LookBehavior look;
    private final CombatWants<Entity, Vec3> wants = new CombatWants<>();
    private final AimSmoother smoother = new AimSmoother(new Random());
    // one hand, one click rhythm: the alto swings and self defense share it, two clickers would be twice the cps
    private final LegacyClicker clicker = new LegacyClicker(new Random());

    CombatAim(IPlayerContext ctx, LookBehavior look) {
        this.ctx = ctx;
        this.look = look;
    }

    // the pre 1.9 click pacing, see LegacyCombat. unused on a modern server
    public LegacyClicker clicker() {
        return clicker;
    }

    // look at this mob this tick, please. call it every tick you want it, an ask is good for one tick
    public void want(Entity entity, Priority priority) {
        want(entity, priority, null);
    }

    // with a point you'd rather we looked at (a visible bit of a half hidden mob), in world coordinates
    public void want(Entity entity, Priority priority, Vec3 preferred) {
        wants.want(entity, priority.ordinal(), preferred);
    }

    // the kill task's stall breaker: it already told the path to go, but a path only goes once it's safe to cancel, and
    // the stall breaker is for when that never comes. so this ask doesn't yield to the path being alive. it still loses
    // to a look target somebody set this tick, which a movement mid step usually does, so it only wins the ticks where
    // the path is up but nothing is steering
    public void wantOverPath(Entity entity, Priority priority) {
        want(entity, priority);
        overPath = true;
    }

    private boolean overPath;

    // whether this tick's asks may take the head from a walking path, see wantOverPath
    boolean overPath() {
        return overPath;
    }

    // LookBehavior, at PRE: somebody else has the head this tick (a block look, a movement) or a path is walking. the ask
    // is dropped, not saved for later
    Rotation yieldTick() {
        wants.clear();
        wants.idle();
        overPath = false;
        return null;
    }

    // LookBehavior, at PRE with the head free: the rotation for this tick, or null when nobody asked
    Rotation resolve() {
        Entity entity = wants.who();
        Vec3 preferred = wants.point();
        wants.clear();
        overPath = false;
        LocalPlayer player = ctx.player();
        if (entity == null || player == null || entity.isRemoved()) {
            wants.idle();
            return null;
        }
        if (wants.engage(entity)) {
            smoother.acquire();
        }
        // one tick of lead from how far it moved last tick. a remote mob's deltaMovement on the client is mostly a lie
        double lx = entity.getX() - entity.xo;
        double ly = entity.getY() - entity.yo;
        double lz = entity.getZ() - entity.zo;
        AABB box = entity.getBoundingBox().move(lx, ly, lz);
        double[] b = {box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ};
        Vec3 eyeVec = player.getEyePosition();
        double[] pref = preferred == null ? CombatAimMath.preferredPoint(b, eyeVec.y)
                : new double[]{preferred.x + lx, preferred.y + ly, preferred.z + lz};
        // an enderman reads a look at its head as a challenge and teleports away, so every ask for one, even a caller who
        // knows better, gets its legs
        boolean ender = entity instanceof Enderman;
        double[] inner = ender ? CombatAimMath.innerBox(b, CombatAimMath.ENDERMAN_LOW, CombatAimMath.ENDERMAN_HIGH) : CombatAimMath.innerBox(b);
        if (ender) {
            pref = CombatAimMath.capHeight(pref, b, CombatAimMath.ENDERMAN_HIGH);
        }
        double[] eye = {eyeVec.x, eyeVec.y, eyeVec.z};
        Rotation now = ctx.playerRotations();
        double[] dir = CombatAimMath.direction(now.getYaw(), now.getPitch());
        double[] point = CombatAimMath.aimPoint(eye, dir, inner, pref);
        float[] to = CombatAimMath.rotationTo(eye, point);
        float[] stepped = smoother.step(now.getYaw(), now.getPitch(), to[0], to[1], Baritone.settings().smoothCombatAim.value);
        // same limits the per tick reroll has always had, just as a slow wander instead of a shake
        float[] wobble = smoother.wobble(Baritone.settings().randomLooking113.value / 2, Baritone.settings().randomLooking.value);
        float yaw = stepped[0] + wobble[0];
        float pitch = stepped[1] + wobble[1];
        if (ender) {
            // the smoothing walks the head there over a few ticks and the wobble sits on top, so the point being low does not
            // promise the look is: if the head is passing through its eye cone this tick, it goes down instead
            Vec3 ee = entity.getEyePosition();
            if (CombatAimMath.inEyeCone(eye, CombatAimMath.direction(yaw, pitch), new double[]{ee.x, ee.y, ee.z})) {
                pitch = Math.min(90, pitch + 15);
            }
        }
        return new Rotation(yaw, pitch);
    }

    void reset() {
        wants.reset();
        overPath = false;
    }

    // would a swing right now land on this mob, as the server sees it: the rotation in our last look packet, from our eye,
    // hits its box (grown by the pick radius like vanilla's crosshair) within reach, and no block is in the way first.
    // the rotation is serverRotation and not the player's: this tick's turn hasn't been sent yet, the swing goes out before it
    public boolean onTarget(Entity target, double reach) {
        LocalPlayer player = ctx.player();
        if (player == null || target == null) {
            return false;
        }
        Optional<Rotation> sent = look.serverRotation();
        Rotation rot = sent.orElseGet(() -> new Rotation(player.getYRot(), player.getXRot()));
        Vec3 eye = player.getEyePosition();
        double[] dir = CombatAimMath.direction(rot.getYaw(), rot.getPitch());
        AABB box = target.getBoundingBox().inflate(target.getPickRadius());
        double t = CombatAimMath.rayEntry(new double[]{eye.x, eye.y, eye.z}, dir,
                new double[]{box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ});
        if (Double.isNaN(t) || t > reach) {
            return false;
        }
        if (t <= 0) {
            // eye inside its box, nothing can be in between
            return true;
        }
        // a hair short of the box, so a mob pressed against a wall doesn't get the wall's face counted as in the way
        double d = Math.max(0, t - 0.01);
        Vec3 entry = eye.add(dir[0] * d, dir[1] * d, dir[2] * d);
        // collision shapes first, on purpose: the server doesn't check occlusion at all, and the outline alone (what the
        // crosshair stops at) would have us stand in a field of grass never swinging because every blade is "in the way".
        // a fence is the other way round, its collision box is a block and a half tall and its outline is one, so a cow in
        // a pen is only hidden by the first. blocked means both say so
        return clear(eye, entry, ClipContext.Block.COLLIDER, player) || clear(eye, entry, ClipContext.Block.OUTLINE, player);
    }

    private boolean clear(Vec3 from, Vec3 to, ClipContext.Block shape, LocalPlayer player) {
        return ctx.world().clip(new ClipContext(from, to, shape, ClipContext.Fluid.NONE, player)).getType() == HitResult.Type.MISS;
    }
}
