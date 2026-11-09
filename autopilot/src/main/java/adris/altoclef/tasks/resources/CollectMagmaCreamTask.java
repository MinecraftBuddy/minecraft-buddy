package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasksystem.Task;
import baritone.api.utils.Dimension;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.world.entity.monster.cubemob.MagmaCube;
import net.minecraft.world.item.Items;

public class CollectMagmaCreamTask extends ResourceTask {
    private final int _count;
    private final Task _deltas = new adris.altoclef.tasks.movement.SearchChunkForBlockTask(net.minecraft.world.level.block.Blocks.BASALT);

    // the buddy's fork, Tuning brew_errand (CollectBlazeRodsTask.brewErrand): the brew errand's hunt, a cube at any height the
    // path reaches (the level rule off; the floor, lava, sea, drop and shore rules as they stand), and no walk to the basalt
    // deltas with none in reach (the errand gives up for no cube instead)
    private final boolean _errand;

    public CollectMagmaCreamTask(int count) {
        this(count, false);
    }

    public CollectMagmaCreamTask(int count, boolean errand) {
        super(Items.MAGMA_CREAM, count);
        _count = count;
        _errand = errand;
    }

    // a cube that drops cream (size 2 and up) in the hunt's range, passing the rods task's rules for the cream hunt; errand: at
    // any height, and not one the tracker gave up reaching nor one an errand hunt let go
    static java.util.function.Predicate<net.minecraft.world.entity.Entity> fit(AltoClef mod, boolean errand) {
        var us = mod.getPlayer();
        double range = adris.altoclef.util.helpers.Tuning.get("cream_range", CollectBlazeRodsTask.CREAM_RANGE);
        return e -> e instanceof MagmaCube c && c.isAlive() && c.getSize() > 1 && c.distanceTo(us) <= range
                && CollectBlazeRodsTask.creamCubeWhyNot(mod, c, errand) == null
                && (!errand || mod.getEntityTracker().isEntityReachable(c) && !LET_GO.contains(c.getId()) && wayTo(mod, c));
    }

    // (Tuning route_cube_way, route jar 8, the errand's hunt only: a hunt begins only with a walk from the buddy to a stand of the
    // cube, CubeWay; half of route jar 7's hunts were let go "out of reach (no way to it)". the verdict per cube is kept
    // WAY_TICKS, so the search runs at most once in that per cube)
    static final long WAY_TICKS = 40;
    private static final java.util.Map<Integer, long[]> WAY = new java.util.LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<Integer, long[]> eldest) {
            return size() > 256;
        }
    };

    static boolean wayTo(AltoClef mod, MagmaCube cube) {
        if (!adris.altoclef.util.helpers.CubeWay.on() || mod.getClientBaritone().getPlayerContext() == null) return true;
        long now = mod.getWorld().getGameTime();
        long[] seen = WAY.get(cube.getId());
        if (seen != null && now >= seen[0] && now - seen[0] < WAY_TICKS) return seen[1] != 0;
        var us = mod.getPlayer();
        var cells = baritone.pathing.movement.CliffAvoidance.of(new baritone.utils.BlockStateInterface(mod.getClientBaritone().getPlayerContext(), true));
        net.minecraft.core.BlockPos at = cube.blockPosition();
        boolean ok = adris.altoclef.util.helpers.CubeWay.way(cells, us.getBlockX(), net.minecraft.util.Mth.floor(us.getY() + 0.1251), us.getBlockZ(),
                at.getX(), at.getY(), at.getZ());
        WAY.put(cube.getId(), new long[]{now, ok ? 1 : 0});
        return ok;
    }

    // a cube to hunt in sight, or its cream on the ground within the hunt's range
    static boolean inSight(AltoClef mod, boolean errand) {
        var us = mod.getPlayer();
        double range = adris.altoclef.util.helpers.Tuning.get("cream_range", CollectBlazeRodsTask.CREAM_RANGE);
        return mod.getEntityTracker().getClosestEntity(us.position(), fit(mod, errand), MagmaCube.class).isPresent()
                || mod.getEntityTracker().getClosestItemDrop(us.position(), Items.MAGMA_CREAM).filter(d -> d.distanceTo(us) <= range).isPresent();
    }

    // ---- the held cube (Tuning route_errand, the errand's hunt only; route jar 2). the hunt asked the rules afresh every tick, and
    // a cube that jumps, or a line to it that a step changes, passed one tick and failed the next: the hunt and the fortress
    // search took the tick from each other about once a second for two minutes (34 times each way), the cube close and never
    // reached. a cube once picked is held till it is dead, out of reach (the tracker's ban, or HOLD_SLACK past the hunt's
    // range), by lava on the ground, or hunted RouteRules.CUBE_HOLD_S; then it is let go for good (LET_GO), never picked again
    static final double HOLD_SLACK = 16;
    // the cubes an errand hunt let go, by entity id (a new world's ids start over: bounded, the oldest dropped)
    static final java.util.Set<Integer> LET_GO = java.util.Collections.newSetFromMap(new java.util.LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<Integer, Boolean> eldest) {
            return size() > 256;
        }
    });
    private MagmaCube _held;
    private long _heldAt;

    private static boolean holdOn() {
        return adris.altoclef.util.helpers.RouteRules.errandOn();
    }

    // the cube held, null with none (or with the hold off): one let go now says why, once
    MagmaCube held(AltoClef mod) {
        if (!_errand || !holdOn() || _held == null) return null;
        var us = mod.getPlayer();
        long now = mod.getWorld().getGameTime();
        if (now < _heldAt) _heldAt = now;
        String why = null;
        if (!_held.isAlive() || _held.isRemoved()) {
            _held = null;
            return null;
        }
        double range = adris.altoclef.util.helpers.Tuning.get("cream_range", CollectBlazeRodsTask.CREAM_RANGE);
        double d = _held.distanceTo(us);
        if (!mod.getEntityTracker().isEntityReachable(_held)) why = "out of reach (no way to it)";
        else if (d > range + HOLD_SLACK) why = String.format(java.util.Locale.ROOT, "out of reach (%.0f blocks off)", d);
        else if (now - _heldAt > adris.altoclef.util.helpers.RouteRules.CUBE_HOLD_S * 20) why = "not killed in " + (int) adris.altoclef.util.helpers.RouteRules.CUBE_HOLD_S + " s";
        else if (_held.onGround()) {
            String no = CollectBlazeRodsTask.creamCubeWhyNot(mod, _held, true);
            if ("lava".equals(no) || "sea".equals(no)) why = "by " + no;
        }
        // (Tuning route_cube_alone, route jar 7: let go with the buddy's health down or a crowd by it, RouteRules.cubeLetGo)
        double alone = adris.altoclef.util.helpers.RouteRules.cubeAlone();
        if (why == null && alone > 0) {
            why = adris.altoclef.util.helpers.RouteRules.cubeLetGo(CollectBlazeRodsTask.cubesBy(mod, _held, alone), us.getHealth());
        }
        if (why == null) return _held;
        LET_GO.add(_held.getId());
        adris.altoclef.Debug.logInternal(String.format(java.util.Locale.ROOT, "cream: the cube at %s let go, %s, after %.0f s",
                _held.blockPosition().toShortString(), why, (now - _heldAt) / 20.0));
        _held = null;
        return null;
    }

    // a cube held by this hunt, still to be killed
    boolean holding(AltoClef mod) {
        return held(mod) != null;
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {

    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        /*
         * If in nether:
         *      If magma cube located, hunt magma cube.
         *      If not enough potential blaze powder, hunt blazes.
         * If in overworld:
         *      If not enough potential slimes, hunt slimes
         *      Otherwise, go to nether.
         * If in end:
         *      Go to overworld lol
         */
        int currentCream = mod.getItemStorage().getItemCount(Items.MAGMA_CREAM);
        int neededCream = _count - currentCream;
        switch (WorldHelper.getCurrentDimension()) {
            case NETHER -> {
                // (the buddy's fork: a cube that drops cream (size 2 and up), in the hunt's range and passing the rods task's
                // rules for the cream hunt (level, floor, lava, the sea, a drop), never any cube anywhere: that chased cubes into
                // the lava. none about: the basalt deltas, where cubes live, rather than blaze powder, which went through the rods
                // task's brew back to the cream, round and round (steering 58, piece 1, 6 Nov 2026))
                var us = mod.getPlayer();
                double range = adris.altoclef.util.helpers.Tuning.get("cream_range", CollectBlazeRodsTask.CREAM_RANGE);
                java.util.function.Predicate<net.minecraft.world.entity.Entity> fit = fit(mod, _errand);
                // (Tuning route_errand: the cube held is the one hunted, picked once and kept, see held)
                if (_errand && holdOn()) {
                    MagmaCube held = held(mod);
                    if (held == null) {
                        held = (MagmaCube) mod.getEntityTracker().getClosestEntity(us.position(), fit, MagmaCube.class).orElse(null);
                        if (held != null) {
                            _held = held;
                            _heldAt = mod.getWorld().getGameTime();
                            adris.altoclef.Debug.logInternal(String.format(java.util.Locale.ROOT, "cream: hunting the cube at %s, %.0f blocks off, %+d in height",
                                    held.blockPosition().toShortString(), held.distanceTo(us), (int) Math.round(held.getY() - us.getY())));
                        }
                    }
                    if (held != null) {
                        MagmaCube target = held;
                        setDebugState("Killing the magma cube held");
                        // (Tuning route_cube_id: a kill task of the cube's own id, so the runner never keeps the last cube's
                        // task, whose rule matched only that cube, for the next one, RouteRules.cubeByIdOn)
                        if (adris.altoclef.util.helpers.RouteRules.cubeByIdOn()) {
                            return new KillAndLootTask(target, new ItemTarget(Items.MAGMA_CREAM, neededCream));
                        }
                        return new KillAndLootTask(MagmaCube.class, e -> e == target && e.isAlive(), new ItemTarget(Items.MAGMA_CREAM, neededCream));
                    }
                } else if (mod.getEntityTracker().getClosestEntity(us.position(), fit, MagmaCube.class).isPresent()) {
                    setDebugState("Killing Magma cube");
                    return new KillAndLootTask(MagmaCube.class, fit, new ItemTarget(Items.MAGMA_CREAM, neededCream));
                }
                if (mod.getEntityTracker().getClosestItemDrop(us.position(), Items.MAGMA_CREAM).filter(d -> d.distanceTo(us) <= range).isPresent()) {
                    setDebugState("Picking up the magma cream");
                    return new adris.altoclef.tasks.movement.PickupDroppedItemTask(new ItemTarget(Items.MAGMA_CREAM, neededCream), true);
                }
                if (_errand) {
                    setDebugState("No magma cube in reach");
                    return null;
                }
                setDebugState("Looking for magma cubes: the basalt deltas");
                return _deltas;
            }
            case OVERWORLD -> {
                // (the buddy's fork: straight to the nether, its cubes; no slime hunt first, which swam an ocean among drowned for
                // a quarter of an hour, m4cream-1 run 3. slime balls in the bag are no use without blaze powder anyway)
                setDebugState("Going to the nether for magma cubes");
                return new DefaultGoToDimensionTask(Dimension.NETHER);
            }
            case END -> {
                setDebugState("Going to overworld, no magma cream materials exist here.");
                return new DefaultGoToDimensionTask(Dimension.OVERWORLD);
            }
        }

        setDebugState("INVALID DIMENSION??: " + WorldHelper.getCurrentDimension());
        return null;
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        return other instanceof CollectMagmaCreamTask t && t._errand == _errand;
    }

    @Override
    protected String toDebugStringName() {
        return "Collecting " + _count + " Magma cream.";
    }
}
