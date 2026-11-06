package adris.altoclef.chains;

import baritone.Baritone;
import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.commands.AltoClefCommands;
import baritone.utils.accessor.IDeathScreen;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.time.TimerGame;
import adris.altoclef.util.time.TimerReal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public class DeathMenuChain extends TaskChain {

    // Sometimes we fuck up, so we might want to retry considering the death screen.
    private final TimerReal _deathRetryTimer = new TimerReal(8);
    private final TimerGame _reconnectTimer = new TimerGame(1);
    private final TimerGame _waitOnDeathScreenBeforeRespawnTimer = new TimerGame(2);
    private ServerData _prevServerEntry = null;
    private boolean _reconnecting = false;
    private int _deathCount = 0;
    private Class _prevScreen = null;
    private final List<String> _pendingDeathCommands = new ArrayList<>();
    private LocalPlayer _deadPlayer = null;
    private RespawnWait _respawnWait = new RespawnWait();


    public DeathMenuChain(TaskRunner runner) {
        super(runner);
    }

    private boolean shouldAutoRespawn(AltoClef mod) {
        return Baritone.settings().altoAutoRespawn.value;
    }

    private boolean shouldAutoReconnect(AltoClef mod) {
        return Baritone.settings().altoAutoReconnect.value;
    }

    @Override
    protected void onStop(AltoClef mod) {

    }

    @Override
    public void onInterrupt(AltoClef mod, TaskChain other) {

    }

    @Override
    protected void onTick(AltoClef mod) {

    }

    // sends the queued death commands once we're actually alive again
    private void tickDeathCommands(AltoClef mod) {
        if (_pendingDeathCommands.isEmpty()) return;
        LocalPlayer player = Minecraft.getInstance().player;
        // respawning swaps in a new LocalPlayer, the dead one stays dead forever (isAlive() on it is never coming back)
        boolean respawned = player != null && player != _deadPlayer && player.isAlive();
        switch (_respawnWait.tick(player != null && AltoClef.inGame(), respawned)) {
            case WAIT -> {
            }
            case DROP -> {
                Debug.logWarning("Respawn never finished, not running the death command.");
                _pendingDeathCommands.clear();
                _deadPlayer = null;
            }
            case SEND -> {
                String prefix = AltoClefCommands.prefix();
                for (String command : _pendingDeathCommands) {
                    if (command.startsWith(prefix)) {
                        AltoClefCommands.executeTrusted(command);
                    } else if (command.startsWith("/")) {
                        player.connection.sendCommand(command.substring(1));
                    } else {
                        player.connection.sendChat(command);
                    }
                }
                _pendingDeathCommands.clear();
                _deadPlayer = null;
            }
        }
    }

    @Override
    public float getPriority(AltoClef mod) {
        //MinecraftClient.getInstance().getCurrentServerEntry().address;
//        MinecraftClient.getInstance().
        Screen screen = Minecraft.getInstance().gui.screen();
        tickDeathCommands(mod);
        // This might fix Weird fail to respawn that happened only once
        if (_prevScreen == DeathScreen.class) {
            if (_deathRetryTimer.elapsed()) {
                Debug.logMessage("(RESPAWN RETRY WEIRD FIX...)");
                _deathRetryTimer.reset();
                _prevScreen = null;
            }
        } else {
            _deathRetryTimer.reset();
        }
        // Keep track of the last server we were on so we can re-connect.
        if (AltoClef.inGame()) {
            _prevServerEntry = Minecraft.getInstance().getCurrentServer();
        }

        if (screen instanceof DeathScreen) {
            if (_waitOnDeathScreenBeforeRespawnTimer.elapsed()) {
                _waitOnDeathScreenBeforeRespawnTimer.reset();
                if (shouldAutoRespawn(mod)) {
                    _deathCount++;
                    Debug.logMessage("RESPAWNING... (this is death #" + _deathCount + ")");
                    assert Minecraft.getInstance().player != null;
                    Component screenMessage = ((IDeathScreen) screen).getMessage();
                    String deathMessage = screenMessage != null ? screenMessage.getString() : "Unknown"; //"(not implemented yet)"; //screen.children().toString();
                    // the death command has to wait for the respawn to actually happen, so it's queued
                    // and sent from tickDeathCommands once the new player entity shows up
                    _pendingDeathCommands.clear();
                    for (String i : Baritone.settings().altoDeathCommand.value.split(" & ")) {
                        String command = i.replace("{deathmessage}", DeathCommandText.sanitize(deathMessage));
                        if (!command.isEmpty()) {
                            _pendingDeathCommands.add(command);
                        }
                    }
                    _deadPlayer = Minecraft.getInstance().player;
                    _respawnWait = new RespawnWait();
                    Minecraft.getInstance().player.respawn();
                    Minecraft.getInstance().gui.setScreen(null);
                } else {
                    // Cancel if we die and are not auto-respawning.
                    mod.cancelUserTask();
                }
            }
        } else {
            if (AltoClef.inGame()) {
                _waitOnDeathScreenBeforeRespawnTimer.reset();
            }
            if (screen instanceof DisconnectedScreen) {
                if (shouldAutoReconnect(mod)) {
                    Debug.logMessage("RECONNECTING: Going to Multiplayer Screen");
                    _reconnecting = true;
                    Minecraft.getInstance().gui.setScreen(new JoinMultiplayerScreen(new TitleScreen()));
                } else {
                    // Cancel if we disconnect and are not auto-reconnecting.
                    mod.cancelUserTask();
                }
            } else if (screen instanceof JoinMultiplayerScreen && _reconnecting && _reconnectTimer.elapsed()) {
                _reconnectTimer.reset();
                Debug.logMessage("RECONNECTING: Going ");
                _reconnecting = false;

                if (_prevServerEntry == null) {
                    Debug.logWarning("Failed to re-connect to server, no server entry cached.");
                } else {
                    Minecraft client = Minecraft.getInstance();
                    ConnectScreen.startConnecting(screen, client, ServerAddress.parseString(_prevServerEntry.ip), _prevServerEntry, false, null);
                    //ConnectScreen.connect(screen, client, ServerAddress.parse(_prevServerEntry.address), _prevServerEntry);
                    //client.setScreen(new ConnectScreen(screen, client, _prevServerEntry));
                }
            }
        }
        if (screen != null)
            _prevScreen = screen.getClass();
        return Float.NEGATIVE_INFINITY;
    }

    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    public String getName() {
        return "Death Menu Respawn Handling";
    }

    @Override
    public String getHudName() {
        return "Respawning";
    }
}
