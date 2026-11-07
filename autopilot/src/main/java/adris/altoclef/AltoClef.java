package adris.altoclef;

import adris.altoclef.butler.Butler;
import adris.altoclef.chains.*;
import adris.altoclef.commands.AltoClefCommands;
import adris.altoclef.control.EdgeBrake;
import adris.altoclef.control.InputControls;
import adris.altoclef.control.PlayerExtraController;
import adris.altoclef.control.SlotHandler;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.trackers.*;
import adris.altoclef.trackers.storage.ContainerSubTracker;
import adris.altoclef.trackers.storage.ItemStorageTracker;
import adris.altoclef.ui.CommandStatusOverlay;
import adris.altoclef.ui.GamerHudOverlay;
import adris.altoclef.ui.MessagePriority;
import adris.altoclef.ui.MessageSender;
import adris.altoclef.util.helpers.InputHelper;
import adris.altoclef.util.helpers.SeenFilter;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.helpers.PhantomScoops;
import baritone.api.utils.Dimension;
import baritone.altoclef.BaritoneSettingsScope;
import baritone.Baritone;
import baritone.altoclef.AltoClefSettings;
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.utils.RayTraceUtils;
import baritone.altoclef.SettingsOverrides;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.function.Consumer;

/**
 * Central access point for AltoClef
 */
public class AltoClef {

    // Static access to altoclef
    private static final Queue<Consumer<AltoClef>> _postInitQueue = new ArrayDeque<>();
    private static AltoClef _instance;

    // Applies the baritone settings altoclef wants while it is running and puts the old ones back afterwards
    private final BaritoneSettingsScope _baritoneScope = new BaritoneSettingsScope(this);
    // Central Managers
    private TaskRunner _taskRunner;
    private TrackerManager _trackerManager;
    private BotBehaviour _botBehaviour;
    private PlayerExtraController _extraController;
    // Task chains
    private UserTaskChain _userTaskChain;
    private FoodChain _foodChain;
    private MobDefenseChain _mobDefenseChain;
    private MLGBucketFallChain _mlgBucketChain;
    // Trackers
    private ItemStorageTracker _storageTracker;
    private ContainerSubTracker _containerSubTracker;
    private EntityTracker _entityTracker;
    private BlockTracker _blockTracker;
    private SimpleChunkTracker _chunkTracker;
    // every "leave that alone" anybody decided, the trackers ask it before they offer a thing (see Bans)
    private final Bans _bans = new Bans(Debug::logInternal);
    private int _banToolTicks;
    // the vitals line, the stuck cursor flag, the stand watch and the baritone debug tap, see Monitors
    private final Monitors _monitors = new Monitors();
    private Dimension _banDimension;
    private MiscBlockTracker _miscBlockTracker;
    private LandMemory _landMemory;
    // Renderers
    private CommandStatusOverlay _commandStatusOverlay;
    private GamerHudOverlay _gamerHudOverlay;
    // Misc managers/input
    private MessageSender _messageSender;
    private InputControls _inputControls;
    private final EdgeBrake _edgeBrake = new EdgeBrake();
    private SlotHandler _slotHandler;
    // Butler
    private Butler _butler;

    // Are we in game (playing in a server/world)
    public static boolean inGame() {
        return Minecraft.getInstance().player != null && Minecraft.getInstance().getConnection() != null;
    }

    /**
     * The one AltoClef, null until Soprano has created it (see baritone.altoclef.AltoClefBridge).
     */
    public static AltoClef getInstance() {
        return _instance;
    }

    /**
     * Whether AltoClef currently has the bot: a user task is running or the idle gate is on.
     */
    public static boolean isRunning() {
        return _instance != null && _instance._taskRunner != null && _instance._taskRunner.isActive();
    }

    public void onInitializeLoad() {
        // This code should be run after Minecraft loads everything else in.
        // Soprano calls it from the first title screen tick (it used to be a TitleScreen mixin).
        _instance = this;
        // baritone's quiet debug lines go to the log from here on
        Monitors.tapBaritone();

        // Central Managers
        _taskRunner = new TaskRunner(this);
        _trackerManager = new TrackerManager(this);
        _botBehaviour = new BotBehaviour(this);
        _extraController = new PlayerExtraController(this);

        // Task chains
        _userTaskChain = new UserTaskChain(_taskRunner);
        // things on the way the caller wished for (TakeAlong), 51 while a detour runs: over the user task, under the rest
        new TakeAlongChain(_taskRunner);
        // the buddy's fork: into cover from a ghast that sees us on a rim or over the lava sea (55)
        new adris.altoclef.chains.GhastShelterChain(_taskRunner);
        _mobDefenseChain = new MobDefenseChain(_taskRunner);
        new DeathMenuChain(_taskRunner);
        new PlayerInteractionFixChain(_taskRunner);
        _mlgBucketChain = new MLGBucketFallChain(_taskRunner);
        new WorldSurvivalChain(_taskRunner);
        _foodChain = new FoodChain(_taskRunner);

        // Trackers
        _storageTracker = new ItemStorageTracker(this, _trackerManager, container -> _containerSubTracker = container);
        _entityTracker = new EntityTracker(_trackerManager);
        _blockTracker = new BlockTracker(this, _trackerManager);
        _chunkTracker = new SimpleChunkTracker(this);
        _miscBlockTracker = new MiscBlockTracker(this);
        _landMemory = new LandMemory(this);

        // Renderers
        _commandStatusOverlay = new CommandStatusOverlay();
        _gamerHudOverlay = new GamerHudOverlay();

        // Misc managers
        _messageSender = new MessageSender();
        _inputControls = new InputControls();
        _slotHandler = new SlotHandler(this);

        _butler = new Butler(this);

        // The settings are Soprano's (Baritone.settings().alto*), the old altoclef_settings.json comes along once
        AltoSettingsMigration.migrateIfNeeded();

        // Debug jank/hookup
        Debug.jankModInstance = this;

        // Playground
        Playground.IDLE_TEST_INIT_FUNCTION(this);

        // External mod initialization
        runEnqueuedPostInits();

        Debug.logInternal(BuildInfo.line());
    }

    // Client tick. Soprano calls this at the top of its own tick (ahead of baritone's behaviors and processes), the
    // same spot the old Minecraft.tick HEAD mixin ran from.
    public void onClientTick() {
        runEnqueuedPostInits();

        // The idle gate (altoRunsWhenIdle, read every tick so a #set takes effect at once): with it on we always run,
        // with it off we only run while a user task is going. Turning it off while idle disables the runner here, and
        // that puts baritone's own settings back
        boolean runsWhenIdle = Baritone.settings().altoRunsWhenIdle.value;
        if (runsWhenIdle && !_taskRunner.isActive()) {
            _taskRunner.enable();
            // the idle command used to be started when the settings loaded, now that is whenever the gate comes up
            if ((!_userTaskChain.isActive() || _userTaskChain.isRunningIdleTask()) && AltoSettings.shouldRunIdleCommandWhenNotActive()) {
                _userTaskChain.signalNextTaskToBeIdleTask();
                AltoClefCommands.executeTrusted(Baritone.settings().altoIdleCommand.value);
            }
        } else if (!runsWhenIdle && _taskRunner.isActive() && !_userTaskChain.isActive()) {
            _taskRunner.disable();
        }

        // Releases whatever we pressed last tick. Has to keep going when idle or the last press of a finished task sticks.
        _inputControls.onTickPre();
        // the walk out of a portal ends at its edge, whichever task asked for it (PortalExit)
        adris.altoclef.util.helpers.PortalExit.letGoOutside(this);
        // the last firm ground in the nether, for a wander stuck on a rim (RimRetreat)
        adris.altoclef.util.helpers.RimRetreat.note(this);

        if (_taskRunner.isActive()) {
            // a #set of the throwaway items has to reach what we handed baritone too
            _baritoneScope.refreshThrowaway();

            // Cancel shortcut
            if (InputHelper.isKeyPressed(InputConstants.KEY_LCONTROL) && InputHelper.isKeyPressed(InputConstants.KEY_K)) {
                _userTaskChain.cancel(this);
                if (_taskRunner.getCurrentTaskChain() != null) {
                    _taskRunner.getCurrentTaskChain().stop(this);
                }
            }

            // TODO: should this go here?
            _storageTracker.setDirty();
            _containerSubTracker.onServerTick();
            _miscBlockTracker.tick();
            tickBans();
            PhantomScoops.tick(this);
            _extraController.tickHurtWatch();

            _trackerManager.tick();
            _blockTracker.preTickTask();
            try {
                _taskRunner.tick();
            } finally {
                _blockTracker.postTickTask();
            }
            // (the buddy's fork: the way back into a blaze fight's hiding place, measured every tick, whoever has the wheel)
            adris.altoclef.util.helpers.BlazeShelter.tickClock(this);
            adris.altoclef.util.helpers.BlazeShelter.tickHide(this);
            _monitors.onActiveTick(this);
        }

        _monitors.onTick(this);
        _butler.tick();
        _messageSender.tick();
        // outside the runner block above: a gate going false (task over, runner idle) has to let go of the sneak too
        // (an idle command counts: that can be a whole #gamer game and its ledges are just as tall. a human on the keys still
        // wins, realMoveKey reads the real key)
        _edgeBrake.tick(this, _userTaskChain.isActive());
        // (the buddy's fork: the snapshots of the nether fights, after every chain had its say this tick; off unless tuned on)
        adris.altoclef.util.snapshot.Snapshots.tick(this);
        // (the buddy's fork: the danger meter in shadow, logged only; nothing reads it)
        adris.altoclef.util.helpers.DangerShadow.tick(this);

        _inputControls.onTickPost();
    }

    // the clock, and every second the pick tier (a few inventory looks, not worth doing 20 times a second)
    private void tickBans() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || getPlayer() == null) {
            return;
        }
        _bans.tick(level.getGameTime());
        Dimension dim = WorldHelper.getCurrentDimension();
        if (_banDimension != null && dim != _banDimension) {
            _bans.dimensionLeft(_banDimension);
        }
        _banDimension = dim;
        // our own count and not game time % 20, a server time correction can jump right over the 20
        if (++_banToolTicks % 20 == 0) {
            _bans.toolTier(StorageHelper.getCurrentMiningRequirement(this).ordinal());
        }
    }

    /// GETTERS AND SETTERS

    // Called from Soprano's hud extract mixin at the end of every frame
    public void onClientRenderOverlay(GuiGraphicsExtractor graphics) {
        // nothing of ours on screen unless we are the ones driving
        if (_taskRunner.isActive()) {
            _commandStatusOverlay.render(this, graphics);
        }
        // not behind the check: after a win the runner is idle and the card is still up for a while. it works out for itself when
        // there is nothing to draw
        _gamerHudOverlay.render(this, graphics);
    }

    public GamerHudOverlay getGamerHudOverlay() {
        return _gamerHudOverlay;
    }

    // Soprano: TaskRunner calls these as it switches on and off. Everything altoclef changes about baritone happens
    // in between and is undone on the way out, so a Soprano user who never runs a task never sees any of it.
    public void onTaskRunnerEnable() {
        // the bottom BotBehaviour state is what pop() writes back, so make sure it is today's settings and not startup's
        _botBehaviour.rebaseline();
        _baritoneScope.apply();
        _botBehaviour.push();
        _botBehaviour.setPauseOnLostFocus(false);
        // Don't break blocks or place blocks where we are explicitly protected. These ride in the pushed state so
        // the pop in TaskRunner#disable takes them back out of the global AltoClefSettings.
        _botBehaviour.avoidBlockBreaking(AltoSettings::isPositionExplicitlyProtected);
        _botBehaviour.avoidBlockPlacing(AltoSettings::isPositionExplicitlyProtected);
    }

    // the way out. every step has its own try, because this is the thing that has to work when something else just
    // blew up: whatever else fails, the user's settings come back, interactions get unpaused and the stack is clean
    public void onTaskRunnerDisabled() {
        try {
            _baritoneScope.restore();
        } catch (Throwable t) {
            t.printStackTrace();
            // restore() does its own cleanup in a finally, this is for the registry itself throwing
            SettingsOverrides.restoreAll();
        }
        try {
            // nothing of altoclef's may stay in the shared knobs when nobody is driving
            AltoClefSettings.getInstance().resetAll();
            RayTraceUtils.fluidHandling = ClipContext.Fluid.NONE;
        } catch (Throwable t) {
            t.printStackTrace();
        }
        try {
            // read from the real values again, now that they are back
            _botBehaviour.resetStack();
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    // soprano left the world (quit to title, disconnect, kicked). a task that survives that would resume in whatever
    // world comes next, and its overrides would sit on the title screen, so it is cancelled and everything is put back.
    // the trackers go too: what they know belongs to a world that is gone
    public void onWorldLeft() {
        try {
            if (_taskRunner.isActive()) {
                _userTaskChain.cancel(this);
                _taskRunner.disable();
            }
        } finally {
            if (_trackerManager != null) {
                _trackerManager.resetAll();
            }
            // what the gamer has seen belongs to a world that is gone too
            SeenFilter.reset();
            // the disable above already did this when we were running, this is for when we were not
            if (_botBehaviour != null) {
                _botBehaviour.clearPaused();
            }
            // and so does what the placer told plain alto's station book
            adris.altoclef.util.helpers.StationHook.clearPlain();
            _edgeBrake.letGo(_inputControls);
        }
    }

    // altoclef is being given up on for the session (too many exceptions). each step is on its own, and nothing here
    // may throw, the bridge calls it from the middle of cleaning up an exception
    public void emergencyShutdown() {
        try {
            if (_userTaskChain != null) {
                _userTaskChain.cancel(this);
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
        try {
            if (_taskRunner != null) {
                _taskRunner.disable();
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
        try {
            _edgeBrake.letGo(_inputControls);
        } catch (Throwable t) {
            t.printStackTrace();
        }
        // even if the runner never got as far as being active: everything we may have set goes back
        onTaskRunnerDisabled();
        if (_instance == this) {
            _instance = null;
        }
    }

    // a failed startup leaves a half built instance behind: the static handle and whatever subscribed to the bus
    public static void discardInstance() {
        _instance = null;
    }

    /**
     * Runs the highest priority task chain
     * (task chains run the task tree)
     */
    public TaskRunner getTaskRunner() {
        return _taskRunner;
    }

    /**
     * The user task chain (runs your command. Ex. Get Diamonds, Beat the Game)
     */
    public UserTaskChain getUserTaskChain() {
        return _userTaskChain;
    }

    /**
     * Controls bot behaviours, like whether to temporarily "protect" certain blocks or items
     */
    public BotBehaviour getBehaviour() {
        return _botBehaviour;
    }

    /**
     * Tracks items in your inventory and in storage containers.
     */
    public ItemStorageTracker getItemStorage() {
        return _storageTracker;
    }

    /**
     * Tracks loaded entities
     */
    public EntityTracker getEntityTracker() {
        return _entityTracker;
    }

    /**
     * Tracks blocks and their positions
     */
    public BlockTracker getBlockTracker() {
        return _blockTracker;
    }

    public Bans getBans() {
        return _bans;
    }

    /**
     * Tracks of whether a chunk is loaded/visible or not
     */
    public SimpleChunkTracker getChunkTracker() {
        return _chunkTracker;
    }

    /**
     * Tracks random block things, like the last nether portal we used
     */
    public MiscBlockTracker getMiscBlockTracker() {
        return _miscBlockTracker;
    }

    public LandMemory getLandMemory() {
        return _landMemory;
    }

    /**
     * Baritone access (could just be static honestly)
     */
    public Baritone getClientBaritone() {
        if (getPlayer() == null) {
            return (Baritone) BaritoneAPI.getProvider().getPrimaryBaritone();
        }
        return (Baritone) BaritoneAPI.getProvider().getBaritoneForPlayer(getPlayer());
    }

    /**
     * Baritone settings access (could just be static honestly)
     */
    public Settings getClientBaritoneSettings() {
        return Baritone.settings();
    }

    /**
     * Baritone settings special to AltoClef (could just be static honestly)
     */
    public AltoClefSettings getExtraBaritoneSettings() {
        return AltoClefSettings.getInstance();
    }

    /**
     * Butler controller. Keeps track of users and lets you receive user messages
     */
    public Butler getButler() {
        return _butler;
    }

    /**
     * Sends chat messages (avoids auto-kicking)
     */
    public MessageSender getMessageSender() {
        return _messageSender;
    }

    /**
     * Does Inventory/container slot actions
     */
    public SlotHandler getSlotHandler() {
        return _slotHandler;
    }

    /**
     * Minecraft player client access (could just be static honestly)
     */
    public LocalPlayer getPlayer() {
        return Minecraft.getInstance().player;
    }

    /**
     * Minecraft world access (could just be static honestly)
     */
    public ClientLevel getWorld() {
        return Minecraft.getInstance().level;
    }

    /**
     * Minecraft client interaction controller access (could just be static honestly)
     */
    public MultiPlayerGameMode getController() {
        return Minecraft.getInstance().gameMode;
    }

    /**
     * Extra controls not present in ClientPlayerInteractionManager. This REALLY should be made static or combined with something else.
     */
    public PlayerExtraController getControllerExtras() {
        return _extraController;
    }

    /**
     * Manual control over input actions (ex. jumping, attacking)
     */
    public InputControls getInputControls() {
        return _inputControls;
    }

    /**
     * Run a user task
     */
    public void runUserTask(Task task) {
        runUserTask(task, () -> {
        });
    }

    /**
     * Run a user task
     */
    public void runUserTask(Task task, Runnable onFinish) {
        _userTaskChain.runTask(this, task, onFinish);
    }

    /**
     * Cancel currently running user task
     */
    public void cancelUserTask() {
        _userTaskChain.cancel(this);
        // the movement task's stop parked its path for a handover (keepPathOnSameGoal), a user stop is not one
        getClientBaritone().getPathingBehavior().dropPark();
    }

    /**
     * Takes control away to eat food
     */
    public FoodChain getFoodChain() {
        return _foodChain;
    }

    /**
     * Takes control away to defend against mobs
     */
    public MobDefenseChain getMobDefenseChain() {
        return _mobDefenseChain;
    }

    /**
     * Takes control away to perform bucket saves
     */
    public MLGBucketFallChain getMLGBucketChain() {
        return _mlgBucketChain;
    }

    public void log(String message) {
        log(message, MessagePriority.TIMELY);
    }

    /**
     * Logs to the console and also messages any player using the bot as a butler.
     */
    public void log(String message, MessagePriority priority) {
        Debug.logMessage(message);
        _butler.onLog(message, priority);
    }

    public void logWarning(String message) {
        logWarning(message, MessagePriority.TIMELY);
    }

    /**
     * Logs a warning to the console and also alerts any player using the bot as a butler.
     */
    public void logWarning(String message, MessagePriority priority) {
        Debug.logWarning(message);
        _butler.onLogWarning(message, priority);
    }

    private void runEnqueuedPostInits() {
        synchronized (_postInitQueue) {
            while (!_postInitQueue.isEmpty()) {
                _postInitQueue.poll().accept(this);
            }
        }
    }

}
