package adris.altoclef.ui;

import adris.altoclef.Debug;
import adris.altoclef.util.time.BaseTimer;
import adris.altoclef.util.time.TimerReal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * We can't send messages immediately as the server will kick us.
 * As such, we will send messages in a delayed queued fashion.
 */
public class MessageSender {

    // How many messages can we send quickly before giving a little pause?
    private static final int FAST_LIMIT = 6;
    private static final int SLOW_LIMIT = 3;
    // a long inventory list or a looping task must not leave minutes of backlog behind it
    static final int MAX_QUEUED = 30;

    // in the order they were asked for, a conversation that arrives sorted by importance reads like nonsense
    private final Deque<BaseMessage> _queue = new ArrayDeque<>();

    private final BaseTimer _fastSendTimer = new TimerReal(0.3f);
    private final BaseTimer _bigSendTimer = new TimerReal(3.5);
    private final BaseTimer _bigBigSendTimer = new TimerReal(10);

    private int _fastCount;
    private int _slowCount;

    public void tick() {
        if (_queue.isEmpty()) {
            return;
        }
        if (Minecraft.getInstance().player == null) {
            // whatever was meant for the last server is not meant for the next one
            _queue.clear();
            return;
        }
        if (canSendMessage()) {
            String line = nextLine();
            if (line != null) {
                sendChatUpdateTimers(line);
            }
        }
    }

    // the next thing worth sending, skipping messages that turn out to be empty or addressed to nobody. null when dry
    String nextLine() {
        while (!_queue.isEmpty()) {
            String line = _queue.poll().getChatInput();
            if (line != null) {
                return line;
            }
        }
        return null;
    }

    int queued() {
        return _queue.size();
    }

    public void enqueueWhisper(String username, String message, MessagePriority priority) {
        enqueue(new Whisper(username, message, priority));
    }

    public void enqueueChat(String message, MessagePriority priority) {
        enqueue(new ChatMessage(message, priority));
    }

    // when it is full the unimportant things go: a message that is not urgent is dropped, an urgent one pushes out the
    // oldest message that was less important than it
    private void enqueue(BaseMessage msg) {
        if (_queue.size() >= MAX_QUEUED) {
            if (msg.priority.getImportance() < MessagePriority.TIMELY.getImportance()) {
                return;
            }
            Iterator<BaseMessage> it = _queue.iterator();
            boolean made = false;
            while (it.hasNext()) {
                if (it.next().priority.getImportance() < msg.priority.getImportance()) {
                    it.remove();
                    made = true;
                    break;
                }
            }
            if (!made) {
                return;
            }
        }
        _queue.add(msg);
    }

    private boolean canSendMessage() {
        return _bigBigSendTimer.elapsed() && _bigSendTimer.elapsed() && _fastSendTimer.elapsed();
    }

    private void sendChatUpdateTimers(String message) {
        sendChatInstant(message);
        _fastSendTimer.reset();
        _fastCount++;
        if (_fastCount >= FAST_LIMIT) {
            _bigSendTimer.reset();
            _fastCount = 0;
            _slowCount++;
            if (_slowCount >= SLOW_LIMIT) {
                _bigBigSendTimer.reset();
                _slowCount = 0;
            }
        }
    }

    // does what the chat box does when you press enter: "/something" goes out as a command, the rest as chat. sendCommand
    // (and not sendUnsignedCommand, which refuses anything with a message argument, /msg included) signs when the
    // client has a chat session and sends it unsigned when it does not, which is the offline server case. we used to
    // only print it in our own chat, so nobody ever heard a reply
    private void sendChatInstant(String message) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || player.connection == null) {
            Debug.logError("Failed to send chat message as no client loaded.");
            return;
        }
        if (message.startsWith("/")) {
            player.connection.sendCommand(message.substring(1));
        } else {
            player.connection.sendChat(message);
        }
    }

    private static abstract class BaseMessage {
        public MessagePriority priority;

        public BaseMessage(MessagePriority priority) {
            this.priority = priority;
        }

        // null means there is nothing to send
        public abstract String getChatInput();
    }

    private static class Whisper extends BaseMessage {
        public String username;
        public String message;

        public Whisper(String username, String message, MessagePriority priority) {
            super(priority);
            this.username = username;
            this.message = message;
        }

        @Override
        public String getChatInput() {
            return ChatText.whisperLine(username, message);
        }
    }

    private static class ChatMessage extends BaseMessage {

        public String message;

        public ChatMessage(String message, MessagePriority priority) {
            super(priority);
            this.message = message;
        }

        @Override
        public String getChatInput() {
            String text = ChatText.clean(message);
            // a public line that starts with a slash would go out as a command, which a plain message does not get to do
            if (text.isEmpty() || text.startsWith("/")) {
                return null;
            }
            return text;
        }
    }
}
