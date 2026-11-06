package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

// a tiny voxel world: stone, water or air, and nothing else. enough to ask "can i grab this from the shore"
public class ItemPickupRulesTest {

    private enum Cell { AIR, STONE, WATER }

    private static class Grid implements ItemPickupRules.Terrain {
        private final Map<Long, Cell> cells = new HashMap<>();
        private final Cell fallback;

        Grid(Cell fallback) {
            this.fallback = fallback;
        }

        private static long key(int x, int y, int z) {
            return ((long) x & 0xFFFFF) | (((long) y & 0xFFFFF) << 20) | (((long) z & 0xFFFFF) << 40);
        }

        Grid set(int x, int y, int z, Cell c) {
            cells.put(key(x, y, z), c);
            return this;
        }

        Grid fill(int x1, int y1, int z1, int x2, int y2, int z2, Cell c) {
            for (int x = x1; x <= x2; x++)
                for (int y = y1; y <= y2; y++)
                    for (int z = z1; z <= z2; z++)
                        set(x, y, z, c);
            return this;
        }

        Cell at(int x, int y, int z) {
            return cells.getOrDefault(key(x, y, z), fallback);
        }

        @Override
        public boolean water(int x, int y, int z) {
            return at(x, y, z) == Cell.WATER;
        }

        @Override
        public boolean solid(int x, int y, int z) {
            return at(x, y, z) == Cell.STONE;
        }

        @Override
        public boolean open(int x, int y, int z) {
            return at(x, y, z) == Cell.AIR;
        }
    }

    // a cave: stone everywhere under y=0, a ledge of floor for x < 0 (so you stand on it with feet at y=1), and a
    // lake for x in [0, 9] whose water fills y=-4..0. air above everything
    private static Grid lake() {
        Grid g = new Grid(Cell.AIR);
        g.fill(-10, -10, -10, 20, -1, 10, Cell.STONE);
        g.fill(-10, 0, -10, -1, 0, 10, Cell.STONE);
        g.fill(0, -4, -10, 9, 0, 10, Cell.WATER);
        return g;
    }

    @Test
    public void dryItemsAreAlwaysFine() {
        assertTrue(ItemPickupRules.isPickupSafe(5.5, 1.1, 5.5, false, lake()));
    }

    @Test
    public void itemFloatingInTheMiddleOfALakeIsSkipped() {
        assertFalse(ItemPickupRules.isPickupSafe(5.5, 0.4, 3.5, true, lake()));
    }

    @Test
    public void itemSunkToTheBottomOfALakeIsSkipped() {
        assertFalse(ItemPickupRules.isPickupSafe(5.5, -3.6, 3.5, true, lake()));
    }

    @Test
    public void itemRightAtTheShoreIsGrabbableFromTheLedge() {
        // water starts at x=0, we stand on the ledge at x=-1. the item is half a block into the water
        assertTrue(ItemPickupRules.isPickupSafe(0.4, 0.6, 3.5, true, lake()));
    }

    @Test
    public void itemTwoBlocksOutIsOutOfReachFromTheLedge() {
        assertFalse(ItemPickupRules.isPickupSafe(2.5, 0.6, 3.5, true, lake()));
    }

    @Test
    public void itemSunkBelowTheLedgeIsOutOfReach() {
        // right at the shore but three blocks down, reach is half a block under the feet
        assertFalse(ItemPickupRules.isPickupSafe(0.4, -2.6, 3.5, true, lake()));
    }

    @Test
    public void noDryFloorAnywhereMeansSkip() {
        Grid g = new Grid(Cell.WATER);
        g.fill(-10, -10, -10, 20, -5, 10, Cell.STONE);
        assertFalse(ItemPickupRules.isPickupSafe(0.4, 0.6, 3.5, true, g));
    }

    @Test
    public void onePuddleDeepIsWadeable() {
        Grid g = new Grid(Cell.AIR);
        g.fill(-10, -10, -10, 20, 0, 10, Cell.STONE);
        g.fill(3, 1, 3, 6, 1, 6, Cell.WATER);
        assertTrue(ItemPickupRules.isPickupSafe(4.5, 1.2, 4.5, true, g));
    }

    @Test
    public void twoDeepIsNotWadeable() {
        Grid g = new Grid(Cell.AIR);
        g.fill(-10, -10, -10, 20, 0, 10, Cell.STONE);
        g.fill(3, 1, 3, 6, 2, 6, Cell.WATER);
        assertFalse(ItemPickupRules.isPickupSafe(4.5, 1.5, 4.5, true, g));
    }

    @Test
    public void puddleUnderALowRoofIsNotWadeable() {
        Grid g = new Grid(Cell.AIR);
        g.fill(-10, -10, -10, 20, 0, 10, Cell.STONE);
        g.fill(3, 1, 3, 6, 1, 6, Cell.WATER);
        g.fill(3, 2, 3, 6, 2, 6, Cell.STONE);
        assertFalse(ItemPickupRules.isPickupSafe(4.5, 1.2, 4.5, true, g));
    }

    @Test
    public void shoreSpotMustHaveHeadroom() {
        // same shore but a ceiling right over the ledge: nowhere to stand, nothing to grab from
        Grid g = lake();
        g.fill(-3, 2, -10, -1, 2, 10, Cell.STONE);
        assertFalse(ItemPickupRules.isPickupSafe(0.4, 0.6, 3.5, true, g));
    }

    @Test
    public void aBlockSittingInTheLakeCountsAsFloor() {
        // a stone block poking out of the water you could stand on, item right next to it
        Grid g = lake();
        g.set(5, 0, 3, Cell.STONE);
        assertTrue(ItemPickupRules.isPickupSafe(6.4, 0.6, 3.5, true, g));
    }

    @Test
    public void aDropThatVanishedUnderOurFeetWasPickedUp() {
        assertTrue(ItemPickupRules.collected(true, 0.5));
        assertTrue(ItemPickupRules.collected(true, 3.9));
    }

    @Test
    public void aDropThatVanishedFarAwayWasNotUs() {
        // despawned, or a villager got it: still the search's problem
        assertFalse(ItemPickupRules.collected(true, 12));
    }

    @Test
    public void aDropThatIsStillThereIsNotCollected() {
        assertFalse(ItemPickupRules.collected(false, 0.5));
    }

    @Test
    public void aPartlyFullStackOfTheSameThingHasRoom() {
        // full bag, cobble at 60, a drop of 10 more: 4 fit and vanilla takes them
        assertTrue(ItemPickupRules.hasRoom(new int[]{64, 60, 64}, new boolean[]{false, true, false}, 64));
    }

    @Test
    public void aFullBagOfOtherThingsHasNoRoom() {
        assertFalse(ItemPickupRules.hasRoom(new int[]{64, 64, 12}, new boolean[]{true, false, false}, 64));
    }

    @Test
    public void anEmptySlotIsRoom() {
        assertTrue(ItemPickupRules.hasRoom(new int[]{64, 0}, new boolean[]{false, false}, 64));
    }

    @Test
    public void aDropThatFitsEvenPartlyIsJustPickedUp() {
        assertEquals(ItemPickupRules.Room.FITS, ItemPickupRules.room(true, true));
        assertEquals(ItemPickupRules.Room.FITS, ItemPickupRules.room(true, false));
    }

    @Test
    public void aDropWithNoRoomMakesRoomWhenSomethingCanGo() {
        assertEquals(ItemPickupRules.Room.MAKE_ROOM, ItemPickupRules.room(false, true));
    }

    @Test
    public void aDropWithNoRoomAndNothingToThrowIsGivenUp() {
        assertEquals(ItemPickupRules.Room.GIVE_UP, ItemPickupRules.room(false, false));
    }

    @Test
    public void onlyTheValuablesAreWorthAWetPickup() {
        for (String item : new String[]{"ender_eye", "ender_pearl", "blaze_rod", "diamond_pickaxe", "iron_sword", "diamond_sword", "shield",
                "water_bucket", "iron_chestplate", "iron_boots", "diamond_helmet", "diamond_leggings"}) {
            assertTrue(item, ItemPickupRules.worthSwimming(item));
        }
        for (String item : new String[]{"cobblestone", "stone_pickaxe", "wooden_sword", "bucket", "bread", "iron_ingot", "leather_helmet"}) {
            assertFalse(item, ItemPickupRules.worthSwimming(item));
        }
    }

    @Test
    public void wearableArmorGetsASlotFromTheLastResort() {
        // the golden helmet on the death pile, a bag of things we keep and nothing garbage
        assertTrue(ItemPickupRules.canMakeRoom(false, true, true));
        assertFalse(ItemPickupRules.canMakeRoom(false, true, false));
        assertFalse(ItemPickupRules.canMakeRoom(false, false, true));
        assertTrue(ItemPickupRules.canMakeRoom(true, false, false));
        assertEquals(ItemPickupRules.Room.MAKE_ROOM, ItemPickupRules.room(false, ItemPickupRules.canMakeRoom(false, true, true)));
    }
}
