package net.milenco.buddy.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** (place_click_clock) the Placer's clock per cell: 5 s aimed with no block fails the cell, which then rests 10 s. */
class PlaceClockTest {
	private static final long CELL = 42L, OTHER = 7L;

	/** Aim every tick (50 ms) from {@code from} up to and with {@code to}; true when the clock ran out on the way. */
	private static boolean aimFor(PlaceClock c, long cell, long from, long to) {
		boolean out = false;
		for (long t = from; t <= to; t += 50) out |= c.aimed(cell, t);
		return out;
	}

	@Test
	void fiveSecondsAimedWithNoBlockFailTheCell() {
		PlaceClock c = new PlaceClock();
		assertFalse(aimFor(c, CELL, 0, PlaceClock.AIM_MS));
		assertTrue(c.aimed(CELL, PlaceClock.AIM_MS + 50));
		// given up: it rests, and fails at once for the rest's length
		assertTrue(c.resting(CELL, PlaceClock.AIM_MS + 100));
		assertTrue(c.resting(CELL, PlaceClock.AIM_MS + 50 + PlaceClock.REST_MS - 1));
		assertFalse(c.resting(CELL, PlaceClock.AIM_MS + 50 + PlaceClock.REST_MS));
		// another cell has a clock of its own
		assertFalse(c.resting(OTHER, PlaceClock.AIM_MS + 100));
	}

	@Test
	void aCellAimedAtAgainAfterTheRestStartsAfresh() {
		PlaceClock c = new PlaceClock();
		assertTrue(aimFor(c, CELL, 0, PlaceClock.AIM_MS + 50));
		long back = PlaceClock.AIM_MS + 50 + PlaceClock.REST_MS;
		assertFalse(aimFor(c, CELL, back, back + PlaceClock.AIM_MS));
	}

	@Test
	void aGapInTheAimStartsTheClockAgain() {
		PlaceClock c = new PlaceClock();
		assertFalse(aimFor(c, CELL, 0, 4000));
		// the caller away more than GAP_MS (a meal, another cell): a fresh 5 s from the return
		long back = 4000 + PlaceClock.GAP_MS + 50;
		assertFalse(aimFor(c, CELL, back, back + PlaceClock.AIM_MS));
		assertTrue(c.aimed(CELL, back + PlaceClock.AIM_MS + 50));
	}

	@Test
	void twoCellsInTurnKeepTheirOwnClocks() {
		PlaceClock c = new PlaceClock();
		boolean out = false;
		// a caller that asks one cell and then the other each tick: neither clock is started again by the other
		for (long t = 0; t <= PlaceClock.AIM_MS; t += 50) { out |= c.aimed(CELL, t); out |= c.aimed(OTHER, t); }
		assertFalse(out);
		assertTrue(c.aimed(CELL, PlaceClock.AIM_MS + 50));
	}

	@Test
	void aBlockInTheCellClearsItsClock() {
		PlaceClock c = new PlaceClock();
		assertTrue(aimFor(c, CELL, 0, PlaceClock.AIM_MS + 50));
		c.done(CELL);
		assertFalse(c.resting(CELL, PlaceClock.AIM_MS + 100));
		assertFalse(aimFor(c, CELL, PlaceClock.AIM_MS + 100, 2 * PlaceClock.AIM_MS + 100));
	}
}
