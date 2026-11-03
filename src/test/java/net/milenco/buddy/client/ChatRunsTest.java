package net.milenco.buddy.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * A chat line goes to the panel in runs of one look: a bounty's face (a full block per pixel, each its own colour) and the
 * clicks of a server line, as the game would draw and follow them.
 */
class ChatRunsTest {
	private static MutableComponent px(int rgb) { return Component.literal("█").withStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb))); }

	@Test
	void faceRowKeepsEveryPixelsColour() {
		// a row of NotBounties' "Gezocht" block, as the buddy was sent it: two pixels alike are joined
		MutableComponent row = Component.empty().append(px(0xBA3730)).append(px(0xCC241F)).append(px(0xCC241F))
			.append(Component.literal("  ").withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xCC241F))))
			.append(Component.literal("Gezocht").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD, ChatFormatting.UNDERLINE));
		List<Map<String, Object>> runs = Details.chatRuns(row);
		assertEquals(3, runs.size());
		assertEquals(Map.of("t", "█", "c", "#ba3730"), runs.get(0));
		assertEquals(Map.of("t", "██  ", "c", "#cc241f"), runs.get(1));
		assertEquals(Map.of("t", "Gezocht", "c", "#ffaa00", "b", 1, "u", 1), runs.get(2));
	}

	@Test
	void clicksAndHoverGoAlong() {
		MutableComponent line = Component.literal("Event: Sumo ").append(Component.literal("[Meedoen]")
			.withStyle(Style.EMPTY.withColor(ChatFormatting.GREEN).withClickEvent(new ClickEvent.RunCommand("event join"))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal("Doe mee"))))).append(Component.literal(" ")
			.append(Component.literal("[Discord]").withStyle(Style.EMPTY.withClickEvent(new ClickEvent.OpenUrl(URI.create("https://discord.gg/x")))))
			.append(Component.literal("[Typ]").withStyle(Style.EMPTY.withClickEvent(new ClickEvent.SuggestCommand("/bounty ")))));
		List<Map<String, Object>> runs = Details.chatRuns(line);
		assertEquals(Map.of("t", "[Meedoen]", "c", "#55ff55", "k", "run", "v", "event join", "h", "Doe mee"), runs.get(1));
		assertEquals(Map.of("t", "[Discord]", "k", "url", "v", "https://discord.gg/x"), runs.get(3));
		assertEquals(Map.of("t", "[Typ]", "k", "suggest", "v", "/bounty "), runs.get(4));
	}

	@Test
	void aPlainLineNeedsNoRuns() {
		assertNull(Details.chatRuns(Component.literal("Steve is online")));
	}
}
