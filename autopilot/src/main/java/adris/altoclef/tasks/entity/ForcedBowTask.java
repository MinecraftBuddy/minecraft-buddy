package adris.altoclef.tasks.entity;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.DangerMeter;
import adris.altoclef.util.helpers.MobThreat;
import baritone.api.utils.input.Input;
import baritone.behavior.CombatAim;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

// the buddy's fork: the forced fight's bow (the danger meter's count ladder, (b2) review point 4: the forced fight was melee
// only, so a blaze hovering out of reach or a ghast was never reached while the meter's K counted a walk up to it). for a
// flyer out of the sword's reach with a clear line, DangerForceChain shoots it with the kit's bow: the head turned by the
// combat aim (a few degrees a tick, like a hand on a mouse, never written straight), the bow drawn by holding the use key
// and let go once fully drawn with the arrow's way (the look, less its drop) through the mob's box and no block on the
// line, one arrow at a time. the fork ticks at the top of the client tick, so the draw and the let go reach the server at
// its start, as every swing does. never a shot through a block: no line, no draw, and a draw under way is held (a key let
// up mid draw looses the arrow) until the line is back or the chain takes the hand for the sword, whose swap ends the draw.
// a new target keeps the draw (retarget): the aim swings over as a player's would. (9 Nov 2026: in the ladder's first block
// no arrow left in 14 fights. the buddy's own bow, Bow.startTick in the mod, let go of every bow draw it had not begun at
// the start of each tick, one tick into the draw; and the target flipping between two blazes every few seconds threw the
// draw away with the task)
public class ForcedBowTask extends Task {
    // a bow at full draw (vanilla: 20 ticks of use)
    static final int FULL_DRAW = 20;
    // after an arrow, the hand rests this long before the next draw
    static final int REST_TICKS = 4;
    // an arrow leaves at about this many blocks a tick at full draw, and falls by this much a tick squared (with the air's drag
    // the fall is a little more; DROP_FUDGE)
    static final double ARROW_SPEED = 2.9;
    static final double GRAVITY = 0.05;
    static final double DROP_FUDGE = 1.1;

    private Entity _target;
    private long _restUntil = Long.MIN_VALUE;
    private int _shots;
    // draws begun, for the shot's line: many draws to a shot is a draw that keeps being cut short
    private int _draws;
    private boolean _wasDrawing;

    public ForcedBowTask(Entity target) {
        _target = target;
    }

    // another mob for the same bow: the draw under way is kept, the aim goes over to it
    public void retarget(Entity target) {
        _target = target;
    }

    public Entity target() {
        return _target;
    }

    // a bow and an arrow in the bag (and the bow not switched off: tuning key danger_bow=0, melee only and the meter's K
    // without it, should the bow misbehave in a drill)
    public static boolean canShoot(AltoClef mod) {
        return adris.altoclef.util.helpers.Tuning.get("danger_bow", 1) > 0 && mod.getItemStorage().hasItem(Items.BOW)
                && mod.getItemStorage().hasItem(Items.ARROW, Items.SPECTRAL_ARROW, Items.TIPPED_ARROW);
    }

    // a flyer by the meter's own table (blaze, ghast, phantom, ...)
    public static boolean flyer(Entity e) {
        MobThreat row = MobThreat.forMeter(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath(), 1);
        return row != null && row.flies();
    }

    // the bow's case: a flyer, a bow and arrows, out of the sword's reach and a clear line from the eye to it
    public static boolean wants(AltoClef mod, Entity target) {
        LocalPlayer player = mod.getPlayer();
        if (player == null || target == null || !target.isAlive() || !flyer(target) || !canShoot(mod)) return false;
        Vec3 eye = player.getEyePosition();
        if (boxDistance(eye, target.getBoundingBox()) <= mod.getControllerExtras().swingReach()) return false;
        return clear(mod, eye, target.getBoundingBox().getCenter());
    }

    static double boxDistance(Vec3 p, AABB b) {
        double dx = Math.max(Math.max(b.minX - p.x, 0), p.x - b.maxX);
        double dy = Math.max(Math.max(b.minY - p.y, 0), p.y - b.maxY);
        double dz = Math.max(Math.max(b.minZ - p.z, 0), p.z - b.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    static boolean clear(AltoClef mod, Vec3 from, Vec3 to) {
        return mod.getWorld().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mod.getPlayer()))
                .getType() == HitResult.Type.MISS;
    }

    // where to aim: the middle of the mob raised by how far the arrow falls on its way there (no higher than a little over
    // its top), pure for the test
    static double[] aimPoint(double[] eye, double[] centre, double top) {
        double dx = centre[0] - eye[0];
        double dz = centre[2] - eye[2];
        double flat = Math.sqrt(dx * dx + dz * dz);
        double ticks = flat / ARROW_SPEED;
        double drop = 0.5 * GRAVITY * ticks * ticks * DROP_FUDGE;
        return new double[]{centre[0], Math.min(centre[1] + drop, top + drop * 0.5), centre[2]};
    }

    // {yaw, pitch} from eye to point
    static float[] rotationTo(double[] eye, double[] point) {
        double dx = point[0] - eye[0];
        double dy = point[1] - eye[1];
        double dz = point[2] - eye[2];
        double flat = Math.sqrt(dx * dx + dz * dz);
        return new float[]{(float) Math.toDegrees(Math.atan2(-dx, dz)), (float) -Math.toDegrees(Math.atan2(dy, flat))};
    }

    // the look as a unit vector, as Entity.calculateViewVector
    static double[] direction(float yaw, float pitch) {
        double p = Math.toRadians(pitch);
        double y = Math.toRadians(-yaw);
        double flat = Math.cos(p);
        return new double[]{Math.sin(y) * flat, -Math.sin(p), Math.cos(y) * flat};
    }

    // whether an arrow loosed now along the look meets the mob: the look carried out to the mob's middle across the ground,
    // lowered by the arrow's drop over that way (aimPoint's), lands inside its box (minX, minY, minZ, maxX, maxY, maxZ).
    // pure for the test
    static boolean onTarget(double[] eye, float yaw, float pitch, double[] box) {
        double[] d = direction(yaw, pitch);
        double cx = (box[0] + box[3]) / 2 - eye[0];
        double cz = (box[2] + box[5]) / 2 - eye[2];
        double flat = Math.sqrt(cx * cx + cz * cz);
        double dFlat = Math.sqrt(d[0] * d[0] + d[2] * d[2]);
        if (dFlat < 1e-6) return false;
        double t = flat / dFlat;
        double ticks = flat / ARROW_SPEED;
        double drop = 0.5 * GRAVITY * ticks * ticks * DROP_FUDGE;
        double x = eye[0] + d[0] * t;
        double y = eye[1] + d[1] * t - drop;
        double z = eye[2] + d[2] * t;
        return x >= box[0] && x <= box[3] && y >= box[1] && y <= box[4] && z >= box[2] && z <= box[5];
    }

    @Override
    protected void onStart(AltoClef mod) {
        _restUntil = Long.MIN_VALUE;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        LocalPlayer player = mod.getPlayer();
        // (the target gone: nothing loosed; the chain picks the next one)
        if (player == null || !_target.isAlive()) return null;
        long now = mod.getWorld().getGameTime();
        if (!mod.getSlotHandler().forceEquipItem(Items.BOW)) {
            setDebugState("bow to hand");
            return null;
        }
        Vec3 eyeV = player.getEyePosition();
        double[] eye = {eyeV.x, eyeV.y, eyeV.z};
        AABB box = _target.getBoundingBox();
        Vec3 c = box.getCenter();
        double[] point = aimPoint(eye, new double[]{c.x, c.y, c.z}, box.maxY);
        Vec3 aim = new Vec3(point[0], point[1], point[2]);
        mod.getControllerExtras().aimAt(_target, CombatAim.Priority.KILL_TASK, aim);
        boolean drawing = player.isUsingItem() && player.getUseItem().is(Items.BOW);
        if (drawing && !_wasDrawing) _draws++;
        _wasDrawing = drawing;
        if (!clear(mod, eyeV, c) || !clear(mod, eyeV, aim)) {
            // no line: no draw begun and none loosed (a draw under way stays held); the chain closes in after a while
            setDebugState("no line");
            return null;
        }
        if (!drawing) {
            if (now < _restUntil) return null;
            setDebugState("drawing");
            mod.getInputControls().hold(Input.CLICK_RIGHT, this);
            return null;
        }
        // keep the key down while the draw runs (another owner's release may have lifted it)
        mod.getInputControls().hold(Input.CLICK_RIGHT, this);
        if (player.getTicksUsingItem() < FULL_DRAW) return null;
        // (the rotation the server last heard, which the arrow leaves by: the let go goes out before this tick's turn)
        baritone.api.utils.Rotation sent = mod.getClientBaritone().getLookBehavior().serverRotation()
                .orElseGet(() -> new baritone.api.utils.Rotation(player.getYRot(), player.getXRot()));
        if (!onTarget(eye, sent.getYaw(), sent.getPitch(), new double[]{box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ})) {
            setDebugState("aiming");
            return null;
        }
        // a plain release: every claim on the key goes, so a shield's hold on it (the mob defense raises one against a
        // fireball, and with the bow in the main hand that holds the draw) cannot keep the arrow on the string
        mod.getInputControls().release(Input.CLICK_RIGHT);
        _restUntil = now + REST_TICKS;
        _shots++;
        Debug.logInternal("danger: bow shot " + _shots + " at " + BuiltInRegistries.ENTITY_TYPE.getKey(_target.getType()).getPath()
                + " " + DangerMeter.num(eyeV.distanceTo(c)) + " off, " + _draws + " draws");
        setDebugState("loosed");
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getInputControls().releaseOwnedBy(this);
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof ForcedBowTask t && t._target == _target;
    }

    @Override
    protected String toDebugString() {
        return "Shooting " + _target.getType().getDescriptionId();
    }

    @Override
    protected String toHudString() {
        return "Shooting a " + BuiltInRegistries.ENTITY_TYPE.getKey(_target.getType()).getPath();
    }
}
