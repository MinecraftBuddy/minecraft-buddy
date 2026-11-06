package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.AltoClef;
import adris.altoclef.BotBehaviour;
import adris.altoclef.BuildInfo;
import adris.altoclef.Debug;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.Subscription;
import adris.altoclef.eventbus.events.BlockPlaceEvent;
import adris.altoclef.tasks.construction.StationBox;
import adris.altoclef.tasks.container.AsyncSmelting;
import adris.altoclef.tasks.container.SmeltSplit;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfigs;
import adris.altoclef.tasks.speedrun.gamer.phases.DragonPhase;
import adris.altoclef.tasks.speedrun.gamer.phases.EndPrepPhase;
import adris.altoclef.tasks.speedrun.gamer.phases.EyesPhase;
import adris.altoclef.tasks.speedrun.gamer.phases.GatherPhase;
import adris.altoclef.tasks.speedrun.gamer.phases.IronPhase;
import adris.altoclef.tasks.speedrun.gamer.phases.LocatePhase;
import adris.altoclef.tasks.speedrun.gamer.phases.NetherPhase;
import adris.altoclef.tasks.speedrun.gamer.phases.NetherRegress;
import adris.altoclef.tasks.speedrun.gamer.phases.OpenPhase;
import adris.altoclef.tasks.speedrun.gamer.phases.PortalPhase;
import adris.altoclef.tasks.speedrun.gamer.phases.ReturnPhase;
import adris.altoclef.tasks.speedrun.gamer.phases.RoomPhase;
import adris.altoclef.tasks.movement.EnterNetherPortalTask;
import adris.altoclef.tasks.movement.HealUpTask;
import adris.altoclef.util.helpers.CombatRules;
import adris.altoclef.tasks.speedrun.gamer.tasks.EscapeRun;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherRecoverTask;
import adris.altoclef.tasks.speedrun.gamer.tasks.BedCraft;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherTripRules;
import adris.altoclef.tasks.speedrun.gamer.tasks.NightBeds;
import adris.altoclef.tasks.speedrun.gamer.tasks.NightSleepTask;
import adris.altoclef.tasks.speedrun.gamer.tasks.ShelterTask;
import adris.altoclef.tasks.speedrun.gamer.tasks.RecoverItemsTask;
import adris.altoclef.tasks.speedrun.gamer.tasks.RecoverRules;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.trace.Trace;
import adris.altoclef.trace.Vitals;
import adris.altoclef.util.helpers.DeathStash;
import adris.altoclef.util.helpers.CombatCommit;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.NightSleepHook;
import adris.altoclef.util.helpers.StationHook;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.Baritone;
import baritone.altoclef.SettingsOverrides;
import baritone.api.Settings;
import baritone.api.utils.Dimension;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.WinScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.commons.lang3.ArrayUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// beats the game: a phase state machine over PhaseHandlers with its memory in RunState (see gamer-design.md). this class is
// the part that touches the game: facts, deaths, saving, the hud and the settings for the run. which phase we are in and
// when it gives up is PhaseMachine, which does not know there is a game. nothing in here is static, two runs never share anything
public class GamerTask extends Task {
    // what a full inventory (altoThrowAwayUnusedItems, which the run forces on) must never throw away: the gear and
    // materials the End needs, wool until it is beds, the stuff the next phases still have to smelt, trade and craft with
    private static final Item[] PROTECTED = protectedItems();
    // the same list for ItemValue, which wants it whether or not a run is going
    private static final Set<Item> GUARDED = new HashSet<>(List.of(PROTECTED));
    private static final Block[] TRACKED = ArrayUtils.addAll(new Block[]{
            Blocks.END_PORTAL_FRAME, Blocks.END_PORTAL, Blocks.CRAFTING_TABLE, Blocks.CHEST, Blocks.SPAWNER,
            Blocks.NETHER_PORTAL}, ItemHelper.itemsToBlocks(ItemHelper.BED));
    // the blocks we want to bridge and pillar with, on top of whatever the user listed
    private static final Item[] BUILD_BLOCKS = {Items.COBBLESTONE, Items.DIRT, Items.NETHERRACK, Items.END_STONE, Items.COBBLED_DEEPSLATE};

    private static final double SAVE_EVERY_SECONDS = 20;
    // an interrupt (mob defense flickering on and off) saves at most this often, wall clock
    private static final long INTERRUPT_SAVE_MILLIS = 5000;
    private static final double RECOVER_BUDGET_SECONDS = 90;
    // the saved clock is further ahead than the world's own: the world was reset or this is another one
    private static final long CLOCK_SLACK_TICKS = 200;

    private final PhaseMachine machine;
    private final GamerPhase startAt;
    private final Host host = new Host();
    // the pickup for phases that do not run their own (sweepBenches)
    private final Workbenches benches = new Workbenches();
    // the traps the tracker offers. here and not in the phases, every overworld phase walks to tracked blocks
    private final DangerFilter danger = new DangerFilter();

    private AltoClef mod;
    private MinecraftFacts facts;
    private GamerConfig cfg;
    private RunState state;
    private Path statePath;
    // false until the first tick with a player loaded the run state. onStart runs again after every interrupt (eating, mob
    // defense) and that is only the task coming back, never a new run
    private boolean begun;
    // machine.now() when another chain took the wheel, -1 while we have it
    private double interruptedAt = -1;
    // set by loadState when the saved run carries on as it was, so the attempt clocks keep their real start
    private boolean resumedAsIs;
    // the saved run was already won when this task began (#gamer on a finished world): no win card for a run we did not just play
    private boolean startedOver;
    // the win card went to the overlay, it goes once
    private boolean winShown;
    private boolean pushed;
    private BotBehaviour.State level;
    // watches for crafting tables, furnaces and smokers we place, so the registry (Workbenches) only ever takes back its own
    private Subscription<BlockPlaceEvent> placeWatch;
    // the server's word on where we respawn (Respawn), written into the state as it comes
    private RespawnWatch respawnWatch;
    // the engine owns the end portal walk flag: whatever a handler asked for last is put back on every (re)start

    private double lastSaveSeconds;
    private long lastWriteMillis;
    private String lastWritten = "";

    // death tracking
    private LocalPlayer lastPlayer;
    private boolean deadSeen;
    private Dimension deathDimension;
    private BlockPos deathPos;
    private long deathGameTime;
    private NetherTripRules.Cause deathCause = NetherTripRules.Cause.OTHER;
    private RecoverItemsTask recover;
    // the pile's age clock (starts when it can be loaded, not at the death) and what the bag held the last tick we were alive
    private RecoverRules.Allowance allowance;
    // the walk to the pile stands still to heal (RecoverRules.walkHold), same idea as the nether walk. recover is not ticked
    // while we stand (it survives an interrupt anyway), so its own clock does not pay for the standing either
    private final HealUpTask healUp = new HealUpTask();
    private boolean recoverHolding;
    private long recoverHeldTicks;
    private long recoverHoldTick;
    // which pile the recovery is for, deathPos is already the next death's by the time we need it
    private BlockPos recoverPos;
    private Dimension recoverDimension;
    private DeathRules.Worth worth;
    // a rich pile that waits for the day (DeathRules.waitsForDay): where, and in which dimension
    private BlockPos waitingPile;
    private Dimension waitingPileDimension;
    // the walk back into the nether for a pile we left there, alive for as long as state.netherTrip is set
    private NetherRecoverTask netherTrip;
    // the walk back out of a nether we should not be in (WrongDimension)
    private Task homeward;
    // last tick the trip sat out a night hide, for the stage clock's credit. tripHiding lets the night branch run in the nether
    // phase for that one call
    private long tripHideTick = Long.MIN_VALUE;
    private boolean tripHiding;
    // the same bridge off a floating portal the nether phase has: the walk back arrives through a portal too
    private final EscapeRun tripEscape = new EscapeRun();
    // deaths on the books when this run was (re)started by hand, so the run wide cap only counts the new ones
    private int deathsAtStart;

    // the night in bed (NightSleep): what tonight has used up, the trip while there is one, and who we were when it began
    private final NightSleep.Budget nightBudget = new NightSleep.Budget();
    private NightSleepTask nightTrip;
    private LocalPlayer nightPlayer;
    // the evening before it: a bed in the bag by dusk (NightPrep), whether it has the wheel this tick, and the one log line
    private final NightPrep nightPrep = new NightPrep(() -> cfg.overworld);
    private boolean nightPrepHolding;
    // the last leftover/bag/standing triple nightBedReady printed, -1 before the first
    private int nightReadyKey = -1;
    private LocalPlayer nightPrepPlayer;
    // the tick a container screen came up, for how long it has held a night back (-1 when none is up)
    private long screenSince = -1;
    private long loadSince = -1;
    private final NightSleep.Said nightSaid = new NightSleep.Said();
    private final NightSleep.Said shelterNoHoleSaid = new NightSleep.Said();
    // the hole for the night after a death with no armor and no bed (NightShelter), its own once per night flag, and the look throttle
    private ShelterTask shelter;
    private final NightShelter.Once shelterOnce = new NightShelter.Once();
    // where the last try tonight gave up, so the retry digs somewhere else
    private BlockPos shelterFailedSpot;
    private long shelterFailedNight = Long.MIN_VALUE;
    // halved so the subtraction in shelterTick can't wrap around into the negatives and look like "just looked" forever
    private long shelterLookAt = Long.MIN_VALUE / 2;

    // what the user had before we changed it for the run, to hand back on stop
    private Boolean userBlastFurnace;
    private Boolean userThrowUnused;
    private Integer userFoodKeep;
    private Integer userReserve;
    // the floor we last put, so a #set from the user mid-run is not stomped on the next tick
    private Integer foodKeepPut;
    private Boolean userLadderClutch;
    private List<Item> userThrowaway;
    private List<Item> userFuels;
    private Boolean userReplant;

    // hud text is only rebuilt when something in it changed
    private PhaseHandler hudHandler;
    private String hudState;
    private int hudAttempt;
    private String hudDebug = "";
    private String hudText = "";
    private boolean hudCardOn;
    // the card on the right (GamerHudOverlay reads the snapshot, the builder keeps the rows that just finished)
    private final GamerHud hudCard = new GamerHud();
    private GamerHudState hudSnapshot;

    public GamerTask() {
        this(null);
    }

    // startAt = dev jump (#gamer phase x): begin there whatever the saved run says
    public GamerTask(GamerPhase startAt) {
        this.startAt = startAt;
        List<PhaseHandler> handlers = List.of(new GatherPhase(), new IronPhase(), new PortalPhase(), new NetherPhase(),
                new EyesPhase(), new ReturnPhase(), new LocatePhase(), new RoomPhase(), new OpenPhase(), new EndPrepPhase(),
                new DragonPhase());
        machine = new PhaseMachine(handlers, host);
    }

    public static boolean guards(Item item) {
        return GUARDED.contains(item);
    }

    private static Item[] protectedItems() {
        Item[] own = {
                Items.ENDER_EYE, Items.ENDER_PEARL, Items.BLAZE_ROD, Items.BLAZE_POWDER, Items.BUCKET, Items.WATER_BUCKET,
                Items.LAVA_BUCKET, Items.FLINT_AND_STEEL, Items.CRAFTING_TABLE, Items.OBSIDIAN, Items.SHIELD,
                Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, Items.IRON_SWORD, Items.DIAMOND_SWORD, Items.STONE_SWORD, Items.IRON_AXE, Items.DIAMOND_AXE,
                Items.IRON_INGOT, Items.RAW_IRON, Items.GOLD_INGOT, Items.DIAMOND, Items.COAL, Items.CHARCOAL, Items.FLINT,
                Items.SHEARS, Items.CARVED_PUMPKIN, Items.GOLDEN_BOOTS, Items.GOLDEN_HELMET, Items.GOLD_BLOCK, Items.GOLD_NUGGET,
                Items.RAW_GOLD, Items.LADDER,
                // same deal as the table: a station we mined to carry along is not garbage. unguarded, a lone furnace was the
                // "unused" pick, got thrown to make room, and the pickup refused our own because it would be the next thing out
                Items.FURNACE, Items.SMOKER,
                // the nether digs a lot of netherrack and the spares are all that stands between that and bare hands
                Items.STONE_PICKAXE, Items.WOODEN_PICKAXE};
        Item[] all = own;
        for (Item[] more : new Item[][]{ItemHelper.BED, ItemHelper.WOOL, ItemHelper.IRON_ARMORS, ItemHelper.DIAMOND_ARMORS, ItemHelper.GOLDEN_ARMORS}) {
            all = ArrayUtils.addAll(all, more);
        }
        return all;
    }

    // ---- task plumbing

    // runs again every time the task comes back from an interrupt, so it only puts things back that are not there
    @Override
    protected void onStart(AltoClef mod) {
        this.mod = mod;
        if (facts == null) {
            facts = new MinecraftFacts(mod);
        }
        if (!pushed) {
            applyBehaviour(mod);
        }
        // never handed back from memory: the phase that wants the portal says so on its own tick, after it looked at the bag
        mod.getExtraBaritoneSettings().canWalkOnEndPortal(false);
        // back from an interrupt: same run, same phase, the stall timer should not blame the chain that had the wheel. the
        // facts are stale in here (only engineTick refreshes them), so the forgiving happens on the first real tick
    }

    // a pause (another chain has the wheel for a moment) keeps the item protection, the tracked blocks and the settings, it
    // is exactly then that a full inventory could lose something. only a real stop lets go of them
    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        if (isInterrupting()) {
            // whoever has the wheel now has no business on the portal, and may be driving a body that just respawned with an empty bag
            mod.getExtraBaritoneSettings().canWalkOnEndPortal(false);
            if (begun && interruptedAt < 0) {
                interruptedAt = machine.now();
                // a recovery that got interrupted stops giving time back, the excuse below covers the stall
                machine.releaseHold();
            }
            softSave();
            return;
        }
        endRun(mod);
    }

    // #stop while we were paused: Task.stop does not call onStop for that, this is its replacement
    @Override
    protected void onStopWhilePaused(AltoClef mod) {
        endRun(mod);
    }

    private void endRun(AltoClef mod) {
        try {
            if (state != null && begun) {
                host.save();
                machine.exitCurrent(mod);
                showWin(mod);
            }
        } catch (RuntimeException e) {
            Debug.logInternal("gamer: onStop " + e);
        } finally {
            begun = false;
            interruptedAt = -1;
            // mob defense must not stay stood down for a trip nobody is running
            nightTrip = null;
            NightSleepHook.clear();
            dropShelter();
            // the food task must stop seeing our jobs once we are gone
            AsyncSmelting.clear();
            CookTrip.unbind();
            StationHook.clear();
            stopWatchingPlacements();
            if (respawnWatch != null) {
                respawnWatch.stop();
                respawnWatch = null;
            }
            releaseBehaviour(mod);
        }
    }

    // exceptions from the child the handler handed back are thrown by Task.tick after our onTick returned, outside every
    // net the machine has. same road as a handler that throws, and the broken child goes away so a retry builds a new one
    @Override
    public void tick(AltoClef mod, TaskChain parentChain) {
        try {
            super.tick(mod, parentChain);
        } catch (RuntimeException | StackOverflowError e) {
            // a child that recursed itself to death is still just a failed attempt, not a reason to end the whole run.
            // only that Error though, an OutOfMemoryError means something way worse is going on
            if (!begun || machine.current() == null) {
                throw e;
            }
            if (state.netherTrip != null) {
                // the trip's own child threw, not the phase. the phase machine is not ticking while the trip has the wheel,
                // so a trip left standing would throw again every tick until its six minutes ran out
                host.say("gave up: the trip hit an error (" + e.getClass().getSimpleName() + ")"
                        + (NetherRegress.kitShort(facts, cfg) ? ", rebuilding" : ", carrying on"));
                state.netherTrip = null;
                netherTrip = null;
                host.save();
                dropChild(mod);
                return;
            }
            machine.failFromChild(e);
            dropChild(mod);
        }
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof GamerTask;
    }

    @Override
    protected String toDebugString() {
        return "Beat the game";
    }

    @Override
    protected String toHudString() {
        return "Beating the game";
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        if (state == null) {
            return false;
        }
        // credits can show up between two of our ticks (the dragon dies, the screen opens), record it now so a task that
        // is finished but never stopped (altoRunsWhenIdle) still leaves a saved DONE behind
        if (begun && !machine.ended() && Minecraft.getInstance().gui.screen() instanceof WinScreen) {
            machine.finish(true);
        }
        // the win card is handed over here and not only on stop: with altoRunsWhenIdle the runner stays up and a finished task is
        // dropped without ever being stopped
        if (begun && machine.ended()) {
            showWin(mod);
        }
        return machine.ended();
    }

    // ---- behaviour and settings for the run

    private void applyBehaviour(AltoClef mod) {
        // kept so the cobble floor lands in our level and not in whichever child is on top this tick
        level = mod.getBehaviour().push();
        pushed = true;
        mod.getBehaviour().addProtectedItems(PROTECTED);
        // a furnace or smoker with a job of ours in it is not ours to dig through. one predicate in our own level, reading a snapshot
        // (baritone asks from its own thread), so a station stops being protected the moment its job is done
        mod.getBehaviour().avoidBlockBreaking(p -> jobSpots.contains(p));
        // and nothing builds on or beside a standing station: the cell over it and the four around it. breaking stays free, a box
        // there would refuse the ore and the logs next to the table
        mod.getBehaviour().avoidBlockPlacing(p -> StationBox.avoids(stationBox, p));
        mod.getBlockTracker().trackBlock(TRACKED);
        applyRunSettings();
    }

    // the cobble the stone kit still eats stays spoken for in our own level, whatever task level is on top. a craft only
    // reserves its own recipe, so the sword and the furnace got built into the staircase and mined again. the task reserves
    // are slices of this need, BotBehaviour takes the bigger of the two and does not add them. only GATHER and IRON have
    // a stone kit, and only plain cobble: the build blocks the later phases place on purpose are not ours to hold back
    private void updateStoneFloor(AltoClef mod) {
        if (level == null || state == null) {
            return;
        }
        boolean kitPhase = state.phase == GamerPhase.GATHER || state.phase == GamerPhase.IRON;
        int cobble = kitPhase ? KitPlanner.stoneFloor(facts, cfg.overworld) : 0;
        mod.getBehaviour().setReserveFloor(level, cobble > 0 ? Map.of(Items.COBBLESTONE, cobble) : Map.of());
    }

    // the same phases as the stone floor above. the furnace's own 8 are left out (KitPlanner.toolCobble), they are what a new one
    // would be made of
    private int toolCobbleOwed() {
        if (state == null || (state.phase != GamerPhase.GATHER && state.phase != GamerPhase.IRON)) {
            return 0;
        }
        return KitPlanner.toolCobble(facts, cfg.overworld);
    }

    private void releaseBehaviour(AltoClef mod) {
        adris.altoclef.util.helpers.FuelPolicy.clear();
        try {
            mod.getBlockTracker().stopTracking(TRACKED);
        } catch (RuntimeException e) {
            Debug.logInternal("gamer: stopTracking " + e);
        }
        try {
            mod.getExtraBaritoneSettings().canWalkOnEndPortal(false);
            releaseRunSettings();
        } catch (RuntimeException e) {
            Debug.logInternal("gamer: releasing settings " + e);
        }
        if (level != null) {
            // empty floor first: if the pop below lands on a different level the cobble must not stay spoken for
            mod.getBehaviour().setReserveFloor(level, Map.of());
            level = null;
        }
        if (pushed) {
            pushed = false;
            mod.getBehaviour().pop();
        }
    }

    // a blast furnace costs five iron we want for armor, and the build blocks have to be throwaway or a full inventory eats
    // the end stone we bridge with. the user's values come back on stop (put with their own value is "let go" in the registry)
    private void applyRunSettings() {
        Settings s = Baritone.settings();
        if (!SettingsOverrides.isHeld(s.altoUseBlastFurnace)) {
            userBlastFurnace = s.altoUseBlastFurnace.value;
        }
        if (!SettingsOverrides.isHeld(s.altoThrowAwayUnusedItems)) {
            userThrowUnused = s.altoThrowAwayUnusedItems.value;
        }
        if (!SettingsOverrides.isHeld(s.altoThrowawayItems)) {
            userThrowaway = s.altoThrowawayItems.value;
        }
        if (!SettingsOverrides.isHeld(s.altoFoodKeepUnits)) {
            userFoodKeep = s.altoFoodKeepUnits.value;
        }
        foodKeepPut = null;
        if (!SettingsOverrides.isHeld(s.altoReservedBuildingBlockCount)) {
            userReserve = s.altoReservedBuildingBlockCount.value;
        }
        if (!SettingsOverrides.isHeld(s.allowLadderClutch)) {
            userLadderClutch = s.allowLadderClutch.value;
        }
        if (!SettingsOverrides.isHeld(s.altoSupportedFuels)) {
            userFuels = s.altoSupportedFuels.value;
        }
        if (!SettingsOverrides.isHeld(s.replantCrops)) {
            userReplant = s.replantCrops.value;
        }
        // a replant is a carrot and a click for a field we never come back to
        SettingsOverrides.put(s.replantCrops, false);
        // the kit chops spare logs for the cook (cookFuelLogs) and WoodReserve decides which ones may burn, but the default list is
        // coal and charcoal only: the cook gate counted the logs and the smoker refused them with the meat already in it
        SettingsOverrides.put(s.altoSupportedFuels, withWoodFuel(userFuels != null ? userFuels : s.altoSupportedFuels.value));
        SettingsOverrides.put(s.altoUseBlastFurnace, false);
        SettingsOverrides.put(s.altoThrowAwayUnusedItems, true);
        // the iron kit has ladders in it for a reason, a long fall in the nether is what they are for
        SettingsOverrides.put(s.allowLadderClutch, true);
        SettingsOverrides.put(s.altoThrowawayItems, withBuildBlocks(userThrowaway != null ? userThrowaway : s.altoThrowawayItems.value));
        // junk disposal keeps this many throwaway blocks and drops the rest, so under the arrival stack it would throw away the very
        // stack the nether just dug (see BuildBlocks.reserveFor)
        int keep = userReserve != null ? userReserve : s.altoReservedBuildingBlockCount.value;
        SettingsOverrides.put(s.altoReservedBuildingBlockCount, BuildBlocks.reserveFor(keep, GamerConfigs.get().overworld.netherEntryBlocks));
    }

    // a full bag throws cobble and junk before any food, and food only when the bag is over the same floor the planner refills to
    // (minFoodUnits, the end has its own). per tick because the dimension changes under us and cfg is only read once the run begins
    private void updateFoodKeep() {
        if (cfg == null) {
            return;
        }
        int floor = facts.dimension() == Dimension.END ? cfg.end.minFoodUnits : cfg.overworld.minFoodUnits;
        if (foodKeepPut == null || foodKeepPut != floor) {
            foodKeepPut = floor;
            SettingsOverrides.put(Baritone.settings().altoFoodKeepUnits, floor);
        }
    }

    private static List<Item> withBuildBlocks(List<Item> list) {
        List<Item> out = new ArrayList<>(list);
        for (Item item : BUILD_BLOCKS) {
            if (!out.contains(item)) {
                out.add(item);
            }
        }
        return out;
    }

    // logs and planks on top of the user's fuels. FuelPolicy keeps the wood the kit still wants out of the furnace, so this only
    // lets the spare burn. the crimson stems in the log list burn nothing and stay out
    static List<Item> withWoodFuel(List<Item> list) {
        List<Item> out = new ArrayList<>(list);
        for (Item[] group : new Item[][]{ItemHelper.LOG, ItemHelper.PLANKS}) {
            for (Item item : group) {
                if (ItemHelper.isFuel(item) && !out.contains(item)) {
                    out.add(item);
                }
            }
        }
        return out;
    }

    private void releaseRunSettings() {
        Settings s = Baritone.settings();
        if (userFuels != null) {
            SettingsOverrides.put(s.altoSupportedFuels, userFuels);
        }
        if (userReplant != null) {
            SettingsOverrides.put(s.replantCrops, userReplant);
        }
        if (userBlastFurnace != null) {
            SettingsOverrides.put(s.altoUseBlastFurnace, userBlastFurnace);
        }
        if (userThrowUnused != null) {
            SettingsOverrides.put(s.altoThrowAwayUnusedItems, userThrowUnused);
        }
        if (userLadderClutch != null) {
            SettingsOverrides.put(s.allowLadderClutch, userLadderClutch);
        }
        if (userThrowaway != null) {
            SettingsOverrides.put(s.altoThrowawayItems, userThrowaway);
        }
        if (userReserve != null) {
            SettingsOverrides.put(s.altoReservedBuildingBlockCount, userReserve);
        }
        if (userFoodKeep != null) {
            SettingsOverrides.put(s.altoFoodKeepUnits, userFoodKeep);
        }
    }

    // ---- state

    // first tick with a player: the facts are real now, so the clocks and the saved run can be compared with the world
    private void beginRun(AltoClef mod) {
        cfg = GamerConfigs.get();
        // a job from a run before this one is not ours to collect
        AsyncSmelting.clear();
        // and neither is a death from before the run started (the stash outlives the task that read it last)
        DeathStash.clear();
        startedOver = false;
        winShown = false;
        loadState(mod);
        facts.useState(state);
        // the cook's backoff and station live in the run's state now, so a fresh state is a fresh cook
        CookTrip.bind(state.cook);
        // so CollectFoodTask can count the meat that is cooking without knowing what a RunState is
        AsyncSmelting.watchJobs(facts::furnaceJobs);
        // and so the smelt task knows a furnace in the tracker is the one we put down (and not a reason to place another)
        // (smokers too, the smoker task asks the same question)
        AsyncSmelting.watchFurnaces(p -> {
            RunState.Pos at = new RunState.Pos(p.getX(), p.getY(), p.getZ());
            return state.placedFurnaces.contains(at) || state.placedSmokers.contains(at);
        });
        // and so the container tasks know where our stations stand (never craft a second one within reach) and which one is coming
        // down (nothing may place or walk to it). rebuilt from the saved lists, so a relog keeps what we put down
        Workbenches.sync(state, facts.gameTime());
        // (a station the placer reports goes into the book the way a heard one does, with the save a crash must not lose)
        StationHook.install(Workbenches.source(state, facts, this::toolCobbleOwed, host::save));
        // the first read had no state to look in, so station flags and furnace jobs came out empty and the phase's first plan
        // would have been made on that. after the hook: the held flags ask it what is owed and what is coming down
        facts.refresh();
        deathsAtStart = state.deaths.size();
        lastSaveSeconds = machine.now();
        begun = true;
        // so a log of a run says which jar played it
        Debug.logInternal(BuildInfo.line());
        watchPlacements();
        if (respawnWatch != null) {
            respawnWatch.stop();
        }
        respawnWatch = new RespawnWatch(state, () -> facts.dimension().name(), host::save);
        respawnWatch.start();
        machine.begin(mod, resumedAsIs);
    }

    private void watchPlacements() {
        if (placeWatch != null) {
            return;
        }
        placeWatch = EventBus.subscribe(BlockPlaceEvent.class, evt -> {
            // the hook publishes every conducting block that appears on the client level, so this is a guess about who put
            // it there: a crafting table, furnace or smoker, in whatever dimension we are in, inside our own placing reach
            LocalPlayer player = Minecraft.getInstance().player;
            if (state == null || !begun || player == null || facts == null
                    || !(evt.blockState.is(Blocks.CRAFTING_TABLE) || evt.blockState.is(Blocks.FURNACE) || evt.blockState.is(Blocks.SMOKER)
                    || isJobBlock(evt.blockState))) {
                return;
            }
            RunState.Pos pos = new RunState.Pos(evt.blockPos.getX(), evt.blockPos.getY(), evt.blockPos.getZ());
            if (!WorkbenchRules.placedByUs(player.getX(), player.getEyeY(), player.getZ(), pos)) {
                return;
            }
            if (isJobBlock(evt.blockState)) {
                // VillageLoot must not take a blast furnace we crafted for a village
                Workbenches.addOnce(state.placedJobBlocks, pos);
                return;
            }
            // the registry takes it from here (a village's smoker never gets this far: it was not placed within reach of us)
            StationHook.Kind kind = evt.blockState.is(Blocks.CRAFTING_TABLE) ? StationHook.Kind.TABLE
                    : evt.blockState.is(Blocks.FURNACE) ? StationHook.Kind.FURNACE : StationHook.Kind.SMOKER;
            if (Workbenches.record(state, kind, pos, facts.dimension().name(), facts.gameTime())) {
                // a crash or a disconnect inside the autosave window must not lose the only record of a station we stood next to
                host.save();
            }
        });
    }

    private static boolean isJobBlock(BlockState block) {
        return block.is(Blocks.BLAST_FURNACE) || block.is(Blocks.GRINDSTONE) || block.is(Blocks.SMITHING_TABLE);
    }

    // a bed gone from where we respawn is forgotten, and a night bed that holds the respawn on the stronghold leg stays (a trip that
    // is still running adopts it itself when it wraps up, taking its bed from under it mid-sleep would break the lift)
    private void trackRespawn(AltoClef mod) {
        if (respawnWatch != null) {
            respawnWatch.tick(mod.getWorld());
        }
        if (nightTrip == null && Respawn.adoptNightBed(state, facts.count(ItemHelper.BED), cfg.end.beds)) {
            host.save();
        }
    }

    private void stopWatchingPlacements() {
        if (placeWatch != null) {
            EventBus.unsubscribe(placeWatch);
            placeWatch = null;
        }
    }

    private void loadState(AltoClef mod) {
        statePath = RunStateStore.resolvePath(mod);
        if (RunStateStore.isSharedFallback(statePath)) {
            host.say("No world folder to save into yet, the run state goes to a file shared by all worlds");
        }
        RunStateStore.Loaded loaded = RunStateStore.load(statePath, RunStateStore.fingerprint());
        state = loaded.state();
        boolean resumed = loaded.resumed();
        resumedAsIs = false;
        if (resumed && state.phaseEnteredGameTime > facts.gameTime() + CLOCK_SLACK_TICKS) {
            // game time only ever goes forward inside one world, so this file was written in another one (or the same one
            // before it was reset). not ours to resume
            host.say("The saved run is from a world that has been reset, starting over");
            String fingerprint = state.fingerprint;
            state = new RunState();
            state.fingerprint = fingerprint;
            resumed = false;
        }
        if (state.startedEpochMs == 0) {
            state.startedEpochMs = System.currentTimeMillis();
            state.startedGameTime = facts.gameTime();
        }
        if (state.phase == GamerPhase.STUCK) {
            // never written by us, but a hand edit could, and there is no handler for it
            state.phase = GamerPhase.GATHER;
        }
        if (startAt != null) {
            jumpTo(startAt);
        } else if (resumed) {
            resumeSaved();
        }
    }

    private void jumpTo(GamerPhase phase) {
        state.phase = phase;
        // a jump starts a new run as far as the win card's total goes, or a jump to the dragon would show the hours since GATHER
        state.startedGameTime = facts.gameTime();
        state.stuck = false;
        state.stuckReason = "";
        state.finished = false;
        // a dev jump is the user taking the wheel, a half done trip from before it is not theirs
        state.netherTrip = null;
        netherTrip = null;
        state.phaseAttempts.put(phase.name(), 1);
        state.deathsThisPhase = 0;
        state.regressCounts.clear();
        state.restartedThisPhase = false;
        host.say("Starting at: " + phase.hud());
    }

    private void resumeSaved() {
        if (state.finished || state.phase == GamerPhase.DONE) {
            startedOver = true;
            state.phase = GamerPhase.DONE;
            state.finished = true;
            host.say("This world's run is already finished. #gamer reset starts a new one.");
            return;
        }
        if (state.stuck) {
            host.say("Resuming after getting stuck (" + state.stuckReason + ")");
            state.stuck = false;
            state.stuckReason = "";
            state.deathsThisPhase = 0;
            state.regressCounts.clear();
            state.phaseAttempts.put(state.phase.name(), 1);
            state.restartedThisPhase = false;
        } else {
            host.say("Resuming at: " + state.phase.hud());
            resumedAsIs = true;
        }
        if (state.attemptsOf(state.phase) < 1) {
            state.phaseAttempts.put(state.phase.name(), 1);
        }
    }

    // every ~20 s, and only when something changed: handlers poke the state without telling us
    private void autosave(double now) {
        if (now - lastSaveSeconds < SAVE_EVERY_SECONDS) {
            return;
        }
        lastSaveSeconds = now;
        host.save();
    }

    // an interrupt storm (mob defense on and off, on and off) must not be a file write each
    private void softSave() {
        if (state != null && begun && System.currentTimeMillis() - lastWriteMillis >= INTERRUPT_SAVE_MILLIS) {
            host.save();
        }
    }

    // ---- the tick

    @Override
    protected Task onTick(AltoClef mod) {
        try {
            return engineTick(mod);
        } catch (RuntimeException e) {
            // the engine itself must not take the bridge's error strikes, a bug here ends the run with a saved state
            Debug.logWarning("gamer engine error: " + e);
            e.printStackTrace();
            if (begun) {
                machine.stuck("the engine hit an error (" + e + ")");
            }
            return null;
        }
    }

    // the blocks of this dimension's furnace jobs, a fresh immutable set each tick
    private volatile Set<BlockPos> jobSpots = Set.of();
    // the cells around the standing stations of this dimension (StationBox), rebuilt only when the stations change
    private volatile LongSet stationBox = LongSets.EMPTY_SET;
    private List<BlockPos> boxedStations = List.of();

    // the stations of ours that stand in this dimension, from the registry (RunState.benches). one being picked up is not in it:
    // the pickup may need to build its way to it
    static List<BlockPos> standingStations(RunState state, String dimension) {
        if (state == null || state.benches.isEmpty()) {
            return List.of();
        }
        List<BlockPos> out = new ArrayList<>();
        for (Bench b : state.benches) {
            if (b.dimension.equals(dimension) && b.state != Bench.State.PICKING_UP && b.state != Bench.State.IN_BAG) {
                out.add(new BlockPos(b.pos.x, b.pos.y, b.pos.z));
            }
        }
        return out;
    }

    private void refreshStationBox(String dimension) {
        List<BlockPos> stations = standingStations(state, dimension);
        if (!stations.equals(boxedStations)) {
            boxedStations = stations;
            stationBox = StationBox.offLimits(stations);
        }
    }

    static Set<BlockPos> spotsOf(RunState state, String dimension) {
        if (state == null || state.furnaceJobs.isEmpty()) {
            return Set.of();
        }
        Set<BlockPos> out = new HashSet<>();
        for (RunState.FurnaceJob job : state.furnaceJobs) {
            if (dimension.equals(job.dimension)) {
                out.add(new BlockPos(job.pos.x, job.pos.y, job.pos.z));
            }
        }
        return Set.copyOf(out);
    }

    private Task engineTick(AltoClef mod) {
        if (!facts.refresh()) {
            // no player yet (an idle command on join): the clocks must not start from zero
            return null;
        }
        if (!begun) {
            beginRun(mod);
        }
        if (machine.ended()) {
            return null;
        }
        cfg = GamerConfigs.get();
        jobSpots = spotsOf(state, facts.dimension().name());
        refreshStationBox(facts.dimension().name());
        double now = machine.now();
        if (interruptedAt >= 0) {
            // first fresh tick after another chain had the wheel. forgiven, not reset: a reset made two chains trading the
            // wheel every few seconds look like progress forever
            machine.excuseStall(now - interruptedAt);
            interruptedAt = -1;
        }
        state.runTicks++;
        takeLoadedFurnaces();
        // the furnaces read this: wood the kit still wants is not fuel. the handlers get the same budget (machine.wood)
        WoodBudget wood = WoodBudget.of(facts, cfg.overworld, cfg.end.beds, state.phase);
        WoodReserve.apply(wood);
        machine.giveWood(wood);
        updateStoneFloor(mod);
        updateFoodKeep();
        if (facts.creditsShown()) {
            machine.finish(true);
            return null;
        }
        trackDeaths(mod);
        trackRespawn(mod);
        trackNightGates();
        if (machine.ended()) {
            return null;
        }
        // dead and not respawned yet: the bag reads empty, and a regress decided off it sent the run back to gather before the
        // respawn got to start the walk back for our things. nothing to decide until the new body shows up
        if (deadSeen) {
            machine.observe();
            machine.holdClocks();
            return null;
        }
        if (facts.dimension() == Dimension.OVERWORLD) {
            danger.tick(mod, state);
        }
        // a new player is a death: the night trip died with the old one, and its stand down must not outlive it into the walk back
        // for our things (the recovery branches below return before the night branch is asked)
        if (nightTrip != null && mod.getPlayer() != nightPlayer) {
            // (a dimension change makes a new player too, and that one is a failed try, not a clean slate)
            if (NightSleep.droppedByDimension(nightTrip.isFinished(mod), facts.dimension() == Dimension.OVERWORLD)) {
                nightBudget.failed(facts.gameTime() * 0.05, cfg.overworld.nightCooldownSeconds);
            }
            dropNightTrip();
        }
        if (nightPrepPlayer != mod.getPlayer()) {
            // a new player is a death: whatever the evening's prep was doing went with the old one
            nightPrepPlayer = mod.getPlayer();
            nightPrep.drop();
            // (and so did the hole: a death in it is a respawn up top, the new body starts from the recovery)
            dropShelter();
        }
        startDeferredTrip();
        startWaitingPile();
        // a naked respawn at night hides first, the trip picks up where it was once the night is over. only when the night branch
        // really has something to do (hole, bed): at dusk it has not, and the walk for blocks goes on until it does. a hole or bed
        // trip already going is seen through to the end, dawn or not
        Task hideNight = null;
        if (state.netherTrip != null && NetherTripRules.hides(NetherTripRules.Stage.parse(state.netherTrip.stage), facts.dimension(),
                darkAndBare(mod) || nightTrip != null || shelter != null)) {
            tripHiding = true;
            hideNight = nightSleepTick(mod);
            tripHiding = false;
        }
        if (hideNight != null) {
            // the stage clock sits out the hide, or pick() would time out the moment the trip came back
            state.netherTrip.stageTick += NetherTripRules.hiddenCredit(tripHideTick, facts.gameTime());
            tripHideTick = facts.gameTime();
            machine.observe();
            machine.holdClocks();
            setDebugState("Hiding from the night before the trip.", "Hiding from the night");
            updateRecoveryHud("Hiding from the night");
            autosave(now);
            return hideNight;
        }
        if (state.netherTrip != null) {
            // whatever stretch the hide lost to a fight or the dawn is not the stage's time either
            state.netherTrip.stageTick += NetherTripRules.hiddenCredit(tripHideTick, facts.gameTime());
        }
        tripHideTick = Long.MIN_VALUE;
        if (state.netherTrip != null) {
            dropShelter();
            dropNightTrip();
            // same deal as the recovery below: the trip is not the phase's time and not its fault. the task clears the
            // state when it is over, and the machine has the next tick
            if (netherTrip == null) {
                netherTrip = new NetherRecoverTask(state, facts, () -> cfg, host::say, host::save, machine);
            }
            machine.observe();
            machine.holdClocks();
            setDebugState("Going back to the nether for our stuff.", "Getting our stuff back");
            updateRecoveryHud(tripWords());
            autosave(now);
            Task off = tripEscape.step(mod, facts.dimension() == Dimension.NETHER, facts.gameTime(), host::say, what -> machine.progress());
            return off != null ? off : netherTrip;
        }
        netherTrip = null;
        tripEscape.reset();
        if (recoverStillWanted(now)) {
            dropNightTrip();
            dropShelter();
            // the recovery is not the phase's time and not its fault: both clocks wait for it (a stall is forgiven the same way)
            machine.observe();
            machine.holdClocks();
            setDebugState("Recovering items after a death.", "Getting our stuff back");
            updateRecoveryHud(HudRules.recoveryWords(blocksTo(recoverPos)));
            autosave(now);
            if (recoverHeals()) {
                setDebugState("Healing up before the walk to our stuff.", "Healing up");
                return healUp;
            }
            return recover;
        }
        Task night = nightSleepTick(mod);
        if (night != null) {
            // same deal again: a night in bed is not the phase's time and not its fault
            machine.observe();
            machine.holdClocks();
            String words = night == shelter ? "Hiding from the night" : nightPrepHolding ? "Getting a bed ready for the night" : "Sleeping through the night";
            setDebugState(words + ".", words);
            updateRecoveryHud(words);
            autosave(now);
            return night;
        }
        // nobody asked for the nether and the phase is an overworld one: back through the portal we are most likely standing in
        if (WrongDimension.fires(state.phase, facts.dimension())) {
            if (homeward == null) {
                Debug.logInternal("in the nether during " + state.phase + " with no trip that wants it, heading back through a portal");
                homeward = new EnterNetherPortalTask(Dimension.OVERWORLD);
            }
            machine.observe();
            machine.holdClocks();
            setDebugState("Wrong dimension, going home.", "Heading home");
            autosave(now);
            return homeward;
        }
        homeward = null;
        Task child = machine.tick(mod);
        if (!machine.ended()) {
            child = sweepBenches(mod, child);
            updateHud(machine.current());
        }
        autosave(now);
        return child;
    }

    // GATHER, IRON and PORTAL run the station pickup with their own plan (PhaseHandler.ownsBenches). any other phase can still
    // put a table down (a craft is a craft), and nobody would ever come back for it: the same rules with no plan take it back
    // once its screen has been shut for a second. not in the End, nothing there is worth stopping a fight for
    private Task sweepBenches(AltoClef mod, Task child) {
        PhaseHandler h = machine.current();
        if (h == null || h.ownsBenches() || facts.dimension() == Dimension.END) {
            return child;
        }
        Task pickup = benches.sweep(mod, machine);
        return pickup != null ? pickup : child;
    }

    // the smelt tasks cannot see our state, a furnace they loaded and walked away from is waiting in a queue for us
    private void takeLoadedFurnaces() {
        List<RunState.FurnaceJob> loaded = AsyncSmelting.drain();
        for (RunState.FurnaceJob job : loaded) {
            FurnaceJobs.record(state.furnaceJobs, job);
            if (job.stranded) {
                host.say("Left " + job.count + " " + job.input.replace('_', ' ') + " in the " + job.kind + ", not lit, going back for it");
                continue;
            }
            host.say((job.unitsEach > 0 ? "Cooking in the background (" : "Smelting in the background (") + job.count + " "
                    + job.output.replace('_', ' ') + ", ~" + (job.doneTick - job.startTick) / 20 + "s)");
        }
        if (!loaded.isEmpty()) {
            // lit and cooking: the job is the memory now, the early load is no longer in flight. only if it was the iron
            if (EarlyIronPick.endsLoad(loaded)) {
                state.earlyLoadTick = -1;
            }
            host.save();
        }
    }

    private void updateHud(PhaseHandler h) {
        String sub = h.hudState();
        int attempt = machine.attempt();
        // with the card on, the tree's line is just the phase: the step, the kit and the clocks are on the card
        boolean card = Baritone.settings().altoGamerHud.value;
        if (h != hudHandler || attempt != hudAttempt || card != hudCardOn || (sub == null ? hudState != null : !sub.equals(hudState))) {
            hudHandler = h;
            hudState = sub;
            hudAttempt = attempt;
            hudCardOn = card;
            hudDebug = "Phase " + state.phase + ", attempt " + attempt;
            hudText = sub == null || card ? h.hud() : h.hud() + ": " + sub;
        }
        setDebugState(hudDebug, hudText);
        hudSnapshot = card ? hudCard.build(mod, machine, state, facts, cfg, h) : null;
    }

    // a recovery (the walk back for a death pile, or the trip into the nether for one) never reaches updateHud, so the card is
    // told what it is doing here instead. the kit rows are gone while it lasts, see GamerHud.recovering
    private void updateRecoveryHud(String action) {
        hudSnapshot = Baritone.settings().altoGamerHud.value
                ? hudCard.recovering(state, facts, cfg, machine.foodForCard(), machine.secondsInPhase(), machine.attempt(), action) : null;
    }

    // the nether trip's stage in words. the pile is only a distance worth showing once we are in the nether with it (the portal
    // and the dirt stages are in the overworld, where the pile's x and z mean nothing)
    private String tripWords() {
        RunState.NetherTrip trip = state.netherTrip;
        NetherTripRules.Stage stage = NetherTripRules.Stage.parse(trip.stage);
        boolean there = stage == NetherTripRules.Stage.WALK || stage == NetherTripRules.Stage.RECOVER;
        return HudRules.tripWords(stage, there ? blocksTo(trip.pile.x, trip.pile.y, trip.pile.z) : -1);
    }

    private int blocksTo(BlockPos at) {
        return blocksTo(at.getX(), at.getY(), at.getZ());
    }

    private int blocksTo(int x, int y, int z) {
        return HudRules.blocksAway(facts.x() - x, facts.y() - y, facts.z() - z);
    }

    // the run ended DONE: the task goes away with the runner and nothing of ours draws once it is idle, so the last card is
    // handed to the overlay, which keeps it up for a while (HudRules.WIN_LINGER_MILLIS). STUCK hides at once as it always did, and
    // so does a run that was already over when this one started: there is nothing in that to show off
    private void showWin(AltoClef mod) {
        if (winShown || !state.finished || state.phase != GamerPhase.DONE || startedOver || !Baritone.settings().altoGamerHud.value) {
            return;
        }
        winShown = true;
        double total = HudRules.runSeconds(state.startedGameTime, facts.gameTime(), state.runTicks);
        mod.getGamerHudOverlay().win(GamerHud.won(hudSnapshot, cfg, total));
    }

    // the phase the run is in, null before it has begun. a plain read for the chains that behave differently per phase
    public GamerPhase phase() {
        return begun && state != null ? state.phase : null;
    }

    // the card's numbers as of the last engine tick, null while there is no run on the screen worth a card
    public GamerHudState hudSnapshot() {
        return begun && !machine.ended() ? hudSnapshot : null;
    }

    // the gamer group of the vitals line. the card's numbers when there is a card, else just the phase and attempt (the
    // card setting being off is no reason for the log to go blind). null before the run has begun
    public Vitals.Gamer vitals() {
        if (!begun || state == null) {
            return null;
        }
        GamerHudState s = hudSnapshot();
        if (s == null) {
            return new Vitals.Gamer(state.phase.name(), machine.attempt(), null, 0, -1, -1);
        }
        String head = null;
        int want = 0;
        for (GamerHudState.KitRow row : s.rows()) {
            if (row.working()) {
                head = row.catalogueName();
                want = row.want();
                break;
            }
        }
        return new Vitals.Gamer(s.phase().name(), s.attempt(), head, want, s.foodUnits(), s.foodTarget());
    }

    // ---- deaths

    // respawning swaps the LocalPlayer (and so does changing dimension), so a new instance only means a death when something
    // saw one. our own tick is the weakest witness: mob defense holds the wheel through a fight, the death and the death
    // screen, and we never get a tick. the death packet writes the stash whoever holds the wheel, and the old instance is
    // the last resort (a respawn only reads it, so it keeps its zero health)
    private void trackDeaths(AltoClef mod) {
        LocalPlayer player = mod.getPlayer();
        if (player == null) {
            return;
        }
        if (player != lastPlayer) {
            if (lastPlayer != null) {
                swapped(lastPlayer);
            }
            lastPlayer = player;
            deadSeen = false;
        }
        boolean dying = player.isDeadOrDying() || Minecraft.getInstance().gui.screen() instanceof DeathScreen;
        if (!deadSeen && dying) {
            deadSeen = true;
            adopt(DeathStash.snapshot(player));
        }
        if (!dying) {
            // after the swap above on purpose: the respawn already read the last living bag, this one is the new body's
            worth = DeathRules.worth(facts);
        }
    }

    private void swapped(LocalPlayer old) {
        // taken whatever happens next, a stash left lying around would be the next portal's death
        DeathStash.Death stashed = DeathStash.take();
        DeathRules.Source source = DeathRules.source(deadSeen, stashed != null, old.isDeadOrDying());
        switch (source) {
            case STASH -> adopt(stashed);
            case OLD_INSTANCE -> adopt(DeathStash.snapshot(old));
            case TICK -> {
            }
            default -> {
                // a dimension change swaps the instance too, with the old one alive and nothing in the stash
                Debug.logInternal("new player instance with no death seen (a portal or a reconnect), the old one had " + Math.round(old.getHealth())
                        + " hp, now in " + facts.dimension() + ", no death counted");
                return;
            }
        }
        onRespawn(source);
    }

    // where it happened comes from the record and never from where we are now, a nether death respawns in the overworld
    private void adopt(DeathStash.Death death) {
        deathDimension = death.dimension();
        deathPos = new BlockPos(death.x(), death.y(), death.z());
        deathGameTime = death.gameTime();
        // lava eats the pile and the void takes it, so those are the two we write down
        deathCause = NetherTripRules.cause(death.inLava(), death.outOfWorld());
    }

    private void onRespawn(DeathRules.Source seenBy) {
        // first thing, before this method logs and the lead up scrolls off the end of the buffer
        Trace.dump("respawn");
        PhaseHandler live = machine.current();
        if (live != null) {
            live.onRespawn();
        }
        // the walk in was decided by the body that had the beds. this one has an empty bag, and the recovery walks wherever it likes
        // before END_PREP gets another look
        mod.getExtraBaritoneSettings().canWalkOnEndPortal(false);
        // the same number the recovery decides on, and who told us about it
        double blocks = flatBlocksTo(deathPos);
        Debug.logInternal("respawned after dying in " + deathDimension + " at " + deathPos.toShortString() + ", now in " + facts.dimension()
                + " " + Math.round(blocks) + " blocks away (recovery goes up to " + DeathRules.reachFor(worth, cfg.overworld.refillStartFoodUnits, cfg.death.recoverReach)
                + ", seen by " + seenBy.name().toLowerCase(Locale.ROOT).replace('_', ' ') + ")");
        // all read before this death goes in the book and before the old recovery is dropped. only this run's deaths count
        // for a spot, a hand restart is a clean slate
        int killedBefore = DeathRules.killedHere(state.deaths.subList(Math.min(deathsAtStart, state.deaths.size()), state.deaths.size()),
                deathDimension, deathPos.getX(), deathPos.getZ());
        // a recovery that finished is only dropped on a tick of ours, and we may not have had one since
        boolean wasRecovering = recover != null && !recover.isFinished(mod);
        boolean diedAtThePile = wasRecovering && recoverPos != null && deathDimension == recoverDimension
                && DeathRules.sameSpot(deathPos.getX(), deathPos.getZ(), recoverPos.getX(), recoverPos.getZ());
        RunState.Death death = new RunState.Death();
        death.dimension = deathDimension.name();
        death.x = deathPos.getX();
        death.y = deathPos.getY();
        death.z = deathPos.getZ();
        death.gameTime = deathGameTime;
        death.phase = state.phase.name();
        death.cause = deathCause.name().toLowerCase(Locale.ROOT);
        state.deaths.add(death);
        state.deathsThisPhase++;
        WoolHunt.onDeath(state);
        // dying on the way to a pile is the end of that trip: the second pile is not worth a third life
        boolean wasOnTrip = state.netherTrip != null;
        if (wasOnTrip) {
            host.say("gave up: died on the way, rebuilding");
            state.netherTrip = null;
            netherTrip = null;
        }
        host.save();
        recover = null;
        if (state.deathsThisPhase >= cfg.death.maxPerPhase) {
            host.say(DeathRules.leaving("died " + state.deathsThisPhase + " times in this phase, the run is stuck"));
            machine.stuck("died " + state.deathsThisPhase + " times in this phase");
            return;
        }
        if (state.deaths.size() - deathsAtStart >= cfg.death.maxTotal) {
            host.say(DeathRules.leaving("died " + (state.deaths.size() - deathsAtStart) + " times since the run was started, the run is stuck"));
            machine.stuck("died " + (state.deaths.size() - deathsAtStart) + " times since the run was started");
            return;
        }
        if (wasRecovering) {
            // the older pile is not worth another life either, and the new one is judged below on its own
            host.say("death: died on the way to the last pile, giving that one up");
        }
        // same night the shelter uses, with a minute of walking ahead of it counted (the shelter is blocked while we recover)
        boolean darkAndBare = darkAndBare(mod);
        String why = DeathRules.refuse(facts.dimension() == deathDimension, blocks,
                DeathRules.reachFor(worth, cfg.overworld.refillStartFoodUnits, cfg.death.recoverReach), worth,
                cfg.overworld.refillStartFoodUnits, killedBefore, diedAtThePile, darkAndBare);
        waitingPile = null;
        if (why == null && DeathRules.waitsForDay(darkAndBare, worth, cfg.overworld.refillStartFoodUnits,
                DeathRules.pileUnloaded(facts.x() - deathPos.getX(), facts.z() - deathPos.getZ(), cfg.death.tickingBlocks))) {
            host.say(DeathRules.waiting(blocks, worth));
            waitingPile = deathPos;
            waitingPileDimension = deathDimension;
        } else if (why == null) {
            startRecovery(deathPos, blocks, deathGameTime);
        } else if (NetherTripRules.applies(deathDimension, facts.dimension())) {
            // the nether has its own trip and its own words
            if (!wasOnTrip) {
                maybeGoBackToTheNether();
            }
        } else {
            host.say(DeathRules.leaving(why));
        }
    }

    private void startRecovery(BlockPos pile, double blocks, long sinceTicks) {
        host.say(DeathRules.going(blocks, worth));
        recoverHolding = false;
        recoverHeldTicks = 0;
        recover = new RecoverItemsTask(pile, RecoverRules.budget(RECOVER_BUDGET_SECONDS, blocks),
                () -> KitPlanner.toEquip(facts, cfg.overworld));
        recoverPos = pile;
        recoverDimension = deathDimension;
        allowance = new RecoverRules.Allowance(sinceTicks / 20.0, cfg.death.recoverSeconds);
    }

    // the pile that waited out the night: day (or armor from the rebuild) and we go, same dimension or it is let go
    private void startWaitingPile() {
        if (waitingPile == null || recover != null || darkAndBare(mod)) {
            return;
        }
        // another dimension right now: the pile keeps waiting for the next visit here, a new death is what clears it
        if (facts.dimension() != waitingPileDimension) {
            return;
        }
        BlockPos pile = waitingPile;
        Dimension where = waitingPileDimension;
        waitingPile = null;
        // (the allowance starts now, the night already ate the one from the death)
        startRecovery(pile, flatBlocksTo(pile), facts.gameTime());
        recoverDimension = where;
    }

    private boolean darkAndBare(AltoClef mod) {
        return facts.dimension() == Dimension.OVERWORLD
                && NightShelter.dark(!mod.getWorld().isBrightOutside(), mod.getWorld().getOverworldClockTime(), NightShelter.PILE_LEAD_TICKS)
                && facts.armorPoints() < NightShelter.ARMOR_LINE;
    }

    // a nether death respawns us at home, so the dimension test above says no and the whole kit used to be rebuilt. the pile
    // down there does not age while nobody is around to load it though, so it is worth a trip when the rules say so
    private void maybeGoBackToTheNether() {
        if (!NetherTripRules.applies(deathDimension, facts.dimension())) {
            return;
        }
        // a new nether pile is the one that matters, an older deferred one is stale
        state.deferredPile = null;
        String where = deathPos.getX() + " " + deathPos.getY() + " " + deathPos.getZ();
        String why = NetherTripRules.refuse(deathCause, state.overworldPortal != null,
                cfg.death.netherRecover && cfg.death.netherTripSeconds > 0, false,
                NetherTripRules.tried(state.lastTripPile, deathPos.getX(), deathPos.getZ()),
                !NetherRegress.kitShort(facts, cfg));
        if (why != null) {
            host.say("nether death at " + where + ", not going back: " + why + ", rebuilding");
            return;
        }
        RunState.Pos pile = new RunState.Pos(deathPos.getX(), deathPos.getY(), deathPos.getZ());
        double far = flatBlocksFromHome(pile);
        if (NetherTripRules.defers(far) && !NetherTripRules.defersFor(worth)) {
            // a pile with no eye parts in it is a stick and a bucket the rebuild makes anyway, and the second walk to it goes
            // through whatever killed us the first time
            host.say("nether death at " + where + ", " + (int) far + " blocks from the portal with no rods, powder, pearls or eyes in it, "
                    + "letting the pile go, rebuilding");
            return;
        }
        if (NetherTripRules.defers(far)) {
            // naked and that far out is how the last few trips died, so the pile waits for a kit and the rebuild runs
            state.deferredPile = pile;
            host.save();
            host.say("nether death at " + where + ", " + (int) far + " blocks from the portal, too far to walk naked: "
                    + "deferring the pile until the kit is back, rebuilding");
            return;
        }
        state.netherTrip = new RunState.NetherTrip(pile, NetherTripRules.Stage.BLOCKS.name(), facts.gameTime());
        state.lastTripPile = pile;
        host.save();
        host.say("nether death at " + where + ", going back for it");
    }

    // how far a nether pile is from the nether side of the home portal, flat. the overworld portal over eight when the nether
    // side was never recorded. no portal known at all reads as zero (refuse already turned that death down)
    private double flatBlocksFromHome(RunState.Pos pile) {
        double hx;
        double hz;
        if (state.netherPortal != null) {
            hx = state.netherPortal.x;
            hz = state.netherPortal.z;
        } else if (state.overworldPortal != null) {
            hx = state.overworldPortal.x / 8.0;
            hz = state.overworldPortal.z / 8.0;
        } else {
            return 0;
        }
        return Math.hypot(pile.x - hx, pile.z - hz);
    }

    // the pile we put off, once we stand in the nether with the kit on: start the trip at the walk. a pile out of reach is let go
    private void startDeferredTrip() {
        RunState.Pos pile = state.deferredPile;
        if (pile == null || state.netherTrip != null || facts.dimension() != Dimension.NETHER) {
            return;
        }
        boolean kitShort = NetherRegress.kitShort(facts, cfg);
        double far = Math.hypot(pile.x - facts.x(), pile.z - facts.z());
        if (NetherTripRules.dropsDeferred(facts.dimension(), kitShort, far)) {
            state.deferredPile = null;
            host.save();
            host.say("deferred pile at " + pile.x + " " + pile.y + " " + pile.z + " is " + (int) far + " blocks away, letting it go");
        } else if (NetherTripRules.startsDeferred(facts.dimension(), kitShort, far)) {
            state.deferredPile = null;
            state.lastTripPile = pile;
            state.netherTrip = new RunState.NetherTrip(pile, NetherTripRules.Stage.WALK.name(), facts.gameTime());
            host.save();
            host.say("kit is on and the deferred pile at " + pile.x + " " + pile.y + " " + pile.z + " is " + (int) far
                    + " blocks away, going for it");
        }
    }

    // flat, height does not make a walk longer
    private double flatBlocksTo(BlockPos at) {
        double dx = facts.x() - at.getX();
        double dz = facts.z() - at.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    // the walk is the part before the pile's neighbourhood. past that the task is chasing drops that are despawning, and it
    // does not stand for anyone
    private boolean recoverWalking() {
        return recoverPos != null && flatBlocksTo(recoverPos) > RecoverRules.DROP_RADIUS;
    }

    // hurt on the way to the pile, nobody shooting: stand and heal instead of dropping into the cave at half a bar. the pile
    // keeps aging while we stand (it is loaded in range), so the allowance is not credited, the minute cap is the price
    private boolean recoverHeals() {
        long t = facts.gameTime();
        boolean hold = recoverWalking() && RecoverRules.walkHold(mod.getPlayer().getHealth(), NetherRecoverTask.shooterSees(mod),
                mod.getFoodChain().ticksSinceBodyHit(mod), CombatRules.canRegen(mod.getPlayer().getFoodData().getFoodLevel(),
                        mod.getFoodChain().hasHealFood()), recoverHolding, recoverHeldTicks);
        if (hold) {
            long gap = recoverHolding ? Math.max(0, Math.min(t - recoverHoldTick, 2)) : 0;
            recoverHeldTicks += gap;
            recoverHoldTick = t;
        }
        recoverHolding = hold;
        return hold;
    }

    private boolean recoverStillWanted(double now) {
        if (recover == null) {
            recoverHolding = false;
            recoverHeldTicks = 0;
            return false;
        }
        // every way out says so once. a task that finished itself already said why (most of its ways out log, the no way and no pickaxe ones just say so on the hud), so that one
        // is quiet here. recoverPos and not deathPos: a death on the way has already written the next pile over deathPos
        String over = null;
        if (recover.isFinished(mod)) {
            recover = null;
            return false;
        } else if (facts.dimension() != recoverDimension) {
            over = "we left the dimension";
        } else if (recoverWalking() && RecoverRules.walkAbort(mod.getPlayer().getHealth(), mod.getFoodChain().ticksSinceBodyHit(mod))) {
            over = "hurt and under fire on the way";
        } else if (allowance == null || allowance.expired(now, flatBlocksTo(recoverPos))) {
            over = "it has been lying there too long";
        }
        if (over != null) {
            host.say("death: no longer going for the pile, " + over);
            recover = null;
            return false;
        }
        return true;
    }

    // ---- the night (altoSleepAtNight, NightSleep)

    // the task to run instead of the phase, or null. a trip that is running keeps the wheel until it says it is over, a new one
    // starts when NightSleep.decide says so. mob defense hears about the stage through the hook, every tick
    private Task nightSleepTick(AltoClef mod) {
        double seconds = facts.gameTime() * 0.05;
        nightBudget.roll(NightSleep.nightOf(mod.getWorld().getOverworldClockTime()));
        if (nightTrip != null) {
            if (nightTrip.isFinished(mod) || facts.dimension() != Dimension.OVERWORLD) {
                // (the bed a dead or dropped trip left is picked up by a cleanup trip)
                if (NightSleep.droppedByDimension(nightTrip.isFinished(mod), facts.dimension() == Dimension.OVERWORLD)) {
                    nightBudget.failed(seconds, cfg.overworld.nightCooldownSeconds);
                }
                dropNightTrip();
                machine.releaseHold();
                return null;
            }
            NightSleepHook.set(nightTrip.stage(), mod.getWorld().getGameTime());
            return nightTrip;
        }
        NightSleepHook.clear();
        if (!Baritone.settings().altoSleepAtNight.value || facts.dimension() != Dimension.OVERWORLD) {
            nightPrep.drop();
            dropShelter();
            return null;
        }
        boolean leftover = leftoverNightBed(mod);
        boolean windowOpen = !mod.getWorld().isBrightOutside();
        // the evening's own tasks open screens (a table, the inventory), which are not a reason to stop them halfway
        NightSleep.Busy busy = nightBusy(mod, nightPrep.running());
        boolean fighting = nightFighting(mod);
        // before decide on purpose: low health and a spent bed trip answer QUIET/LOW_HEALTH there, and neither is a reason to
        // stay out on the surface with no armor
        Task hole = shelterTick(mod, leftover, windowOpen, busy);
        if (hole != null) {
            return hole;
        }
        boolean hurt = NightSleep.shouldAbandon(mod.getPlayer().getHealth());
        boolean spent = nightBudget.spent(cfg.overworld.nightAttempts, cfg.overworld.nightSeconds);
        boolean inPortal = WorldHelper.isStandingInPortal(mod);
        NightSleep.Inputs cheap = new NightSleep.Inputs(true, true, windowOpen, busy != NightSleep.Busy.NONE, fighting, hurt, spent,
                nightBudget.cooling(seconds), NightSleep.BedSource.BAG, leftover, inPortal);
        NightSleep.Why why = NightSleep.decide(cheap);
        if (why == NightSleep.Why.GO) {
            nightPrepDone(mod);
            BlockPos found = findNightBed(mod);
            NightSleep.BedSource source = NightSleep.bedSource(found != null, facts.count(ItemHelper.BED),
                    BedCraft.bestWoolStack(mod), BedCraft.usableWood(mod));
            boolean keepSpawn = NightSleep.keepsSpawnBed(state.phase, source, spawnBedHeld(mod));
            sayNight(mod, source == NightSleep.BedSource.NONE || keepSpawn ? NightSleep.Why.NO_BED : why, busy, source);
            if (source == NightSleep.BedSource.NONE || keepSpawn) {
                // (a prep that was still going when the window opened is over, it does not get to keep its screens all night)
                nightPrep.drop();
                return null;
            }
            startNightTrip(mod, source, found);
            return nightTrip;
        }
        if (why == NightSleep.Why.LEFTOVER) {
            nightPrepDone(mod);
            sayNight(mod, why, busy, NightSleep.BedSource.NONE);
            startNightTrip(mod, NightSleep.BedSource.NONE, null);
            return nightTrip;
        }
        // not time yet, or not allowed to go: the bed gets made ready for when it is
        NightPrep.Gate gate = new NightPrep.Gate(busy != NightSleep.Busy.PHASE && !NightSleep.keepsSpawnBed(state.phase, NightSleep.BedSource.BAG, spawnBedHeld(mod)), inPortal || (busy != NightSleep.Busy.NONE && busy != NightSleep.Busy.PHASE),
                fighting, hurt, spent, windowOpen);
        Task prep = nightPrep.tick(mod, gate, () -> nightBedReady(mod, leftover));
        sayNight(mod, why, busy, null);
        if (prep == null) {
            nightPrepDone(mod);
        }
        nightPrepHolding = prep != null;
        return prep;
    }

    // the spawn bed the night rules protect
    private boolean spawnBedHeld(AltoClef mod) {
        return spawnBedStands(mod) && Respawn.worthKeeping(state.phase, state);
    }

    // the overworld respawn bed we know of is still standing where we put it (unloaded counts as still there)
    private boolean spawnBedStands(AltoClef mod) {
        RunState.Pos at = Respawn.bed(state);
        if (at == null) {
            return false;
        }
        BlockPos pos = new BlockPos(at.x, at.y, at.z);
        // an unloaded chunk is not news, only a loaded one with no bed in it says the spawn is gone (Respawn.stale)
        return !Respawn.stale("OVERWORLD", "OVERWORLD", mod.getWorld().hasChunkAt(pos),
                mod.getWorld().getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.BedBlock);
    }

    // the hole for a night with no armor and no bed after a death (NightShelter). a running one keeps the wheel until it is over,
    // a new one starts when the trigger says so. the bed trip's budget is not touched either way
    private Task shelterTick(AltoClef mod, boolean leftover, boolean windowOpen, NightSleep.Busy busy) {
        long gameTime = mod.getWorld().getGameTime();
        long night = NightSleep.nightOf(mod.getWorld().getOverworldClockTime());
        if (shelter != null) {
            if (shelter.isFinished(mod)) {
                // a try that gave up gets one more at another spot, anything else (and every other way out) leaves the night spent
                if (shelter.gaveUp() && shelterOnce.reopen(night)) {
                    shelterFailedSpot = shelter.top();
                    shelterFailedNight = night;
                }
                dropShelter();
                machine.releaseHold();
                return null;
            }
            if (shelter.sealed()) {
                NightSleepHook.setSheltered(gameTime);
            } else {
                NightSleepHook.clearSheltered();
            }
            return shelter;
        }
        long sinceDeath = deathGameTime > 0 ? gameTime - deathGameTime : -1;
        // the cheap questions first, the bed hunt and the column scan only when everything else says yes
        // (a daytime thunderstorm opens the window too, it is dark enough to sleep but not to hide from: time of day says dusk)
        boolean dusk = NightShelter.dark(windowOpen, mod.getWorld().getOverworldClockTime(), 0);
        // only a fight that is really on counts, the heal-up hold after one is the very case for a hole
        boolean inFight = mod.getMobDefenseChain().combatMode() != CombatCommit.Mode.NONE;
        // a nether or eyes leg home for food has no bed it may use: no armor or death needed, the hole is the answer
        boolean tripNight = state.phase == GamerPhase.NETHER || state.phase == GamerPhase.EYES;
        NightShelter.Trigger t = new NightShelter.Trigger(dusk, true, facts.armorPoints(), !leftover && (tripNight || facts.count(ItemHelper.BED) == 0),
                SmeltSurface.depthBelowSky(mod), recover != null, inFight, busy != NightSleep.Busy.NONE || WorldHelper.isStandingInPortal(mod),
                shelterOnce.used(night), sinceDeath, tripNight, mod.getPlayer().getHealth(), mod.getPlayer().getFoodData().getFoodLevel(),
                mod.getFoodChain().hasHealFood());
        if (!NightShelter.wanted(t) || gameTime - shelterLookAt < 20) {
            return null;
        }
        shelterLookAt = gameTime;
        NightSleep.BedSource source = NightSleep.bedSource(findNightBed(mod) != null, facts.count(ItemHelper.BED), BedCraft.bestWoolStack(mod),
                BedCraft.usableWood(mod));
        // a bed that keepsSpawnBed would not let it use is no bed for tonight
        boolean noBed = NightShelter.noBedTonight(state.phase, source, spawnBedHeld(mod));
        BlockPos skipSpot = shelterFailedNight == night ? shelterFailedSpot : null;
        BlockPos spot = noBed ? ShelterTask.pick(mod, skipSpot) : null;
        if (spot == null) {
            // (the depth gate is already inside wanted, so the reasons left are the ground and the columns)
            if (noBed) {
                String why = ShelterTask.whyNoColumn(mod, skipSpot);
                // the counts shift as we walk, so the key is the text with the nonzero numbers blanked: it says again when the failing mix changes
                // (the block ids are in the key too, and those change every few steps on mixed ground, hence the gap)
                if (shelterNoHoleSaid.worthSaying(why.replaceAll("[1-9]\\d*", "N"), gameTime, 100)) {
                    Debug.logInternal("night: shelter wanted, no hole (" + why + ")");
                }
            }
            return null;
        }
        shelterOnce.mark(night);
        nightPrep.drop();
        nightPrepDone(mod);
        shelter = new ShelterTask(spot, night);
        return shelter;
    }

    private void dropShelter() {
        shelter = null;
        NightSleepHook.clearSheltered();
    }

    // the prep had the wheel and does not any more: the phase gets its clocks back, same as when a trip ends
    private void nightPrepDone(AltoClef mod) {
        if (nightPrepHolding) {
            nightPrepHolding = false;
            machine.releaseHold();
        }
    }

    // one line when the answer to "why is it not in bed" changes, and only through the evening and the night (a screen opening
    // in the middle of the day is nobody's business). the key is what changed, the rest rides along
    private void sayNight(AltoClef mod, NightSleep.Why why, NightSleep.Busy busy, NightSleep.BedSource source) {
        long timeOfDay = Math.floorMod(mod.getWorld().getOverworldClockTime(), NightSleep.DAY_LENGTH);
        long lead = Math.max(cfg.overworld.nightCraftLeadTicks, cfg.overworld.nightWoolLeadTicks);
        boolean evening = timeOfDay >= NightSleep.EARLIEST_WINDOW - lead && timeOfDay < NightSleep.PREP_END;
        if (!evening && mod.getWorld().isBrightOutside()) {
            return;
        }
        NightSleep.Prep prep = nightPrep.last();
        String key = why + "/" + busy + "/" + source + "/" + prep;
        if (nightSaid.worthSaying(key, facts.gameTime(), 20)) {
            Debug.logInternal("night: " + why + (busy == NightSleep.Busy.NONE ? "" : ", held by " + busy) + (source == null ? "" : ", bed " + source)
                    + ", prep " + prep + ", day time " + timeOfDay);
        }
    }

    private void dropNightTrip() {
        nightTrip = null;
        NightSleepHook.clear();
    }

    private void startNightTrip(AltoClef mod, NightSleep.BedSource source, BlockPos found) {
        if (state.nightBed == null) {
            nightBudget.began();
        }
        nightPlayer = mod.getPlayer();
        nightPrep.drop();
        nightTrip = new NightSleepTask(state, () -> cfg.overworld, nightBudget, this::nightBlocks, host::say, host::save, source, found, cfg.end.beds);
    }

    // blocks the pillar may really spend: the cobble the stone kit still eats is spoken for (updateStoneFloor) and placing never
    // touches it, so counting it here would start a pillar that runs dry halfway up
    private int nightBlocks() {
        boolean kitPhase = state.phase == GamerPhase.GATHER || state.phase == GamerPhase.IRON;
        return Math.max(0, facts.buildBlocks() - NightSleep.blockReserve(state.phase, kitPhase ? KitPlanner.stoneFloor(facts, cfg.overworld) : 0));
    }

    // what may not be interrupted for a night (NightSleep.busy has the reasoning): phases that must not be cut into, a station
    // coming down that is being driven, ore going into a furnace, the first iron's load, a screen that is up. the evening's own
    // tasks open screens, so while one runs a screen does not count
    private NightSleep.Busy nightBusy(AltoClef mod, boolean ownScreens) {
        boolean pickup = false;
        for (Bench b : state.benches) {
            pickup |= WorkbenchRules.inFlight(b, facts.gameTime());
        }
        boolean screenUp = !ownScreens && screenSince >= 0 && NightSleep.screenHolds(facts.gameTime() - screenSince);
        boolean loading = loadSince >= 0 && NightSleep.loadHolds(facts.gameTime() - loadSince);
        PhaseHandler h = machine.current();
        boolean phaseHolds = h != null && h.phase() == state.phase && h.nightHold();
        return NightSleep.busy(tripHiding || NightSleep.phaseAllowed(state.phase), !tripHiding && phaseHolds, pickup, loading, facts.earlyLoadInFlight(),
                earlyCollect(mod), screenUp);
    }

    // the early iron is about to come out of a furnace we are standing near: the armor is worth the half minute, the bed is not going anywhere
    private boolean earlyCollect(AltoClef mod) {
        if (!EarlyIronPick.collectNow(facts, cfg.overworld)) {
            return false;
        }
        RunState.Pos me = FurnaceWatch.where(mod);
        for (RunState.FurnaceJob job : facts.furnaceJobs()) {
            if (FurnacePlan.earlyIronNear(job, me, facts.gameTime())) {
                return true;
            }
        }
        return false;
    }

    // since when a container screen has been up and a furnace load has been going. every engine tick, not only the ones the night
    // branch runs on, or a screen that closed and opened again during a trip would look like it had been up the whole time
    private void trackNightGates() {
        // (a chat box, the pause menu or the bed screen is not items moving, only a container is)
        if (Minecraft.getInstance().gui.screen() instanceof AbstractContainerScreen<?>) {
            if (screenSince < 0) {
                screenSince = facts.gameTime();
            }
        } else {
            screenSince = -1;
        }
        SmeltSplit.Batch batch = facts.smeltBatch();
        if (batch != null && batch.loadingAt() != null) {
            if (loadSince < 0) {
                loadSince = facts.gameTime();
            }
        } else {
            loadSince = -1;
        }
    }

    private boolean nightFighting(AltoClef mod) {
        // (healing up counts: the trip's low health line is 8, the hold runs up to 14, and the hold outbids the trip's walk anyway)
        return mod.getMobDefenseChain().combatMode() != CombatCommit.Mode.NONE || mod.getMobDefenseChain().isRecovering();
    }

    // (NightBeds has the reasoning, the schematic build asks the same two questions)
    private boolean leftoverNightBed(AltoClef mod) {
        return NightBeds.leftover(mod, state, cfg.overworld.nightBedRadius, host::save);
    }

    private BlockPos findNightBed(AltoClef mod) {
        return NightBeds.find(mod, cfg.overworld.nightBedRadius);
    }

    // the prep's "is a bed ready". all three get asked (no short circuit) so the line can say which one moved when the answer
    // flaps, and the line only comes out when one of them does
    private boolean nightBedReady(AltoClef mod, boolean leftover) {
        boolean bag = facts.count(ItemHelper.BED) > 0;
        boolean found = findNightBed(mod) != null;
        int key = (leftover ? 4 : 0) | (bag ? 2 : 0) | (found ? 1 : 0);
        if (key != nightReadyKey) {
            nightReadyKey = key;
            Debug.logInternal("night prep: bed ready? leftover " + leftover + ", bag " + facts.count(ItemHelper.BED) + ", standing "
                    + found + " (" + NightBeds.nearestWhy(mod, cfg.overworld.nightBedRadius) + ")");
        }
        return leftover || bag || found;
    }

    // ---- what the command shows

    public List<String> statusLines() {
        List<String> lines = new ArrayList<>();
        if (state == null) {
            lines.add("Not started yet.");
            return lines;
        }
        GamerPhase shown = state.stuck ? GamerPhase.STUCK : state.phase;
        lines.add("Phase: " + shown.hud() + " (attempt " + machine.attempt() + ")");
        lines.add("Time in the run: " + minutes(state.runTicks) + ", deaths: " + state.deaths.size());
        lines.addAll(milestones(state));
        return lines;
    }

    static String minutes(long ticks) {
        long seconds = ticks / 20;
        return seconds / 60 + " min " + seconds % 60 + " s";
    }

    // the same list for a saved run that is not running, so it takes just the state
    public static List<String> milestones(RunState s) {
        List<String> out = new ArrayList<>();
        if (s.overworldPortal != null) {
            out.add("Nether portal at " + s.overworldPortal);
        }
        if (!s.fortress.isEmpty()) {
            out.add("Fortress seen at " + s.fortress.get(0));
        }
        if (s.spawner != null) {
            out.add("Blaze spawner at " + s.spawner);
        }
        if (s.strongholdEstimate != null) {
            out.add("Stronghold is near " + s.strongholdEstimate + " (" + s.strongholdRays.size() + " eye throws)");
        }
        if (s.endPortalCenter != null) {
            out.add("End portal at " + s.endPortalCenter + ", " + s.framesFilled + " of 12 frames filled");
        }
        if (s.dragonDead) {
            out.add("The dragon is dead");
        }
        return out;
    }

    // ---- what the machine needs from the game

    private final class Host implements PhaseMachine.Host {
        @Override
        public GamerConfig cfg() {
            return cfg;
        }

        @Override
        public RunState state() {
            return state;
        }

        @Override
        public GamerFacts facts() {
            return facts;
        }

        @Override
        public void say(String line) {
            Debug.logMessage(line);
        }

        // identical json is not written twice, the saves come from phase moves, deaths, stops and a timer
        @Override
        public void save() {
            if (statePath == null) {
                return;
            }
            String json = RunStateStore.toJson(state);
            if (json.equals(lastWritten)) {
                return;
            }
            if (RunStateStore.write(statePath, json)) {
                lastWritten = json;
                lastWriteMillis = System.currentTimeMillis();
            }
        }

        @Override
        public void onPhaseReset() {
            if (mod != null) {
                dropChild(mod);
            }
        }

        @Override
        public void walkOnEndPortal(boolean on) {
            if (mod != null) {
                mod.getExtraBaritoneSettings().canWalkOnEndPortal(on);
            }
        }
    }
}
