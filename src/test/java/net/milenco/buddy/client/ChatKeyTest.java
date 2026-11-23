package net.milenco.buddy.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

/** When the game quits for a new chat key: a key due at 40 hours that expires at 48, as Mojang hands them out. */
class ChatKeyTest {
	private static final Instant START = Instant.parse("2026-11-19T15:20:00Z");
	private static final Instant DUE = START.plus(Duration.ofHours(40));
	private static final Instant EXPIRES = START.plus(Duration.ofHours(48));

	private static boolean at(Duration after, boolean safe, boolean quiet) {
		return ChatKey.restartNow(START.plus(after), DUE, EXPIRES, safe, quiet);
	}

	@Test
	void notBeforeTheGameHadItsTryAtRenewing() {
		assertFalse(at(Duration.ofHours(39), true, true));
		assertFalse(at(Duration.ofHours(40).plusMinutes(5), true, true));
		assertTrue(at(Duration.ofHours(40).plusMinutes(11), true, true));
	}

	@Test
	void aBusyBuddyWaitsUntilShortlyAfterExpiry() {
		assertFalse(at(Duration.ofHours(44), true, false));
		assertFalse(at(Duration.ofHours(48).plusMinutes(5), true, false));
		assertTrue(at(Duration.ofHours(48).plusMinutes(11), true, false));
	}

	@Test
	void neverAtAnUnsafeMoment() {
		assertFalse(at(Duration.ofHours(44), false, true));
		assertFalse(at(Duration.ofHours(60), false, false));
	}
}
