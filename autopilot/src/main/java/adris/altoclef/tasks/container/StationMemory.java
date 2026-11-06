package adris.altoclef.tasks.container;

import adris.altoclef.AltoClef;
import adris.altoclef.AltoSettings;
import adris.altoclef.tasks.container.AsyncSmelting;
import adris.altoclef.trackers.storage.ContainerCache;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StationHook;
import adris.altoclef.util.helpers.WalkCost;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

// what a smelt task knows about a furnace or smoker it is not looking at right now. an interrupt (the pickup, a mob, a death)
// restarts the task with empty caches, and "nothing in the caches" used to read as "nothing in the furnace": the bot mined
// the 37 raw iron it had already loaded, and fetched fuel for the coal that was already in the fuel slot. the container
// tracker's last look is the memory, the real slots decide the moment the screen is open again
final class StationMemory {
    private StationMemory() {
    }

    // what the slots showed beats the memory, and so does an open screen (a real zero). only a closed screen with nothing
    // ever seen falls back to the last look. a stale number is fine, the screen corrects it
    static double known(boolean screenOpen, double seen, double remembered) {
        return seen > 0 || screenOpen ? seen : Math.max(0, remembered);
    }

    // fuel still to find once the furnace's own is counted
    static double fuelStillNeeded(double needed, double inFurnace) {
        return Math.max(0, needed - inFurnace);
    }

    // raw stuff of ours in the last look at this spot. only a furnace we put down counts (a village's was never loaded by us)
    static int materialsRemembered(AltoClef mod, BlockPos at, ItemTarget materials) {
        if (at == null || !AsyncSmelting.isOurFurnace(at)) {
            return 0;
        }
        return mod.getItemStorage().getContainerAtPosition(at).map(c -> c.getItemCount(materials.getMatches())).orElse(0);
    }

    // the raw stack of ours the tracker last saw in this station, empty when none or it is not ours. a cook that was cut off and
    // restarted has empty caches, and "nothing cached" is not "nothing in the station" (same story as materialsRemembered)
    static ItemStack materialLook(AltoClef mod, BlockPos at, ItemTarget materials) {
        if (at == null || !AsyncSmelting.isOurFurnace(at)) {
            return ItemStack.EMPTY;
        }
        return mod.getItemStorage().getContainerAtPosition(at).map(c -> {
            for (net.minecraft.world.item.Item item : materials.getMatches()) {
                int n = c.getItemCount(item);
                if (n > 0) {
                    return new ItemStack(item, n);
                }
            }
            return ItemStack.EMPTY;
        }).orElse(ItemStack.EMPTY);
    }

    // a restarted smelt task's picture of its station (everything it cached went with the restart): where, what raw stuff of ours,
    // the fuel in the slot, and what is burning. the tracker cannot see the fire, but the block can (lit furnaces show it), and a
    // load that was cut off after the last fuel went in is lit with an empty fuel slot. null when there is nothing of ours in it by
    // the last look, or a job already points at it (that one has its own timer, a guess from a stale look would replace it)
    record Look(BlockPos at, ItemStack material, double slotFuel, double burning) {
    }

    static Look restartedLook(AltoClef mod, BlockPos at, ItemTarget materials) {
        ItemStack material = materialLook(mod, at, materials);
        if (material.isEmpty() || AsyncSmelting.jobAt(at)) {
            return null;
        }
        boolean lit = mod.getWorld().getBlockState(at).getOptionalValue(BlockStateProperties.LIT).orElse(false);
        return new Look(at, material, fuelRemembered(mod, at, materials), lit ? material.getCount() : 0);
    }

    // smelts of fuel in the last look: the fuel slot, in the same units the smelt tasks count in. the cargo is not fuel even
    // when it burns (planks to smelt are not planks to burn)
    static double fuelRemembered(AltoClef mod, BlockPos at, ItemTarget materials) {
        if (at == null || !AsyncSmelting.isOurFurnace(at)) {
            return 0;
        }
        return mod.getItemStorage().getContainerAtPosition(at).map(c -> fuelOf(c, materials)).orElse(0.0);
    }

    // the tracked block of this kind that is ours and still has something of ours in it by the last look, null if none. a load
    // that got cut off is finished at THAT furnace: the nearest one may be a village's or an empty one of ours, and walking to
    // it read the loaded one's 37 ore as 0
    static BlockPos ourLoaded(AltoClef mod, net.minecraft.world.level.block.Block block) {
        var me = mod.getPlayer().position();
        // inside the forget distance only: a stale look at a furnace across the map must not pin every smelt to it (the registry
        // forgets those too). nearest is the same straight line the rest of the station choice goes by. a station whose job went
        // stale and was given up is not a load to finish either, or the next smelt walks right back to what we just gave up on
        return mod.getBlockTracker().getNearestTracking(me,
                p -> holdsOurStuff(mod, p) && !StationHook.givenUp(p) && adris.altoclef.util.helpers.WorldHelper.canReach(mod, p)
                        && WalkCost.distance3d(p.getX() + 0.5 - me.x, p.getY() + 0.5 - me.y, p.getZ() + 0.5 - me.z) <= WalkCost.STATION_FORGET,
                (fx, fy, fz, tx, ty, tz) -> WalkCost.distance3d(tx - fx, ty - fy, tz - fz), block).orElse(null);
    }

    // one we put down, with something of ours in it by the last look at its slots (a village's was never loaded by us)
    static boolean holdsOurStuff(AltoClef mod, BlockPos at) {
        return AsyncSmelting.isOurFurnace(at) && mod.getItemStorage().getContainerAtPosition(at).map(ContainerCache::holdsMoreThanFuel).orElse(false);
    }

    private static double fuelOf(ContainerCache cache, ItemTarget materials) {
        return cache.sumOver((item, count) -> {
            if (materials.matches(item) || !AltoSettings.isSupportedFuel(item)) {
                return 0.0;
            }
            return ItemHelper.getFuelAmount(new ItemStack(item, count));
        });
    }
}
