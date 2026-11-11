package adris.altoclef.util.helpers;

import java.util.Locale;

// the buddy's fork (S137, a place goal by a safer route; Tuning risk_flee_route, on under risk_flee): go or flee where the
// danger meter says flee while the task walks to a place. the meter's race counts every mob that wants the buddy; a place
// the task has to be is a reason to get there all the same, by the way that costs the least hp (the walk's searches price
// the mob field then, RiskCost.route) and with what earlier ways ran into still in the field (RiskField's memory):
//   go: the buddy outlives the piece, its hp less what the mobs are expected to take on the rest of the way and during
//     the work at the goal stays over the meter's margin. no run for the meter starts; the walk is watched (RiskWatch), and
//     more risk on it is judged again
//   go, at a goal that lies in the danger, whole: no way goes round a danger at the goal itself, and healing gains nothing
//     more. once going there the buddy goes on; the last-ditch rule ends it (hp less the loss within the meter's horizon
//     at the margin: the brain asks that first, whatever the goal)
//   flee otherwise, as built: the run, the heal, and the search again, which still pays for what this way ran into
// pure numbers: the brain (CombatBrain) hands in the path's and the goal's
public final class RouteRisk {

    // how long a piece of work at a place takes, in seconds (Tuning risk_route_work): the stay at a goal that lies in the
    // danger costs its rate times this. a guess: a wart broken and picked up, a chest opened and emptied
    public static final double WORK_S = 5;
    // a search for the way gets this long before the meter's flee is a run after all
    public static final long WAIT_TICKS = 20;
    // the answer before counts as going this long after it was given (the meter's level blinks between flee and cover)
    public static final long GOING_TICKS = 40;

    private RouteRisk() {}

    public static boolean on() {
        return Tuning.get("risk_flee", 0) >= 1 && Tuning.get("risk_flee_route", 1) > 0;
    }

    public static double work() {
        return Math.max(0, Tuning.get("risk_route_work", WORK_S));
    }

    // hp: the buddy's, absorption included. way: the hp the mobs are expected to take on the rest of the way. stay: what the
    // work at the goal is expected to cost (0 where the way does not end at the goal). inDanger: the goal's own cell is in
    // the danger. going: the answer before was go (the margin's band, and a buddy going at a goal in the danger goes on)
    public static boolean go(double hp, double way, double stay, boolean inDanger, boolean going) {
        double margin = DangerMeter.MARGIN * (going ? 1 : 1 + DangerMeter.BAND);
        if (hp - (way + stay) > margin) return true;
        return inDanger && (going || hp >= RecoverHold.DONE_AT);
    }

    // route: go|flee, <way> hp expected on the way and <stay> at the goal, hp <hp>[, the goal lies in the danger]
    public static String line(boolean go, double hp, double way, double stay, boolean inDanger) {
        return String.format(Locale.ROOT, "route: %s, %.1f hp expected on the way and %.1f at the goal, hp %.1f%s", go ? "go" : "flee",
                way, stay, hp, inDanger ? ", the goal lies in the danger" : "");
    }
}
