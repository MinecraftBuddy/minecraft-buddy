package adris.altoclef.util.helpers;

// the brewing of fire resistance, without the game (BrewFireResistanceTask): water and a nether wart make awkward, awkward and
// a magma cream make fire resistance, that and a redstone dust make it eight minutes long. a step is 400 ticks for the three
// bottles at once, a blaze powder is twenty steps of fuel
public final class BrewRules {
    private BrewRules() {
    }

    public static final int BATCH = 3;
    public static final int BREWS_PER_POWDER = 20;
    // an ingredient in its slot with nothing brewing this long fits no bottle: it comes back out (the stand starts within a
    // tick or two of a fitting one going in, and the client hears of it a tick later)
    public static final int IDLE_INGREDIENT_TICKS = 40;

    // how far the bottles are, the earliest one counts: it is what the next ingredient is for
    public enum Stage { NONE, WATER, AWKWARD, FIRE, LONG_FIRE }

    public static Stage stage(int water, int awkward, int fire, int longFire) {
        if (water > 0) return Stage.WATER;
        if (awkward > 0) return Stage.AWKWARD;
        if (fire > 0) return Stage.FIRE;
        if (longFire > 0) return Stage.LONG_FIRE;
        return Stage.NONE;
    }

    public static boolean needsCream(Stage stage) {
        return stage == Stage.WATER || stage == Stage.AWKWARD;
    }

    // fuel is wanted for a step still to brew. -1 is a stand not seen yet (a new one has none)
    public static boolean needsFuel(int knownFuel, Stage stage, boolean redstone) {
        boolean stepLeft = stage == Stage.WATER || stage == Stage.AWKWARD || stage == Stage.FIRE && redstone;
        return stepLeft && knownFuel <= 0;
    }

    public enum Ingredient { WART, CREAM, REDSTONE }

    // what the bottles will be once the ingredient in the stand has brewed: a wart over water makes awkward, a cream over
    // awkward fire resistance, a redstone over that the long one. the stand is read only with its screen open, and it brews
    // on with the screen shut: read as the bottles before the ingredient, a wart brewing over water sent the buddy off for a
    // second wart the moment the screen shut, in the middle of every brew (piece2ap-2 runs 6 and 7, 6 Nov 2026)
    public static Stage after(Stage stage, Ingredient brewing) {
        if (brewing == Ingredient.WART && stage == Stage.WATER) return Stage.AWKWARD;
        if (brewing == Ingredient.CREAM && stage == Stage.AWKWARD) return Stage.FIRE;
        if (brewing == Ingredient.REDSTONE && stage == Stage.FIRE) return Stage.LONG_FIRE;
        return stage;
    }

    // the next ingredient for the bottles in the stand, null when they are done (long, or short with no redstone to hand)
    public static Ingredient next(int water, int awkward, int fire, boolean redstone) {
        if (water > 0) return Ingredient.WART;
        if (awkward > 0) return Ingredient.CREAM;
        if (fire > 0 && redstone) return Ingredient.REDSTONE;
        return null;
    }

    // ---- the bag's slots 9 to 12 and the stand's screen (Tuning brew_hidden_out, on: a fix, 10 Nov 2026). the stand's screen is
    // read as the player's own (Slot.getCurrentContainerType; read through the stand's own slots the brew stalled, p4-cream5),
    // so its window slots 5 to 8, the bag's 9 to 12, read as armour, and a wart, cream, powder or bottle there is missing while
    // the stand is open. a full bag puts things there: in realfort-route-16 the brew gave up 15 to 19 s in for "no magma
    // cream" with one in the bag (runs 7 and 22), or read "missing nether wart" for 8 min with three (run 49); phase 5 passed 3
    // of 13 there, against 38 of 43 with a kit that left room. before the stand opens, a brew item in 9 to 12 changes places with an
    // empty hotbar slot, else with a hotbar thing that is neither a brew item nor a weapon, tool or food
    public static final int HIDDEN_FROM = 9;
    public static final int HIDDEN_TO = 12;

    public static boolean hiddenOutOn() {
        return Tuning.get("brew_hidden_out", 0) > 0;
    }

    // per inventory slot 0 to 35, brew (a brew item), empty, keep (a hotbar thing not to move: a weapon, a tool, food, the hand's
    // slot): {from, to}, the first brew item in 9 to 12 and the hotbar slot it changes places with (a number key's swap, one
    // click, nothing on the cursor): an empty one first, else one that is neither a brew item nor kept; null with none
    public static int[] hiddenMove(boolean[] brew, boolean[] empty, boolean[] keep) {
        for (int from = HIDDEN_FROM; from <= HIDDEN_TO; from++) {
            if (!brew[from]) continue;
            for (int to = 0; to < 9; to++) if (empty[to]) return new int[]{from, to};
            for (int to = 0; to < 9; to++) if (!brew[to] && !keep[to]) return new int[]{from, to};
            return null;
        }
        return null;
    }
}
