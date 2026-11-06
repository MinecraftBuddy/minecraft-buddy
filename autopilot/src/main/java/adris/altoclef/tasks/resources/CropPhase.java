package adris.altoclef.tasks.resources;

// break or replant, decided from facts and nothing else so a test can walk it tick by tick. the old code asked
// "is there a hole near me" fresh every tick, and the answer changed under it: the hole only counted once the crop
// was gone, the crop was only gone once the swing landed, and every flip threw the other task away after one tick
public enum CropPhase {
    BREAK,
    REPLANT;

    // getting INTO replant takes a hole in range. staying in it only takes a committed hole (one we already picked, it may
    // be a step out of range by now) so walking to it can't undo the decision. no seed or no holes and we're back to breaking
    public static CropPhase next(CropPhase current, boolean replantOn, boolean haveSeed, int holesInRange, boolean committed) {
        if (!replantOn || !haveSeed) {
            return BREAK;
        }
        if (holesInRange > 0) {
            return REPLANT;
        }
        return current == REPLANT && committed ? REPLANT : BREAK;
    }
}
