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

    // (decision 10, a fight on the way is the piece in hand) the hp a fight is expected to cost: the seconds to kill its target
    // (DangerMeter.killTime) at the reading's loss (hp in the meter's horizon, every mob that hurts counted). more mobs come:
    // the loss grows, and the piece with it
    public static double piece(double killSeconds, double loss) {
        if (Double.isNaN(killSeconds) || killSeconds < 0) return Double.POSITIVE_INFINITY;
        return killSeconds * Math.max(0, loss) / DangerMeter.HORIZON;
    }

    // (decision 12, the piece in hand is outlived up to the next safe meal) how long a meal takes, in seconds (Tuning
    // risk_piece_meal_s; 0: the whole rest of the way counts, as decision 10 was built). an item eaten is 1.6 s
    public static final double MEAL_S = 1.6;

    public static double mealTicks() {
        return Math.max(0, Tuning.get("risk_piece_meal_s", MEAL_S)) * 20;
    }

    // (decision 12) where on the rest of the way a meal fits before the next fight: the first move from `from` on that begins
    // a stretch of moves the field prices calm (each under `calm` hp a second), together at least mealTicks long. hp[i]: the
    // hp the mobs are expected to take over move i's exposure (its cost, at most cap ticks); cost[i]: its ticks. -1: none
    public static int mealAt(double[] hp, double[] cost, double cap, int from, double mealTicks, double calm) {
        if (mealTicks <= 0) return -1;
        int start = -1;
        double quiet = 0;
        for (int i = Math.max(0, from); i < hp.length && i < cost.length; i++) {
            double exposure = Math.max(1, Math.min(cost[i], cap));
            if (hp[i] * 20 / exposure >= calm) {
                start = -1;
                quiet = 0;
                continue;
            }
            if (start < 0) start = i;
            quiet += cost[i];
            if (quiet >= mealTicks) return start;
        }
        return -1;
    }

    // (decision 12) what the piece's line adds: ", up to a safe meal <n> moves on (<whole> on the whole way)", ", no safe meal
    // on the way", ", no food for a meal on the way"; nothing with the key at 0
    public static String mealPart(boolean on, double[] toMeal, boolean food, double whole) {
        if (!on) return "";
        if (toMeal == null) return ", no safe meal on the way";
        if (!food) return ", no food for a meal on the way";
        return String.format(Locale.ROOT, ", up to a safe meal %d moves on (%.1f on the whole way)", Math.round(toMeal[1]), whole);
    }

    // route: go|flee, the fight in hand <piece> hp (<k> s to kill), <way> on the way and <stay> at the goal, hp <hp>[, ...]
    public static String pieceLine(boolean go, double hp, double piece, double k, double way, double stay, boolean inDanger) {
        return String.format(Locale.ROOT, "route: %s, the fight in hand %.1f hp (%.1f s to kill), %.1f on the way and %.1f at the goal, hp %.1f%s",
                go ? "go" : "flee", piece, k, way, stay, hp, inDanger ? ", the goal lies in the danger" : "");
    }

    // route: go|flee, <way> hp expected on the way and <stay> at the goal, hp <hp>[, the goal lies in the danger]
    public static String line(boolean go, double hp, double way, double stay, boolean inDanger) {
        return String.format(Locale.ROOT, "route: %s, %.1f hp expected on the way and %.1f at the goal, hp %.1f%s", go ? "go" : "flee",
                way, stay, hp, inDanger ? ", the goal lies in the danger" : "");
    }
}
