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
import net.minecraft.world.item.Items;

// the buddy's fork (Tuning bag_keep_stations, a fix of 13 Nov 2026, on): a furnace, smoker or crafting table that a smelt or a
// craft put down itself goes back in the bag once that smelt or craft is done, as the branch mine does with its table and the
// gold batch with its furnace. plain alto leaves every station where it stood: in M3.3's smoke 5 the shield's iron smelt left
// its furnace at the surface, the bag had none for the next gold batch and no wood for a table to make one. the smelt and craft
// tasks report the station when they stop done (left); the buddy's wrapper round each run (SleepThroughNightsTask) asks for the
// way back before its task's next tick (task). only from NEAR blocks and for KEEP_TICKS after the report, and only a station
// still standing, so one the task walked far from or broke itself is no errand
// (Tuning table_back_on_cut, a fix of 14 Nov 2026, on) a crafting table whose craft was cut, by a fight or a parent that moved
// on, is reported too (cut): while the cut craft can still come back (a chain holds it, isActive) the table waits for it; once
// that craft is stopped the table goes back in the bag like a done one, from CUT_NEAR blocks and for CUT_KEEP_TICKS, and only
// while the bag holds no table. M3.3's last planks became a table for the spare golden helmet, a fight cut the craft, and the
// table stayed in the world: no table, no planks, no logs from then on (test heel, look 89)
public final class StationsBack {

    // a game half minute, as the gold batch's furnace (CollectGoldIngotTask)
    public static final long KEEP_TICKS = 600;
    public static final double NEAR = 12;
    // (table_back_on_cut) a fight may take the buddy off and keep it a while: five game minutes, and a walk of 32 blocks back
    public static final long CUT_KEEP_TICKS = 6000;
    public static final double CUT_NEAR = 32;

    // owner: the craft that was cut (null for a done one), cut: reported by a cut craft
    private record Left(BlockPos pos, Item item, String dimension, long tick, Task owner, boolean cut) {
    }

    private static final List<Left> LEFT = new ArrayList<>();
    // the one being taken back, how many of its kind the bag held when that began, and when it began
    private static Left current;
    private static int had;
    private static long since;

    private StationsBack() {
    }

    // whether a station reported this long ago, this far off, is still worth taking back
    static boolean due(long leftTick, long now, double distance) {
        return now >= leftTick && now - leftTick <= KEEP_TICKS && distance <= NEAR;
    }

    // (table_back_on_cut) a cut craft's table: still in time (its keep), within its reach, the craft that put it down stopped
    // for good (one a chain only paused comes back to it), and no table in the bag
    static boolean cutDue(long cutTick, long now, double distance, boolean ownerActive, boolean tableInBag) {
        return now >= cutTick && now - cutTick <= CUT_KEEP_TICKS && distance <= CUT_NEAR && !ownerActive && !tableInBag;
    }

    // a cut entry kept for later: in time still, whatever the distance or the owner says now
    static boolean cutKept(long cutTick, long now) {
        return now >= cutTick && now - cutTick <= CUT_KEEP_TICKS;
    }

    public static boolean cutOn() {
        return adris.altoclef.util.helpers.Tuning.get("table_back_on_cut", 0) > 0;
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
        LEFT.add(new Left(at, item, WorldHelper.getCurrentDimension().name(), mod.getWorld().getGameTime(), null, false));
    }

    // (table_back_on_cut) a craft was cut (paused by a chain, or stopped before it was done) and its own crafting table stands
    // at pos (null: none of its own). `owner` is the craft: while it is still active it may come back to the table
    public static synchronized void cut(AltoClef mod, BlockPos pos, Task owner) {
        if (pos == null || mod.getWorld() == null || !GarbageRules.keepStationsOn() || !cutOn()) {
            return;
        }
        Item item = mod.getWorld().getBlockState(pos).getBlock().asItem();
        if (item != net.minecraft.world.item.Items.CRAFTING_TABLE) {
            return;
        }
        BlockPos at = pos.immutable();
        // a done report of the same table stays the done one
        if (LEFT.stream().anyMatch(l -> l.pos().equals(at) && !l.cut())) {
            return;
        }
        boolean known = LEFT.removeIf(l -> l.pos().equals(at));
        LEFT.add(new Left(at, item, WorldHelper.getCurrentDimension().name(), mod.getWorld().getGameTime(), owner, true));
        if (!known) {
            Debug.logInternal("stations back: the craft at the crafting table at " + at.toShortString()
                    + " was cut, the table goes back in the bag once the craft is over (table_back_on_cut)");
        }
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
            // (a cut one's time runs from when the walk to it began, it may have waited minutes for its craft)
            long from = current.cut() ? since : current.tick();
            if (count > had || now < from || now - from > KEEP_TICKS) {
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
            boolean inTime = l.cut() ? cutKept(l.tick(), now) : due(l.tick(), now, Double.NEGATIVE_INFINITY);
            if (!l.dimension().equals(dimension) || !inTime || !stands(mod, l)) {
                if (l.cut() && l.dimension().equals(dimension) && !inTime && stands(mod, l)) {
                    Debug.logInternal("stations back: the crafting table at " + l.pos().toShortString() + " of a cut craft not taken back in time"
                            + " (table_back_on_cut)");
                }
                it.remove();
                continue;
            }
            boolean ready = l.cut()
                    ? cutDue(l.tick(), now, d, l.owner() != null && l.owner().isActive(), mod.getItemStorage().getItemCount(Items.CRAFTING_TABLE) > 0)
                    : d <= NEAR;
            if (ready && d < bestDistance) {
                best = l;
                bestDistance = d;
            }
        }
        if (best == null) {
            return null;
        }
        LEFT.remove(best);
        current = best;
        since = now;
        had = mod.getItemStorage().getItemCount(best.item());
        Debug.logInternal("stations back: the " + name(best.item()) + " a smelt or craft put down at " + best.pos().toShortString()
                + (best.cut() ? " (its craft was cut, " + Math.round(bestDistance) + " blocks off) goes back in the bag (table_back_on_cut)"
                : " goes back in the bag (bag_keep_stations)"));
        return new DestroyBlockTask(best.pos());
    }

    private static boolean stands(AltoClef mod, Left l) {
        return l.item() instanceof BlockItem b && mod.getWorld().getBlockState(l.pos()).is(b.getBlock());
    }

    private static String name(Item item) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).getPath().replace('_', ' ');
    }
}
