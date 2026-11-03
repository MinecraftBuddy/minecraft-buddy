package net.milenco.buddy.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.numbers.FixedFormat;
import net.minecraft.network.chat.numbers.StyledFormat;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The server's sidebar as label and value ("Ruby" -> "20K", "Shards" -> "41"), in the order the server shows it,
 * written to the event {@code scorebord} whenever it changes. What the alt has in money, shards and claim blocks
 * could otherwise only be seen by logging in as the alt; the Telegram bridge shows it in its status.
 */
final class Sidebar {
	private Sidebar() {}

	// "Shards: 41", "/vote: 58/150": a label, a colon and what stands behind it
	private static final Pattern BOARD_LINE = Pattern.compile("^\\s*(\\S[^:]*?):\\s*(\\S.*?)\\s*$");
	private static Map<String, String> sent = Map.of();
	private static long sentAt = 0;

	/** Once a second is called from the tick; when it changed, at most once in five seconds. */
	static void tick(Minecraft mc) {
		long now = System.currentTimeMillis();
		Map<String, String> board = board(mc);
		if (board.isEmpty() || board.equals(sent) || now - sentAt <= 5000) return;
		sent = board; sentAt = now;
		Exchange.emit("scoreboard", "lines", board);
	}

	private static Map<String, String> board(Minecraft mc) {
		Map<String, String> out = new LinkedHashMap<>();
		if (mc.level == null) return out;
		Scoreboard board = mc.level.getScoreboard();
		for (DisplaySlot slot : DisplaySlot.values()) {
			if (slot == DisplaySlot.LIST || slot == DisplaySlot.BELOW_NAME) continue;   // numbers beside names, no lines
			var obj = board.getDisplayObjective(slot);
			if (obj == null) continue;
			List<PlayerScoreEntry> scores = new ArrayList<>(board.listPlayerScores(obj));
			scores.sort(Comparator.comparingInt(PlayerScoreEntry::value).reversed().thenComparing(PlayerScoreEntry::owner, String.CASE_INSENSITIVE_ORDER));
			for (PlayerScoreEntry score : scores) {
				String text = clean(line(board, score)), label, value;
				Matcher m = BOARD_LINE.matcher(text);
				if (m.find()) { label = m.group(1); value = m.group(2); }
				else if (score.numberFormatOverride() instanceof FixedFormat || obj.numberFormat() instanceof FixedFormat) {
					// TAB on Tweakcraft ("Ruby||20K"): the label is the line, the value the fixed text on the right
					label = text.replaceAll(":\\s*$", "");
					value = clean(score.formatValue(obj.numberFormatOrDefault(StyledFormat.SIDEBAR_DEFAULT)).getString()).trim();
				} else continue;   // a heading or an empty line
				// a server may put an icon from its resource pack in front of a label: a private-use character, which reads
				// as a box anywhere else and made the label one nobody could look up
				label = label.replaceAll("[\\uE000-\\uF8FF]", "").trim();
				if (!label.isEmpty() && !value.isEmpty()) out.putIfAbsent(label, value);
			}
		}
		return out;
	}

	/**
	 * The visible text of a scoreboard line. A line's own name can be just a pile of colour codes (§0§6§r), with the
	 * text in the prefix and suffix of the team that name is in.
	 */
	private static String line(Scoreboard board, PlayerScoreEntry score) {
		PlayerTeam team = board.getPlayersTeam(score.owner());
		return PlayerTeam.formatNameForTeam(team, score.ownerName()).getString();
	}

	/** Strip colour codes and odd spaces. */
	private static String clean(String r) {
		return r.replaceAll("§[0-9A-Fa-fK-Ok-oRr]", "").replace('\u00a0', ' ');
	}
}
