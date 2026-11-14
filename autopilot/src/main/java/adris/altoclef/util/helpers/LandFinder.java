package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

// the buddy's fork: where dry land is, seen from out on the water. a fresh world dropped it in the sea, and the food task's
// wander swam it about for three game days with one chicken to show for it, kelp it had nothing to dry with and no wood
// for a boat (the runner, 2 Nov 2026). out there everything it needs is on land, so land comes first
public final class LandFinder {

    // how far the ray for the sea floor looks, and how far along the way the floor rises the goal is put
    public static final int FLOOR_REACH = 160;
    public static final int SHORE_STEP = 64;

    private LandFinder() {
    }

    // half a game minute out on open water before it steers: crossing a lake or a river is the explore's own business, and
    // turning back to the bank it came from at every crossing would be a loop
    public static final long WET_BEFORE_LAND = 600;

    // one per task that wanders: after WET_BEFORE_LAND in open water, the task to land (or null), worked out again every
    // five game seconds, until it is out of the water
    // the game times the last few steers began (a swim out turned back to land), for whoever wants to know that the water keeps
    // being the way: a boat then, so that the explore can cross it (the buddy's Alto.wantsBoat). the food task went into the sea
    // 98 times on one coast and found no food in 74 game minutes (the runner, 3 Nov 2026, attempt 97)
    private static final long[] STEERS = new long[4];
    private static int _steerAt;

    // the steers kept (RunMemory): a fresh run starts with none
    public static synchronized int steersKept() {
        int n = 0;
        for (long t : STEERS) if (t != 0) n++;
        return n;
    }

    public static synchronized void forgetSteers() {
        java.util.Arrays.fill(STEERS, 0);
        _steerAt = 0;
    }

    private static synchronized void noteSteer(long now) {
        STEERS[_steerAt++ % STEERS.length] = now;
    }

    // how many steers began at or after this game time (of the last four)
    public static synchronized int steersSince(long gameTime) {
        int n = 0;
        for (int i = 0; i < Math.min(_steerAt, STEERS.length); i++) if (STEERS[i] >= gameTime) n++;
        return n;
    }

    public static final class Steer {
        private boolean _counted;
        private BlockPos _land;
        private boolean _shore;
        private long _look = Long.MIN_VALUE;
        private long _wetSince = -1;
        private String _what = "";

        public adris.altoclef.tasksystem.Task next(AltoClef mod) {
            long now = mod.getWorld().getGameTime();
            if (!inOpenWater(mod)) {
                // brought ashore: the way inland is worked out once, for inland() below
                if (_land != null && _shore) {
                    _ashoreAt = now;
                    _inland = inlandPoint(mod, mod.getPlayer().blockPosition());
                }
                _land = null;
                _wetSince = -1;
                _counted = false;
                return null;
            }
            if (_wetSince < 0 || now < _wetSince) _wetSince = now;
            if (now - _wetSince < WET_BEFORE_LAND) return null;
            if (!_counted) {
                _counted = true;
                noteSteer(now);
            }
            BlockPos feet = mod.getPlayer().blockPosition();
            // a shore once found is kept while it is still dry land: worked out anew every five seconds it moved by a block or
            // two each time and the path was asked for again every second at five times the speed (the runner, 2 Nov 2026).
            // the way the sea floor rises is looked at again every five seconds, and a kept shore once a minute
            boolean keep = _land != null && _shore && now >= _look && now - _look < 1200 && isLand(mod, _land);
            if (!keep && (_land == null || _shore || now - _look >= 100 || now < _look)) {
                _look = now;
                _land = nearestLand(mod, feet, 128);
                _shore = _land != null;
                if (_land == null) _land = shoreward(mod, feet);
            }
            if (_land == null) return null;
            if (_shore) {
                _what = "Open water: to dry land at " + _land.toShortString();
                return new adris.altoclef.tasks.movement.GetToBlockTask(_land);
            }
            _what = "Open water, no land in sight: the way the sea floor rises, to " + _land.getX() + " " + _land.getZ();
            return new adris.altoclef.tasks.movement.GetToXZTask(_land.getX(), _land.getZ(), 4);
        }

        // for the debug state of whoever asked
        public String what() {
            return _what;
        }

        private long _ashoreAt = Long.MIN_VALUE;
        private BlockPos _inland;

        // just brought ashore: a walk inland first, to where the land is widest, for INLAND_FOR at most, or null. the explore
        // set off over the sea again each time it was brought back, and it went to and fro on one beach for three game days
        // without finding food (the practice runner, 2 Nov 2026, attempt 69)
        public adris.altoclef.tasksystem.Task inland(AltoClef mod) {
            long now = mod.getWorld().getGameTime();
            if (_inland == null || now < _ashoreAt || now - _ashoreAt > INLAND_FOR) return null;
            BlockPos feet = mod.getPlayer().blockPosition();
            if (Math.abs(feet.getX() - _inland.getX()) <= 4 && Math.abs(feet.getZ() - _inland.getZ()) <= 4) {
                _inland = null;
                return null;
            }
            _what = "Ashore: inland first, to " + _inland.getX() + " " + _inland.getZ();
            return new adris.altoclef.tasks.movement.GetToXZTask(_inland.getX(), _inland.getZ(), 4);
        }

        // asked by whoever explores afterwards: it came ashore and walked inland in the last INLAND_FOR, so it starts again from
        // where it is now and not from the old origin by the sea
        public boolean cameInland(AltoClef mod) {
            long now = mod.getWorld().getGameTime();
            return _ashoreAt != Long.MIN_VALUE && now >= _ashoreAt && now - _ashoreAt <= INLAND_FOR + 200;
        }
    }

    // how long a walk inland after coming ashore may take, two game minutes
    public static final long INLAND_FOR = 2400;

    // the point INLAND_REACH out along the one of sixteen rays with the most dry land on it (the top block, leaves left out,
    // solid and no fluid, every eight blocks out to FLOOR_REACH in what is loaded), or null with nothing loaded to go by
    public static final int INLAND_REACH = 96;

    public static BlockPos inlandPoint(AltoClef mod, BlockPos feet) {
        Level level = mod.getWorld();
        int best = -1;
        double bx = 0, bz = 0;
        for (int i = 0; i < 16; i++) {
            double a = i * Math.PI / 8, dx = Math.cos(a), dz = Math.sin(a);
            int dry = 0;
            for (int d = 8; d <= FLOOR_REACH; d += 8) {
                int x = (int) Math.floor(feet.getX() + 0.5 + dx * d), z = (int) Math.floor(feet.getZ() + 0.5 + dz * d);
                if (!level.hasChunk(x >> 4, z >> 4)) break;
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockState ground = level.getBlockState(new BlockPos(x, top - 1, z));
                if (ground.getFluidState().isEmpty() && ground.isSolid()) dry++;
            }
            if (dry > best) {
                best = dry;
                bx = dx;
                bz = dz;
            }
        }
        if (best <= 0) return null;
        return new BlockPos((int) Math.floor(feet.getX() + 0.5 + bx * INLAND_REACH), feet.getY(), (int) Math.floor(feet.getZ() + 0.5 + bz * INLAND_REACH));
    }

    // swimming at the surface with water two deep under the feet and the sky above: the sea or a lake, not a puddle by the
    // shore, a river crossing or a flooded cave
    public static boolean inOpenWater(AltoClef mod) {
        LocalPlayer p = mod.getPlayer();
        if (p == null || !p.isInWater()) return false;
        Level level = mod.getWorld();
        BlockPos feet = p.blockPosition();
        if (!level.canSeeSky(feet.above())) return false;
        return level.getFluidState(feet.below()).is(FluidTags.WATER) && level.getFluidState(feet.below(2)).is(FluidTags.WATER);
    }

    // still a place to stand on dry land: the block under it solid and no fluid
    static boolean isLand(AltoClef mod, BlockPos at) {
        Level level = mod.getWorld();
        if (!level.hasChunk(at.getX() >> 4, at.getZ() >> 4)) return false;
        BlockState ground = level.getBlockState(at.below());
        return ground.getFluidState().isEmpty() && ground.isSolid() && level.getFluidState(at).isEmpty();
    }

    // the nearest loaded column whose top (leaves left out) is a solid block and no fluid, within reach, or null. rings of
    // two blocks near by, of four further out
    public static BlockPos nearestLand(AltoClef mod, BlockPos feet, int reach) {
        return nearestLand(mod, feet, reach, false);
    }

    // the ice kinds baritone does not break (MovementHelper.avoidBreaking: ice always, packed ice by water): a branch mine's
    // staircase down through them stood walled in
    public static boolean ice(BlockState state) {
        return state.is(net.minecraft.world.level.block.Blocks.ICE) || state.is(net.minecraft.world.level.block.Blocks.PACKED_ICE)
                || state.is(net.minecraft.world.level.block.Blocks.BLUE_ICE) || state.is(net.minecraft.world.level.block.Blocks.FROSTED_ICE);
    }

    // how deep under a top (or the feet) ice or water makes the place no land to dig in: an iceberg's top is snow over packed ice
    public static final int ICE_DEPTH = 5;

    // ice or water in the depth blocks under pos (pos.below() down)
    public static boolean iceUnder(Level level, BlockPos pos, int depth) {
        for (int i = 1; i <= depth; i++) {
            BlockState b = level.getBlockState(pos.below(i));
            if (ice(b) || !b.getFluidState().isEmpty()) return true;
        }
        return false;
    }

    // noIce: land with no ice and no water in the ICE_DEPTH blocks under its top either (an iceberg or a frozen sea is no land
    // to dig in: the top of an iceberg counted as land, and the staircase from it stepped down onto the ice again, the walk to
    // land and the next step taking turns twice a second for an hour, 10 Nov 2026)
    public static BlockPos nearestLand(AltoClef mod, BlockPos feet, int reach, boolean noIce) {
        return nearestLand(mod, feet, reach, noIce, null);
    }

    // (the buddy's fork, Tuning mine_leave_dry) the nearest land that `prefer` likes, else the nearest of any (MineLeave.landPick);
    // prefer null: the nearest
    public static BlockPos nearestLand(AltoClef mod, BlockPos feet, int reach, boolean noIce, java.util.function.Predicate<BlockPos> prefer) {
        BlockPos any = null;
        Level level = mod.getWorld();
        for (int r = 2; r <= reach; r += r < 64 ? 2 : 4) {
            int s = r < 64 ? 2 : 4;
            for (int dx = -r; dx <= r; dx += s) {
                for (int dz = -r; dz <= r; dz += s) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int x = feet.getX() + dx, z = feet.getZ() + dz;
                    if (!level.hasChunk(x >> 4, z >> 4)) continue;
                    int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    BlockState ground = level.getBlockState(new BlockPos(x, top - 1, z));
                    if (ground.getFluidState().isEmpty() && ground.isSolid() && !(noIce && (ice(ground) || iceUnder(level, new BlockPos(x, top, z), ICE_DEPTH)))) {
                        BlockPos land = new BlockPos(x, top, z);
                        if (prefer == null || prefer.test(land)) return land;
                        if (any == null) any = land;
                    }
                }
            }
        }
        return MineLeave.landPick(null, any);
    }

    // no land loaded: the way the sea floor rises, a point SHORE_STEP blocks that way at the surface, or null with nothing
    // loaded to go by. the floor climbs to every shore, so the highest floor seen along sixteen rays is the way to one
    public static BlockPos shoreward(AltoClef mod, BlockPos feet) {
        Level level = mod.getWorld();
        int bestFloor = Integer.MIN_VALUE;
        double bx = 0, bz = 0;
        for (int i = 0; i < 16; i++) {
            double a = i * Math.PI / 8, dx = Math.cos(a), dz = Math.sin(a);
            int floor = Integer.MIN_VALUE;
            for (int d = 16; d <= FLOOR_REACH; d += 8) {
                int x = (int) Math.floor(feet.getX() + 0.5 + dx * d), z = (int) Math.floor(feet.getZ() + 0.5 + dz * d);
                if (!level.hasChunk(x >> 4, z >> 4)) break;
                floor = Math.max(floor, level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z));
            }
            if (floor > bestFloor) {
                bestFloor = floor;
                bx = dx;
                bz = dz;
            }
        }
        if (bestFloor == Integer.MIN_VALUE) return null;
        return new BlockPos((int) Math.floor(feet.getX() + 0.5 + bx * SHORE_STEP), level.getSeaLevel(), (int) Math.floor(feet.getZ() + 0.5 + bz * SHORE_STEP));
    }
}
