package adris.altoclef.tasks.misc;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.GarbageRules;
import adris.altoclef.util.helpers.WorldHelper;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;

// the buddy's fork (Tuning bag_keep_stations, a fix of 13 Nov 2026, on): a furnace, smoker or crafting table that a smelt or a
// craft put down itself goes back in the bag once that smelt or craft is done, as the branch mine does with its table and the
// gold batch with its furnace. plain alto leaves every station where it stood: in M3.3's smoke 5 the shield's iron smelt left
// its furnace at the surface, the bag had none for the next gold batch and no wood for a table to make one. the smelt and craft
// tasks report the station when they stop done (left); the buddy's wrapper round each run (SleepThroughNightsTask) asks for the
// way back before its task's next tick (task). only from NEAR blocks and for KEEP_TICKS after the report, and only a station
// still standing, so one the task walked far from or broke itself is no errand
public final class StationsBack {

    // a game half minute, as the gold batch's furnace (CollectGoldIngotTask)
    public static final long KEEP_TICKS = 600;
    public static final double NEAR = 12;

    private record Left(BlockPos pos, Item item, String dimension, long tick) {
    }

    private static final List<Left> LEFT = new ArrayList<>();
    // the one being taken back, and how many of its kind the bag held when that began
    private static Left current;
    private static int had;

    private StationsBack() {
    }

    // whether a station reported this long ago, this far off, is still worth taking back
    static boolean due(long leftTick, long now, double distance) {
        return now >= leftTick && now - leftTick <= KEEP_TICKS && distance <= NEAR;
    }

    // a smelt or a craft stopped done and its own station stands at pos (null: none of its own, nothing to do)
    public static synchronized void left(AltoClef mod, BlockPos pos) {
        if (pos == null || mod.getWorld() == null || !GarbageRules.keepStationsOn()) {
            return;
        }
        Item item = mod.getWorld().getBlockState(pos).getBlock().asItem();
        if (!GarbageRules.station(item)) {
            return;
        }
        BlockPos at = pos.immutable();
        LEFT.removeIf(l -> l.pos().equals(at));
        LEFT.add(new Left(at, item, WorldHelper.getCurrentDimension().name(), mod.getWorld().getGameTime()));
    }

    // the way back for a station left near, or null. the bag counts its kind up by one: done
    public static synchronized Task task(AltoClef mod) {
        if (LEFT.isEmpty() && current == null) {
            return null;
        }
        if (!GarbageRules.keepStationsOn() || mod.getPlayer() == null || mod.getWorld() == null) {
            LEFT.clear();
            current = null;
            return null;
        }
        long now = mod.getWorld().getGameTime();
        if (current != null) {
            int count = mod.getItemStorage().getItemCount(current.item());
            if (count > had || now < current.tick() || now - current.tick() > KEEP_TICKS) {
                Debug.logInternal(count > had ? "stations back: the " + name(current.item()) + " is in the bag again"
                        : "stations back: the " + name(current.item()) + " at " + current.pos().toShortString() + " not taken back in time");
                current = null;
                return null;
            }
            if (stands(mod, current)) {
                return new DestroyBlockTask(current.pos());
            }
            return new PickupDroppedItemTask(current.item(), had + 1);
        }
        String dimension = WorldHelper.getCurrentDimension().name();
        Left best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (var it = LEFT.iterator(); it.hasNext(); ) {
            Left l = it.next();
            double d = Math.sqrt(l.pos().distToCenterSqr(mod.getPlayer().position()));
            if (!l.dimension().equals(dimension) || !due(l.tick(), now, Double.NEGATIVE_INFINITY) || !stands(mod, l)) {
                it.remove();
                continue;
            }
            if (d <= NEAR && d < bestDistance) {
                best = l;
                bestDistance = d;
            }
        }
        if (best == null) {
            return null;
        }
        LEFT.remove(best);
        current = best;
        had = mod.getItemStorage().getItemCount(best.item());
        Debug.logInternal("stations back: the " + name(best.item()) + " a smelt or craft put down at " + best.pos().toShortString()
                + " goes back in the bag (bag_keep_stations)");
        return new DestroyBlockTask(best.pos());
    }

    private static boolean stands(AltoClef mod, Left l) {
        return l.item() instanceof BlockItem b && mod.getWorld().getBlockState(l.pos()).is(b.getBlock());
    }

    private static String name(Item item) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).getPath().replace('_', ' ');
    }
}
