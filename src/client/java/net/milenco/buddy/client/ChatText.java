package net.milenco.buddy.client;

/**
 * A line as the server takes it from a client. The server throws a player off for a '§' or a control character in chat
 * or a command ("illegal characters in chat"), and the game's own chat box sends at most 256 characters: a plugin line
 * the buddy passes on as the answer to a PM command (Alt.serverLine), or a line typed in the panel or on Telegram,
 * could hold either.
 */
public final class ChatText {
	private ChatText() { }

	public static final int MAX = 256;
	private static final java.util.regex.Pattern FORMAT = java.util.regex.Pattern.compile("\u00a7[0-9A-FK-ORXa-fk-orx]");

	/** The text without what the server refuses, its white space as single spaces, at most 256 characters. */
	public static String sendable(String text) {
		if (text == null) return "";
		// a colour or style code goes whole, its letter too ("§aRuby" is "Ruby"), then any '§' that is left
		text = FORMAT.matcher(text).replaceAll("");
		StringBuilder b = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '§' || c == 127) continue;
			b.append(c < ' ' || Character.isWhitespace(c) ? ' ' : c);
		}
		String s = b.toString().trim().replaceAll(" {2,}", " ");
		if (s.length() <= MAX) return s;
		int end = Character.isHighSurrogate(s.charAt(MAX - 1)) ? MAX - 1 : MAX;
		return s.substring(0, end);
	}
}
