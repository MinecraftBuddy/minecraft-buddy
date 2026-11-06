package adris.altoclef.butler;

import adris.altoclef.Debug;
import adris.altoclef.util.time.TimerGame;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WhisperChecker {

    private static final TimerGame _repeatTimer = new TimerGame(0.1);

    // what the butler uses when the user did not write their own, see parseWhisper
    public static final String DEFAULT_FORMAT = "{from} {to} {message}";

    private static String _lastMessage = null;

    public static MessageResult tryParse(String ourUsername, String whisperFormat, String message) {
        // walk the format placeholder by placeholder: every literal chunk in between gets quoted on its own, so a "[" or a
        // "." in the format is text and not regex. parts ends up in the order the groups appear
        List<String> parts = new ArrayList<>();
        StringBuilder regex = new StringBuilder();
        int at = 0;
        while (true) {
            int next = -1;
            String found = null;
            for (String part : new String[]{"{from}", "{to}", "{message}"}) {
                int i = whisperFormat.indexOf(part, at);
                if (i != -1 && (next == -1 || i < next)) {
                    next = i;
                    found = part;
                }
            }
            if (found == null) break;
            if (next > at) regex.append(Pattern.quote(whisperFormat.substring(at, next)));
            regex.append("(.+)");
            parts.add(found);
            at = next + found.length();
        }
        if (at < whisperFormat.length()) regex.append(Pattern.quote(whisperFormat.substring(at)));
        Pattern p = Pattern.compile(regex.toString());
        Matcher m = p.matcher(message);
        Map<String, String> values = new HashMap<>();
        if (m.matches()) {
            for (int i = 0; i < m.groupCount(); ++i) {
                // parts is sorted, so the order should lign up.
                if (i >= parts.size()) {
                    Debug.logError("Invalid whisper format parsing: " + whisperFormat + " for message: " + message);
                    break;
                }
                //Debug.logInternal("     GOT: " + parts.get(i) + " -> " + m.group(i + 1));
                values.put(parts.get(i), m.group(i + 1));
            }
        }

        if (values.containsKey("{to}")) {
            // Make sure the "to" target is us.
            String toUser = values.get("{to}");
            if (!toUser.equals(ourUsername)) {
                Debug.logInternal("Rejected message since it is sent to " + toUser + " and not " + ourUsername);
                return null;
            }
        }
        if (values.containsKey("{from}") && values.containsKey("{message}")) {
            MessageResult result = new MessageResult();
            result.from = values.get("{from}");
            result.message = values.get("{message}");
            return result;
        }
        return null;
    }

    public MessageResult receiveMessage(String sender, String receiver, String message) {
        // sender and message can't run into each other in the key, a name has no newline in it
        String key = sender + "\n" + message;
        boolean duplicate = key.equals(_lastMessage);
        if (duplicate && !_repeatTimer.elapsed()) {
            _repeatTimer.reset();
            // It's probably an actual duplicate. IDK why we get those but yeah.
            return null;
        }

        _lastMessage = key;
        return parseWhisper(sender, receiver, message, ButlerConfig.getInstance().whisperFormats);
    }

    // the chat event already knows who sent the whisper and what it said, so the default format never goes through a
    // regex: "{from} {to} {message}" over "sender receiver message" was greedy (multi word commands never parsed) and
    // let a message like "x <bot> stop" move the sender. a custom format can still pick the message out of the joined
    // text, but "from" is always the real sender, nothing a player types can change it
    public static MessageResult parseWhisper(String sender, String receiver, String message, String[] formats) {
        if (sender == null || sender.isBlank() || receiver == null || message == null || formats == null) {
            return null;
        }
        String whole = sender + " " + receiver + " " + message;
        for (String format : formats) {
            if (format == null) {
                continue;
            }
            if (format.trim().equals(DEFAULT_FORMAT)) {
                return new MessageResult(sender, message);
            }
            MessageResult custom = tryParse(receiver, format, whole);
            if (custom != null && custom.message != null) {
                return new MessageResult(sender, custom.message);
            }
        }
        return null;
    }

    public static class MessageResult {
        public String from;
        public String message;

        public MessageResult() {
        }

        public MessageResult(String from, String message) {
            this.from = from;
            this.message = message;
        }

        @Override
        public String toString() {
            return "MessageResult{" +
                    "from='" + from + '\'' +
                    ", message='" + message + '\'' +
                    '}';
        }
    }
}
