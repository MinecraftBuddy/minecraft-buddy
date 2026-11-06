package adris.altoclef.tasks.construction.schematic;

import adris.altoclef.AltoClef;
import adris.altoclef.AltoSettings;
import adris.altoclef.Debug;
import adris.altoclef.tasks.resources.CollectBedTask;
import adris.altoclef.tasks.speedrun.gamer.NightPrep;
import adris.altoclef.tasks.speedrun.gamer.NightSleep;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.tasks.speedrun.gamer.tasks.BedCraft;
import adris.altoclef.tasks.speedrun.gamer.tasks.NightBeds;
import adris.altoclef.tasks.speedrun.gamer.tasks.NightSleepTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.CombatCommit;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.NightSleepHook;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.Baritone;
import baritone.api.utils.Dimension;
import baritone.api.utils.Helper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.ToIntFunction;

// the gamer's night (altoSleepAtNight) for a survival schematic build: same rules (NightSleep.decide), same evening prep
// (NightPrep), same trip (NightSleepTask). this only feeds them what a build knows instead of what a speedrun knows, the
// gamer's own copy of this flow is GamerTask.nightSleepTick
public final class BuildNightSleep {

    // who gets a tick of the build
    public enum Turn {
        // nothing about the night, the build runs (and its guard counts the tick)
        BUILD,
        // the night has the wheel and the builder isn't running, so there's nothing to cut
        NIGHT,
        // the night has the wheel and the builder is running: it goes, and a fresh count follows in the morning
        CUT_BUILDER
    }

    // a fresh config, never the gamer's saved one: only the night numbers get read and the defaults are the tuned ones
    private final OverworldConfig cfg = new OverworldConfig();
    // only its nightBed is used, that's where the trip writes down the bed it put down and the cleanup finds it again. not saved,
    // a relog mid night leaves the bed out there (the build doesn't survive a relog either)
    private final RunState beds = new RunState();
    private final NightSleep.Budget budget = new NightSleep.Budget();
    private final NightPrep prep = new NightPrep(() -> cfg);
    // how long GO waits on the tracker's first bed scan before going with what it has. the first look is right away
    // (BlockTracker.needsFirstLook), this is only for a tracker that's slow about it
    private static final long BED_SCAN_WAIT_TICKS = 10 * 20;

    private NightSleepTask trip;
    private LocalPlayer player;
    private long screenSince = -1;
    private String said;
    // the last leftover/bag/standing triple bedReady printed, -1 before the first
    private int readyKey = -1;
    // we asked the block tracker to look for beds. NightBeds.find only knows what the tracker has, and the tracker only
    // scans what somebody tracks, so a bed the player put down before the build was never in it. the count is per
    // caller (BlockTracker ref counts), so the gamer's own tracking and ours can't switch each other off
    private boolean trackingBeds;
    // when GO first had to wait for that first bed scan, -1 while it isn't waiting
    private long scanWaitSince = -1;
    // this tick's GO is waiting on that scan
    private boolean scanHolding;

    // the night (a trip, the evening's wool or craft, or a GO waiting on the bed scan) has the wheel. the prep can hand
    // back nothing for a tick in the middle of a craft, and a build that ran for that one tick would start the builder
    // just to cut it again
    public boolean holding() {
        return trip != null || prep.running() || scanHolding;
    }

    // ---- the pure bits (BuildNightSleepTest)

    public static Turn turn(boolean nightHolds, boolean building) {
        if (!nightHolds) {
            return Turn.BUILD;
        }
        return building ? Turn.CUT_BUILDER : Turn.NIGHT;
    }

    // what keeps a night from starting. away is another dimension than the build's (the trip home is a walk the night
    // shouldn't cut in half). a container that's been up a moment is items moving, same rule as the gamer's, except the
    // evening's own craft opens screens and those don't count
    public static NightSleep.Busy busy(boolean away, boolean ownScreens, long screenUpTicks) {
        boolean screenUp = !ownScreens && screenUpTicks >= 0 && NightSleep.screenHolds(screenUpTicks);
        return NightSleep.busy(!away, false, false, false, screenUp);
    }

    // blocks a pillar and the bed's supports may spend: what's held above the reserve floor, item by item. the floor is the
    // rest of the build, a night that ate it would just send us gathering again in the morning
    public static <K> int spare(Map<K, Integer> held, ToIntFunction<K> reserved) {
        int sum = 0;
        for (Map.Entry<K, Integer> e : held.entrySet()) {
            // (FULLY_RESERVED is Integer.MAX_VALUE, held minus that doesn't wrap)
            sum += Math.max(0, e.getValue() - reserved.applyAsInt(e.getKey()));
        }
        return sum;
    }

    // the bill as of the last count, minus what the bag has spent since. scaffolding comes off too, so it's on the low side
    // on purpose, the next count bills it again. never below zero, and an item the bag gained doesn't grow the bill
    public static Map<String, Integer> leftAfterUse(Map<String, Integer> left, Map<String, Integer> haveThen, Map<String, Integer> haveNow) {
        Map<String, Integer> out = new LinkedHashMap<>();
        left.forEach((id, n) -> {
            int used = Math.max(0, haveThen.getOrDefault(id, 0) - haveNow.getOrDefault(id, 0));
            out.put(id, Math.max(0, n - used));
        });
        return out;
    }

    // ---- the game side

    // the task to run instead of the build this tick, or null. away: the player isn't in the build's dimension
    public Task tick(AltoClef mod, boolean away) {
        long now = mod.getWorld().getGameTime();
        trackScreen(now);
        scanHolding = false;
        if (player != mod.getPlayer()) {
            // a new player is a death: the trip and the prep went with the old one
            player = mod.getPlayer();
            drop();
        }
        budget.roll(NightSleep.nightOf(mod.getWorld().getOverworldClockTime()));
        boolean overworld = WorldHelper.getCurrentDimension() == Dimension.OVERWORLD;
        if (trip != null) {
            if (trip.isFinished(mod) || !overworld) {
                // (a bed a dead or dropped trip left is picked up by a cleanup trip, beds.nightBed still has it)
                dropTrip();
                return null;
            }
            NightSleepHook.set(trip.stage(), now);
            return trip;
        }
        NightSleepHook.clear();
        if (!Baritone.settings().altoSleepAtNight.value) {
            // nobody sleeps, nobody needs the beds scanned
            untrack(mod);
        }
        if (!Baritone.settings().altoSleepAtNight.value || !overworld) {
            prep.drop();
            return null;
        }
        if (!trackingBeds) {
            // from the first overworld tick on and for the whole build, so by the evening the scan has long been done
            trackingBeds = true;
            mod.getBlockTracker().trackBlock(CollectBedTask.BEDS);
            // the tracker only scans when somebody asks it something, and nobody else here might for a while
            mod.getBlockTracker().getKnownLocations(CollectBedTask.BEDS);
        }
        return decide(mod, away);
    }

    private Task decide(AltoClef mod, boolean away) {
        int radius = cfg.nightBedRadius;
        boolean leftover = NightBeds.leftover(mod, beds, radius, () -> { });
        boolean windowOpen = !mod.getWorld().isBrightOutside();
        long now = mod.getWorld().getGameTime();
        NightSleep.Busy busy = busy(away, prep.running(), screenSince < 0 ? -1 : now - screenSince);
        // (healing up counts, same as GamerTask.nightFighting)
        boolean fighting = mod.getMobDefenseChain().combatMode() != CombatCommit.Mode.NONE || mod.getMobDefenseChain().isRecovering();
        boolean hurt = NightSleep.shouldAbandon(mod.getPlayer().getHealth());
        boolean spent = budget.spent(cfg.nightAttempts, cfg.nightSeconds);
        NightSleep.Inputs cheap = new NightSleep.Inputs(true, true, windowOpen, busy != NightSleep.Busy.NONE, fighting, hurt, spent,
            budget.cooling(now * 0.05), NightSleep.BedSource.BAG, leftover);
        NightSleep.Why why = NightSleep.decide(cheap);
        if (why == NightSleep.Why.GO) {
            // a bed standing next to us beats one from the bag or a craft, so the answer waits for the tracker's
            // first look at beds. a few seconds at most, a tracker that never gets round to it doesn't cost the night
            if (waitForBedScan(mod, now)) {
                // held, not handed back to the build: a builder started now would only be cut again in a moment
                scanHolding = true;
                note("GO, waiting for the bed scan");
                return null;
            }
            BlockPos found = NightBeds.find(mod, radius);
            NightSleep.BedSource source = NightSleep.bedSource(found != null, mod.getItemStorage().getItemCount(ItemHelper.BED),
                BedCraft.bestWoolStack(mod), BedCraft.usableWood(mod));
            if (source == NightSleep.BedSource.NONE) {
                note(NightSleep.Why.NO_BED + "", nearestBed(mod, radius));
                // (a prep that was still going when the window opened is over, it doesn't get to keep its screens all night)
                prep.drop();
                return null;
            }
            note(why + ", bed " + source);
            return start(mod, source, found);
        }
        if (why == NightSleep.Why.LEFTOVER) {
            note(why + "");
            return start(mod, NightSleep.BedSource.NONE, null);
        }
        // not time yet, or not allowed to go: the bed gets made ready for when it is
        NightPrep.Gate gate = new NightPrep.Gate(busy != NightSleep.Busy.PHASE, busy != NightSleep.Busy.NONE && busy != NightSleep.Busy.PHASE,
            fighting, hurt, spent, windowOpen);
        Task task = prep.tick(mod, gate, () -> bedReady(mod, leftover, radius));
        note(why + ", prep " + prep.last());
        return task;
    }

    private Task start(AltoClef mod, NightSleep.BedSource source, BlockPos found) {
        if (beds.nightBed == null) {
            budget.began();
        }
        prep.drop();
        trip = new NightSleepTask(beds, () -> cfg, budget, () -> spareBlocks(mod), Helper.HELPER::logDirect, () -> { }, source, found, 0);
        return trip;
    }

    // every task tick, not only the ones that ask, or a screen that closed and opened again would look like it had been up
    // the whole time. same as the gamer's trackNightGates
    private void trackScreen(long now) {
        if (Minecraft.getInstance().gui.screen() instanceof AbstractContainerScreen<?>) {
            if (screenSince < 0) {
                screenSince = now;
            }
        } else {
            screenSince = -1;
        }
    }

    // the prep's "is a bed ready", same as the gamer's nightBedReady: all three asked, a line only when one of them moves.
    // its own line and not note(), note only keys on `what` and the prep's line would swallow it
    private boolean bedReady(AltoClef mod, boolean leftover, int radius) {
        int bag = mod.getItemStorage().getItemCount(ItemHelper.BED);
        boolean found = NightBeds.find(mod, radius) != null;
        int key = (leftover ? 4 : 0) | (bag > 0 ? 2 : 0) | (found ? 1 : 0);
        if (key != readyKey) {
            readyKey = key;
            Debug.logInternal("build night: bed ready? leftover " + leftover + ", bag " + bag + ", standing " + found
                + " (" + NightBeds.nearestWhy(mod, radius) + ")");
        }
        return leftover || bag > 0 || found;
    }

    // one debug line when the answer changes, not one a tick
    private void note(String what) {
        note(what, null);
    }

    // the detail rides along on the line but isn't part of "changed": a distance that ticks down as we walk would
    // otherwise be a line a tick
    private void note(String what, String detail) {
        if (!what.equals(said)) {
            said = what;
            Debug.logInternal("build night: " + what + (detail == null ? "" : ", " + detail));
        }
    }

    // true while GO should hold off for the first bed scan. the cap is from when the waiting started, so one long
    // wait in the evening doesn't make every later evening skip the check
    private boolean waitForBedScan(AltoClef mod, long now) {
        var tracker = mod.getBlockTracker();
        // a query is what gets a scan going (hasBeenScanned and scanPending only look), so ask every tick we wait
        tracker.getKnownLocations(CollectBedTask.BEDS);
        if (tracker.hasBeenScanned(CollectBedTask.BEDS) && !tracker.scanPending(CollectBedTask.BEDS)) {
            scanWaitSince = -1;
            return false;
        }
        if (scanWaitSince < 0) {
            scanWaitSince = now;
        }
        return now - scanWaitSince < BED_SCAN_WAIT_TICKS;
    }

    // why NO_BED, when it isn't obvious: the nearest bed the tracker knows and how far it is against the radius, so a
    // bed that's simply too far shows up as one. NightBeds.find also turns down near ones (unreachable, monsters in
    // the box, trial chamber copper), which is the "within" case
    private static String nearestBed(AltoClef mod, int radius) {
        double best = Double.POSITIVE_INFINITY;
        for (BlockPos pos : mod.getBlockTracker().getKnownLocations(CollectBedTask.BEDS)) {
            best = Math.min(best, pos.distToCenterSqr(mod.getPlayer().position()));
        }
        return bedLine(Math.sqrt(best), radius);
    }

    // infinity = the tracker knows no bed at all
    public static String bedLine(double nearest, int radius) {
        if (Double.isInfinite(nearest)) {
            return "no bed known, radius " + radius;
        }
        int dist = (int) Math.round(nearest);
        return "nearest known bed " + dist + " blocks, radius " + radius + (nearest <= radius ? " (within, but no good)" : "");
    }

    // stops our bed tracking. the build calls it once it's over for good, an interrupt keeps it (same as the trip)
    public void untrack(AltoClef mod) {
        if (trackingBeds) {
            trackingBeds = false;
            mod.getBlockTracker().stopTracking(CollectBedTask.BEDS);
        }
    }

    private void dropTrip() {
        trip = null;
        NightSleepHook.clear();
    }

    // the build is over, or the player is new: nothing of tonight is ours any more. the bed claim stays, a trip can still
    // pick it up if the same build is still running
    public void drop() {
        dropTrip();
        prep.drop();
    }

    // the throwaways the pillar can pick, the same list and the same exclusions the gamer counts (MinecraftFacts), minus
    // what the reserve floor holds back right now
    private static int spareBlocks(AltoClef mod) {
        Map<Item, Integer> held = new HashMap<>();
        for (ItemStack stack : mod.getPlayer().getInventory().getNonEquipmentItems()) {
            Item item = stack.getItem();
            if (!stack.isEmpty() && AltoSettings.isBuildBlock(item) && AltoSettings.isThrowaway(item)) {
                held.merge(item, stack.getCount(), Integer::sum);
            }
        }
        return spare(held, mod.getExtraBaritoneSettings()::reservedCount);
    }
}
