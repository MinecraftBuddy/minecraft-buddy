package adris.altoclef.tasks.construction.schematic;

// what SchematicBuildTask does once a count is in. pure, so the order of the checks has tests: which one wins when
// two are true at once is the whole point
public final class CountStep {

    public enum Next {
        // the builder let go and only what we can't get is left
        DONE,
        // the builder let go, the count still sees work, and there's nothing to fetch for it. the builder's settings
        // (okIfAir, substitutes, a selection) call cells done that the count doesn't, the builder wins
        DONE_BUT_DIFFERS,
        // GatherGuard says the build stopped getting further
        STUCK,
        // gettable things are short and nothing fits
        BAG_FULL,
        // the builder paused for things the bag already has
        CANT_PLACE,
        // nothing to fetch, (re)start the builder
        BUILD,
        // go get the batch
        GATHER
    }

    private CountStep() {
    }

    // ended = the builder let go without pausing, restEmpty = nothing left but skipped items, stuck = the guard's
    // round, fromPause = a builder pause sent us here
    public static Next next(boolean ended, boolean restEmpty, boolean stuck, boolean bagFull, boolean nothingToGet,
                            boolean fromPause) {
        if (ended && restEmpty) {
            // done beats stuck: the last round adds nothing because there's nothing left to add
            return Next.DONE;
        }
        if (ended && nothingToGet && !bagFull) {
            return Next.DONE_BUT_DIFFERS;
        }
        if (stuck) {
            return Next.STUCK;
        }
        if (bagFull) {
            return Next.BAG_FULL;
        }
        if (nothingToGet) {
            return fromPause ? Next.CANT_PLACE : Next.BUILD;
        }
        return Next.GATHER;
    }
}
