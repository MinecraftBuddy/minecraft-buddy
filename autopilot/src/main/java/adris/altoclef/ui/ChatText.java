package adris.altoclef.ui;

// what we are willing to put on the wire. the server kicks a client for a section sign or a control character in chat,
// and for anything longer than 256, so a task error with a stray one in it must not cost us the connection
public final class ChatText {

    // the chat box stops at 256, so does the server
    public static final int MAX_LENGTH = 256;

    private ChatText() {
    }

    public static String clean(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(Math.min(text.length(), MAX_LENGTH));
        boolean space = false;
        for (int i = 0; i < text.length() && out.length() < MAX_LENGTH; i++) {
            char c = text.charAt(i);
            // same test as the game's own SharedConstants#isAllowedChatCharacter. whitespace of any kind folds to one space
            if (Character.isWhitespace(c)) {
                space = out.length() > 0;
                continue;
            }
            if (c < 32 || c == '§' || c == 127) {
                continue;
            }
            if (space) {
                out.append(' ');
                space = false;
                if (out.length() >= MAX_LENGTH) {
                    break;
                }
            }
            out.append(c);
        }
        // cut in the middle of an emoji is half a character the server may not like
        if (out.length() > 0 && Character.isHighSurrogate(out.charAt(out.length() - 1))) {
            out.setLength(out.length() - 1);
        }
        return out.toString();
    }

    // a name has no whitespace or control characters in it or it is not a name, and the command would grow extra words
    public static boolean isSendableName(String name) {
        if (name == null || name.isEmpty() || name.length() > 16) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c <= 32 || c >= 127) {
                return false;
            }
        }
        return true;
    }

    // the line a vanilla client would send for "/msg name text", or null when there is nothing worth sending
    public static String whisperLine(String name, String message) {
        String body = clean(message);
        if (!isSendableName(name) || body.isEmpty()) {
            return null;
        }
        return "/msg " + name + " " + body;
    }
}
