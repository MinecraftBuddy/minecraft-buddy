package adris.altoclef.tasks.speedrun.gamer;

// pure: IRON's sheep chase for the beds. same clock as GoldHunt, its seconds live in RunState. out of time only takes the wool off
// IRON's plan so the kit can move on, the beds themselves are END_PREP's hard gate and it does not read any of this
public final class WoolHunt {
    // the catalogue name of the chase
    public static final String NEED = "wool";

    private WoolHunt() {
    }

    // the chase's clock, only on ticks the kit runner ran it with no sheep in view (same shape as GoldHunt.tick). walking up to a sheep is
    // the chase working, it's the looking around for one that needs a limit
    public static DiamondHunt.Step tick(RunState s, double lastAge, boolean chased, double age, double minutes) {
        if (!chased || s.woolHuntGaveUp) {
            return new DiamondHunt.Step(-1, false);
        }
        s.woolHuntSeconds = DiamondHunt.add(s.woolHuntSeconds, lastAge, age);
        if (s.woolHuntSeconds >= minutes * 60) {
            s.woolHuntGaveUp = true;
            return new DiamondHunt.Step(-1, true);
        }
        return new DiamondHunt.Step(age, false);
    }

    // a death can bring us back through IRON, and that visit gets its own go at the sheep. the old clock would quietly drop the
    // wool the death just cost us
    public static void onDeath(RunState s) {
        s.woolHuntSeconds = 0;
        s.woolHuntGaveUp = false;
    }

    // the need the kit runner is on is the wool chase
    public static boolean chasing(KitNeed running) {
        return running != null && NEED.equals(running.catalogueName());
    }
}
