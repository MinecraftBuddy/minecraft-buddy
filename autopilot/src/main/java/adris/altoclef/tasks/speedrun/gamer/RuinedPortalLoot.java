package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.container.LootContainerTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.SeenFilter;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.utils.Dimension;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

// opportunistic: a ruined portal chest we have SEEN with our own eyes (never searched for) is free obsidian, flint and
// steel and gold. each chest gets one visit, in memory only (a relog forgets, the chest is then "unopened" again
// and one more look is not the end of the world)
public final class RuinedPortalLoot {
    private static final List<Item> LOOT = List.of(
            Items.OBSIDIAN, Items.FLINT_AND_STEEL, Items.FIRE_CHARGE, Items.FLINT, Items.IRON_INGOT,
            Items.GOLD_INGOT, Items.GOLD_NUGGET, Items.GOLD_BLOCK, Items.RAW_GOLD,
            Items.GOLDEN_BOOTS, Items.GOLDEN_HELMET, Items.GOLDEN_CHESTPLATE, Items.GOLDEN_LEGGINGS);
    private static final int CHECK_EVERY_TICKS = 20;

    private final Set<BlockPos> done = new HashSet<>();
    private final Set<BlockPos> notPortalChests = new HashSet<>();
    private boolean tracking;
    private int ticks;
    private BlockPos target;
    private LootContainerTask task;
    private long activeTicks;

    public void onEnter(AltoClef mod) {
        if (!tracking) {
            mod.getBlockTracker().trackBlock(Blocks.CHEST);
            tracking = true;
        }
    }

    public void onExit(AltoClef mod) {
        if (tracking) {
            mod.getBlockTracker().stopTracking(Blocks.CHEST);
            tracking = false;
        }
        target = null;
        task = null;
    }

    // the loot task while there is a chest to visit, otherwise null
    public Task tick(AltoClef mod, GamerContext ctx) {
        if (target != null) {
            // our own tick count, not the game clock: every second mob defense spends swinging is a second this
            // chest was not being looted, and the wall clock would happily charge us for it
            double elapsed = ++activeTicks / 20.0;
            boolean settled = task.isFinished(mod);
            boolean timedOut = elapsed > ctx.cfg().overworld.lootChestSeconds;
            if (!settled && !timedOut) {
                return task;
            }
            // finished means the chest was open, synced and had nothing left we want (LootContainerTask makes sure of
            // it), so done is earned. the timer is the other way out
            ctx.log(LootVisit.line("ruined portal", target.getX(), target.getY(), target.getZ(),
                    LootVisit.why(settled, timedOut, !task.taken().isEmpty()), task.taken()));
            done.add(target);
            target = null;
            task = null;
        }
        if (ctx.facts().dimension() != Dimension.OVERWORLD || ++ticks % CHECK_EVERY_TICKS != 0) {
            return null;
        }
        Optional<BlockPos> next = findChest(mod, ctx.cfg().overworld.ruinedPortalLootRadius);
        if (next.isEmpty()) {
            return null;
        }
        target = next.get();
        task = new LootContainerTask(target, LOOT);
        activeTicks = 0;
        ctx.progress("looting a ruined portal chest");
        return task;
    }

    private Optional<BlockPos> findChest(AltoClef mod, int radius) {
        if (!mod.getBlockTracker().isTracking(Blocks.CHEST)) {
            return Optional.empty();
        }
        return mod.getBlockTracker().getNearestTracking(pos ->
                !done.contains(pos) && !notPortalChests.contains(pos)
                        && mod.getPlayer().blockPosition().closerThan(pos, radius)
                        && WorldHelper.isUnopenedChest(mod, pos)
                        && SeenFilter.isSeen(mod, pos)
                        && looksLikePortalChest(mod, pos), Blocks.CHEST);
    }

    // same heuristic the old tasks used: not under water, not deep, netherrack close by. the answer for a chest never
    // changes, so the no's are remembered (the scan is 9x5x9 blocks)
    private boolean looksLikePortalChest(AltoClef mod, BlockPos pos) {
        boolean waterAbove = mod.getWorld().getBlockState(pos.above()).getBlock() == Blocks.WATER;
        boolean netherrack = false;
        for (BlockPos check : WorldHelper.scanRegion(mod, pos.offset(-4, -2, -4), pos.offset(4, 2, 4))) {
            if (mod.getWorld().getBlockState(check).getBlock() == Blocks.NETHERRACK) {
                netherrack = true;
                break;
            }
        }
        boolean ok = plausible(pos.getY(), waterAbove, netherrack);
        if (!ok) {
            notPortalChests.add(pos);
        }
        return ok;
    }

    public static boolean plausible(int y, boolean waterAbove, boolean netherrackNearby) {
        return !waterAbove && y >= 50 && netherrackNearby;
    }
}
