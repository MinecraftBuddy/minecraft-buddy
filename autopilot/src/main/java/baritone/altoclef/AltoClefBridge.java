/*
 * This file is part of Soprano.
 *
 * Soprano is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Soprano is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Soprano.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.altoclef;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.commands.AltoClefCommands;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.ChunkLoadEvent;
import adris.altoclef.eventbus.events.ChunkUnloadEvent;
import adris.altoclef.ui.gui.mc.ghost.SchematicGhostRenderer;
import adris.altoclef.ui.gui.mc.move.MoveGizmo;
import adris.altoclef.util.helpers.DeathStash;
import baritone.Baritone;
import baritone.api.Settings;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.event.events.ChunkEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.utils.RayTraceUtils;
import baritone.api.utils.SettingsUtil;
import baritone.behavior.trip.TripGatherer;
import baritone.event.GameEventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;

// the one place soprano and altoclef touch. it owns the AltoClef instance, feeds it soprano's tick and chunk events
// (these used to be four altoclef mixins) and keeps any exception it throws from taking the game down with it
public final class AltoClefBridge implements AbstractGameEventListener {

    // after this many exceptions inside a minute altoclef is switched off for the session instead of spamming the log
    // every tick. a quiet minute wipes the slate, one stray exception an hour is not a reason to give up
    private static final int MAX_ERRORS = 5;
    private static final long ERROR_WINDOW_NANOS = 60_000_000_000L;

    private static AltoClef altoClef;
    private static AltoClefBridge instance;
    private static boolean hudBroken;
    private static TripGatherBridge tripGatherer;
    // set the moment the error limit is hit, from any thread (a predicate on the pathing thread can be the one that
    // tips it). everything that asks "is altoclef alive" looks at this, the cleanup itself waits for the next tick
    private static volatile boolean gaveUp;
    private static int errors;
    private static long lastErrorNanos;

    private boolean initTried;
    private boolean wasInGame;

    private AltoClefBridge() {
    }

    // called once for the primary baritone. goes to the FRONT of the listener list: altoclef used to tick at the top
    // of Minecraft.tick, ahead of everything baritone does, and it sets goals and inputs that the behaviors and
    // processes are supposed to see this very tick
    public static void attach(Baritone primary) {
        // this runs inside Minecraft's constructor, nothing of altoclef's gets to throw out of it
        try {
            instance = new AltoClefBridge();
            EventBus.setErrorHandler(AltoClefBridge::onHookError);
            ((GameEventHandler) primary.getGameEventHandler()).registerEventListenerFirst(instance);
            // the commands go in now, long before altoclef itself exists, so #help and tab completion know about them from
            // the title screen on. running one is what creates altoclef, see require
            AltoClefCommands.register(primary);
            // the ghost of the schematic picked in the gui, it draws whatever McSchematics.ghost() says
            SchematicGhostRenderer.attach(primary);
            // the arrows of the schematic move mode, after the ghost so they draw over it
            MoveGizmo.attach(primary);
        } catch (Throwable t) {
            System.err.println("altoclef could not hook into soprano, it stays off this session");
            t.printStackTrace();
        }
    }

    // the instance for a command that is about to run. altoclef is made lazily on the first tick it can be, so a command
    // typed before that (or after altoclef gave up) has to say so instead of falling over a null
    public static AltoClef require() throws CommandException {
        if (altoClef == null && instance != null && !instance.initTried) {
            instance.init();
        }
        if (altoClef == null || gaveUp) {
            throw new CommandInvalidStateException(instance != null && instance.initTried
                    ? "altoclef is off for this session, it failed to start or crashed too many times (the log says why)"
                    : "altoclef is not up yet, give it a second");
        }
        return altoClef;
    }

    // what #set does to save settings.txt. altoclef lends baritone a pile of settings while a task runs and those
    // must not be written down as the user's, so they are swapped for the user's own values for the duration of the
    // save. goes through the registry and not through the instance, so it still works after altoclef gave up
    public static void saveSettings(Settings settings) {
        SettingsOverrides.saveWithoutOverrides(() -> SettingsUtil.save(settings));
    }

    // #set (or #set reset) just changed this setting by hand. what the user typed wins, and sticks after the task
    public static void userChanged(Settings.Setting<?> setting) {
        SettingsOverrides.userChanged(setting);
    }

    public static void userChangedAll() {
        SettingsOverrides.userChangedAll();
    }

    // what stop does about altoclef. never creates it, a user task can't be running if it was never made
    public static void cancelUserTask() {
        AltoClefCommands.abortSequences();
        if (altoClef != null) {
            try {
                altoClef.cancelUserTask();
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
    }

    // true while an altoclef task (or the idle gate) has the bot. mixins that change vanilla behaviour check this
    public static boolean isRunning() {
        return altoClef != null && !gaveUp && AltoClef.isRunning();
    }

    // the instance if it's up and hasn't given up, never creates it
    static AltoClef instanceOrNull() {
        return isAlive() ? altoClef : null;
    }

    // a real user task (#get, #gamer...) is running, not just the idle gate. the trip ask counts that as the user moving on
    public static boolean userTaskActive() {
        AltoClef ac = instanceOrNull();
        return ac != null && ac.getUserTaskChain().isActive() && !ac.getUserTaskChain().isRunningIdleTask();
    }

    // autoTorch asks before every placement. altoclef off is a yes, see TorchGate for what it waits out while it runs
    public static boolean torchingAllowed() {
        AltoClef ac = isRunning() ? instanceOrNull() : null;
        return ac == null || TorchGate.allows(ac);
    }

    // who collects blocks for a trip (TripBehavior). null while altoclef is off, the trip just says it can't
    public static TripGatherer tripGatherer() {
        if (instance == null || gaveUp) {
            return null;
        }
        if (tripGatherer == null) {
            tripGatherer = new TripGatherBridge();
        }
        return tripGatherer;
    }

    // true when altoclef exists and has not given up. for the hooks that have to keep listening while it is idle
    // (chat for the butler, chunks for the chunk tracker)
    public static boolean isAlive() {
        return altoClef != null && !gaveUp;
    }

    // how every hook publishes. altoclef code runs inside the subscribers, and the hooks sit in Level#setBlock, the chat
    // handler, the packet handlers: nothing thrown in there may get out, that is how you disconnect a player
    public static void publish(Object event) {
        if (!isAlive()) {
            return;
        }
        try {
            EventBus.publish(event);
        } catch (Throwable t) {
            onHookError(t);
        }
    }

    // something altoclef ran from a hook (a subscriber, a rule, a mixin body) threw. counted, logged, and then nothing,
    // the game goes on. any thread
    public static void onHookError(Throwable t) {
        System.err.println("altoclef threw inside a hook");
        t.printStackTrace();
        countError();
    }

    // true when this one was the last straw
    private static synchronized boolean countError() {
        long now = System.nanoTime();
        if (errors > 0 && now - lastErrorNanos > ERROR_WINDOW_NANOS) {
            errors = 0;
        }
        lastErrorNanos = now;
        if (++errors >= MAX_ERRORS) {
            gaveUp = true;
            return true;
        }
        return false;
    }

    // called from MixinAltoClefGui at the end of the hud extract, every frame
    public static void renderHud(GuiGraphicsExtractor graphics) {
        if (hudBroken || !isRunning()) {
            return;
        }
        try {
            altoClef.onClientRenderOverlay(graphics);
        } catch (Throwable t) {
            hudBroken = true;
            System.err.println("altoclef hud threw, turning it off");
            t.printStackTrace();
        }
    }

    // MixinAltoClefOptions: vanilla is about to write options.txt, and a pauseOnLostFocus that altoclef turned off
    // is not what the user chose
    public static void beforeOptionsSave() {
        try {
            SettingsOverrides.beginOptionsSave();
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    public static void afterOptionsSave() {
        try {
            SettingsOverrides.endOptionsSave();
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    @Override
    public void onTick(TickEvent event) {
        if (!initTried) {
            init();
        }
        if (altoClef == null) {
            return;
        }
        if (gaveUp) {
            // somebody (maybe another thread) hit the limit, the cleanup is ours to do and this is the right thread
            shutDown();
            return;
        }
        // OUT is the title screen / loading, nothing of altoclef's runs there
        if (event.getType() != TickEvent.Type.IN) {
            if (wasInGame) {
                wasInGame = false;
                onWorldLeft();
            }
            return;
        }
        wasInGame = true;
        try {
            PtShots.tick();
            LocalPlayer me = Minecraft.getInstance().player;
            if (me != null) {
                DeathStash.watch(me);
            }
            altoClef.onClientTick();
        } catch (Throwable t) {
            onError(t);
        }
    }

    // quit to title, disconnect, kicked: no task and no override gets to follow the player into the next world. a
    // dimension change is not this, the player never goes null there and tasks like cross dimension goto rely on that
    private void onWorldLeft() {
        AltoClefCommands.abortSequences();
        try {
            altoClef.onWorldLeft();
        } catch (Throwable t) {
            onError(t);
        }
    }

    @Override
    public void onChunkEvent(ChunkEvent event) {
        if (!isAlive()) {
            return;
        }
        try {
            // POST populate is "the packet's chunk is in the world", which is when the old loadChunkFromPacket mixin fired
            if (event.isPostPopulate()) {
                publish(new ChunkLoadEvent(new ChunkPos(event.getX(), event.getZ())));
            } else if (event.getState() == EventState.POST && event.getType() == ChunkEvent.Type.UNLOAD) {
                publish(new ChunkUnloadEvent(new ChunkPos(event.getX(), event.getZ())));
            }
        } catch (Throwable t) {
            onHookError(t);
        }
    }

    // the death packet, while the dying player is still the player. the stash has to be written here and not by a task: a
    // chain that holds the wheel through the fight, the death and the death screen never lets the user task tick, and the
    // task that wants to go back for the pile is a user task. (the same packet is what saves baritone's death waypoint)
    @Override
    public void onPlayerDeath() {
        if (!isAlive()) {
            return;
        }
        try {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null) {
                return;
            }
            DeathStash.Death death = DeathStash.snapshot(player);
            DeathStash.put(death);
            Debug.logInternal("death: recorded at " + death.where() + " in " + death.dimension() + " (" + death.cause() + ")");
        } catch (Throwable t) {
            onHookError(t);
        }
    }

    // altoclef used to start on the first TitleScreen#init. same moment here (registries and resources are up by
    // then), or the first tick with a world for people who skip the title screen
    private void init() {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.gui.screen() instanceof TitleScreen) && mc.level == null) {
            return;
        }
        initTried = true;
        try {
            AltoClef ac = new AltoClef();
            // set before onInitializeLoad, not after: the idle command can run a command while it is still starting and
            // that command asks for the instance
            altoClef = ac;
            ac.onInitializeLoad();
        } catch (Throwable t) {
            System.err.println("altoclef failed to start, it stays off this session");
            t.printStackTrace();
            // a half built altoclef leaves subscribers on the bus and a static handle behind
            shutDown();
        }
    }

    // a tick (or the world-left cleanup) threw. whatever altoclef was doing is what blew up, so stop doing it. each step
    // on its own: the cancel throwing is exactly when the disable has to still happen
    private void onError(Throwable t) {
        System.err.println("altoclef threw in its tick");
        t.printStackTrace();
        AltoClef ac = altoClef;
        if (ac != null) {
            try {
                ac.cancelUserTask();
            } catch (Throwable t2) {
                t2.printStackTrace();
            }
            try {
                ac.getTaskRunner().disable();
            } catch (Throwable t2) {
                t2.printStackTrace();
            }
        }
        if (countError()) {
            shutDown();
        }
    }

    // altoclef is done for the session. nothing of it may stay applied: the user's settings come back, interactions are
    // unpaused, every rule and toggle is cleared and nobody listens on the bus. each step on its own, none may throw
    private static void shutDown() {
        gaveUp = true;
        System.err.println("altoclef is switched off for this session");
        AltoClef ac = altoClef;
        altoClef = null;
        try {
            if (ac != null) {
                ac.emergencyShutdown();
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
        // in case the instance died before it got that far (or never really existed)
        try {
            SettingsOverrides.restoreAll();
            AltoClefSettings.getInstance().resetAll();
            RayTraceUtils.fluidHandling = ClipContext.Fluid.NONE;
        } catch (Throwable t) {
            t.printStackTrace();
        }
        try {
            EventBus.clear();
            AltoClef.discardInstance();
            AltoClefCommands.abortSequences();
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
