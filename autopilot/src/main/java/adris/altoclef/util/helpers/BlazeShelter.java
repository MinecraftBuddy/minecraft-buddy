package adris.altoclef.util.helpers;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

// the buddy's fork: the blaze fight's corner, for the mob defense's run as well (CombatBrain). the rods task's own run went into
// the corner, but a run of the mob defense ("Running 24 blocks from ...") ran from it along the bridge and died there
// (corner17 run 3, 4 Nov 2026). set by BlazeFight each tick its corner holds; read only while fresh, so no one has to clear it
public final class BlazeShelter {
    // a corner this far off is the run's goal; past it the plain run
    public static final double RANGE = 12;
    // set this many ticks ago at most, or it is stale (the fight is over). long: while the mob defense has the wheel the rods
    // task does not tick and sets nothing, and at 40 ticks its run had forgotten the corner and ran 24 blocks from it (corner18
    // run 1); within RANGE of a corner only, so a stale one far off does nothing
    static final long FRESH_TICKS = 2400;

    private static volatile BlockPos corner;
    // a look out of the corner under way (BlazeLureTask), and the last tick the fight was hitting a blaze: neither is a way back
    public static volatile boolean lookingOut;
    public static volatile long killAt = Long.MIN_VALUE / 2;
    private static volatile HideStats hide = new HideStats();

    // a new order's counts start from nothing (a rods task's start)
    public static void resetHide() {
        hide = new HideStats();
    }

    // every tick (AltoClef): with a fresh corner within RANGE, the hiding numbers (HideStats)
    public static void tickHide(adris.altoclef.AltoClef mod) {
        if (mod.getPlayer() == null || mod.getWorld() == null) return;
        long now = mod.getWorld().getGameTime();
        BlockPos c = fresh(now);
        if (c == null || mod.getPlayer().position().distanceTo(Vec3.atBottomCenterOf(c)) > RANGE) return;
        hide.tick(mod.getPlayer(), c, now, !lookingOut && (now - killAt > 20 || now < killAt), lookingOut);
    }
    private static volatile long at = Long.MIN_VALUE / 2;

    private BlazeShelter() {
    }

    public static void set(BlockPos c, long now) {
        corner = c;
        at = now;
    }

    // the standing pillbox that is the fight's corner: its cell, its door (BlazePillbox.door, the cell outside the look out's
    // open side; null for a raised box) and the cells it holds. set by the rods task every tick it runs, null with no box
    // standing; read for a corner equal to its cell only, so a box left behind changes no other corner's walk (BoxWay)
    private record Box(BlockPos cell, BlockPos door, java.util.function.Predicate<BlockPos> holds) {
    }

    private static volatile Box box;

    public static void box(BlockPos cell, BlockPos door, java.util.function.Predicate<BlockPos> holds) {
        box = cell == null ? null : new Box(cell, door, holds);
    }

    // the door of the box whose cell is this corner, null for any other corner
    public static BlockPos door(BlockPos corner) {
        Box b = box;
        return b != null && b.cell().equals(corner) ? b.door() : null;
    }

    // the feet in the box whose cell is this corner (its cell, front cell or look out)
    public static boolean inBox(BlockPos corner, BlockPos feet) {
        Box b = box;
        return b != null && b.cell().equals(corner) && b.holds() != null && b.holds().test(feet);
    }

    // the corner while fresh, else null (a clock that went backwards, a new world, is stale too)
    public static BlockPos fresh(long now) {
        BlockPos c = corner;
        return c != null && now >= at && now - at <= FRESH_TICKS ? c : null;
    }

    // the nearest fire within FIRE_REACH of us, at our feet or head, that lies on the way to the corner (nearer to it than we
    // are): the pathfinder never walks through fire, and a fireball's fire in the door left the run to the corner without a
    // path, standing in the blazes' fire (corner17 run 4). null with none
    static final int FIRE_REACH = 4;

    public static BlockPos fireAhead(net.minecraft.world.level.Level level, Vec3 from, BlockPos to, java.util.Set<BlockPos> skip) {
        BlockPos feet = BlockPos.containing(from);
        double ours = from.distanceTo(Vec3.atBottomCenterOf(to));
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -FIRE_REACH; dx <= FIRE_REACH; dx++) {
            for (int dz = -FIRE_REACH; dz <= FIRE_REACH; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    BlockPos p = feet.offset(dx, dy, dz);
                    if (skip.contains(p) || !(level.getBlockState(p).getBlock() instanceof net.minecraft.world.level.block.BaseFireBlock)) continue;
                    if (Vec3.atBottomCenterOf(p).distanceTo(Vec3.atBottomCenterOf(to)) >= ours) continue;
                    double d = from.distanceTo(Vec3.atCenterOf(p));
                    if (d < bestD) {
                        bestD = d;
                        best = p;
                    }
                }
            }
        }
        return best;
    }

    // the volley clock (steering 20): when each blaze about turned charged (Blaze.isOnFire is its charge, the client sees it; a
    // charged blaze shoots the moment it sees us once 60 ticks have passed), by its id, in game ticks. ticked by AltoClef every
    // tick, read by the looks (BlazeLureTask) and the pillbox's building (BlazePillboxTask)
    private static final java.util.Map<Integer, Long> chargedSince = new java.util.HashMap<>();

    public static synchronized void tickClock(adris.altoclef.AltoClef mod) {
        if (mod.getPlayer() == null || mod.getWorld() == null) return;
        long now = mod.getWorld().getGameTime();
        java.util.Set<Integer> live = new java.util.HashSet<>();
        try {
            for (net.minecraft.world.entity.monster.Blaze b : mod.getEntityTracker().getTrackedEntities(net.minecraft.world.entity.monster.Blaze.class)) {
                if (!b.isAlive()) continue;
                live.add(b.getId());
                if (b.isOnFire()) chargedSince.putIfAbsent(b.getId(), now);
                else chargedSince.remove(b.getId());
            }
        } catch (java.util.ConcurrentModificationException ignored) {
            return;
        }
        chargedSince.keySet().retainAll(live);
    }

    // the longest a blaze has been charged that sees the cell (its head points), 0 with none
    public static synchronized long loadedOn(adris.altoclef.AltoClef mod, BlockPos cell, long now) {
        long most = 0;
        try {
            for (net.minecraft.world.entity.monster.Blaze b : mod.getEntityTracker().getTrackedEntities(net.minecraft.world.entity.monster.Blaze.class)) {
                Long since = chargedSince.get(b.getId());
                if (since == null || !b.isAlive() || b.distanceTo(mod.getPlayer()) > BlazeCover.SIGHT) continue;
                if (BlazeCover.hidden(mod.getWorld(), java.util.List.of(b.getEyePosition()), cell.getX(), cell.getY(), cell.getZ(), mod.getPlayer())) continue;
                most = Math.max(most, now - since);
            }
        } catch (java.util.ConcurrentModificationException ignored) {
            return Long.MAX_VALUE;
        }
        return most;
    }

    // a pillbox going up, the last tick it was (BlazePillboxTask, set by the rods task): no shield meanwhile. a raised shield
    // holds the use key, and the builder could not get its place click in ("couldn't get a place click in for 2.0s") for five
    // blocks of six, corner23d; the build takes seconds
    private static volatile long buildingAt = Long.MIN_VALUE / 2;

    public static void building(long now) {
        buildingAt = now;
    }

    // a pillbox stands and is the fight's corner, the last tick it was (set by the rods task): CombatCommit lets go of a foe
    // that only stands by its wall
    private static volatile long boxStandsAt = Long.MIN_VALUE / 2;

    public static void boxStands(long now) {
        boxStandsAt = now;
    }

    public static boolean boxStands(long now, long within) {
        return now >= boxStandsAt && now - boxStandsAt <= within;
    }

    public static boolean building(adris.altoclef.AltoClef mod) {
        if (mod.getWorld() == null) return false;
        long now = mod.getWorld().getGameTime();
        return now >= buildingAt && now - buildingAt <= 5;
    }

    // the brew box holds (BrewBoxTask.holds, Tuning brew_box), the last tick it did (set by the rods task): the mob defense stands
    // down meanwhile. fresh for a few ticks only, so a mob inside, fire in a cell or the box broken (the rods task stops setting
    // it) gives the fight back at once
    static final long BREW_BOX_FRESH = 5;
    private static volatile long brewBoxAt = Long.MIN_VALUE / 2;

    public static void brewBox(long now) {
        brewBoxAt = now;
    }

    public static boolean brewBoxHolds(long now) {
        return now >= brewBoxAt && now - brewBoxAt <= BREW_BOX_FRESH;
    }

    // standing in a cell: within 0.8 of its middle across (BlazeLureTask.in)
    public static boolean in(Vec3 at, BlockPos cell) {
        double dx = at.x - (cell.getX() + 0.5), dz = at.z - (cell.getZ() + 0.5);
        return dx * dx + dz * dz <= 0.64 && Math.abs(at.y - cell.getY()) < 1;
    }
}
