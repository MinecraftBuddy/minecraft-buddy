package adris.altoclef.tasks.speedrun.gamer.end;

import adris.altoclef.util.helpers.ReachRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

// where the pillar fight stands and clicks, and when. the bed goes on top of the bedrock pillar with us tucked against it one
// below the top, so the pillar eats the blast and all we get is the flat bit. pure so the task and the tests agree
public final class PillarBed {
    public enum Step {
        // sat down: the head is out of range from the stand and the wings shove anything by the body
        RING,
        STAND,
        // up in the air and we're there already. a walk that starts with the landing loses to the clouds
        WAIT,
        PLACE,
        CLICK
    }

    // the head is only ever this close while it comes in over the pillar
    public static final double HEAD_RANGE = 5.3;
    // LivingEntity.hurtServer: for the first 10 ticks after a hit (invulnerableTime > 10) a new hit only counts for what it adds over
    // the last one, and one bed adds nothing over another. one more tick so a blast that lands late isn't still inside it
    public static final int CLICK_SPACING = 11;
    public static final int PLACE_EVERY = 2;
    // a plain player's block reach, eye to the bed's box. the server allows one more on top, that's its slack and not ours
    public static final double BED_REACH = 4.5;
    // the stand and the floor beside the pillar are both 0, all the armor lets through there is the flat 0.4. a tenth is what a
    // shove can leave showing for about 1.5 through iron, past that it's the walk back
    public static final double SEEN_LIMIT = 0.1;
    // damage/4 + min(damage, 1) off about 52 point blank, because only the head takes a bed whole
    public static final double BODY_BED_DAMAGE = 14;
    public static final double FINISH_BODY_RANGE = 4;
    // the bedrock column goes this far down from its top, under that it's the bedrock floor and nobody is standing in that
    private static final int PILLAR_DEPTH = 3;
    // the look at the pillar top has to read as east to the server, and right on the diagonal it reads as whatever it feels like
    private static final double EAST_CONE = 0.9;
    private static final double STAND_REACH = 0.5;
    // the dragon's feet this close to the pillar top is sat down, whatever the phase still says
    private static final int SAT_ABOVE_TOP = 2;

    private PillarBed() {
    }

    public static BlockPos stand(BlockPos portalTop) {
        return portalTop.offset(-1, -1, 0);
    }

    public static BlockPos bed(BlockPos portalTop) {
        return portalTop.above();
    }

    public static boolean sat(boolean sitting, double dragonY, int portalTopY) {
        return sitting || dragonY < portalTopY + SAT_ABOVE_TOP;
    }

    // the bed goes off at the middle of its head half, the half hanging off the pillar on the far side from us
    public static Vec3 blast(BlockPos portalTop) {
        return Vec3.atCenterOf(bed(portalTop).east());
    }

    // ServerExplosion.getSeenPercent's own grid of rays, with the pillar as the only thing in the way. the real one can't be asked
    // while the bed stands: its rays all end inside the bed's own box, so it says 0 from anywhere. the server only asks once the
    // bed is gone. anything else in the way just makes us safer than this says
    public static double seen(BlockPos portalTop, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        double xs = 1 / ((maxX - minX) * 2 + 1);
        double ys = 1 / ((maxY - minY) * 2 + 1);
        double zs = 1 / ((maxZ - minZ) * 2 + 1);
        double xOff = (1 - Math.floor(1 / xs) * xs) / 2;
        double zOff = (1 - Math.floor(1 / zs) * zs) / 2;
        int rays = 0;
        int through = 0;
        for (double a = 0; a <= 1; a += xs) {
            for (double b = 0; b <= 1; b += ys) {
                for (double c = 0; c <= 1; c += zs) {
                    rays++;
                    if (!pillarInTheWay(portalTop, minX + a * (maxX - minX) + xOff, minY + b * (maxY - minY), minZ + c * (maxZ - minZ) + zOff)) {
                        through++;
                    }
                }
            }
        }
        return (double) through / rays;
    }

    private static boolean pillarInTheWay(BlockPos top, double x, double y, double z) {
        Vec3 to = blast(top);
        double[] enter = {0};
        double[] leave = {1};
        return slab(x, to.x, top.getX(), top.getX() + 1, enter, leave)
                && slab(y, to.y, top.getY() - PILLAR_DEPTH, top.getY() + 1, enter, leave)
                && slab(z, to.z, top.getZ(), top.getZ() + 1, enter, leave);
    }

    // one axis of a ray against a box, false once the ray can't be inside on every axis at the same time
    private static boolean slab(double from, double to, double min, double max, double[] enter, double[] leave) {
        double d = to - from;
        if (Math.abs(d) < 1e-9) {
            return from >= min && from <= max;
        }
        double a = (min - from) / d;
        double b = (max - from) / d;
        enter[0] = Math.max(enter[0], Math.min(a, b));
        leave[0] = Math.min(leave[0], Math.max(a, b));
        return enter[0] <= leave[0];
    }

    public static double reachGap(BlockPos portalTop, double eyeX, double eyeY, double eyeZ) {
        BlockPos bed = bed(portalTop);
        return ReachRules.gap(eyeX, eyeY, eyeZ, bed.getX(), bed.getY(), bed.getZ(), bed.getX() + 1, bed.getY() + 1, bed.getZ() + 1);
    }

    // the blast's shadow is wider than the east quarter. out past it a place would wait forever on a turn that can't happen
    public static boolean looksEast(BlockPos portalTop, double x, double z) {
        double dx = portalTop.getX() + 0.5 - x;
        return dx > 0 && Math.abs(portalTop.getZ() + 0.5 - z) < dx * EAST_CONE;
    }

    // safe to set one off from here, stand or no stand: a wing can put us somewhere else with the landing still on, and the click
    // goes straight in so it doesn't care what's between us and the bed. on the ground: in the air we move before it blows
    public static boolean covered(boolean grounded, double reachGap, double seen) {
        return grounded && reachGap <= BED_REACH && seen <= SEEN_LIMIT;
    }

    // a bed that's already down doesn't care which way we look, only a new one takes our yaw
    public static boolean fightHere(boolean covered, boolean bedDown, boolean looksEast) {
        return covered && (bedDown || looksEast);
    }

    // feet too: the well floor passes the flat check but it isn't where we walk to. whether it will do is covered's call
    public static boolean atStand(BlockPos stand, double x, double z, int feetY) {
        double dx = x - (stand.getX() + 0.5);
        double dz = z - (stand.getZ() + 0.5);
        return dx * dx + dz * dz < STAND_REACH * STAND_REACH && feetY >= stand.getY();
    }

    public static Step step(boolean sat, boolean landing, boolean ready, boolean bedDown) {
        if (sat) return Step.RING;
        if (!ready) return Step.STAND;
        if (!landing) return Step.WAIT;
        return bedDown ? Step.CLICK : Step.PLACE;
    }

    // the bed's head half goes the way we face and it has to land past the pillar, that's where it explodes
    public static boolean placeNow(Direction facing, long tick) {
        return facing == Direction.EAST && tick % PLACE_EVERY == 0;
    }

    // the blast starts at the head half, so the foot sits on the pillar and the head hangs off the far side from us. anything
    // else has nothing between us and it
    public static boolean rightWay(Direction facing, boolean foot) {
        return foot && facing == Direction.EAST;
    }

    // a sat dragon never gets here (step's RING): the bed can't go down through the body, the head turns away and the wings shove
    public static boolean finish(double dragonHp, int bedsLeft, double bodyDistance) {
        return dragonHp <= bedsLeft * BODY_BED_DAMAGE && bodyDistance <= FINISH_BODY_RANGE;
    }

    // finishing: the beds we have can end it off the body alone, and waiting for the head to swing by is how the crystals win
    public static boolean clickNow(boolean covered, double headDistance, boolean finishing, long tick, long lastClick) {
        return covered && (headDistance < HEAD_RANGE || finishing) && tick - lastClick >= CLICK_SPACING;
    }
}
