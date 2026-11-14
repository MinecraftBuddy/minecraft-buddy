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
// (Tuning cut_parent_hold, a fix of 14 Nov 2026, on) and not while the task that ticked the cut craft (its parent) still runs and
// the craft's item is not in the bag, for PARENT_HOLD_TICKS from the cut at most: that parent left the craft for something the
// craft's own work needs and comes back to it. a chain's top (the run's wrapper) is no such parent
// (Tuning stations_back_cap, a fix of 14 Nov 2026, on) and at most CAP_TAKES take-backs of a cut craft's table begun at one spot
// (within CAP_SPOT) in CAP_WINDOW_TICKS; past that the table is left standing, with a line (a done craft's station is not
// counted: a kit's crafts in a row put the table down and take it back once each). the furnace for the chestplate's iron put the table
// down, the table out of the bag held a log more back from the fuel (WoodFuel, fuel_table_standing), the smelt left the furnace
// craft for a fuel trip, and the cut table went back in the bag before the trip reached its log: fuel enough again, the furnace
// craft again, the table down again, 25 minutes of that in the open (test heel, look 96)
public final class StationsBack {

    // a game half minute, as the gold batch's furnace (CollectGoldIngotTask)
    public static final long KEEP_TICKS = 600;
    public static final double NEAR = 12;
    // (table_back_on_cut) a fight may take the buddy off and keep it a while: five game minutes, and a walk of 32 blocks back
    public static final long CUT_KEEP_TICKS = 6000;
    public static final double CUT_NEAR = 32;
    // (cut_parent_hold) three game minutes: a fuel or a stone trip near, and still two of the cut's five to take the table back
    public static final long PARENT_HOLD_TICKS = 3600;
    // (stations_back_cap) two take-backs at one spot in a game minute, a spot being 4 blocks across
    public static final int CAP_TAKES = 2;
    public static final long CAP_WINDOW_TICKS = 1200;
    public static final double CAP_SPOT = 4;

    // owner: the craft that was cut (null for a done one), cut: reported by a cut craft
    private record Left(BlockPos pos, Item item, String dimension, long tick, Task owner, boolean cut) {
    }

    private static final List<Left> LEFT = new ArrayList<>();
    // the one being taken back, how many of its kind the bag held when that began, and when it began
    private static Left current;
    private static int had;
    private static long since;
    // (stations_back_cap) the take-backs of a cut craft's table begun, and the last spot the cap's line was said for
    record Take(int x, int y, int z, String dimension, long tick) {
    }

    private static final List<Take> TAKES = new ArrayList<>();
    private static Take capSaid;
    // (cut_parent_hold) the cut entries whose hold was said, by position and cut tick
    private static final java.util.Map<BlockPos, Long> HOLD_SAID = new java.util.HashMap<>();

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

    // (cut_parent_hold) the cut craft's parent still runs and the craft's item is not in the bag: the table waits, from the cut
    // for PARENT_HOLD_TICKS at most
    static boolean parentHolds(long cutTick, long now, boolean parentRuns, boolean craftDone) {
        return parentRuns && !craftDone && now >= cutTick && now - cutTick < PARENT_HOLD_TICKS;
    }

    public static boolean parentHoldOn() {
        return adris.altoclef.util.helpers.Tuning.get("cut_parent_hold", 0) > 0;
    }

    // (stations_back_cap) the take-backs begun at the spot (within CAP_SPOT, the same dimension) in the last CAP_WINDOW_TICKS
    static int takesAt(List<Take> takes, int x, int y, int z, String dimension, long now) {
        int n = 0;
        for (Take t : takes) {
            if (sameSpot(t, x, y, z, dimension) && now >= t.tick() && now - t.tick() <= CAP_WINDOW_TICKS) {
                n++;
            }
        }
        return n;
    }

    // (stations_back_cap) CAP_TAKES already begun at the spot in the window: this one is left standing
    static boolean capped(List<Take> takes, int x, int y, int z, String dimension, long now) {
        return takesAt(takes, x, y, z, dimension, now) >= CAP_TAKES;
    }

    static boolean sameSpot(Take t, int x, int y, int z, String dimension) {
        double dx = t.x() - x, dy = t.y() - y, dz = t.z() - z;
        return t.dimension().equals(dimension) && dx * dx + dy * dy + dz * dz <= CAP_SPOT * CAP_SPOT;
    }

    public static boolean capOn() {
        return adris.altoclef.util.helpers.Tuning.get("stations_back_cap", 0) > 0;
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

    // (Tuning root_done_table, a fix of 14 Nov 2026, on) a user task is done and dropped without a stop (UserTaskChain.onTaskFinish),
    // so its last craft never reports its table: the crafting table that craft's own placer put down, still standing, is reported
    // here as a done one (left), and the next task's wrapper takes it back in the bag within KEEP_TICKS and NEAR. plain alto keeps a
    // station as "ours" only in the task whose placer put it down (StationHook has no registry without the gamer, the plain book
    // answers nothing), so the next task saw it as nobody's: the table for two iron pickaxes stayed at a cave's floor when the task
    // ended a second after the craft, and 18 minutes later an obsidian pour with no table in the bag walked back to it 61 blocks
    // straight up and searched "0.0 blocks" for two minutes (box 1 t8, heel-23 run 5). off: nothing reported
    public static boolean rootDoneOn() {
        return adris.altoclef.util.helpers.Tuning.get("root_done_table", 0) > 0;
    }

    public static synchronized void rootDone(AltoClef mod, Task root) {
        if (root == null || mod.getWorld() == null || !rootDoneOn() || !GarbageRules.keepStationsOn()) {
            return;
        }
        Task found = deepestOwnTable(root.lineDown(), t -> t instanceof adris.altoclef.tasks.container.DoStuffInContainerTask d
                && d.stationKind() == adris.altoclef.util.helpers.StationHook.Kind.TABLE && d.ownStation(mod) != null);
        if (!(found instanceof adris.altoclef.tasks.container.DoStuffInContainerTask craft)) {
            return;
        }
        BlockPos at = craft.ownStation(mod);
        left(mod, at);
        Debug.logInternal("stations back: the task is done with the crafting table its craft put down at " + at.toShortString()
                + " standing, back in the bag on the next task (root_done_table)");
    }

    // the task nearest the leaf in a root's line that `ownTable` likes, null for none
    static <T> T deepestOwnTable(List<T> line, java.util.function.Predicate<? super T> ownTable) {
        for (int i = line.size() - 1; i >= 0; i--) {
            if (ownTable.test(line.get(i))) {
                return line.get(i);
            }
        }
        return null;
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
            HOLD_SAID.clear();
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
            if (ready && l.cut() && parentHoldOn() && l.owner() != null) {
                Task parent = l.owner().parentTask();
                // a chain's top (the run's wrapper, which asks for this) runs for the whole run: no parent to wait for
                boolean runs = parent != null && parent.parentTask() != null && parent.isActive();
                if (parentHolds(l.tick(), now, runs, done(mod, l.owner()))) {
                    ready = false;
                    Long said = HOLD_SAID.get(l.pos());
                    if (said == null || said != l.tick()) {
                        HOLD_SAID.put(l.pos(), l.tick());
                        Debug.logInternal("stations back: the crafting table at " + l.pos().toShortString() + " waits, the cut craft's parent ("
                                + parent.getClass().getSimpleName() + ") still runs and its item is not in the bag, "
                                + (PARENT_HOLD_TICKS / 20) + " s at most (cut_parent_hold)");
                    }
                }
            }
            if (ready && d < bestDistance) {
                best = l;
                bestDistance = d;
            }
        }
        HOLD_SAID.keySet().removeIf(p -> LEFT.stream().noneMatch(l -> l.pos().equals(p)));
        if (best == null) {
            return null;
        }
        LEFT.remove(best);
        HOLD_SAID.remove(best.pos());
        TAKES.removeIf(t -> now < t.tick() || now - t.tick() > CAP_WINDOW_TICKS);
        BlockPos at = best.pos();
        if (best.cut() && capOn() && capped(TAKES, at.getX(), at.getY(), at.getZ(), dimension, now)) {
            if (capSaid == null || !sameSpot(capSaid, at.getX(), at.getY(), at.getZ(), dimension) || now < capSaid.tick()
                    || now - capSaid.tick() > CAP_WINDOW_TICKS) {
                capSaid = new Take(at.getX(), at.getY(), at.getZ(), dimension, now);
                Debug.logInternal("stations back: the " + name(best.item()) + " at " + at.toShortString() + " left standing, "
                        + CAP_TAKES + " take-backs at this spot within " + (CAP_WINDOW_TICKS / 20) + " s already (stations_back_cap)");
            }
            return null;
        }
        if (best.cut()) {
            TAKES.add(new Take(at.getX(), at.getY(), at.getZ(), dimension, now));
        }
        current = best;
        since = now;
        had = mod.getItemStorage().getItemCount(best.item());
        Debug.logInternal("stations back: the " + name(best.item()) + " a smelt or craft put down at " + best.pos().toShortString()
                + (best.cut() ? " (its craft was cut, " + Math.round(bestDistance) + " blocks off) goes back in the bag (table_back_on_cut)"
                : " goes back in the bag (bag_keep_stations)"));
        return new DestroyBlockTask(best.pos());
    }

    // (cut_parent_hold) the cut craft's item is in the bag (its own test, a count of the bag)
    private static boolean done(AltoClef mod, Task owner) {
        try {
            return owner.isFinished(mod);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean stands(AltoClef mod, Left l) {
        return l.item() instanceof BlockItem b && mod.getWorld().getBlockState(l.pos()).is(b.getBlock());
    }

    private static String name(Item item) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).getPath().replace('_', ' ');
    }
}
