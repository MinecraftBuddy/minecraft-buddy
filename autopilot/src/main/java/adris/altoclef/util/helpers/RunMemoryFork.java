package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.construction.compound.ConstructNetherPortalObsidianTask;
import adris.altoclef.tasks.container.AsyncSmelting;
import adris.altoclef.tasks.container.BrewFireResistanceTask;
import adris.altoclef.tasks.resources.ObtainSweep;
import baritone.Baritone;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.LinkedHashMap;
import java.util.Map;

// the fork's memories that outlive an order (RunMemory), and the measure line's numbers beyond their sizes. the lambdas
// load nothing until a measure or a clear asks
final class RunMemoryFork {
    private static final String P = "adris.altoclef.";

    private RunMemoryFork() {
    }

    private static AltoClef mod() {
        AltoClef mod = AltoClef.getInstance();
        if (mod == null || !AltoClef.inGame()) {
            throw new IllegalStateException("no world");
        }
        return mod;
    }

    static void register() {
        // what the trackers know (the size: block positions, every dimension; the clear takes the entity and container
        // memories too). the chunk tracker stays: what it knows is loaded now
        RunMemory.register("trackers", () -> mod().getBlockTracker().knownCount(), () -> mod().getTrackerManager().forgetRun());
        // the portal last used and the last dry land, per dimension
        RunMemory.register("portals_and_dry_land", () -> mod().getMiscBlockTracker().size(), () -> mod().getMiscBlockTracker().reset());
        // the chunks known as land or water; what is loaded now is looked at again at once
        RunMemory.register("land_chunks", () -> mod().getLandMemory().size(), () -> {
            mod().getLandMemory().reset();
            mod().getLandMemory().sweep();
        });
        // every ban and strike, the timed ones too
        RunMemory.register("bans", () -> mod().getBans().count(), () -> mod().getBans().clearRun("a fresh run"));
        // Baritone's cached world of this server, in RAM and its region files (the size: the files)
        RunMemory.register("baritone_cache", () -> mod().getClientBaritone().getWorldProvider().cachedRegionFiles(),
                () -> mod().getClientBaritone().getWorldProvider().forgetServerCache(),
                "baritone.cache.WorldProvider#worldCache");
        RunMemory.register("brew_stand", () -> BrewFireResistanceTask.remembered(), () -> BrewFireResistanceTask.forgetRun(),
                P + "tasks.container.BrewFireResistanceTask#_stand", P + "tasks.container.BrewFireResistanceTask#_standDim",
                P + "tasks.container.BrewFireResistanceTask#_standStage", P + "tasks.container.BrewFireResistanceTask#_standFuel",
                P + "tasks.container.BrewFireResistanceTask#_boxStand");
        RunMemory.register("portal_frame", () -> ConstructNetherPortalObsidianTask.remembered(), () -> ConstructNetherPortalObsidianTask.forgetRun(),
                P + "tasks.construction.compound.ConstructNetherPortalObsidianTask#_lastOrigin",
                P + "tasks.construction.compound.ConstructNetherPortalObsidianTask#_lastDimension",
                P + "tasks.construction.compound.ConstructNetherPortalObsidianTask#_lastCount");
        RunMemory.register("land_steers", () -> LandFinder.steersKept(), () -> LandFinder.forgetSteers(),
                P + "util.helpers.LandFinder#STEERS", P + "util.helpers.LandFinder#_steerAt");
        // a branch mine's level, left in Baritone's setting by BranchMineTask
        RunMemory.register("legit_mine_y", () -> Baritone.settings().legitMineYLevel.value.equals(Baritone.settings().legitMineYLevel.defaultValue) ? 0 : 1,
                () -> Baritone.settings().legitMineYLevel.reset());
        RunMemory.register("route", () -> RouteRules.remembered(), () -> RouteRules.reset(),
                P + "util.helpers.RouteRules#AWAY_ZONES", P + "util.helpers.RouteRules#exitBegun",
                P + "util.helpers.RouteRules#exitGaveUp", P + "util.helpers.RouteRules#outSaid");
        // the fortress sweep's memory, in RAM and its files beside #gamer's
        RunMemory.register("obtain_sweep", () -> ObtainSweep.remembered(mod()), () -> {
            try {
                ObtainSweep.forgetRun(mod());
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }, P + "tasks.resources.ObtainSweep#BY_WORLD");
        RunMemory.register("seen_filter", () -> SeenFilter.remembered(), () -> SeenFilter.reset(),
                P + "util.helpers.SeenFilter#MEMO", P + "util.helpers.SeenFilter#MISSES", P + "util.helpers.SeenFilter#BUDGET",
                P + "util.helpers.SeenFilter#memoLevel");
        RunMemory.register("stations_plain", () -> StationHook.plainBook().size(), () -> StationHook.clearPlain(),
                P + "util.helpers.StationHook#plain");
        RunMemory.register("placed_by_us", () -> PlacedByUs.GLOBAL.size(), () -> PlacedByUs.GLOBAL.clear(),
                P + "util.helpers.PlacedByUs#GLOBAL");
        // the furnace jobs loaded and the smelt's batch
        RunMemory.register("smelting", () -> AsyncSmelting.remembered(), () -> AsyncSmelting.clear(),
                P + "tasks.container.AsyncSmelting#LOADED", P + "tasks.container.AsyncSmelting#jobs",
                P + "tasks.container.AsyncSmelting#ours", P + "tasks.container.AsyncSmelting#lastWork",
                P + "tasks.container.SmeltSplit#current", P + "tasks.container.SmeltSplit#site",
                P + "tasks.container.SmeltSplit#fuelGaveUpFor");
        RunMemory.register("death_stash", () -> DeathStash.remembered(), () -> DeathStash.clear(),
                P + "util.helpers.DeathStash#pending");
        // a branch mine's tunnel of its own and baritone's mine's stops for no path, kept over a mine made anew (mine_own_keep,
        // mine_no_path_tunnel)
        RunMemory.register("own_tunnel", () -> OwnTunnel.KEPT.size(), () -> OwnTunnel.KEPT.clear(),
                P + "util.helpers.OwnTunnel#KEPT");
    }

    // the measure line beyond the sizes: positions known per block (every dimension), the farthest wart known in this
    // dimension (blocks, -1 none), Baritone's regions in RAM and region files on disk of this server. empty with no world
    static Map<String, Object> measure() {
        Map<String, Object> out = new LinkedHashMap<>();
        AltoClef mod = AltoClef.getInstance();
        if (mod == null || !AltoClef.inGame() || mod.getPlayer() == null) {
            return out;
        }
        Map<String, Integer> positions = new LinkedHashMap<>();
        for (Block b : new Block[]{Blocks.NETHER_WART, Blocks.SPAWNER, Blocks.NETHER_PORTAL, Blocks.CHEST, Blocks.BREWING_STAND,
                Blocks.OBSIDIAN, Blocks.CRAFTING_TABLE, Blocks.FURNACE}) {
            positions.put(BuiltInRegistries.BLOCK.getKey(b).getPath(), mod.getBlockTracker().knownCount(b));
        }
        out.put("positions", positions);
        out.put("wart_farthest", Math.round(mod.getBlockTracker().farthestKnown(mod.getPlayer().position(), Blocks.NETHER_WART)));
        out.put("regions_ram", mod.getClientBaritone().getWorldProvider().cachedRegionsInRam());
        out.put("region_files", mod.getClientBaritone().getWorldProvider().cachedRegionFiles());
        return out;
    }
}
