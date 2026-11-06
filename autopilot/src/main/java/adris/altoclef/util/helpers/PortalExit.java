package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.OptionalInt;

// the walk out of a nether portal that six tasks used to copy: hold sneak + forward in whatever direction we happen to face. facing
// the obsidian frame (or the block we were digging) that goes nowhere for as long as the portal takes to teleport us. this one
// picks a cell next to us that is actually free, turns to it, and walks. no sneak: sneaking refuses to step off an edge, and a
// one block drop next to a frame is exactly the edge we might be leaving by
public final class PortalExit {
    public enum Cell {
        PORTAL,
        // free to stand in (and to walk through)
        AIR,
        SOLID,
        // lava, fire, magma, cactus, and shapes that are no floor (open trapdoors): not in it, not on it
        HAZARD
    }

    public interface Terrain {
        Cell at(int x, int y, int z);
    }

    // south, west, north, east = yaw 0, 90, 180, 270
    private static final int[] DX = {0, -1, 0, 1};
    private static final int[] DZ = {1, 0, -1, 0};

    private PortalExit() {
    }

    public static float yawOf(int dir) {
        return dir * 90f;
    }

    // which of the four sides to leave by, the one nearest where we already look wins so the turn is short. a step down of one is
    // fine (we do not sneak), a step up is not (nothing here jumps)
    public static OptionalInt pick(Terrain t, int x, int y, int z, float yaw) {
        int best = -1;
        float bestTurn = Float.MAX_VALUE;
        for (int d = 0; d < 4; d++) {
            int nx = x + DX[d];
            int nz = z + DZ[d];
            if (!standable(t, nx, y, nz)) {
                continue;
            }
            float turn = Math.abs(wrap(yaw - yawOf(d)));
            if (turn < bestTurn) {
                bestTurn = turn;
                best = d;
            }
        }
        return best < 0 ? OptionalInt.empty() : OptionalInt.of(best);
    }

    private static boolean standable(Terrain t, int x, int y, int z) {
        if (t.at(x, y, z) != Cell.AIR || t.at(x, y + 1, z) != Cell.AIR) {
            return false;
        }
        Cell floor = t.at(x, y - 1, z);
        if (floor == Cell.SOLID) {
            return true;
        }
        // one block down is fine, the shaft above it was checked with the feet and head cells
        return floor == Cell.AIR && t.at(x, y - 2, z) == Cell.SOLID;
    }

    private static float wrap(float deg) {
        float d = deg % 360f;
        if (d > 180f) {
            d -= 360f;
        } else if (d < -180f) {
            d += 360f;
        }
        return d;
    }

    // the exit's own claim on the keys, so it can let go of them by itself (see letGoOutside)
    private static final Object OWNER = new Object();

    // holds the inputs for one tick. falls back to the old sneak + forward when every side is blocked (that is a portal in a
    // cave pocket, the task's own stuck handling takes it from there)
    public static void step(AltoClef mod) {
        BlockPos feet = mod.getPlayer().blockPosition();
        OptionalInt dir = pick(world(mod), feet.getX(), feet.getY(), feet.getZ(), mod.getPlayer().getYRot());
        // the caller may have been backing up into the portal, back and forward held together is a treadmill
        mod.getInputControls().release(Input.MOVE_BACK);
        if (dir.isEmpty()) {
            mod.getInputControls().hold(Input.SNEAK, OWNER);
            mod.getInputControls().hold(Input.MOVE_FORWARD, OWNER);
            return;
        }
        mod.getClientBaritone().getLookBehavior().updateTarget(new Rotation(yawOf(dir.getAsInt()), 0), true);
        mod.getInputControls().release(Input.SNEAK);
        mod.getInputControls().hold(Input.MOVE_FORWARD, OWNER);
    }

    // every tick: out of the portal, the exit's keys go up, path or no path. the callers let go only once a path runs, and
    // with a far path still being searched the held forward walked the buddy three blocks on from its portal, off the
    // nylium and eight blocks down into the lava sea (attempt 169)
    public static void letGoOutside(AltoClef mod) {
        if (mod.getPlayer() != null && !WorldHelper.isStandingInPortal(mod)) {
            mod.getInputControls().releaseOwnedBy(OWNER);
        }
    }

    private static Terrain world(AltoClef mod) {
        return (x, y, z) -> classify(mod, new BlockPos(x, y, z));
    }

    private static Cell classify(AltoClef mod, BlockPos pos) {
        BlockState s = mod.getWorld().getBlockState(pos);
        if (s.is(Blocks.NETHER_PORTAL)) {
            return Cell.PORTAL;
        }
        if (s.is(BlockTags.FIRE) || s.getFluidState().is(FluidTags.LAVA) || s.is(Blocks.MAGMA_BLOCK) || s.is(Blocks.CACTUS)) {
            return Cell.HAZARD;
        }
        if (s.getCollisionShape(mod.getWorld(), pos).isEmpty()) {
            return Cell.AIR;
        }
        // an open trapdoor or a lone fence post has a shape and still is no floor. HAZARD = "not a floor, not air" to the pick
        return s.isFaceSturdy(mod.getWorld(), pos, Direction.UP) ? Cell.SOLID : Cell.HAZARD;
    }
}
