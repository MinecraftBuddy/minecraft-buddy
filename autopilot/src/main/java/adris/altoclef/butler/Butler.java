package adris.altoclef.butler;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.commands.AltoClefCommands;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.ChatMessageEvent;
import adris.altoclef.eventbus.events.TaskFinishedEvent;
import adris.altoclef.ui.MessagePriority;
import baritone.Baritone;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import net.minecraft.network.chat.ChatType;

/**
 * The butler system lets authorized players send commands to the bot to execute.
 * <p>
 * This effectively makes the bot function as a servant, or butler.
 * <p>
 * Authorization is defined in "altoclef_butler_whitelist.txt" and "altoclef_butler_blacklist.txt".
 * The whitelist is required (empty means nobody can command the bot), the blacklist is applied on top of it
 * and wins, unless "useButlerBlacklist" in "configs/butler.json" is off. Names are case-insensitive.
 * The whole thing is switched by the altoButler setting (on by default, and inert until somebody is on the whitelist).
 */
public class Butler {

    private static final String BUTLER_MESSAGE_START = "` ";

    private final AltoClef _mod;

    private final WhisperChecker _whisperChecker = new WhisperChecker();

    private final UserAuth _userAuth;

    // a stranger gets one "not authorized" every half minute at most, and only when replyToUnauthorized is on
    private final ReplyCooldown _strangerCooldown = new ReplyCooldown(30_000, 64);

    private String _currentUser = null;

    // Utility variables for command logic
    private boolean _commandInstantRan = false;
    // a task ended while a whisper was being run (a "stop" from somebody else does that), so whoever had the bot before
    // does not have it anymore and must not get it handed back
    private boolean _taskEnded = false;

    public Butler(AltoClef mod) {
        _mod = mod;
        _userAuth = new UserAuth(mod);

        // Revoke our current user whenever a task finishes.
        EventBus.subscribe(TaskFinishedEvent.class, evt -> {
            if (_currentUser != null) {
                _currentUser = null;
            }
            _taskEnded = true;
        });

        // Receive system events
        EventBus.subscribe(ChatMessageEvent.class, evt -> {
            if (!Baritone.settings().altoButler.value) {
                return;
            }
            boolean debug = ButlerConfig.getInstance().debugWhispers;
            String message = evt.messageContent();
            String sender = evt.senderName();
            ChatType messageType = evt.messageType();
            String receiver = mod.getPlayer().getName().getString();
            if (sender != null && !Objects.equals(sender, receiver) && messageType.chat().style().isItalic()
                    && messageType.chat().style().getColor() != null
                    && Objects.equals(messageType.chat().style().getColor().serialize(), "gray")) {
                if (debug) {
                    Debug.logMessage("RECEIVED WHISPER: \"" + sender + " " + receiver + " " + message + "\".");
                }
                _mod.getButler().receiveMessage(sender, receiver, message);
            }
        });
    }

    private void receiveMessage(String sender, String receiver, String msg) {
        // the sender comes from the packet, not from anything in the text, see WhisperChecker#parseWhisper
        WhisperChecker.MessageResult result = this._whisperChecker.receiveMessage(sender, receiver, msg);
        if (result != null) {
            this.receiveWhisper(result.from, result.message);
        } else if (ButlerConfig.getInstance().debugWhispers) {
            Debug.logMessage("    Not Parsing: MSG format not found.");
        }
    }

    private void receiveWhisper(String username, String message) {

        boolean debug = ButlerConfig.getInstance().debugWhispers;
        // Ignore messages from other bots.
        if (message.startsWith(BUTLER_MESSAGE_START)) {
            if (debug) {
                Debug.logMessage("    Rejecting: MSG is detected to be sent from another bot.");
            }
            return;
        }

        if (_userAuth.isUserAuthorized(username)) {
            executeWhisper(username, message);
        } else {
            if (debug) {
                Debug.logMessage("    Rejecting: User \"" + username + "\" is not authorized.");
            }
            // silent by default: on a public server every stranger who whispers us would get an answer otherwise
            if (ButlerConfig.getInstance().replyToUnauthorized && _strangerCooldown.tryReply(username, System.currentTimeMillis())) {
                sendWhisper(username, ButlerConfig.getInstance().failedAuthorizationResposne.replace("{from}", username), MessagePriority.UNAUTHORIZED);
            }
        }
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean isUserAuthorized(String username) {
        return _userAuth.isUserAuthorized(username);
    }

    // the force field and the terminator ask this one, see UserAuth#isUserSpared
    public boolean isUserSpared(String username) {
        return _userAuth.isUserSpared(username);
    }

    // whitelist command: edits the file and the loaded list together, see UserAuth#editWhitelist
    public WhitelistEditor.Outcome editWhitelist(String name, boolean adding) throws IOException {
        return _userAuth.editWhitelist(name, adding);
    }

    public List<String> whitelistNames() throws IOException {
        return UserAuth.whitelistNames();
    }

    // the owner started a task of their own, which drops the whisperer's one without a word: tell them and stop copying
    // the new task's log lines to them
    public void onOwnerTask() {
        if (_currentUser != null) {
            sendWhisper(_currentUser, "Your command was replaced by the owner's.", MessagePriority.TIMELY);
            _currentUser = null;
        }
    }

    // switching the butler off in the middle of a task must also stop the running commentary
    public void onLog(String message, MessagePriority priority) {
        if (_currentUser != null && Baritone.settings().altoButler.value) {
            sendWhisper(message, priority);
        }
    }

    public void onLogWarning(String message, MessagePriority priority) {
        if (_currentUser != null && Baritone.settings().altoButler.value) {
            sendWhisper("[WARNING:] " + message, priority);
        }
    }

    public void tick() {
        // Nothing for now.
    }

    public String getCurrentUser() {
        return _currentUser;
    }

    public boolean hasCurrentUser() {
        return _currentUser != null;
    }

    private void executeWhisper(String username, String message) {
        // with requirePrefixMsg only messages that start with soprano's command prefix count as commands, the rest is
        // just somebody chatting at us
        if (ButlerConfig.getInstance().requirePrefixMsg && !message.startsWith(AltoClefCommands.prefix())) {
            return;
        }
        String prevUser = _currentUser;
        _commandInstantRan = true;
        _taskEnded = false;
        // per call and not fields: a finish callback of the task we are replacing runs inside this very call (stop does
        // that) and must not look like ours finishing
        boolean[] finished = {false};
        boolean[] failed = {false};
        int startedAt = AltoClefCommands.generation();
        _currentUser = username;
        // the last flag is the allow-list: a whisper can run altoclef's commands and nothing else of soprano's
        AltoClefCommands.execute(message, () -> {
            // On finish. goes to whoever asked, _currentUser can be somebody else by the time a long task ends. a stop
            // (anybody's) bumps the generation, and "finished" would be a lie
            // (a whisper that is itself the stop did what it was asked, that one is finished)
            boolean stopped = AltoClefCommands.generation() != startedAt && !isStopLine(message);
            sendWhisper(username, (stopped ? "Command Stopped: " : "Command Finished: ") + message, MessagePriority.TIMELY);
            if (!_commandInstantRan) {
                _currentUser = null;
            }
            finished[0] = true;
        }, error -> {
            sendWhisper(username, "TASK FAILED: " + error, MessagePriority.ASAP);
            failed[0] = true;
            // an error that comes back while we are still inside execute never started anything, so whoever had the bot
            // before still has it. one that shows up later (a;b where b is bad) means the task chain moved on
            _currentUser = _commandInstantRan && !_taskEnded ? prevUser : null;
            _commandInstantRan = false;
        }, true);
        _commandInstantRan = false;
        // Only set the current user if we're still running.
        if (finished[0]) {
            _currentUser = _taskEnded ? null : prevUser;
        } else if (!failed[0]) {
            // told after the fact so a line that finishes on the spot only says "finished", one line instead of two
            sendWhisper(username, "Command Executing: " + message, MessagePriority.TIMELY);
            // somebody else's task just got dropped for this one and its finish message is never coming
            if (prevUser != null && !prevUser.equalsIgnoreCase(username)) {
                sendWhisper(prevUser, "Your command was replaced by one from " + username + ".", MessagePriority.TIMELY);
            }
        }
    }

    static boolean isStopLine(String message) {
        String first = message.trim().split("\\s+", 2)[0].toLowerCase(java.util.Locale.ROOT);
        return first.equals("stop") || first.equals("cancel");
    }

    private void sendWhisper(String message, MessagePriority priority) {
        if (_currentUser != null) {
            sendWhisper(_currentUser, message, priority);
        } else {
            Debug.logWarning("Failed to send butler message as there are no users present: " + message);
        }
    }

    private void sendWhisper(String username, String message, MessagePriority priority) {
        _mod.getMessageSender().enqueueWhisper(username, BUTLER_MESSAGE_START + message, priority);
    }
}
