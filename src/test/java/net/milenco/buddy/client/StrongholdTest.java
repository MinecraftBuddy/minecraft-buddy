package net.milenco.buddy.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** Two throws of an eye of ender and where their lines cross. */
class StrongholdTest {
	@Test
	void crossing() {
		// a stronghold at 1000, -1500; thrown from 0, 0 and from 200, 0
		double[] c = Stronghold.cross(0, 0, 1000, -1500, 200, 0, 800, -1500);
		assertNotNull(c);
		assertEquals(1000, c[0], 1e-6);
		assertEquals(-1500, c[1], 1e-6);
	}

	@Test
	void nearlyTheSameWayIsNoAnswer() {
		assertNull(Stronghold.cross(0, 0, 1, 0, 0, 10, 1, 0.05));
	}

	@Test
	void behindAThrowIsNoAnswer() {
		assertNull(Stronghold.cross(0, 0, 1, 0, 10, 10, 0, 1));
	}
}
