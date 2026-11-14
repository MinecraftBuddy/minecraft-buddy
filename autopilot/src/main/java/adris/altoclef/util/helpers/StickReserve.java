package adris.altoclef.util.helpers;

// (the buddy's fork, Tuning stick_reserve, a fix of 14 Nov 2026, on) the wood a deep mine keeps for its next pickaxe, and when it
// makes that pickaxe. the diamond pickaxe wore out on the gold mine's rock at y -24, a new iron one needed sticks, the bag had one
// plank and no stick, and the log for them was a spruce 50 blocks over the cave: every search to a log timed out, the wooden
// pickaxe broke and there was no way up (test heel, look 88). so: the furnace burns no wood under the reserve (3 planks, or a
// log, and the planks of 2 sticks while the bag has fewer), the mine turns spare wood into sticks while it holds fewer than 2,
// and the next pickaxe is made from what the bag holds while the last one above wood is still good for the last of its uses,
// not after it broke. pure, the callers pass the bag
public final class StickReserve {
    // what the bag keeps: two sticks (one pickaxe) and three planks, or a log
    public static final int STICKS = 2;
    public static final int PLANKS = 3;
    // planks a log makes, planks two sticks' craft takes (it makes four), a table's planks
    static final int LOG_PLANKS = 4;
    static final int STICK_CRAFT_PLANKS = 2;
    static final int TABLE_PLANKS = 4;
    // what a pickaxe of each kind takes besides its two sticks
    public static final int IRON_INGOTS = 3;
    public static final int STONE_BLOCKS = 3;

    private StickReserve() {
    }

    public static boolean on() {
        return Tuning.get("stick_reserve", 0) > 0;
    }

    // the planks the furnace leaves: the reserve's three and the planks of two sticks while the bag holds fewer than two
    public static int fuelKeepPlanks(int sticks) {
        return PLANKS + (sticks < STICKS ? STICK_CRAFT_PLANKS : 0);
    }

    // the bag's wood in planks, a log counted as its four
    public static int wood(int planks, int logs) {
        return Math.max(0, planks) + LOG_PLANKS * Math.max(0, logs);
    }

    // sticks to make now: fewer than two held and the bag has the planks of the craft past the reserve's three
    public static boolean makeSticks(int sticks, int planks, int logs) {
        return sticks < STICKS && wood(planks, logs) >= STICK_CRAFT_PLANKS + PLANKS;
    }

    // DIAMOND and LOGS only with next_pick_more: a diamond pickaxe from three diamonds, or up for logs, the wood being what the bag
    // lacks
    public enum Next {NONE, IRON, STONE, DIAMOND, LOGS}

    public static final int DIAMONDS = 3;

    // (Tuning next_pick_more, a fix of 14 Nov 2026, on) look 98: the gold mine's last pickaxe gone and a bag of 7 diamonds, 1
    // stick and no wood, so nextPick said NONE and the mine dug on with no pickaxe for half an hour
    public static boolean moreOn() {
        return Tuning.get("next_pick_more", 0) > 0;
    }

    // (Tuning cheap_pick_keep, a fix of 14 Nov 2026, on) look 98: the mine's batch of stone pickaxes for the rock turned the last
    // two planks into sticks and left one stick and no wood, so no next pickaxe could be made
    public static boolean keepOn() {
        return Tuning.get("cheap_pick_keep", 0) > 0;
    }

    // (cheap_pick_keep) the stone pickaxes the mine's batch for the rock may make, two at most: from the sticks past the keep's
    // two and the planks past its three (the table's four first when none is in the bag), never the keep. logs are left out,
    // as the batch always had them
    public static int cheapBatch(int sticks, int planks, boolean tableInBag, int stoneBlocks) {
        int spareWood = Math.max(0, planks - PLANKS - (tableInBag ? 0 : TABLE_PLANKS));
        int spareSticks = Math.max(0, sticks - STICKS) + 4 * (spareWood / STICK_CRAFT_PLANKS);
        return Math.max(0, Math.min(2, Math.min(stoneBlocks / STONE_BLOCKS, spareSticks / 2)));
    }

    // (Tuning kit_diamond_off_rock) one stone pickaxe for the rock when the kit's diamond pickaxe is all that would dig it: the
    // keep's sticks and wood may go into it (it is the next pickaxe the keep is for), logs counted as their planks, the table's
    // four first when none is in the bag. 1 or 0
    public static int kitRockPick(int sticks, int planks, int logs, boolean tableInBag, int stoneBlocks) {
        int wood = wood(planks, logs) - (tableInBag ? 0 : TABLE_PLANKS);
        if (wood < 0 || stoneBlocks < STONE_BLOCKS) {
            return 0;
        }
        int all = Math.max(0, sticks) + 4 * (wood / STICK_CRAFT_PLANKS);
        return all >= STICKS ? 1 : 0;
    }

    // the next pickaxe, from what the bag holds, or NONE. `goodAboveWood` is how many pickaxes above wood in the bag are not worn
    // (ToolWear): none, and the last one is near its end or already gone. the sticks are held or made from the bag's planks, the
    // table is in the bag or made from planks besides those: never a walk to a table or a trip for wood from down here. iron from
    // three ingots, else stone; with `stoneWill` (the mine's blocks come out with a stone one, iron at y 16) stone first, so the
    // ingots of the armour are not spent on a pickaxe stone does as well
    public static Next nextPick(int goodAboveWood, int ironIngots, int stoneBlocks, int sticks, int planks, int logs, boolean tableInBag,
                                boolean stoneWill) {
        return nextPick(goodAboveWood, ironIngots, stoneBlocks, sticks, planks, logs, tableInBag, stoneWill, 0, false);
    }

    // (next_pick_more, `more`) two more answers where the one above says NONE: the wood for the sticks or the table is what the
    // bag lacks, LOGS (up to the surface for them, the caller's climb); and with no iron or stone choice, a diamond pickaxe from
    // three of the bag's diamonds (the kit's diamond pickaxe gone or worn: goodAboveWood counts it while it is good)
    public static Next nextPick(int goodAboveWood, int ironIngots, int stoneBlocks, int sticks, int planks, int logs, boolean tableInBag,
                                boolean stoneWill, int diamonds, boolean more) {
        return nextPick(goodAboveWood, ironIngots, stoneBlocks, sticks, planks, logs, tableInBag, stoneWill, diamonds, more, false);
    }

    // (Tuning pickless_way_out, `tableNear`) a crafting table standing within the bench's reach counts as one in the bag: the craft
    // walks to it, where the table's four planks were what the bag lacked and the answer was the trip up for logs
    public static Next nextPick(int goodAboveWood, int ironIngots, int stoneBlocks, int sticks, int planks, int logs, boolean tableInBag,
                                boolean stoneWill, int diamonds, boolean more, boolean tableNear) {
        if (goodAboveWood > 0) {
            return Next.NONE;
        }
        int wood = wood(planks, logs);
        int stickPlanks = sticks >= STICKS ? 0 : STICK_CRAFT_PLANKS;
        if (wood < stickPlanks + (tableInBag || tableNear ? 0 : TABLE_PLANKS)) {
            return more ? Next.LOGS : Next.NONE;
        }
        boolean stone = stoneBlocks >= STONE_BLOCKS;
        if (stoneWill && stone) {
            return Next.STONE;
        }
        if (ironIngots >= IRON_INGOTS) {
            return Next.IRON;
        }
        if (stone) {
            return Next.STONE;
        }
        return more && diamonds >= DIAMONDS ? Next.DIAMOND : Next.NONE;
    }

    // (Tuning mine_kit_surface, a fix of 14 Nov 2026, on) what a gold mine or a slime dig still lacks before it goes down from the
    // surface, where the wood is: two unworn pickaxes above wood besides the kit's diamond one (PICKS), then the wood for one more
    // (WOOD): its two sticks or their planks, the table or its four planks, and the reserve's three planks. three runs of the test
    // heel stalled 35 to 45 minutes in a cave at y 13 to 47, "No path found" every 2 s to the logs on the surface, under the
    // catalogue's iron pickaxe
    public static final int SURFACE_PICKS = 2;

    public enum Ready {READY, PICKS, WOOD}

    public static boolean surfaceOn() {
        return Tuning.get("mine_kit_surface", 0) > 0;
    }

    public static Ready surfaceReady(int picksBesidesKit, int sticks, int planks, int logs, boolean tableInBag) {
        if (picksBesidesKit < SURFACE_PICKS) {
            return Ready.PICKS;
        }
        return wood(planks, logs) >= surfaceWood(sticks, tableInBag) ? Ready.READY : Ready.WOOD;
    }

    // the wood, in planks, one more pickaxe and the reserve take
    static int surfaceWood(int sticks, boolean tableInBag) {
        return (sticks >= STICKS ? 0 : STICK_CRAFT_PLANKS) + (tableInBag ? 0 : TABLE_PLANKS) + PLANKS;
    }

    // the logs the bag should hold after the trip for that wood: what it holds, what covers the shortfall, and one more
    public static int surfaceLogs(int sticks, int planks, int logs, boolean tableInBag) {
        int lack = surfaceWood(sticks, tableInBag) - wood(planks, logs);
        return lack <= 0 ? logs : logs + (lack + LOG_PLANKS - 1) / LOG_PLANKS + 1;
    }
}
