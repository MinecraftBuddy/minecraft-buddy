package adris.altoclef.tasks.speedrun.gamer.portal;

// the two clocks of a portal build, in the task's own ticks. clearing a rough site has its own, so digging out a bump
// doesn't eat the time the build itself gets
final class BuildClock {
    static final int PREP_TICKS = 20 * 60;
    static final int BUILD_TICKS = 20 * 85;
    // every new site gets fresh clocks, so nothing above stops a run of bad lakes. this is the lid on the whole try, in our own
    // ticks so a snack or a fight in the middle doesn't eat it. walks feed the phase's stall clock, waiting doesn't, that ends in
    // the phase's free retry instead
    static final int POOL_TICKS = 20 * 300;
    static final int MAX_DROPPED_SITES = 2;

    enum Verdict {OK, PREP_OUT, BUILD_OUT}

    // why the pool is done and the cast should have a go, null = carry on. building = past the clearing, those have BUILD_TICKS
    // to finish and walking off a half made frame at the last second saves nobody anything
    static String poolOut(int ticks, int droppedSites, boolean building) {
        if (droppedSites >= MAX_DROPPED_SITES) {
            return "dropped " + droppedSites + " sites";
        }
        return ticks > POOL_TICKS && !building ? "no build in " + POOL_TICKS / 20 + " s" : null;
    }

    private int prep;
    private int build;

    Verdict tick(boolean prepping) {
        if (prepping) {
            return ++prep > PREP_TICKS ? Verdict.PREP_OUT : Verdict.OK;
        }
        return ++build > BUILD_TICKS ? Verdict.BUILD_OUT : Verdict.OK;
    }
}
