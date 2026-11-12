package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

// the burn still to come from the on-fire flag and what lit the buddy (the client never learns the server's fire ticks)
public class BurnClockTest {

    @Test
    public void aFireballBurnsFiveSecondsAndAFireBlockEight() {
        BurnClock clock = new BurnClock();
        assertEquals(100, clock.step(true, BurnClock.FIREBALL_TICKS, 1));
        assertEquals(5, BurnClock.hp(clock.ticks(), false), 0);
        for (int i = 0; i < 40; i++) clock.step(true, 0, 1);
        assertEquals(60, clock.ticks());
        assertEquals(3, BurnClock.hp(clock.ticks(), false), 0);
        // a fire block on top of it: 8 s from now, not added
        assertEquals(160, clock.step(true, BurnClock.FIRE_TICKS, 1));
        // a fireball while that burns changes nothing (the server keeps the longer)
        assertEquals(159, clock.step(true, BurnClock.FIREBALL_TICKS, 1));
    }

    @Test
    public void lavaHoldsFifteenSecondsWhileIn() {
        BurnClock clock = new BurnClock();
        for (int i = 0; i < 30; i++) clock.step(true, BurnClock.LAVA_TICKS, 1);
        assertEquals(300, clock.ticks());
        assertEquals(15, BurnClock.hp(clock.ticks(), false), 0);
    }

    @Test
    public void theFlagRulesTheEndAndANewBurnWithNoCauseIsAFireballs() {
        BurnClock clock = new BurnClock();
        // the flag with no cause seen: a fireball's 5 s
        assertEquals(BurnClock.UNKNOWN_TICKS, clock.step(true, 0, 1));
        // the flag off (the burn ran out, water): nothing left
        assertEquals(0, clock.step(false, 0, 1));
        // the flag still on past the clock: at least a second
        clock.step(true, 0, 1);
        for (int i = 0; i < 150; i++) clock.step(true, 0, 1);
        assertEquals(BurnClock.FLOOR_TICKS, clock.ticks());
    }

    @Test
    public void fireProtectionAndFireResistance() {
        BurnClock clock = new BurnClock();
        // the burning time attribute scales the time (fire protection IV takes 60%)
        assertEquals(40, clock.step(true, BurnClock.FIREBALL_TICKS, 0.4));
        // fire resistance: no hp from it
        assertEquals(0, BurnClock.hp(clock.ticks(), true), 0);
        assertEquals(0, BurnClock.hp(0, false), 0);
        assertEquals(1, BurnClock.hp(1, false), 0);
    }
}
