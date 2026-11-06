package adris.altoclef.tasks.construction;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

// the rules PlaceObsidianBucketTask lives by around its lava, minus the world. P is the cell the lava goes in, S the ledge
// we pour from (one west, one up), W the cell over P where the water goes. pure, the task hands in what it sees
public final class CastLava {

    // ticks in a row of standing dead still with the crosshair parked before the lava goes. the click that fired on the
    // tick the back-up keys came up went out with the pose and the rotation still moving, and the server put it somewhere
    // we never aimed
    public static final int STILL_TICKS = 5;
    // randomLooking alone wobbles us about a degree, two at a couple of blocks is a few cm on a face a block wide
    public static final float AIM_SLACK = 2f;
    // 0.01 blocks a tick. walking friction gets us under that about four ticks after the keys come up
    public static final double STILL_SPEED_SQ = 1e-4;
    // how long the lava we just poured gets to show up before we believe it went nowhere. a round trip and change
    public static final int IN_FLIGHT_TICKS = 30;
    // pours that spent a bucket and left the cell dry before the cast gives the spot up
    public static final int MAX_FAILED_POURS = 3;
    // clicks that spent nothing (wrong thing in hand, bucket never left the bag), a looser cap since they cost no lava
    public static final int MAX_DUD_CLICKS = 6;
    // how long a click gets to show the bucket leaving the bag before it counts as a dud
    public static final int PENDING_TICKS = 10;
    // scooping our own mess or waiting for a flow to drain, same clock as the lava mold's cleanup
    public static final int CLEAN_TICKS = 20 * 8;
    // eye to the middle of the lava, under the 4.5 reach so the ray still has room to land on the source's top
    public static final double SCOOP_REACH = 4.0;

    public enum Mat {
        // nothing to stand in or bump into
        OPEN,
        SOLID,
        LAVA,
        LAVA_FLOW,
        WATER
    }

    public interface Cells {
        Mat at(BlockPos pos);
    }

    public enum Action {
        // nothing loose, the cast carries on
        NONE,
        SCOOP,
        // a stray we can't scoop yet, but this cell's next pour frees a bucket and the stand is fine. the cast carries on
        POUR_FIRST,
        // lava on the stand, the water cell or flowing in the lava cell, and no source of ours to scoop. it drains or it doesn't
        WAIT_DRAIN,
        DROP
    }

    private CastLava() {
    }

    public static BlockPos stand(BlockPos p) {
        return p.offset(-1, 1, 0);
    }

    public static BlockPos water(BlockPos p) {
        return p.above();
    }

    // ---- standing still before the pour

    public static boolean still(boolean onGround, boolean inColumn, boolean keysDown, boolean standingPose, double speedSq,
                                float yawError, float pitchError) {
        return onGround && !inColumn && !keysDown && standingPose && speedSq <= STILL_SPEED_SQ
                && yawError <= AIM_SLACK && pitchError <= AIM_SLACK;
    }

    public static int stillCount(int count, boolean still) {
        return still ? count + 1 : 0;
    }

    public static boolean stillEnough(int count) {
        return count >= STILL_TICKS;
    }

    // 359 and 1 are two degrees apart, not 358
    public static float yawError(float a, float b) {
        float d = (a - b) % 360f;
        if (d < 0) {
            d += 360f;
        }
        return Math.min(d, 360f - d);
    }

    public static boolean inFlight(int clickedAt, int now) {
        return clickedAt != Integer.MIN_VALUE && now - clickedAt <= IN_FLIGHT_TICKS;
    }

    // a pour is a lava bucket that left the bag. a click that kept all of them was a pickaxe to the face of a block
    public static boolean spent(int lavaBucketsBefore, int lavaBucketsNow) {
        return lavaBucketsBefore > 0 && lavaBucketsNow < lavaBucketsBefore;
    }

    // a real pour whose window ran out with the cell neither lava nor obsidian: the lava went somewhere else
    public static boolean pourFailed(Mat cell, boolean obsidian) {
        return cell != Mat.LAVA && !obsidian;
    }

    public static boolean spoiled(int failedPours, int dudClicks) {
        return failedPours >= MAX_FAILED_POURS || dudClicks >= MAX_DUD_CLICKS;
    }

    // two casts are the same running task when they're for the same cell with the same surroundings
    public static boolean sameCast(BlockPos a, List<BlockPos> aFootprint, BlockPos b, List<BlockPos> bFootprint) {
        return a.equals(b) && aFootprint.equals(bFootprint);
    }

    // a scoop that is not ours to take: the cast's own cells (frame, walls, ledge, the cell we pour into) are lava we put
    // there on purpose. a separate pool is fair game
    public static boolean scoopOff(BlockPos cell, BlockPos pos, Collection<BlockPos> footprint, Collection<BlockPos> frame) {
        return cell.equals(pos) || footprint.contains(cell) || frame.contains(cell);
    }

    // ---- where our lava can be

    // every cell lava of ours has no business in: what the parent hands us (the rest of the frame, the inside, the
    // buffer) plus our own cast walls, the ledge, the head room over it and the water cell. P is the one cell it's for
    public static List<BlockPos> footprint(BlockPos p, Collection<BlockPos> extra) {
        Set<BlockPos> out = new LinkedHashSet<>(extra);
        for (Vec3i c : PlaceObsidianBucketTask.CAST_FRAME) {
            out.add(p.offset(c));
        }
        out.add(stand(p));
        out.add(stand(p).above());
        out.add(water(p));
        out.remove(p);
        return new ArrayList<>(out);
    }

    // the cells that have to stay breakable (and not obsidian) for this cast and the ones after it to work: what the
    // parent says (the inside and the buffer, never the frame) plus the ledge and its head room
    public static List<BlockPos> ruinCells(BlockPos p, Collection<BlockPos> extra) {
        Set<BlockPos> out = new LinkedHashSet<>(extra);
        out.add(stand(p));
        out.add(stand(p).above());
        out.remove(p);
        return new ArrayList<>(out);
    }

    public static BlockPos firstRuin(Collection<BlockPos> cells, Predicate<BlockPos> obsidian, Predicate<BlockPos> tooSlow) {
        for (BlockPos c : cells) {
            if (obsidian.test(c) || tooSlow.test(c)) {
                return c;
            }
        }
        return null;
    }

    // any lava at all on the ledge, over it or in the water cell, or a flow in the lava cell. walking to the ledge or
    // pouring water there is walking into it, or cementing a stray where we stand
    public static boolean standBlocked(Cells w, BlockPos p) {
        BlockPos s = stand(p);
        return lava(w.at(s)) || lava(w.at(s.above())) || lava(w.at(water(p))) || w.at(p) == Mat.LAVA_FLOW;
    }

    public static List<BlockPos> sources(Cells w, Collection<BlockPos> footprint) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos c : footprint) {
            if (w.at(c) == Mat.LAVA) {
                out.add(c);
            }
        }
        return out;
    }

    // stray + bucket state -> what to do. never water on a stray: that's obsidian, and nobody on this path promised us a
    // pick that gets through it
    public static Action decide(boolean anySource, boolean standBlocked, boolean emptyBucket, boolean canPourNext, boolean outOfTime) {
        if (!anySource && !standBlocked) {
            return Action.NONE;
        }
        if (outOfTime) {
            return Action.DROP;
        }
        if (!anySource) {
            return Action.WAIT_DRAIN;
        }
        if (emptyBucket) {
            return Action.SCOOP;
        }
        // standBlocked here means a source on the stand or the water cell, or one flowing into the lava cell. pouring
        // into that is the mess we're cleaning
        return canPourNext && !standBlocked ? Action.POUR_FIRST : Action.DROP;
    }

    // the pour this cell does next frees a bucket: lava into an empty cell, or water onto our own lava source
    public static boolean canPourNext(boolean lavaBucket, boolean waterBucket, Mat lavaCell) {
        if (lavaCell == Mat.LAVA) {
            return waterBucket;
        }
        return lavaBucket && lavaCell == Mat.OPEN;
    }

    // ---- where to stand while lava is about

    static boolean lava(Mat m) {
        return m == Mat.LAVA || m == Mat.LAVA_FLOW;
    }

    // floor under us, room for the body, and no lava touching the body cells. a source next to open air spreads into it
    // the moment its turn comes up, so beside lava is not a place to stand
    public static boolean safeStand(Cells w, BlockPos feet) {
        if (w.at(feet.below()) != Mat.SOLID || w.at(feet) != Mat.OPEN || w.at(feet.above()) != Mat.OPEN) {
            return false;
        }
        if (lava(w.at(feet.above(2)))) {
            return false;
        }
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (lava(w.at(feet.relative(d))) || lava(w.at(feet.above().relative(d)))) {
                return false;
            }
        }
        return true;
    }

    // safe cells around `around`, nearest to `from` first. keepOut is cells we never stand in (the cast column)
    public static List<BlockPos> safeStands(Cells w, BlockPos around, int radius, int below, int above, BlockPos from,
                                            Collection<BlockPos> keepOut) {
        List<BlockPos> out = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -below; dy <= above; dy++) {
                    BlockPos c = around.offset(dx, dy, dz);
                    if (!keepOut.contains(c) && safeStand(w, c)) {
                        out.add(c);
                    }
                }
            }
        }
        out.sort((a, b) -> Double.compare(a.distSqr(from), b.distSqr(from)));
        return out;
    }
}
