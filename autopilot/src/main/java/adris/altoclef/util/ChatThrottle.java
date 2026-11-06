package adris.altoclef.util;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

// decides what is allowed into chat. a task that fails every tick used to print its error 170 times, stack trace
// included, one chat line per frame. now the same message shows once per window and the next one after the window
// says how many it swallowed. clock is a parameter so the tests don't sleep
public class ChatThrottle {

    private static final int MAX_REMEMBERED = 64;

    private final long _windowMs;
    private final int _maxLinesPerWindow;
    private final ArrayDeque<Long> _recentLines = new ArrayDeque<>();
    // message -> {time it last got through, how many were swallowed since}. bounded, oldest message falls off
    private final Map<String, long[]> _seen = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, long[]> eldest) {
            return size() > MAX_REMEMBERED;
        }
    };

    public ChatThrottle(long windowMs, int maxLinesPerWindow) {
        _windowMs = windowMs;
        _maxLinesPerWindow = maxLinesPerWindow;
    }

    /**
     * @return the line to put in chat, or null when it should stay out of chat.
     */
    public synchronized String filter(String message, long nowMs) {
        long[] state = _seen.get(message);
        if (state != null && nowMs - state[0] < _windowMs) {
            state[1]++;
            return null;
        }
        // different messages every tick (a counter in the text, say) get past the per message check, so there is
        // a cap on the whole stream too
        while (!_recentLines.isEmpty() && nowMs - _recentLines.peekFirst() >= _windowMs) {
            _recentLines.pollFirst();
        }
        if (_recentLines.size() >= _maxLinesPerWindow) {
            if (state != null) state[1]++;
            return null;
        }
        _recentLines.addLast(nowMs);
        long swallowed = state != null ? state[1] : 0;
        // remove + put so a message we keep seeing counts as recently used and not as the eldest
        _seen.remove(message);
        _seen.put(message, new long[]{nowMs, 0});
        return swallowed > 0 ? message + " (" + swallowed + " more)" : message;
    }

    // chat gets one short line. multi line messages (people put "at:" and a stack in there) keep their first line
    public static String firstLine(String message) {
        if (message == null) return "";
        int newline = message.indexOf('\n');
        String line = newline >= 0 ? message.substring(0, newline) : message;
        return line.stripTrailing();
    }
}
