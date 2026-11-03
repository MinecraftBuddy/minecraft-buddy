package net.milenco.buddy.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** When a fall is worth the water bucket. */
class WaterTest {
	@Test
	void fallDamageAs26point3CountsIt() {
		assertEquals(0, Water.damage(3.0));
		assertEquals(0, Water.damage(3.9));
		assertEquals(1, Water.damage(4.25));     // a jump three down: the top of the jump counts
		assertEquals(13, Water.damage(16.42));   // the fall in the cave on 18 Oct 2026: 13.23 to 0.23
	}

	@Test
	void aJumpDownWithinFollowsLimitDoesNotHurt() {
		// the top of a jump above the take-off, as 26.3 moves a player: 0.42 up, then 0.98 of it less 0.08 a tick
		double y = 0, v = 0.42, top = 0;
		for (int i = 0; i < 40; i++) { y += v; top = Math.max(top, y); v = (v - 0.08) * 0.98; }
		assertEquals(1.2522, top, 0.001);
		assertEquals(0, Water.damage(top + Follow.JUMP_DROP));
		assertEquals(1, Water.damage(top + 3));
	}

	@Test
	void worthItFromTwoHeartsOrWhenLittleIsLeft() {
		assertFalse(Water.worth(0, 20));
		assertFalse(Water.worth(1, 20));          // a jump three down: not for that
		assertFalse(Water.worth(3, 20));
		assertTrue(Water.worth(4, 20));
		assertTrue(Water.worth(13, 15));
		assertTrue(Water.worth(2, 8));            // it would leave three hearts
		assertFalse(Water.worth(2, 9));
	}
}
