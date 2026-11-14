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

    public enum Next {NONE, IRON, STONE}

    // the next pickaxe, from what the bag holds, or NONE. `goodAboveWood` is how many pickaxes above wood in the bag are not worn
    // (ToolWear): none, and the last one is near its end or already gone. the sticks are held or made from the bag's planks, the
    // table is in the bag or made from planks besides those: never a walk to a table or a trip for wood from down here. iron from
    // three ingots, else stone; with `stoneWill` (the mine's blocks come out with a stone one, iron at y 16) stone first, so the
    // ingots of the armour are not spent on a pickaxe stone does as well
    public static Next nextPick(int goodAboveWood, int ironIngots, int stoneBlocks, int sticks, int planks, int logs, boolean tableInBag,
                                boolean stoneWill) {
        if (goodAboveWood > 0) {
            return Next.NONE;
        }
        int wood = wood(planks, logs);
        int stickPlanks = sticks >= STICKS ? 0 : STICK_CRAFT_PLANKS;
        if (wood < stickPlanks + (tableInBag ? 0 : TABLE_PLANKS)) {
            return Next.NONE;
        }
        boolean stone = stoneBlocks >= STONE_BLOCKS;
        if (stoneWill && stone) {
            return Next.STONE;
        }
        if (ironIngots >= IRON_INGOTS) {
            return Next.IRON;
        }
        return stone ? Next.STONE : Next.NONE;
    }
}
