package net.milenco.buddy.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

/**
 * The game's own death messages and advancements are known by their key, on any server and in any language: also behind a
 * server's prefix, with the player's own name where a display name carries a team's prefix, and never from a line that
 * only quotes one.
 */
class DeathLineTest {
	/** A player's name as the game puts it in such a line: the display name, with the plain name as its insertion. */
	private static Component name(String shown, String own) { return Component.literal(shown).withStyle(Style.EMPTY.withInsertion(own)); }

	@Test
	void theGamesDeathMessage() {
		assertArrayEquals(new String[] { "death", "Steve" },
			AfkHelperClient.gameLine(Component.translatable("death.attack.mob", name("[Admin] Steve", "Steve"), Component.literal("Zombie")), 0));
		assertArrayEquals(new String[] { "death", "Alex" },
			AfkHelperClient.gameLine(Component.translatable("death.fell.accident.generic", Component.literal("Alex")), 0));
	}

	@Test
	void behindAServersPrefix() {
		Component line = Component.literal("☠ ").append(Component.translatable("death.attack.drown", name("Steve", "Steve")));
		assertArrayEquals(new String[] { "death", "Steve" }, AfkHelperClient.gameLine(line, 0));
	}

	@Test
	void anAdvancement() {
		assertArrayEquals(new String[] { "advancement", "Steve" },
			AfkHelperClient.gameLine(Component.translatable("chat.type.advancement.task", name("Steve", "Steve"), Component.literal("[Stone Age]")), 0));
	}

	@Test
	void notOtherLines() {
		assertNull(AfkHelperClient.gameLine(Component.literal("Steve was slain by Zombie"), 0));
		assertNull(AfkHelperClient.gameLine(Component.translatable("multiplayer.player.joined", name("Steve", "Steve")), 0));
		// a chat line that quotes a death message is a chat line
		assertNull(AfkHelperClient.gameLine(Component.translatable("chat.type.text", name("Alex", "Alex"),
				Component.translatable("death.attack.mob", name("Steve", "Steve"), Component.literal("Zombie"))), 0));
	}
}
