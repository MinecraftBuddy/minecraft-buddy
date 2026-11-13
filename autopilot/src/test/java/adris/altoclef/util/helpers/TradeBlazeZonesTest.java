package adris.altoclef.util.helpers;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// Tuning trade_blaze_off: the ground of the blazes the trade walked off from with no fire resistance is a wall for the
// search's walks a while, the walker's own zone open; and trade_wastes_grace: wastes in view give the search's bound more time
public class TradeBlazeZonesTest {

    @Test
    public void aCellNearTheBlazeIsAWallForAWalkFromOutside() {
        TradeBlazeZones z = new TradeBlazeZones();
        z.note(100, 100, 1000);
        // the walker 40 off, the cell 10 from the blaze: a wall
        assertTrue(z.wall(110, 100, 1000, 140, 100));
        // a cell past the radius is open
        assertFalse(z.wall(100 + TradeBlazeZones.RADIUS + 1, 100, 1000, 140, 100));
    }

    // the walk off and the way out of the zone the buddy stands in stay open
    @Test
    public void theZoneTheWalkerStandsInIsOpen() {
        TradeBlazeZones z = new TradeBlazeZones();
        z.note(100, 100, 1000);
        assertFalse(z.wall(105, 100, 1000, 110, 100));
    }

    @Test
    public void aZoneRunsOutAndAnotherWorldsClockIsNothing() {
        TradeBlazeZones z = new TradeBlazeZones();
        z.note(100, 100, 1000);
        assertTrue(z.wall(110, 100, 1000 + TradeBlazeZones.LIFE_TICKS, 140, 100));
        assertFalse(z.wall(110, 100, 1001 + TradeBlazeZones.LIFE_TICKS, 140, 100));
        // a clock gone back (a new world): no zone
        assertFalse(z.wall(110, 100, 10, 140, 100));
        assertEquals(0, z.live(10));
    }

    // a blaze hovering round its spawner renews the one zone, it does not stack them
    @Test
    public void aBlazeNearAZoneRenewsIt() {
        TradeBlazeZones z = new TradeBlazeZones();
        z.note(100, 100, 1000);
        z.note(105, 100, 1000 + TradeBlazeZones.LIFE_TICKS - 10);
        assertEquals(1, z.live(1000 + TradeBlazeZones.LIFE_TICKS + 100));
        assertTrue(z.wall(115, 100, 1000 + TradeBlazeZones.LIFE_TICKS + 100, 150, 100));
        long later = 1000 + TradeBlazeZones.LIFE_TICKS;
        z.note(160, 100, later);
        assertEquals(2, z.live(later));
        z.clear();
        assertEquals(0, z.live(later));
    }

    @Test
    public void wastesInViewGiveTheBoundItsGrace() {
        long dead = 600 * 20, grace = 120 * 20;
        // none in view since the last ingot: the bound as before
        assertEquals(dead, FireResTradeRules.deadWithWastes(dead, -1, grace));
        // in view at 9.5 min of no piglin known: the bound at 11.5 min
        assertEquals(Math.round(11.5 * 60 * 20), FireResTradeRules.deadWithWastes(dead, Math.round(9.5 * 60 * 20), grace));
        // early in view: the bound stays at 10 min
        assertEquals(dead, FireResTradeRules.deadWithWastes(dead, 60 * 20, grace));
        // the switch off
        assertEquals(dead, FireResTradeRules.deadWithWastes(dead, Math.round(9.5 * 60 * 20), 0));
    }
}
