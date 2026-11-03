package net.milenco.buddy.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * What a server or another player could use to pass for the one he follows, or to write outside the exchange folder
 * (the security review of 1 Nov 2026).
 */
class TrustTest {
	@Test
	void aNameCountsOnlyAsANameOfItsOwn() {
		assertTrue(Profiles.names("Steve vraagt of je naar hem toe komt [Accepteren]", "Steve"));
		assertTrue(Profiles.names("/tpaccept steve", "Steve"));
		assertTrue(Profiles.names("[Admin] Steve » kom", "Steve"));
		// a Bedrock player of the same gamertag (Floodgate's dot), and a longer name that holds his
		assertFalse(Profiles.names(".Steve has requested that you teleport to them", "Steve"));
		assertFalse(Profiles.names("/tpaccept .Steve", "Steve"));
		assertFalse(Profiles.names("Steve2 has requested that you teleport to them", "Steve"));
		assertFalse(Profiles.names("xSteve has requested that you teleport to them", "Steve"));
		assertFalse(Profiles.names("/pay attacker 1000 yes", "Steve"));
	}

	@Test
	void whatTheBuddySendsGetsNoOneKicked() {
		// a plugin line with colour codes, as it could come back as the answer to a PM command
		assertEquals("/msg Steve Ruby: 10.941", ChatText.sendable("/msg Steve \u00a7aRuby: \u00a7f10.941"));
		assertEquals("a b c", ChatText.sendable("a\nb\t\u0007c\u007f"));
		assertEquals("a lone sign", ChatText.sendable("a lone \u00a7 sign"));
		assertEquals("one space", ChatText.sendable("  one   space "));
		assertEquals(256, ChatText.sendable("x".repeat(400)).length());
		// a character outside the BMP is not cut in half at the end
		String s = ChatText.sendable("x".repeat(255) + "\ud83d\ude00" + "y");
		assertFalse(Character.isHighSurrogate(s.charAt(s.length() - 1)));
		assertEquals("", ChatText.sendable(null));
	}

	@Test
	void aDimensionStaysInsideMaptiles() {
		assertEquals("minecraft_overworld", MapMemory.dimFolder("minecraft:overworld"));
		assertEquals("smp_spawn", MapMemory.dimFolder("smp:spawn"));
		assertEquals("minecraft_____________x", MapMemory.dimFolder("minecraft:../../../../x"));
		assertEquals("a_b_c", MapMemory.dimFolder("a:b/c"));
		assertFalse(MapMemory.dimFolder("x:..").contains(".."));
	}
}
