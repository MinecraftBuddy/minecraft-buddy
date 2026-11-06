package adris.altoclef.tasks.speedrun.gamer;

import java.util.List;

// pure: the clock on the diamond pick dig (KitPlanner.addDiamondPick) and when it is over. the seconds live in RunState, so a retry
// or a relog picks up where the dig left off, and a pick lost later digs again on what is left of the same clock
public final class DiamondHunt {
    // IRON budget the dig leaves when nothing else is owed after it: the portal gate still has its own few crafts to do
    public static final double LAST_MARGIN_SECONDS = 90;
    // and when wool or food is still behind it. a sheep hunt is a few minutes, starving it to dig one more minute is a bad trade
    public static final double MORE_MARGIN_SECONDS = 180;

    private DiamondHunt() {
    }

    // what one IronPhase tick did to the clock: the attempt age to remember for the next one (-1 = this was no dig tick), and
    // whether the dig was given up just now (the phase says so once and saves)
    public record Step(double lastAge, boolean gaveUp) {
    }

    // `dug` = the kit runner ran the dig this tick, not just had it at the head of the plan: a furnace trip, a golem fight or the
    // climb holding the wheel is no digging. `needs` is the tick's plan, for the margin. `age` is secondsInPhase
    public static Step tick(RunState s, double lastAge, boolean dug, List<KitNeed> needs, double age, double minutes, double budgetMinutes) {
        if (!dug || s.diamondHuntGaveUp) {
            return new Step(-1, false);
        }
        s.diamondHuntSeconds = add(s.diamondHuntSeconds, lastAge, age);
        if (over(s.diamondHuntSeconds, minutes, budgetMinutes, age, moreAfter(needs))) {
            s.diamondHuntGaveUp = true;
            return new Step(-1, true);
        }
        return new Step(age, false);
    }

    // `age` is the attempt's clock this tick (secondsInPhase, which already skips the death walk's held time), `lastAge` the same
    // clock on the last tick the dig ran, -1 when it did not (or the phase was just entered). only the stretch between two dig
    // ticks counts, so the time a retry or relog ate, or another job took, is never billed to it
    public static double add(double hunted, double lastAge, double age) {
        return lastAge >= 0 && age > lastAge ? hunted + (age - lastAge) : hunted;
    }

    // dug for diamondPickMinutes all told, or the IRON budget is down to the margin. a dig that starts with less than that left
    // is over on its first tick. a budget of 0 is no budget
    public static boolean over(double hunted, double minutes, double budgetMinutes, double secondsInPhase, boolean moreAfter) {
        if (hunted >= minutes * 60) {
            return true;
        }
        double margin = moreAfter ? MORE_MARGIN_SECONDS : LAST_MARGIN_SECONDS;
        return budgetMinutes > 0 && budgetMinutes * 60 - secondsInPhase < margin;
    }

    // anything still owed behind the dig in this plan. the pick's own craft (diamondSpend off) is the dig finishing, not something after
    public static boolean moreAfter(List<KitNeed> needs) {
        if (needs == null) {
            return false;
        }
        boolean past = false;
        for (KitNeed n : needs) {
            String name = n.catalogueName();
            if (past && !name.equals(KitPlanner.DIAMOND) && !name.equals("diamond_pickaxe")) {
                return true;
            }
            past = past || name.equals(KitPlanner.DIAMOND);
        }
        return false;
    }

    // the need the kit runner is on is the dig
    public static boolean digging(KitNeed running) {
        return running != null && KitPlanner.DIAMOND.equals(running.catalogueName());
    }
}
